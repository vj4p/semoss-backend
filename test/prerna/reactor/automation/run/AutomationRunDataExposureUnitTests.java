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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import prerna.reactor.automation.AutomationConstants;

class AutomationRunDataExposureUnitTests {

	private static Map<String, Object> row(String nodeId, String snapshot) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put(AutomationConstants.RUN_ID, "run-1");
		row.put(AutomationConstants.NODE_ID, nodeId);
		row.put(AutomationConstants.NODE_LABEL, nodeId);
		row.put(AutomationConstants.STATUS, "SUCCESS");
		row.put(AutomationConstants.OUTPUT_VAR_NAME, "city_rows");
		row.put(AutomationConstants.STEP_INPUT_SNAPSHOT, snapshot);
		return row;
	}

	private static final String SNAPSHOT = "{\"variables\":[{\"name\":\"city\",\"kind\":\"string\",\"value\":\"Ashburn\","
			+ "\"redacted\":false,\"truncated\":false}],\"omitted\":3}";

	@Test
	void outputVariableIsAlwaysShownButStepInputsOnlyWhenAsked() {
		Map<String, Object> hidden = AutomationRunStore.buildNodeResults(List.of(row("n1", SNAPSHOT))).get(0);
		assertEquals("city_rows", hidden.get(AutomationConstants.RESULT_OUTPUT_VARIABLE));
		assertFalse(hidden.containsKey(AutomationConstants.RESULT_INPUT_VARIABLES));

		Map<String, Object> shown = AutomationRunStore.buildNodeResults(List.of(row("n1", SNAPSHOT)), true).get(0);
		List<?> variables = (List<?>) shown.get(AutomationConstants.RESULT_INPUT_VARIABLES);
		assertEquals(1, variables.size());
		assertEquals(3, shown.get(AutomationConstants.RESULT_INPUT_VARIABLES + "Omitted"));
	}

	@Test
	void aMissingOrUnreadableSnapshotIsLeftOut() {
		for (String bad : new String[] { null, "", "not json", "{\"variables\":\"x\"}" }) {
			Map<String, Object> result = AutomationRunStore.buildNodeResults(List.of(row("n1", bad)), true).get(0);
			assertFalse(result.containsKey(AutomationConstants.RESULT_INPUT_VARIABLES), String.valueOf(bad));
		}
	}

	@Test
	void retainedTablesAreMarkedOnTopLevelStepsOnly() {
		List<Map<String, Object>> results = new ArrayList<>(
				AutomationRunStore.buildNodeResults(List.of(row("n1", null), row("n2", null))));
		Map<String, Object> table = new LinkedHashMap<>();
		table.put(AutomationConstants.NODE_ID, "n1");
		table.put(AutomationConstants.RESULT_KIND, AutomationConstants.RESULT_KIND_TABLE);
		table.put(AutomationConstants.ROW_COUNT, 42);
		Map<String, Object> loopChild = new LinkedHashMap<>(table);
		loopChild.put(AutomationConstants.NODE_ID, "n2");
		loopChild.put(AutomationConstants.PARENT_NODE_ID, "loop");
		Map<String, Object> value = new LinkedHashMap<>(table);
		value.put(AutomationConstants.NODE_ID, "n2");
		value.put(AutomationConstants.RESULT_KIND, "VALUE");

		GetAutomationRunReactor.markRetainedTables(results, List.of(table, loopChild, value));

		assertEquals(Boolean.TRUE, results.get(0).get(AutomationConstants.RESULT_OUTPUT_DATA_AVAILABLE));
		assertEquals(42L, results.get(0).get(AutomationConstants.RESULT_OUTPUT_DATA_ROW_COUNT));
		assertNull(results.get(1).get(AutomationConstants.RESULT_OUTPUT_DATA_AVAILABLE));
	}

	@Test
	void triggerInputsAreRedactedBeforeTheyAreShown() {
		Map<String, Object> inputs = new LinkedHashMap<>();
		inputs.put("city", "Ashburn");
		inputs.put("api_key", "sk-live-123");
		String shown = AutomationStepSnapshot.redactInputs(inputs);
		assertTrue(shown.contains("Ashburn"));
		assertFalse(shown.contains("sk-live-123"));
		assertTrue(shown.contains(AutomationConstants.STEP_INPUT_REDACTED));
		assertNull(AutomationStepSnapshot.redactInputs(Map.of()));
	}
}
