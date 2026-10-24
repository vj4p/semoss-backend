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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

class ClaudeCodeApprovalsTest {

	private static final Gson GSON = new Gson();

	private static Map<String, Object> action(String toolName, String args, String status) {
		Map<String, Object> a = new HashMap<>();
		a.put("actionId", "act-" + toolName);
		a.put("toolCallId", "call-" + toolName);
		a.put("toolName", toolName);
		a.put("toolArgs", args);
		a.put("status", status);
		a.put("toolMeta", "{\"SMSS_HARNESS_NATIVE\":\"claude_code\"}");
		a.put("toolStatus", ClaudeCodeApprovals.TOOL_STATUS_AWAITING_RESUME);
		return a;
	}

	@Test
	void modeDefaultsToMutatingAndHonoursKnownValues() {
		assertEquals("mutating", ClaudeCodeApprovals.resolveMode(null));
		assertEquals("mutating", ClaudeCodeApprovals.resolveMode(""));
		assertEquals("mutating", ClaudeCodeApprovals.resolveMode("   "));
		assertEquals("off", ClaudeCodeApprovals.resolveMode("off"));
		assertEquals("off", ClaudeCodeApprovals.resolveMode(" OFF "));
		assertEquals("all", ClaudeCodeApprovals.resolveMode("All"));
		assertEquals("mutating", ClaudeCodeApprovals.resolveMode("mutating"));
	}

	@Test
	void theEnvironmentVariableCountsWhenThePropertyIsUnset() {
		assertEquals("off", ClaudeCodeApprovals.resolveMode(ClaudeCodeApprovals.firstSet(null, "off")));
		assertEquals("off", ClaudeCodeApprovals.resolveMode(ClaudeCodeApprovals.firstSet("  ", "off")));
		assertEquals("all", ClaudeCodeApprovals.resolveMode(ClaudeCodeApprovals.firstSet("all", "off")));
		assertEquals("mutating", ClaudeCodeApprovals.resolveMode(ClaudeCodeApprovals.firstSet(null, null)));
	}

	@Test
	void aTypoNeverSwitchesApprovalOff() {
		assertEquals("mutating", ClaudeCodeApprovals.resolveMode("of"));
		assertEquals("mutating", ClaudeCodeApprovals.resolveMode("false"));
	}

	@Test
	void parsePendingReadsTheClientsJson() {
		List<Map<String, Object>> pending = ClaudeCodeApprovals.parsePending(
				"[{\"toolCallId\":\"c1\",\"toolName\":\"Bash\",\"toolArgs\":{\"command\":\"echo hi > a.txt\"}}]");
		assertEquals(1, pending.size());
		assertEquals("Bash", pending.get(0).get("toolName"));
	}

	@Test
	void parsePendingReadsBase64WithAwkwardCharactersIntact() {
		String json = "[{\"toolCallId\":\"c1\",\"toolName\":\"Bash\",\"toolArgs\":{\"command\":\"echo \\\"hi\\\" > a.txt && cat <<EOF\"}}]";
		String encoded = java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		List<Map<String, Object>> pending = ClaudeCodeApprovals.parsePending(encoded);

		assertEquals(1, pending.size());
		assertEquals("echo \"hi\" > a.txt && cat <<EOF", ((Map<?, ?>) pending.get(0).get("toolArgs")).get("command"));
		assertTrue(ClaudeCodeApprovals.parsePending(java.util.Base64.getEncoder().encodeToString("[]".getBytes())).isEmpty());
	}

	@Test
	void parsePendingTreatsAnythingUnusableAsNothingPending() {
		assertTrue(ClaudeCodeApprovals.parsePending(null).isEmpty());
		assertTrue(ClaudeCodeApprovals.parsePending("").isEmpty());
		assertTrue(ClaudeCodeApprovals.parsePending("[]").isEmpty());
		assertTrue(ClaudeCodeApprovals.parsePending("None").isEmpty());
		assertTrue(ClaudeCodeApprovals.parsePending("{\"not\":\"a list\"}").isEmpty());
		assertTrue(ClaudeCodeApprovals.parsePending("[{broken").isEmpty());
		assertTrue(ClaudeCodeApprovals.parsePending("%%%not-base64%%%").isEmpty());
	}

