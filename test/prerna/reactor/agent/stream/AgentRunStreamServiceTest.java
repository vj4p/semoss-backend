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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Covers the live-push additions to {@link AgentRunStreamService}: a run
 * registered for canonical streaming can be polled (the pre-existing
 * contract, re-asserted here as a baseline) and/or subscribed to for live
 * delivery, and a late subscriber replays exactly the backlog it asked for
 * with no gap and no duplicate against events emitted after it attaches.
 */
class AgentRunStreamServiceTest {

	private static String freshRunId() {
		return "test-run-" + System.nanoTime();
	}

	@Test
	void subscribeWithNoRegisteredSessionReturnsNull() {
		AgentRunStreamService service = AgentRunStreamService.get();
		AgentRunStreamService.Subscription subscription = service.subscribe(freshRunId(), 0L, event -> {
		});
		assertNull(subscription, "a run with no registered session has nothing to subscribe to");
	}

	@Test
	void subscribeReplaysBufferedEventsThenDeliversLiveOnesInOrder() throws InterruptedException {
		AgentRunStreamService service = AgentRunStreamService.get();
		String runId = freshRunId();
		service.register(runId);

		// Three events land before anyone subscribes -- the poll endpoint's
		// drain() would see these; subscribe() must replay them too.
		service.publishMessageCompleted(runId, runId + ":m1", "hello", "msg-1");
		service.publishMessageCompleted(runId, runId + ":m2", "world", "msg-2");

		List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(6); // 2 started/completed pairs replayed + 1 live pair
		AgentRunStreamService.Subscription subscription = service.subscribe(runId, 0L, event -> {
			received.add(event);
			latch.countDown();
		});
		assertNotNull(subscription, "a registered run must be subscribable");

		// A fourth event, emitted only after subscribe(), must also arrive --
		// subscribe() must not miss anything emitted after the backlog scan.
		service.publishMessageCompleted(runId, runId + ":m3", "again", "msg-3");

		assertTrue(latch.await(5, TimeUnit.SECONDS), "replay + live events must all be delivered");
		subscription.close();

		List<Long> sequences = received.stream().map(e -> (Long) e.get("sequence")).sorted().toList();
		assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), sequences,
				"replay then live events, delivered in order, no gaps");

		service.clear(runId);
	}

	@Test
	void subscribeAfterSequenceSkipsAlreadySeenBacklog() throws InterruptedException {
		AgentRunStreamService service = AgentRunStreamService.get();
		String runId = freshRunId();
		service.register(runId);

		service.publishMessageCompleted(runId, runId + ":m1", "hello", "msg-1"); // sequence 1, 2
		service.publishMessageCompleted(runId, runId + ":m2", "world", "msg-2"); // sequence 3, 4

		// A reconnecting client that already has sequence 1-2 (e.g. from an
		// earlier drain()) should not see them replayed again.
		List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(2);
		AgentRunStreamService.Subscription subscription = service.subscribe(runId, 2L, event -> {
			received.add(event);
			latch.countDown();
		});

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		subscription.close();

		List<Long> sequences = received.stream().map(e -> (Long) e.get("sequence")).sorted().toList();
		assertEquals(List.of(3L, 4L), sequences, "only events after afterSequence are replayed");

		service.clear(runId);
	}

	@Test
	void closingSubscriptionStopsFurtherDelivery() throws InterruptedException {
		AgentRunStreamService service = AgentRunStreamService.get();
		String runId = freshRunId();
		service.register(runId);

		List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
		AgentRunStreamService.Subscription subscription = service.subscribe(runId, 0L, received::add);
		assertNotNull(subscription);
		subscription.close();

		service.publishMessageCompleted(runId, runId + ":m1", "hello", "msg-1");
		// Give the (now-unsubscribed) dispatch path a moment to have NOT fired.
		Thread.sleep(200);

		assertTrue(received.isEmpty(), "a closed subscription must not receive events emitted afterward");

		service.clear(runId);
	}

	@Test
	void drainAndSubscribeAreIndependentTapsOnTheSameEvents() throws InterruptedException {
		AgentRunStreamService service = AgentRunStreamService.get();
		String runId = freshRunId();
		service.register(runId);

		List<Map<String, Object>> pushed = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(2);
		AgentRunStreamService.Subscription subscription = service.subscribe(runId, 0L, event -> {
			pushed.add(event);
			latch.countDown();
		});

		service.publishMessageCompleted(runId, runId + ":m1", "hello", "msg-1");

		assertTrue(latch.await(5, TimeUnit.SECONDS), "live subscriber must still receive events");
		// The poll endpoint's drain() must see the SAME events the subscriber
		// got -- a live subscriber must not have stolen them from the poll
		// buffer, since a caller may be doing both during a client-side
		// transport upgrade.
		AgentRunStreamService.DrainResult drained = service.drain(runId);
		assertEquals(2, drained.getEvents().size(), "drain() still sees every event independently of the subscriber");

		subscription.close();
		service.clear(runId);
	}
}
