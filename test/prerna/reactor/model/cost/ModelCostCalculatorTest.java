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
package prerna.reactor.model.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import prerna.reactor.model.cost.ModelCostCalculator.ModelTokenTotals;

/**
 * Covers {@link ModelCostCalculator#price}: the arithmetic (verified to the
 * cent against a hand calculation in harness-capability-plan.md's live run —
 * $0.034252 on 50,631 input / 3,460 output / 40,329 cache-read tokens at
 * 0.5/2.0/0.05 per 1M), the three defects that run caught (COALESCE(MODEL_ID,
 * AGENT_ID), PRICING as a flat array not the nested catalog shape, and
 * SERVINGPROVIDER case), and the honesty contract: an unpriced model is
 * reported as unpriced, never silently charged $0.
 */
class ModelCostCalculatorTest {

	private static ModelTokenTotals totals(String modelId, long input, long output, long cacheRead,
			long cacheWrite) {
		ModelTokenTotals t = new ModelTokenTotals(modelId);
		t.modelName = modelId;
		t.llmCalls = 1;
		t.inputTokens = input;
		t.outputTokens = output;
		t.cacheReadTokens = cacheRead;
		t.cacheWriteTokens = cacheWrite;
		return t;
	}

	/** One pricing entry, the flat-array shape SecurityModelMetadataUtils actually stores. */
	private static Map<String, Object> pricingEntry(String servingProvider, double input, double output,
			double cacheRead, Double cacheWrite) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("servingProvider", servingProvider);
		entry.put("input", input);
		entry.put("output", output);
		entry.put("cache_read", cacheRead);
		if (cacheWrite != null) {
			entry.put("cache_write", cacheWrite);
		}
		return entry;
	}

	private static Map<String, Object> metadataWithPricing(String servingProvider, List<Map<String, Object>> pricing) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("servingProvider", servingProvider);
		metadata.put("pricing", pricing);
		return metadata;
	}

	@Test
	void pricesExactlyToTheCentAgainstTheHandCalculationFromTheLiveRun() {
		// 50,631 input / 3,460 output / 40,329 cache-read at 0.5/2.0/0.05 per 1M
		// = 0.0253155 + 0.00692 + 0.0020165 = 0.034252 (rounded)
		ModelTokenTotals t = totals("model-1", 50_631, 3_460, 40_329, 0);
		Map<String, ModelTokenTotals> byModel = Map.of("model-1", t);
		Map<String, Map<String, Object>> metadata = Map.of("model-1",
				metadataWithPricing("ollama", List.of(pricingEntry("ollama", 0.5, 2.0, 0.05, null))));

		Map<String, Object> result = ModelCostCalculator.price(byModel, metadata);

		@SuppressWarnings("unchecked")
		Map<String, Object> totalsMap = (Map<String, Object>) result.get("totals");
		assertEquals(0.034252, (Double) totalsMap.get("cost"), 1e-9);
	}

	@Test
	void matchesPricingEntryByServingProviderCaseInsensitively() {
		// SERVINGPROVIDER is stored uppercase ("OLLAMA") while the catalog writes
		// provider keys lowercase ("ollama") -- an exact-case match would silently
		// price nothing on real data.
		ModelTokenTotals t = totals("model-1", 1_000_000, 0, 0, 0);
		Map<String, ModelTokenTotals> byModel = Map.of("model-1", t);
		Map<String, Map<String, Object>> metadata = Map.of("model-1",
				metadataWithPricing("OLLAMA", List.of(pricingEntry("ollama", 1.0, 2.0, 0.1, null))));

		Map<String, Object> result = ModelCostCalculator.price(byModel, metadata);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
		assertEquals(true, models.get(0).get("priced"));
		assertEquals(1.0, (Double) models.get(0).get("cost"), 1e-9);
	}

	@Test
	void unpricedModelIsReportedNotCharged() {
		// A confidently wrong $0.00 is worse than an honest "unpriced" -- a
		// self-hosted model with no catalog rate must stay out of totals.cost.
		ModelTokenTotals priced = totals("priced-model", 1_000_000, 0, 0, 0);
		ModelTokenTotals unpriced = totals("unpriced-model", 1_000_000, 0, 0, 0);
		Map<String, ModelTokenTotals> byModel = new LinkedHashMap<>();
		byModel.put("priced-model", priced);
		byModel.put("unpriced-model", unpriced);
		Map<String, Map<String, Object>> metadata = Map.of("priced-model",
				metadataWithPricing("ollama", List.of(pricingEntry("ollama", 1.0, 2.0, 0.1, null))));
		// unpriced-model has no metadata entry at all

		Map<String, Object> result = ModelCostCalculator.price(byModel, metadata);

		@SuppressWarnings("unchecked")
		Map<String, Object> coverage = (Map<String, Object>) result.get("coverage");
		assertEquals(1, coverage.get("pricedModels"));
		assertEquals(1, coverage.get("unpricedModels"));
		assertFalse((Boolean) coverage.get("complete"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
		Map<String, Object> unpricedRow = models.stream().filter(m -> "unpriced-model".equals(m.get("modelId")))
				.findFirst().orElseThrow();
		assertEquals(false, unpricedRow.get("priced"));
		assertNull(unpricedRow.get("cost"));
		assertEquals("no model metadata for this engine", unpricedRow.get("unpricedReason"));

		// totals.cost reflects ONLY the priced model, not an implicit $0 for the
		// unpriced one
		@SuppressWarnings("unchecked")
		Map<String, Object> totalsMap = (Map<String, Object>) result.get("totals");
		assertEquals(1.0, (Double) totalsMap.get("cost"), 1e-9);
	}

	@Test
	void multiplePricingEntriesWithNoMatchingProviderStaysUnpricedRatherThanGuessing() {
		// A model can carry several providers at different prices. Picking the
		// wrong one produces a confidently wrong number, so with several entries
		// and no SERVINGPROVIDER match, this must stay unpriced -- never guess.
		ModelTokenTotals t = totals("model-1", 1_000_000, 0, 0, 0);
		Map<String, ModelTokenTotals> byModel = Map.of("model-1", t);
		Map<String, Map<String, Object>> metadata = Map.of("model-1",
				metadataWithPricing("unknown-provider",
						List.of(pricingEntry("openai", 1.0, 2.0, 0.1, null), pricingEntry("anthropic", 3.0, 6.0, 0.3, null))));

		Map<String, Object> result = ModelCostCalculator.price(byModel, metadata);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
		assertEquals(false, models.get(0).get("priced"));
	}

	@Test
	void aSingleEntryIsUsedEvenWithNoServingProviderMatch() {
		// With exactly one pricing entry there is nothing to pick wrong, so it is
		// used as-is even when SERVINGPROVIDER doesn't match (or is null).
		ModelTokenTotals t = totals("model-1", 1_000_000, 0, 0, 0);
		Map<String, ModelTokenTotals> byModel = Map.of("model-1", t);
		Map<String, Map<String, Object>> metadata = Map.of("model-1",
				metadataWithPricing(null, List.of(pricingEntry("openai", 1.0, 2.0, 0.1, null))));

		Map<String, Object> result = ModelCostCalculator.price(byModel, metadata);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
		assertEquals(true, models.get(0).get("priced"));
		assertEquals(1.0, (Double) models.get(0).get("cost"), 1e-9);
	}

	@Test
	void cacheWriteAbsentContributesNothingRatherThanZeroCost() {
		// A missing rate is not the same as a zero rate -- absent must leave that
		// component out of the total, not implicitly treat it as free with a 0.0
		// multiplier (both produce the same arithmetic result here, but the
		// "priced" / rates map must reflect what was actually billed).
		ModelTokenTotals t = totals("model-1", 0, 0, 0, 1_000_000);
		Map<String, ModelTokenTotals> byModel = Map.of("model-1", t);
		Map<String, Map<String, Object>> metadata = Map.of("model-1",
				metadataWithPricing("ollama", List.of(pricingEntry("ollama", 1.0, 2.0, 0.1, null))));

		Map<String, Object> result = ModelCostCalculator.price(byModel, metadata);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
		assertEquals(0.0, (Double) models.get(0).get("cost"), 1e-9);
		@SuppressWarnings("unchecked")
		Map<String, Object> rates = (Map<String, Object>) models.get(0).get("rates");
		assertNull(rates.get("cacheWrite"));
	}

	@Test
	void thinkingTokensAreNotChargedSeparatelyToAvoidDoubleCounting() {
		// Providers bill thinking tokens as output tokens, and OUTPUT_TOKENS
		// already includes them -- charging thinkingTokens again would
		// double-count. Only inputTokens/outputTokens/cache* feed cost.
		ModelTokenTotals t = totals("model-1", 0, 1_000_000, 0, 0);
		t.thinkingTokens = 1_000_000;
		Map<String, ModelTokenTotals> byModel = Map.of("model-1", t);
		Map<String, Map<String, Object>> metadata = Map.of("model-1",
				metadataWithPricing("ollama", List.of(pricingEntry("ollama", 1.0, 2.0, 0.1, null))));

		Map<String, Object> result = ModelCostCalculator.price(byModel, metadata);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
		// Only the output rate (2.0) applies, not an extra 2.0 for thinking tokens
		assertEquals(2.0, (Double) models.get(0).get("cost"), 1e-9);
	}

	@Test
	void coverageIsCompleteOnlyWhenEveryModelIsPriced() {
		ModelTokenTotals t = totals("model-1", 1_000_000, 0, 0, 0);
		Map<String, ModelTokenTotals> byModel = Map.of("model-1", t);
		Map<String, Map<String, Object>> metadata = Map.of("model-1",
				metadataWithPricing("ollama", List.of(pricingEntry("ollama", 1.0, 2.0, 0.1, null))));

		Map<String, Object> result = ModelCostCalculator.price(byModel, metadata);

		@SuppressWarnings("unchecked")
		Map<String, Object> coverage = (Map<String, Object>) result.get("coverage");
		assertTrue((Boolean) coverage.get("complete"));
	}

	@Test
	void emptyTotalsProducesNullCostNotZero() {
		// With no models in scope at all, totals.cost must be null (nothing was
		// priced), not 0.0 (which would read as "this run was free").
		Map<String, Object> result = ModelCostCalculator.price(Map.of(), Map.of());

		@SuppressWarnings("unchecked")
		Map<String, Object> totalsMap = (Map<String, Object>) result.get("totals");
		assertNull(totalsMap.get("cost"));
	}
}
