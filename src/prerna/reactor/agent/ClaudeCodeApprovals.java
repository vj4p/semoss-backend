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
package prerna.reactor.agent;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.f4b6a3.uuid.alt.GUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import prerna.util.Utility;
import prerna.util.ValueUtils;

/**
 * Approval for Claude Code runs: which calls pause, how a paused call is stored, and how a decision is handed back.
 *
 * <p>
 * SEMOSS native executes an approved tool itself. Claude's Bash and Write run inside the CLI, so approval here is
 * stop and resume: the Python client denies a call that needs a person (ending the turn) and reports it; this class
 * turns that report into {@code AGENT_RUN_ACTION} rows; after the decision the run resumes the same Claude session
 * with a short continuation message and the decisions, and Claude's retry of an approved call is allowed once.
 *
 * <p>
 * The policy (which tools ask) lives in {@code approval_policy.py}; this side only carries the mode.
 */
public final class ClaudeCodeApprovals {

	private static final Logger classLogger = LogManager.getLogger(ClaudeCodeApprovals.class);
	// No HTML escaping: the continuation prompt shows commands such as "echo hi > a.txt" to the model as typed.
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
	}.getType();
	private static final Type LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {
	}.getType();

	/** Setting that picks the policy: off, mutating (default) or all. */
	public static final String CFG_APPROVAL = "CLAUDE_CODE_APPROVAL";
	public static final String MODE_OFF = "off";
	public static final String MODE_MUTATING = "mutating";
	public static final String MODE_ALL = "all";

	/** {@code toolMeta} key that marks an action as belonging to a harness that runs its own tools. */
	public static final String META_HARNESS_NATIVE = "SMSS_HARNESS_NATIVE";
	public static final String HARNESS_NAME = ClaudeCodeAgentHarness.NAME;

	/** Decided, but Claude has not been told yet. */
	public static final String TOOL_STATUS_AWAITING_RESUME = "awaiting_resume";
	/** Claude has been told. Keeps an old approval from covering a later identical call. */
	public static final String TOOL_STATUS_APPLIED = "applied";

	private static final int MAX_ARGS_CHARS = 1500;
	private static final int MAX_TASK_CHARS = 1000;

	private ClaudeCodeApprovals() {
	}

	/**
	 * The mode from the setting. Unset or unrecognised means {@code mutating}: the safe choice, so a typo cannot
	 * quietly switch approval off.
	 */
	public static String resolveMode() {
		return resolveMode(Utility.getDIHelperProperty(CFG_APPROVAL));
	}

	static String resolveMode(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return MODE_MUTATING;
		}
		String mode = raw.trim().toLowerCase(Locale.ROOT);
		if (MODE_OFF.equals(mode) || MODE_MUTATING.equals(mode) || MODE_ALL.equals(mode)) {
			return mode;
		}
		classLogger.warn("{}='{}' is not off, mutating or all; using {}", CFG_APPROVAL, raw, MODE_MUTATING);
		return MODE_MUTATING;
	}

	/**
	 * Parses the Python client's {@code take_pending_approvals()} output: base64 of a JSON list (the value crosses the
	 * Python bridge as a string, which does not preserve quotes and angle brackets in a command). A plain JSON list is
	 * accepted too. Anything unusable is "nothing pending".
	 */
	public static List<Map<String, Object>> parsePending(String encoded) {
		if (encoded == null) {
			return Collections.emptyList();
		}
		String trimmed = encoded.trim();
		if (trimmed.isEmpty()) {
			return Collections.emptyList();
		}
		String json = trimmed;
		if (!trimmed.startsWith("[")) {
			try {
				json = new String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8).trim();
			} catch (IllegalArgumentException e) {
				classLogger.warn("The pending approvals from the Claude Code client are neither JSON nor base64");
				return Collections.emptyList();
			}
		}
		if (!json.startsWith("[")) {
			return Collections.emptyList();
		}
		try {
			List<Map<String, Object>> parsed = GSON.fromJson(json, LIST_TYPE);
			return parsed == null ? Collections.emptyList() : parsed;
		} catch (RuntimeException e) {
			classLogger.warn("Could not read the pending approvals from the Claude Code client: {}", e.getMessage());
			return Collections.emptyList();
		}
	}

	/** {@code AGENT_RUN_ACTION} rows for the calls the client stopped on. */
	public static List<Map<String, Object>> toActionRows(List<Map<String, Object>> pending) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> call : pending) {
			String toolName = ValueUtils.trimToNull(call.get("toolName"));
			String toolCallId = ValueUtils.trimToNull(call.get("toolCallId"));
			if (toolName == null || toolCallId == null) {
				continue;
			}
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put(META_HARNESS_NATIVE, HARNESS_NAME);
			meta.put("SMSS_ORIGINAL_TOOL_NAME", toolName);
			Map<String, Object> row = new HashMap<>();
			row.put("actionId", GUID.v7().toUUID().toString());
			row.put("parentMessageId", null);
			row.put("toolCallId", toolCallId);
			row.put("toolName", toolName);
			row.put("toolArgs", call.get("toolArgs"));
			row.put("toolMeta", meta);
			row.put("hasUi", false);
			row.put("uiUrl", null);
			rows.add(row);
		}
		return rows;
	}

	/** The shape {@code AgentInputRequiredException} carries: one map per paused call. */
	public static List<Map<String, Object>> toPauseToolCalls(List<Map<String, Object>> rows) {
		List<Map<String, Object>> calls = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			Map<String, Object> call = new HashMap<>();
			call.put("id", row.get("toolCallId"));
			call.put("name", row.get("toolName"));
			call.put("arguments", row.get("toolArgs"));
			call.put("_meta", row.get("toolMeta"));
			calls.add(call);
		}
		return calls;
	}

	/** Whether a stored action was created by this class (the decision must not execute a tool for it). */
	public static boolean isHarnessNative(Map<String, Object> action) {
		if (action == null) {
			return false;
		}
		Map<String, Object> meta = asMap(action.get("toolMeta"));
		return meta != null && HARNESS_NAME.equals(String.valueOf(meta.get(META_HARNESS_NATIVE)));
	}

	/** Decided native actions Claude has not been told about yet, oldest first. */
	public static List<Map<String, Object>> awaitingResume(List<Map<String, Object>> actionsForRun) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (actionsForRun == null) {
			return out;
		}
		for (Map<String, Object> action : actionsForRun) {
			if (isHarnessNative(action) && decisionOf(action) != null
					&& TOOL_STATUS_AWAITING_RESUME.equals(ValueUtils.trimToNull(action.get("toolStatus")))) {
				out.add(action);
			}
		}
		return out;
	}

	/** approve, edit or reject for a decided row; null while it is still pending. A written reply counts as a no. */
	static String decisionOf(Map<String, Object> action) {
		String status = ValueUtils.trimToNull(action.get("status"));
		if (status == null) {
			return null;
		}
		switch (status) {
		case "APPROVED":
			return "approve";
		case "EDITED":
			return "edit";
		case "REJECTED":
		case "RESPONDED":
			return "reject";
		default:
			return null;
		}
	}

	/** The argument for {@code claude_code.set_decisions(...)}. */
	public static String decisionsJson(List<Map<String, Object>> decided) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> action : decided) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("toolCallId", action.get("toolCallId"));
			entry.put("toolName", action.get("toolName"));
			entry.put("toolArgs", asMap(action.get("toolArgs")));
			entry.put("editedArgs", asMap(action.get("editedArgs")));
			entry.put("decision", decisionOf(action));
			out.add(entry);
		}
		return GSON.toJson(out);
	}

	/** {@link #continuationPrompt(List, String)} without the original task. */
	public static String continuationPrompt(List<Map<String, Object>> decided) {
		return continuationPrompt(decided, null);
	}

	/**
	 * What Claude is told when the run resumes. It states each decision, restates the task (a small model otherwise
	 * loses the thread and asks what to do), and says what a decline means, so the retry of an approved call is not a
	 * surprise and a declined one is not attempted again.
	 */
	public static String continuationPrompt(List<Map<String, Object>> decided, String originalTask) {
		StringBuilder sb = new StringBuilder();
		sb.append("The user reviewed the actions you asked to run:\n");
		boolean anyDeclined = false;
		for (Map<String, Object> action : decided) {
			String tool = String.valueOf(action.get("toolName"));
			String decision = decisionOf(action);
			Map<String, Object> original = asMap(action.get("toolArgs"));
			Map<String, Object> edited = asMap(action.get("editedArgs"));
			if ("approve".equals(decision)) {
				sb.append("- APPROVED: ").append(tool).append(' ').append(brief(original))
						.append(" - run it now, exactly as approved.\n");
			} else if ("edit".equals(decision)) {
				sb.append("- APPROVED WITH CHANGES: ").append(tool).append(" - run it with these arguments instead: ")
						.append(brief(edited != null ? edited : original)).append('\n');
			} else {
				anyDeclined = true;
				sb.append("- DECLINED: ").append(tool).append(' ').append(brief(original))
						.append(" - do not run it or anything equivalent.");
				String note = replyNote(action);
				if (note != null) {
					sb.append(" The user wrote: ").append(note);
				}
				sb.append('\n');
			}
		}
		String task = ValueUtils.trimToNull(originalTask);
		if (task != null) {
			sb.append("Your task was: ").append(task.length() <= MAX_TASK_CHARS ? task : task.substring(0, MAX_TASK_CHARS) + "...")
					.append('\n');
		}
		sb.append("Carry on with that task. Do not ask again for anything that was approved.");
		if (anyDeclined) {
			sb.append(" For anything declined, either take a different approach that does not need it, or tell the user"
					+ " plainly what you could not do.");
		}
		return sb.toString();
	}

	/** A written reply is stored as the action's result; a plain rejection stores a stock sentence that is not a note. */
	private static String replyNote(Map<String, Object> action) {
		if (!"RESPONDED".equals(ValueUtils.trimToNull(action.get("status")))) {
			return null;
		}
		return ValueUtils.trimToNull(action.get("result"));
	}

	private static String brief(Map<String, Object> args) {
		if (args == null) {
			return "";
		}
		String json = GSON.toJson(args);
		return json.length() <= MAX_ARGS_CHARS ? json : json.substring(0, MAX_ARGS_CHARS) + "...";
	}

	/** A stored action carries JSON in strings, a freshly built one carries maps; accept both. */
	@SuppressWarnings("unchecked")
	static Map<String, Object> asMap(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Map) {
			return (Map<String, Object>) value;
		}
		String text = ValueUtils.trimToNull(value);
		if (text == null || !text.startsWith("{")) {
			return null;
		}
		try {
			return GSON.fromJson(text, MAP_TYPE);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
