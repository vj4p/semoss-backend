/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.reactor.agent.stream;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * In-memory item-event stream sessions for canonical agent runs, keyed by
 * runId. Sessions buffer canonical item.started / item.updated / item.completed
 * events until a single consumer drains them ({@link #drain}), and also push
 * them live to any {@link #subscribe}d listener as they are emitted.
 *
 * <h3>Poll and push are independent taps on the same event, not two queues</h3>
 * {@code drain} and {@code subscribe} both react to {@link #emitLocked}, but
 * neither is the other's buffer: {@code drain} empties the deque this session
 * keeps for {@code pollAgentRun}; a subscriber is handed the same event object
 * directly, and keeps nothing of its own. So a run may be polled, pushed to a
 * live subscriber, or both, without either starving the other. The documented
 * poll endpoint keeps working exactly as before — this is additive.
 *
 * <h3>Replay without a gap or a duplicate</h3>
 * {@link #subscribe} takes {@code afterSequence} and must not drop an event
 * emitted between "read the backlog" and "start listening live." It holds
 * {@link Session#lock} across both: collecting every buffered event whose
 * sequence is greater than {@code afterSequence}, and registering the
 * listener, in one critical section. {@link #emitLocked} assigns a event's
 * sequence number under that same lock, so nothing can be emitted in the gap
 * — the backlog and the live feed cannot overlap or skip.
 *
 * <h3>Delivery runs off the agent's own thread</h3>
 * A subscriber callback is usually a network write (an SSE frame). Calling it
 * synchronously from {@link #emitLocked} would make a slow or stalled HTTP
 * client throttle the agent thread holding {@link Session#lock} — every other
 * caller touching that session's events blocks behind it. {@link #DISPATCH_EXECUTOR}
 * decouples the two: the lock is released before a listener ever runs.
 */
public final class AgentRunStreamService {

	private static final Logger logger = LogManager.getLogger(AgentRunStreamService.class);

	private static final int MAX_EVENTS_PER_RUN = 2000;
	private static final long TERMINAL_GRACE_MS = 60_000L;
	private static final long SWEEP_INTERVAL_SEC = 30L;

	private static final String TYPE_ITEM_STARTED = "item.started";
	private static final String TYPE_ITEM_UPDATED = "item.updated";
	private static final String TYPE_ITEM_COMPLETED = "item.completed";

	private static final AgentRunStreamService INSTANCE = new AgentRunStreamService();

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	/**
	 * Delivers events to live subscribers off the thread that emits them. One
	 * slow network write on an SSE connection must not stall every other
	 * caller touching the same run's lock; the pool is small and unbounded on
	 * queue because listener callbacks are expected to be a fast handoff into
	 * an async HTTP response (see A2AResource's SseEventSink.send, which is
	 * itself non-blocking).
	 */
	private final ExecutorService dispatchExecutor = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "agent-run-stream-dispatch");
		t.setDaemon(true);
		return t;
	});

	private AgentRunStreamService() {
		ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "agent-run-stream-sweeper");
			t.setDaemon(true);
			return t;
		});
		sweeper.scheduleAtFixedRate(this::sweepExpired, SWEEP_INTERVAL_SEC, SWEEP_INTERVAL_SEC, TimeUnit.SECONDS);
	}

	public static AgentRunStreamService get() {
		return INSTANCE;
	}

	public static final class DrainResult {
		private final List<Map<String, Object>> events;
		private final long droppedEvents;

		private DrainResult(List<Map<String, Object>> events, long droppedEvents) {
			this.events = events;
			this.droppedEvents = droppedEvents;
		}

		public List<Map<String, Object>> getEvents() {
			return events;
		}

		public long getDroppedEvents() {
			return droppedEvents;
		}
	}

	private static final class Session {
		private final String runId;
		private final ArrayDeque<Map<String, Object>> events = new ArrayDeque<>();
		private final Lock lock = new ReentrantLock();
		private long sequence = 0L;
		private long droppedEvents = 0L;
		private boolean overflowWarned = false;
		private int modelCallOrdinal = 0;
		private String activeMessageId = null;
		private final StringBuilder messageText = new StringBuilder();
		private String activeReasoningId = null;
		private final StringBuilder reasoningText = new StringBuilder();
		private final Set<String> completedSubagents = new HashSet<>();
		private volatile Long expiresAtMs = null;
		private final CopyOnWriteArrayList<Consumer<Map<String, Object>>> listeners = new CopyOnWriteArrayList<>();

		private Session(String runId) {
			this.runId = runId;
		}
	}

	/**
	 * Handle for a live subscription; {@link #close} removes the listener so
	 * a disconnected SSE client stops receiving dispatch work.
	 */
	public static final class Subscription implements AutoCloseable {
		private final Session session;
		private final Consumer<Map<String, Object>> listener;

		private Subscription(Session session, Consumer<Map<String, Object>> listener) {
			this.session = session;
			this.listener = listener;
		}

		@Override
		public void close() {
			session.listeners.remove(listener);
		}
	}

	public void register(String runId) {
		if (runId == null || runId.isBlank()) {
			return;
		}
		sweepExpired();
		sessions.computeIfAbsent(runId, Session::new);
	}

	public boolean isRegistered(String jobId) {
		return jobId != null && sessions.containsKey(jobId);
	}

	/**
	 * Translate a raw provider stream envelope into item events. Unrecognized
	 * shapes are suppressed.
	 */
	@SuppressWarnings("unchecked")
	public void acceptEnvelope(String jobId, Map<String, Object> envelope) {
		Session session = jobId == null ? null : sessions.get(jobId);
		if (session == null || envelope == null) {
			return;
		}
		Object dataObj = envelope.get("data");
		if (!(dataObj instanceof Map)) {
			return;
		}
		Map<String, Object> data = (Map<String, Object>) dataObj;
		if (ClaudeCodeRunActivityAdapter.isProviderEnvelope(data)) {
			ClaudeCodeRunActivityAdapter.publishLive(jobId, data, this);
			return;
		}
		if (data.get("kind") != null) {
			return;
		}
		String streamType = String.valueOf(envelope.get("stream_type"));
		if ("content".equals(streamType)) {
			Object delta = data.get("content");
			if (delta instanceof String && !((String) delta).isEmpty()) {
				appendMessageDelta(session, (String) delta);
			}
		} else if ("thinking".equals(streamType)) {
			Object delta = data.get("thinking");
			if (delta instanceof String && !((String) delta).isEmpty()) {
				appendReasoningDelta(session, (String) delta);
			}
		} else {
			logger.debug("AgentRunStreamService: suppressing stream_type={} for runId={}", streamType, jobId);
		}
	}

	public void beginModelCall(String runId) {
		Session session = sessionFor(runId);
		if (session == null) {
			return;
		}
		session.lock.lock();
		try {
			completeActiveReasoningLocked(session);
			completeActiveMessageLocked(session, null, null);
			session.modelCallOrdinal++;
		} finally {
			session.lock.unlock();
		}
	}

	public void completeActiveMessage(String runId, String messageId, String finalText) {
		Session session = sessionFor(runId);
		if (session == null) {
			return;
		}
		session.lock.lock();
		try {
			if (session.activeMessageId == null && finalText != null && !finalText.isBlank()) {
				String itemId = AgentStreamItems.messageItemId(session.runId, session.modelCallOrdinal);
				emitLocked(session, TYPE_ITEM_STARTED, Map.of("item",
						AgentStreamItems.messageItem(itemId, finalText, messageId)));
				emitLocked(session, TYPE_ITEM_COMPLETED, Map.of("item",
						AgentStreamItems.messageItem(itemId, finalText, messageId)));
				return;
			}
			completeActiveMessageLocked(session, messageId, finalText);
		} finally {
			session.lock.unlock();
		}
	}

	public void completeActiveReasoning(String runId) {
		Session session = sessionFor(runId);
		if (session == null) {
			return;
		}
		session.lock.lock();
		try {
			completeActiveReasoningLocked(session);
		} finally {
			session.lock.unlock();
		}
	}

	public void publishMessageCompleted(String runId, String itemId, String text, String messageId) {
		Map<String, Object> item = AgentStreamItems.messageItem(itemId, text, messageId);
		publishItemEvent(runId, TYPE_ITEM_STARTED, item);
		publishItemEvent(runId, TYPE_ITEM_COMPLETED, item);
	}

	public void publishReasoningCompleted(String runId, String itemId, String summary) {
		Map<String, Object> item = AgentStreamItems.reasoningItem(itemId, summary);
		publishItemEvent(runId, TYPE_ITEM_STARTED, item);
		publishItemEvent(runId, TYPE_ITEM_COMPLETED, item);
	}

    /** A complete replacement of the run's current measured progress. */
    public void publishProgress(String runId, Map<String, Object> progress) {
        Map<String, Object> item = new LinkedHashMap<>(progress);
        item.put("id", runId + ":progress");
        item.put("kind", "progress");
        publishItemEvent(runId, TYPE_ITEM_COMPLETED, item);
    }

	public void publishToolStarted(String runId, Map<String, Object> toolItem) {
		publishItemEvent(runId, TYPE_ITEM_STARTED, toolItem);
	}

	public void publishToolUpdated(String runId, String toolCallId, Map<String, Object> patch) {
		Session session = sessionFor(runId);
		if (session == null || toolCallId == null || toolCallId.isBlank()) {
			return;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("itemId", toolCallId);
		payload.put("kind", AgentStreamItems.KIND_TOOL);
		payload.put("patch", patch == null ? new LinkedHashMap<>() : patch);
		emit(session, TYPE_ITEM_UPDATED, payload);
	}

	public void publishToolCompleted(String runId, Map<String, Object> toolItem) {
		publishItemEvent(runId, TYPE_ITEM_COMPLETED, toolItem);
	}

	public void publishSubagentStarted(String parentRunId, Map<String, Object> subagentItem) {
		publishItemEvent(parentRunId, TYPE_ITEM_STARTED, subagentItem);
	}

	public void publishSubagentUpdated(String parentRunId, String childRunId, Map<String, Object> patch) {
		Session session = sessionFor(parentRunId);
		if (session == null || childRunId == null || childRunId.isBlank()) {
			return;
		}
		session.lock.lock();
		try {
			if (session.completedSubagents.contains(childRunId)) {
				return;
			}
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("itemId", childRunId);
			payload.put("kind", AgentStreamItems.KIND_SUBAGENT);
			payload.put("patch", patch == null ? new LinkedHashMap<>() : patch);
			emitLocked(session, TYPE_ITEM_UPDATED, payload);
		} finally {
			session.lock.unlock();
		}
	}

	public void publishSubagentCompleted(String parentRunId, Map<String, Object> subagentItem) {
		Session session = sessionFor(parentRunId);
		if (session == null || subagentItem == null) {
			return;
		}
		String childRunId = String.valueOf(subagentItem.get("childRunId"));
		session.lock.lock();
		try {
			if (!session.completedSubagents.add(childRunId)) {
				return;
			}
			emitLocked(session, TYPE_ITEM_COMPLETED, Map.of("item", subagentItem));
		} finally {
			session.lock.unlock();
		}
	}

	public DrainResult drain(String runId) {
		sweepExpired();
		Session session = sessionFor(runId);
		if (session == null) {
			return new DrainResult(new ArrayList<>(), 0L);
		}
		session.lock.lock();
		try {
			List<Map<String, Object>> drained = new ArrayList<>(session.events);
			session.events.clear();
			long dropped = session.droppedEvents;
			session.droppedEvents = 0L;
			return new DrainResult(drained, dropped);
		} finally {
			session.lock.unlock();
		}
	}

	/**
	 * Subscribe to live item events for a run, replaying first every buffered
	 * event with {@code sequence > afterSequence}. {@code onEvent} is invoked
	 * off the emitting thread (see {@link #dispatchExecutor}); it must not
	 * block indefinitely, and must tolerate being called after the run's
	 * session has gone terminal (a few more events, then nothing further).
	 *
	 * <p>The returned {@link Subscription} must be closed when the caller
	 * disconnects (e.g. the SSE client goes away), or the listener leaks for
	 * the lifetime of the session.
	 *
	 * @return the subscription, or {@code null} if the run has no session
	 *         (never registered, or already swept away).
	 */
	public Subscription subscribe(String runId, long afterSequence, Consumer<Map<String, Object>> onEvent) {
		Session session = sessionFor(runId);
		if (session == null || onEvent == null) {
			return null;
		}
		session.lock.lock();
		try {
			List<Map<String, Object>> backlog = new ArrayList<>();
			for (Map<String, Object> event : session.events) {
				Object seq = event.get("sequence");
				if (seq instanceof Long && (Long) seq > afterSequence) {
					backlog.add(event);
				}
			}
			session.listeners.add(onEvent);
			Subscription subscription = new Subscription(session, onEvent);
			for (Map<String, Object> event : backlog) {
				dispatchExecutor.execute(() -> safeDeliver(onEvent, event));
			}
			return subscription;
		} finally {
			session.lock.unlock();
		}
	}

	private void safeDeliver(Consumer<Map<String, Object>> listener, Map<String, Object> event) {
		try {
			listener.accept(event);
		} catch (Exception e) {
			logger.debug("AgentRunStreamService: live listener threw, dropping it", e);
		}
	}

	public void markTerminal(String runId) {
		Session session = sessionFor(runId);
		if (session == null) {
			return;
		}
		if (session.expiresAtMs == null) {
			session.expiresAtMs = System.currentTimeMillis() + TERMINAL_GRACE_MS;
		}
	}

	public void clear(String runId) {
		if (runId != null) {
			sessions.remove(runId);
		}
	}

	private Session sessionFor(String runId) {
		return runId == null ? null : sessions.get(runId);
	}

	private void appendMessageDelta(Session session, String delta) {
		session.lock.lock();
		try {
			completeActiveReasoningLocked(session);
			if (session.activeMessageId == null) {
				session.activeMessageId = AgentStreamItems.messageItemId(session.runId, session.modelCallOrdinal);
				session.messageText.setLength(0);
				emitLocked(session, TYPE_ITEM_STARTED, Map.of("item",
						AgentStreamItems.messageItem(session.activeMessageId, "", null)));
			}
			session.messageText.append(delta);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("itemId", session.activeMessageId);
			payload.put("kind", AgentStreamItems.KIND_MESSAGE);
			payload.put("delta", delta);
			emitLocked(session, TYPE_ITEM_UPDATED, payload);
		} finally {
			session.lock.unlock();
		}
	}

	private void appendReasoningDelta(Session session, String delta) {
		session.lock.lock();
		try {
			if (session.activeReasoningId == null) {
				session.activeReasoningId = AgentStreamItems.reasoningItemId(session.runId, session.modelCallOrdinal);
				session.reasoningText.setLength(0);
				emitLocked(session, TYPE_ITEM_STARTED, Map.of("item",
						AgentStreamItems.reasoningItem(session.activeReasoningId, "")));
			}
			session.reasoningText.append(delta);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("itemId", session.activeReasoningId);
			payload.put("kind", AgentStreamItems.KIND_REASONING);
			payload.put("delta", delta);
			emitLocked(session, TYPE_ITEM_UPDATED, payload);
		} finally {
			session.lock.unlock();
		}
	}

	private void completeActiveMessageLocked(Session session, String messageId, String finalText) {
		if (session.activeMessageId == null) {
			return;
		}
		String text = finalText != null ? finalText : session.messageText.toString();
		emitLocked(session, TYPE_ITEM_COMPLETED, Map.of("item",
				AgentStreamItems.messageItem(session.activeMessageId, text, messageId)));
		session.activeMessageId = null;
		session.messageText.setLength(0);
	}

	private void completeActiveReasoningLocked(Session session) {
		if (session.activeReasoningId == null) {
			return;
		}
		emitLocked(session, TYPE_ITEM_COMPLETED, Map.of("item",
				AgentStreamItems.reasoningItem(session.activeReasoningId, session.reasoningText.toString())));
		session.activeReasoningId = null;
		session.reasoningText.setLength(0);
	}

	private void publishItemEvent(String runId, String type, Map<String, Object> item) {
		Session session = sessionFor(runId);
		if (session == null || item == null) {
			return;
		}
		emit(session, type, Map.of("item", item));
	}

	private void emit(Session session, String type, Map<String, Object> payload) {
		session.lock.lock();
		try {
			emitLocked(session, type, payload);
		} finally {
			session.lock.unlock();
		}
	}

	private void emitLocked(Session session, String type, Map<String, Object> payload) {
		session.sequence++;
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("version", 1);
		event.put("eventId", session.runId + ":" + session.sequence);
		event.put("sequence", session.sequence);
		event.put("runId", session.runId);
		event.put("timestamp", Instant.now().toString());
		event.put("type", type);
		event.putAll(payload);
		session.events.addLast(event);
		if (session.events.size() > MAX_EVENTS_PER_RUN) {
			session.events.removeFirst();
			session.droppedEvents++;
			if (!session.overflowWarned) {
				session.overflowWarned = true;
				logger.warn("AgentRunStreamService: event buffer overflow for runId={}; dropping oldest events",
						session.runId);
			}
		}
		dispatchToListeners(session, event);
	}

	/**
	 * Hands the event to each live listener on {@link #dispatchExecutor},
	 * never on the caller's thread. Snapshotting {@code session.listeners}
	 * (a CopyOnWriteArrayList) is cheap and safe to iterate even if a
	 * subscriber attaches or detaches concurrently.
	 */
	private void dispatchToListeners(Session session, Map<String, Object> event) {
		if (session.listeners.isEmpty()) {
			return;
		}
		for (Consumer<Map<String, Object>> listener : session.listeners) {
			dispatchExecutor.execute(() -> safeDeliver(listener, event));
		}
	}

	private void sweepExpired() {
		long now = System.currentTimeMillis();
		for (Map.Entry<String, Session> entry : sessions.entrySet()) {
			Long expiresAt = entry.getValue().expiresAtMs;
			if (expiresAt != null && now > expiresAt) {
				sessions.remove(entry.getKey(), entry.getValue());
			}
		}
	}
}
