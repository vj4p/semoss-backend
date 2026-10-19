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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClaudeCodeRunActivityAdapterTest {

	private static Map<String, Object> toolResult(Map<String, Object> data) {
		Map<String, Object> event = new HashMap<>();
		event.put("event", "tool_result");
		event.put("data", data);
		return event;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> completedItem(Map<String, Object> data) {
		AgentRunStreamService streams = mock(AgentRunStreamService.class);
		ClaudeCodeRunActivityAdapter.publishLive("run-1", toolResult(data), streams);
		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(streams).publishToolCompleted(eq("run-1"), captor.capture());
		return captor.getValue();
	}

	@Test
	void aCompletionNamesTheToolAndKeepsItsArguments() {
		Map<String, Object> data = new HashMap<>();
		data.put("toolUseId", "t1");
		data.put("status", "completed");
		data.put("content", "done");
		data.put("toolName", "Bash");
		data.put("arguments", Map.of("command", "echo hi"));

		Map<String, Object> item = completedItem(data);

		assertEquals("t1", item.get("id"));
		assertEquals("Bash", item.get("name"));
		assertEquals(Map.of("command", "echo hi"), item.get("arguments"));
		assertEquals(AgentStreamItems.TOOL_COMPLETED, item.get("status"));
		assertEquals("done", item.get("output"));
	}

	@Test
	void aFailedCompletionCarriesTheErrorAndStillNamesTheTool() {
		Map<String, Object> data = new HashMap<>();
		data.put("toolUseId", "t2");
		data.put("status", "error");
		data.put("content", "boom");
		data.put("toolName", "Write");
		data.put("arguments", Map.of("file_path", "a.txt"));

		Map<String, Object> item = completedItem(data);

		assertEquals(AgentStreamItems.TOOL_FAILED, item.get("status"));
		assertEquals("boom", item.get("error"));
		assertEquals("Write", item.get("name"));
		assertFalse(item.containsKey("output"));
	}

	@Test
	void aCompletionFromAnOlderClientStillPublishesAsBefore() {
		Map<String, Object> data = new HashMap<>();
		data.put("toolUseId", "t3");
		data.put("status", "completed");
		data.put("content", "ok");

		Map<String, Object> item = completedItem(data);

		assertEquals("t3", item.get("id"));
		assertEquals("ok", item.get("output"));
		assertNull(item.get("name"));
	}

	@Test
	void aCompletionWithoutAToolIdIsIgnored() {
		AgentRunStreamService streams = mock(AgentRunStreamService.class);
		Map<String, Object> data = new HashMap<>();
		data.put("status", "completed");
		ClaudeCodeRunActivityAdapter.publishLive("run-1", toolResult(data), streams);
		verify(streams, never()).publishToolCompleted(any(), any());
	}
}
