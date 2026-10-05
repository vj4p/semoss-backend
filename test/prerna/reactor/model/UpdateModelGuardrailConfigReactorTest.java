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
package prerna.reactor.model;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link UpdateModelGuardrailConfigReactor#validateConfigStructure},
 * the structural (no-DB) half of validation -- the exact mechanism the
 * GLiNER PII guardrail's own javadoc documents attaching with: a
 * {@code pipeline.json} written through this reactor via
 * {@code UpdateModelGuardrailConfig(engine=[...], map=[...])}, no harness
 * code.
 *
 * <p>
 * These are pulled directly from the GLiNER guardrail's own documented
 * {@code pipeline.json} example (its {@code getDefaultMarkdown()}), so a
 * passing suite here is also a check that the one config shape the docs tell
 * an operator to copy-paste actually validates.
 */
class UpdateModelGuardrailConfigReactorTest {

	private static final String INPUT_REACTOR = "prerna.reactor.interceptor.GenericGuardrailInputReactor";

	/** The exact shape from GLiNERGuardrailEngine#getDefaultMarkdown's example. */
	private static Map<String, Object> glinerMaskingPipeline() {
		Map<String, Object> params = Map.of("guardrailEngineId", "gliner-engine-id", "inputMapping",
				Map.of("prompt", "arg0"), "directParameters",
				Map.of("labels", List.of("person", "email address", "phone number", "account number"), "threshold",
						0.7),
				"maskOnGuardrailFailure", true, "blockOnGuardrailFailure", false);
		Map<String, Object> entry = Map.of("reactorClass", INPUT_REACTOR, "params", params);
		Map<String, Object> pipeline = Map.of("input", List.of(entry));
		return Map.of("pipelines", Map.of("askRoom", pipeline));
	}

	@Test
	void acceptsTheGlinerGuardrailsOwnDocumentedExample() {
		Map<String, Object> pipelines = UpdateModelGuardrailConfigReactor.validateConfigStructure(glinerMaskingPipeline());
		assertTrue(pipelines.containsKey("askRoom"));
	}

	@Test
	void emptyPipelinesMapIsValidAndRemovesAllGuardrails() {
		Map<String, Object> pipelines = UpdateModelGuardrailConfigReactor
				.validateConfigStructure(Map.of("pipelines", Map.of()));
		assertTrue(pipelines.isEmpty());
	}

	@Test
	void rejectsAnUnknownTopLevelKey() {
		assertThrows(IllegalArgumentException.class,
				() -> UpdateModelGuardrailConfigReactor.validateConfigStructure(Map.of("pipelines", Map.of(),
						"somethingElse", "value")));
	}

	@Test
	void rejectsAMissingPipelinesKey() {
		assertThrows(IllegalArgumentException.class,
				() -> UpdateModelGuardrailConfigReactor.validateConfigStructure(Map.of()));
	}

	@Test
	void rejectsAPipelineWithNeitherInputNorOutput() {
		assertThrows(IllegalArgumentException.class, () -> UpdateModelGuardrailConfigReactor
				.validateConfigStructure(Map.of("pipelines", Map.of("askRoom", Map.of()))));
	}

	@Test
	void rejectsAReactorClassOutsideTheWhitelist() {
		Map<String, Object> entry = Map.of("reactorClass", "some.random.NotAllowedReactor", "params",
				Map.of("guardrailEngineId", "x", "inputMapping", Map.of("prompt", "arg0"), "blockOnGuardrailFailure",
						true));
		Map<String, Object> pipeline = Map.of("input", List.of(entry));
		assertThrows(IllegalArgumentException.class, () -> UpdateModelGuardrailConfigReactor
				.validateConfigStructure(Map.of("pipelines", Map.of("askRoom", pipeline))));
	}

	@Test
	void rejectsMoreThanOneFailureActionAtOnce() {
		// blockOnGuardrailFailure and maskOnGuardrailFailure both true is
		// ambiguous: exactly one failure action must be active.
		Map<String, Object> params = Map.of("guardrailEngineId", "x", "inputMapping", Map.of("prompt", "arg0"),
				"blockOnGuardrailFailure", true, "maskOnGuardrailFailure", true);
		Map<String, Object> entry = Map.of("reactorClass", INPUT_REACTOR, "params", params);
		Map<String, Object> pipeline = Map.of("input", List.of(entry));
		assertThrows(IllegalArgumentException.class, () -> UpdateModelGuardrailConfigReactor
				.validateConfigStructure(Map.of("pipelines", Map.of("askRoom", pipeline))));
	}

	@Test
	void rejectsMaskOnFailureOnAnOutputSlot() {
		// Masking and returning the guardrail message require an INPUT guardrail
		// -- an output-slot guardrail has nowhere to write a masked value back to.
		Map<String, Object> params = Map.of("guardrailEngineId", "x", "inputMapping", Map.of("prompt", "arg0"),
				"maskOnGuardrailFailure", true);
		Map<String, Object> entry = Map.of("reactorClass", "prerna.reactor.interceptor.GenericGuardrailOutputReactor",
				"params", params);
		Map<String, Object> pipeline = Map.of("output", List.of(entry));
		assertThrows(IllegalArgumentException.class, () -> UpdateModelGuardrailConfigReactor
				.validateConfigStructure(Map.of("pipelines", Map.of("askRoom", pipeline))));
	}

	@Test
	void rejectsAnEmptyInputMapping() {
		// Without a mapping the interceptor calls the guardrail engine with no
		// parameters at all, which fails inside the engine at request time.
		Map<String, Object> params = Map.of("guardrailEngineId", "x", "inputMapping", Map.of(),
				"blockOnGuardrailFailure", true);
		Map<String, Object> entry = Map.of("reactorClass", INPUT_REACTOR, "params", params);
		Map<String, Object> pipeline = Map.of("input", List.of(entry));
		assertThrows(IllegalArgumentException.class, () -> UpdateModelGuardrailConfigReactor
				.validateConfigStructure(Map.of("pipelines", Map.of("askRoom", pipeline))));
	}
}
