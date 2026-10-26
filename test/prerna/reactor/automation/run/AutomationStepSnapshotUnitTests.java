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
package prerna.reactor.automation.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import prerna.reactor.automation.AutomationConstants;
import prerna.reactor.automation.utils.AutomationRuntimeUtils;

class AutomationStepSnapshotUnitTests {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parse(String json) {
		return AutomationRuntimeUtils.GSON.fromJson(json, AutomationRuntimeUtils.MAP_TYPE);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> variable(Map<String, Object> record, String name) {
		return ((List<Map<String, Object>>) record.get("variables")).stream().filter(v -> name.equals(v.get("name")))
				.findFirst().orElseThrow();
	}

	@Test
	void secretNamedVariablesAreRedacted() {
		Map<String, Object> scope = new LinkedHashMap<>();
		scope.put("city", "Ashburn");
		scope.put("api_key", "sk-live-123");
		scope.put("bearer_token", "abc");
		Map<String, Object> record = parse(AutomationStepSnapshot.toJson(scope));

		assertEquals("Ashburn", variable(record, "city").get("value"));
		Map<String, Object> key = variable(record, "api_key");
		assertEquals(AutomationConstants.STEP_INPUT_REDACTED, key.get("value"));
		assertEquals(Boolean.TRUE, key.get("redacted"));
		assertEquals(AutomationConstants.STEP_INPUT_REDACTED, variable(record, "bearer_token").get("value"));
		assertFalse(AutomationStepSnapshot.toJson(scope).contains("sk-live-123"));
	}

	@Test
	void secretNamedKeysInsideAMapAreRedacted() {
		Map<String, Object> headers = new LinkedHashMap<>();
		headers.put("Accept", "application/json");
		headers.put("Authorization", "Bearer xyz");
		Map<String, Object> scope = Map.of("request", Map.of("headers", headers));
		String json = AutomationStepSnapshot.toJson(scope);

		assertFalse(json.contains("Bearer xyz"));
		assertTrue(json.contains("application/json"));
		assertTrue(json.contains(AutomationConstants.STEP_INPUT_REDACTED));
	}

	@Test
	void anUnsetSecretStaysNullInsteadOfLookingSet() {
		Map<String, Object> scope = new LinkedHashMap<>();
		scope.put("password", null);
		Map<String, Object> password = variable(parse(AutomationStepSnapshot.toJson(scope)), "password");
		assertNull(password.get("value"));
		assertEquals(Boolean.FALSE, password.get("redacted"));
	}

	@Test
	void longValuesAreCutAndFlagged() {
		String longText = "x".repeat(AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_VALUE_LENGTH + 500);
		Map<String, Object> v = variable(parse(AutomationStepSnapshot.toJson(Map.of("body", longText))), "body");
		assertEquals(AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_VALUE_LENGTH, ((String) v.get("value")).length());
		assertEquals(Boolean.TRUE, v.get("truncated"));
	}

	@Test
	void theVariableCountIsCappedAndTheRestCounted() {
		Map<String, Object> scope = new LinkedHashMap<>();
		for (int i = 0; i < AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_VARIABLES + 7; i++) {
			scope.put("v" + i, i);
		}
		Map<String, Object> record = parse(AutomationStepSnapshot.toJson(scope));
		assertEquals(AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_VARIABLES,
				((List<?>) record.get("variables")).size());
		assertEquals(7.0, ((Number) record.get("omitted")).doubleValue());
	}

	@Test
	void anOversizedRecordKeepsNamesOnly() {
		Map<String, Object> scope = new LinkedHashMap<>();
		// three bytes per character in UTF-8, so the values together pass the byte cap
		String big = String.valueOf((char) 0x4E2D).repeat(AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_VALUE_LENGTH);
		for (int i = 0; i < AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_VARIABLES; i++) {
			scope.put("v" + i, big);
		}
		String json = AutomationStepSnapshot.toJson(scope);
		assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_BYTES);
		Map<String, Object> first = variable(parse(json), "v0");
		assertNull(first.get("value"), "values are dropped once the record is over the cap");
		assertEquals(Boolean.TRUE, first.get("truncated"));
	}

	@Test
	void kindsDescribeTheValue() {
		Map<String, Object> scope = new LinkedHashMap<>();
		scope.put("n", 3);
		scope.put("flag", true);
		scope.put("list", List.of(1, 2));
		scope.put("nothing", null);
		Map<String, Object> record = parse(AutomationStepSnapshot.toJson(scope));
		assertEquals("number", variable(record, "n").get("kind"));
		assertEquals("boolean", variable(record, "flag").get("kind"));
		assertEquals("array", variable(record, "list").get("kind"));
		assertEquals("null", variable(record, "nothing").get("kind"));
	}
}