	@Test
	void pendingCallsBecomeTaggedActionRows() {
		Map<String, Object> call = new HashMap<>();
		call.put("toolCallId", "c1");
		call.put("toolName", "Write");
		Map<String, Object> args = new HashMap<>();
		args.put("file_path", "a.txt");
		call.put("toolArgs", args);
		Map<String, Object> noName = new HashMap<>();
		noName.put("toolCallId", "c2");

		List<Map<String, Object>> rows = ClaudeCodeApprovals.toActionRows(List.of(call, noName));

		assertEquals(1, rows.size(), "a call with no tool name is dropped");
		Map<String, Object> row = rows.get(0);
		assertNotNull(row.get("actionId"));
		assertEquals("c1", row.get("toolCallId"));
		assertEquals("Write", row.get("toolName"));
		assertNull(row.get("parentMessageId"));
		assertTrue(ClaudeCodeApprovals.isHarnessNative(row), "rows carry the harness-native tag");
	}

	@Test
	void pauseToolCallsCarryWhatTheExceptionNeeds() {
		Map<String, Object> call = new HashMap<>();
		call.put("toolCallId", "c1");
		call.put("toolName", "Bash");
		call.put("toolArgs", Map.of("command", "rm x"));
		List<Map<String, Object>> calls = ClaudeCodeApprovals
				.toPauseToolCalls(ClaudeCodeApprovals.toActionRows(List.of(call)));
		assertEquals("c1", calls.get(0).get("id"));
		assertEquals("Bash", calls.get(0).get("name"));
		assertNotNull(calls.get(0).get("arguments"));
		assertNotNull(calls.get(0).get("_meta"));
	}

	@Test
	void onlyTaggedActionsAreHarnessNative() {
		assertFalse(ClaudeCodeApprovals.isHarnessNative(null));
		Map<String, Object> plain = new HashMap<>();
		plain.put("toolMeta", "{\"SMSS_MCP_EXECUTION\":\"ask\"}");
		assertFalse(ClaudeCodeApprovals.isHarnessNative(plain));
		plain.put("toolMeta", null);
		assertFalse(ClaudeCodeApprovals.isHarnessNative(plain));
		assertTrue(ClaudeCodeApprovals.isHarnessNative(action("Bash", "{}", "PENDING")));
		Map<String, Object> other = new HashMap<>();
		other.put("toolMeta", "{\"SMSS_HARNESS_NATIVE\":\"github_copilot\"}");
		assertFalse(ClaudeCodeApprovals.isHarnessNative(other));
	}

	@Test
	void decisionsMapFromTheStoredStatus() {
		assertEquals("approve", ClaudeCodeApprovals.decisionOf(action("A", "{}", "APPROVED")));
		assertEquals("edit", ClaudeCodeApprovals.decisionOf(action("A", "{}", "EDITED")));
		assertEquals("reject", ClaudeCodeApprovals.decisionOf(action("A", "{}", "REJECTED")));
		assertEquals("reject", ClaudeCodeApprovals.decisionOf(action("A", "{}", "RESPONDED")));
		assertNull(ClaudeCodeApprovals.decisionOf(action("A", "{}", "PENDING")));
		assertNull(ClaudeCodeApprovals.decisionOf(action("A", "{}", "EXECUTING")));
	}

	@Test
	void awaitingResumeKeepsOnlyDecidedUnappliedNativeActions() {
		Map<String, Object> approved = action("Bash", "{}", "APPROVED");
		Map<String, Object> pending = action("Write", "{}", "PENDING");
		Map<String, Object> applied = action("Edit", "{}", "APPROVED");
		applied.put("toolStatus", ClaudeCodeApprovals.TOOL_STATUS_APPLIED);
		Map<String, Object> notNative = action("Other", "{}", "APPROVED");
		notNative.put("toolMeta", "{}");

		List<Map<String, Object>> picked = ClaudeCodeApprovals
				.awaitingResume(List.of(approved, pending, applied, notNative));

		assertEquals(1, picked.size());
		assertEquals("Bash", picked.get(0).get("toolName"));
		assertTrue(ClaudeCodeApprovals.awaitingResume(null).isEmpty());
	}

