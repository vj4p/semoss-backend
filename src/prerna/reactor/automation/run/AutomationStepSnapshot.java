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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import prerna.reactor.automation.AutomationConstants;
import prerna.reactor.automation.utils.AutomationRuntimeUtils;
import prerna.util.SocialPropertySecrets;

/**
 * Builds the redacted, bounded record of the variables a step could read when it
 * started, which run history shows on the step's Input and Variables tabs.
 *
 * <p>
 * A variable whose name marks it as a secret is shown as redacted, including a
 * secret-named key inside a map. Each value is cut to a display length, the
 * number of variables is capped, and the whole record is capped in bytes; a
 * record that would still be too large keeps names only. Live frames are
 * described, never copied.
 */
final class AutomationStepSnapshot {

	private static final int MAX_DEPTH = 8;

	// credentials that SocialPropertySecrets does not name
	private static final Pattern EXTRA_SECRET = Pattern.compile("(?i).*(token|authorization|bearer|cookie).*");

	private AutomationStepSnapshot() {
	}

	/**
	 * @param name a variable or map key
	 * @return whether the value stored under the name is shown as redacted
	 */
	static boolean isSecretName(String name) {
		return name != null && (SocialPropertySecrets.isSecretKey(name) || EXTRA_SECRET.matcher(name).matches());
	}

	/**
	 * Serializes the scope a step starts with.
	 *
	 * @param scope the live variables, in insertion order
	 * @return JSON with a {@code variables} list and the count {@code omitted}
	 */
	static String toJson(Map<String, Object> scope) {
		List<Map<String, Object>> variables = new ArrayList<>();
		int omitted = 0;
		for (Map.Entry<String, Object> entry : scope.entrySet()) {
			if (variables.size() >= AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_VARIABLES) {
				omitted++;
				continue;
			}
			variables.add(describe(entry.getKey(), entry.getValue()));
		}
		String json = write(variables, omitted);
		if (json.getBytes(StandardCharsets.UTF_8).length > AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_BYTES) {
			// too large to keep with values: keep what exists, not what it holds
			List<Map<String, Object>> namesOnly = new ArrayList<>();
			for (Map<String, Object> variable : variables) {
				Map<String, Object> bare = new LinkedHashMap<>();
				bare.put("name", variable.get("name"));
				bare.put("kind", variable.get("kind"));
				bare.put("value", null);
				bare.put("redacted", variable.get("redacted"));
				bare.put("truncated", true);
				namesOnly.add(bare);
			}
			json = write(namesOnly, omitted);
		}
		return json;
	}

	/**
	 * Redacts the trigger inputs a run was started with before they are shown.
	 *
	 * @param inputs the stored trigger inputs
	 * @return the same inputs as JSON with every secret-named entry replaced, or
	 *         {@code null} when there are none
	 */
	static String redactInputs(Map<String, Object> inputs) {
		if (inputs == null || inputs.isEmpty()) {
			return null;
		}
		try {
			return AutomationRuntimeUtils.toRuntimeJson(redact(inputs, 0));
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String write(List<Map<String, Object>> variables, int omitted) {
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("variables", variables);
		record.put("omitted", omitted);
		return AutomationRuntimeUtils.toRuntimeJson(record);
	}

	private static Map<String, Object> describe(String name, Object value) {
		Map<String, Object> variable = new LinkedHashMap<>();
		variable.put("name", name);
		boolean secret = isSecretName(name);
		String kind = kindOf(value);
		variable.put("kind", kind);
		if (secret) {
			variable.put("value", value == null ? null : AutomationConstants.STEP_INPUT_REDACTED);
			variable.put("redacted", value != null);
			variable.put("truncated", false);
			return variable;
		}
		String text = render(value);
		boolean truncated = text != null && text.length() > AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_VALUE_LENGTH;
		variable.put("value",
				truncated ? text.substring(0, AutomationConstants.STEP_INPUT_SNAPSHOT_MAX_VALUE_LENGTH) : text);
		variable.put("redacted", false);
		variable.put("truncated", truncated);
		return variable;
	}

	private static String kindOf(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof AutomationFrameOutput.RegisteredFrame) {
			return "table";
		}
		if (value instanceof CharSequence) {
			return "string";
		}
		if (value instanceof Number) {
			return "number";
		}
		if (value instanceof Boolean) {
			return "boolean";
		}
		if (value instanceof Map<?, ?>) {
			return "object";
		}
		if (value instanceof Iterable<?> || value.getClass().isArray()) {
			return "array";
		}
		return "object";
	}

	private static String render(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof AutomationFrameOutput.RegisteredFrame frame) {
			Object rows = frame.summary() == null ? null : frame.summary().get("rowCount");
			return (rows == null ? "A table" : rows + " rows") + " (not copied)";
		}
		if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
			return value.toString();
		}
		if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
			try {
				return AutomationRuntimeUtils.toRuntimeJson(redact(value, 0));
			} catch (RuntimeException e) {
				return value.getClass().getSimpleName();
			}
		}
		return value.getClass().getSimpleName();
	}

	/** A copy of a nested value with every secret-named map entry replaced. */
	private static Object redact(Object value, int depth) {
		if (depth >= MAX_DEPTH) {
			return "[...]";
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> copy = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey());
				copy.put(key, isSecretName(key) && entry.getValue() != null ? AutomationConstants.STEP_INPUT_REDACTED
						: redact(entry.getValue(), depth + 1));
			}
			return copy;
		}
		if (value instanceof Iterable<?> iterable) {
			List<Object> copy = new ArrayList<>();
			for (Object child : iterable) {
				copy.add(redact(child, depth + 1));
			}
			return copy;
		}
		if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
			return value;
		}
		return value.getClass().getSimpleName();
	}
}