	@Test
	void decisionsJsonCarriesWhatThePythonGateMatchesOn() {
		Map<String, Object> edited = action("Bash", "{\"command\":\"rm -rf build\"}", "EDITED");
		edited.put("editedArgs", "{\"command\":\"rm -rf build/tmp\"}");

		List<Map<String, Object>> parsed = GSON.fromJson(ClaudeCodeApprovals.decisionsJson(List.of(edited)),
				new com.google.gson.reflect.TypeToken<List<Map<String, Object>>>() {
				}.getType());

		assertEquals(1, parsed.size());
		Map<String, Object> entry = parsed.get(0);
		assertEquals("Bash", entry.get("toolName"));
		assertEquals("edit", entry.get("decision"));
		assertEquals("call-Bash", entry.get("toolCallId"));
		assertEquals("rm -rf build", ((Map<?, ?>) entry.get("toolArgs")).get("command"));
		assertEquals("rm -rf build/tmp", ((Map<?, ?>) entry.get("editedArgs")).get("command"));
	}

	@Test
	void continuationPromptStatesEachDecisionAndSaysToCarryOn() {
		Map<String, Object> approved = action("Bash", "{\"command\":\"echo hi > a.txt\"}", "APPROVED");
		Map<String, Object> edited = action("Write", "{\"file_path\":\"a\"}", "EDITED");
		edited.put("editedArgs", "{\"file_path\":\"b\"}");
		Map<String, Object> declined = action("Edit", "{\"file_path\":\"c\"}", "REJECTED");
		List<Map<String, Object>> all = new ArrayList<>(List.of(approved, edited, declined));

		String prompt = ClaudeCodeApprovals.continuationPrompt(all);

		assertTrue(prompt.contains("APPROVED: Bash"), prompt);
		assertTrue(prompt.contains("echo hi > a.txt"), prompt);
		assertTrue(prompt.contains("APPROVED WITH CHANGES: Write"), prompt);
		assertTrue(prompt.contains("\"file_path\":\"b\""), "an edited call shows the edited arguments: " + prompt);
		assertTrue(prompt.contains("DECLINED: Edit"), prompt);
		assertTrue(prompt.contains("Carry on with that task"), prompt);
		assertFalse(prompt.contains("The user wrote"), "a plain rejection carries no note");
	}

	@Test
	void theOriginalTaskIsRestatedAndADeclineSaysWhatToDoNext() {
		Map<String, Object> declined = action("Bash", "{\"command\":\"rm x\"}", "REJECTED");
		String prompt = ClaudeCodeApprovals.continuationPrompt(List.of(declined), "Clean up the build folder");
		assertTrue(prompt.contains("Your task was: Clean up the build folder"), prompt);
		assertTrue(prompt.contains("tell the user plainly what you could not do"), prompt);

		Map<String, Object> approved = action("Bash", "{\"command\":\"ls\"}", "APPROVED");
		String ok = ClaudeCodeApprovals.continuationPrompt(List.of(approved), "List files");
		assertTrue(ok.contains("Your task was: List files"), ok);
		assertFalse(ok.contains("could not do"), "an approval needs no decline advice: " + ok);
		assertFalse(ClaudeCodeApprovals.continuationPrompt(List.of(approved), "  ").contains("Your task was"));
	}

	@Test
	void aWrittenReplyReachesTheAgentAsTheDeclineNote() {
		Map<String, Object> replied = action("Bash", "{\"command\":\"rm x\"}", "RESPONDED");
		replied.put("result", "use git rm instead");
		String prompt = ClaudeCodeApprovals.continuationPrompt(List.of(replied));
		assertTrue(prompt.contains("DECLINED: Bash"), prompt);
		assertTrue(prompt.contains("The user wrote: use git rm instead"), prompt);
	}

	@Test
	void veryLongArgumentsAreShortenedInThePrompt() {
		StringBuilder big = new StringBuilder("{\"content\":\"");
		for (int i = 0; i < 5000; i++) {
			big.append('x');
		}
		big.append("\"}");
		String prompt = ClaudeCodeApprovals.continuationPrompt(List.of(action("Write", big.toString(), "APPROVED")));
		assertTrue(prompt.length() < 2500, "prompt length " + prompt.length());
		assertTrue(prompt.contains("..."));
	}
}
