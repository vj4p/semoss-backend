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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Turns recorded token counts into money.
 *
 * <p>
 * Both halves of this calculation already existed and nothing joined them.
 * {@code MESSAGE} carries exact per-call {@code INPUT_TOKENS},
 * {@code OUTPUT_TOKENS}, {@code CACHE_READ_TOKENS} and
 * {@code CACHE_CREATION_TOKENS}; {@code MODELMETADATA.PRICING} carries real
 * per-million-token rates for most catalog models. There is no spend table, no
 * accumulator, and {@code ENGINE.COST} is hardcoded to the literal {@code "$"} —
 * so before this, nothing in the platform could answer "what did that agent run
 * cost".
 *
 * <h3>Honesty over coverage</h3>
 * <p>
 * A confidently wrong dollar figure is worse than none, so an unpriced model is
 * reported as unpriced rather than as free. Token totals are always returned
 * because they are always correct; cost is returned per model only where a rate
 * was found, alongside the count of models that had none. A self-hosted model has
 * no catalog pricing at all, which is the normal case on a local deployment.
 */
public final class ModelCostCalculator {

	private static final Logger classLogger = LogManager.getLogger(ModelCostCalculator.class);

	/** Catalog rates are quoted per million tokens. */
	private static final double TOKENS_PER_RATE_UNIT = 1_000_000d;

	/** Money is rounded for display only, after all arithmetic is done. */
	private static final int COST_SCALE = 6;

	private ModelCostCalculator() {
	}

	/** Token totals for one model, as summed out of {@code MESSAGE}. */
	public static final class ModelTokenTotals {
		public final String modelId;
		public String modelName;
		public long llmCalls;
		public long inputTokens;
		public long outputTokens;
		public long thinkingTokens;
		public long cacheReadTokens;
		public long cacheWriteTokens;

		public ModelTokenTotals(String modelId) {
			this.modelId = modelId;
		}
	}

	/**
	 * The four rates for one model, per million tokens.
	 *
	 * <p>
	 * {@code cacheWrite} is frequently absent even when the others are present, and
	 * absent is not the same as zero — a missing rate leaves that component out of
	 * the total rather than silently costing nothing.
	 */
	private static final class Rates {
		Double input;
		Double output;
		Double cacheRead;
		Double cacheWrite;

		boolean isEmpty() {
			return input == null && output == null && cacheRead == null && cacheWrite == null;
		}
	}

	/**
	 * Price a set of per-model token totals.
	 *
	 * @param totals            per-model token sums, keyed by model engine id.
	 * @param metadataByEngine  {@code SecurityModelMetadataUtils.getModelMetadata}
	 *                          output for those same ids; may be missing entries.
	 * @return a map carrying {@code models}, {@code totals} and {@code coverage}.
	 */
	public static Map<String, Object> price(Map<String, ModelTokenTotals> totals,
			Map<String, Map<String, Object>> metadataByEngine) {
		List<Map<String, Object>> modelRows = new ArrayList<>();

		long totalInput = 0;
		long totalOutput = 0;
		long totalThinking = 0;
		long totalCacheRead = 0;
		long totalCacheWrite = 0;
		long totalCalls = 0;
		double totalCost = 0d;
		int pricedModels = 0;
		int unpricedModels = 0;

		for (ModelTokenTotals t : totals.values()) {
			Map<String, Object> metadata = metadataByEngine == null ? null : metadataByEngine.get(t.modelId);
			String servingProvider = metadata == null ? null : asTrimmedString(metadata.get("servingProvider"));
			Rates rates = resolveRates(metadata, servingProvider);

			Map<String, Object> row = new LinkedHashMap<>();
			row.put("modelId", t.modelId);
			row.put("modelName", t.modelName);
			row.put("llmCalls", t.llmCalls);
			row.put("inputTokens", t.inputTokens);
			row.put("outputTokens", t.outputTokens);
			row.put("thinkingTokens", t.thinkingTokens);
			row.put("cacheReadTokens", t.cacheReadTokens);
			row.put("cacheWriteTokens", t.cacheWriteTokens);
			row.put("servingProvider", servingProvider);

			if (rates == null || rates.isEmpty()) {
				row.put("priced", false);
				row.put("cost", null);
				row.put("unpricedReason", metadata == null ? "no model metadata for this engine"
						: "no pricing published for this model");
				unpricedModels++;
			} else {
				// Cache reads are billed at their own, much lower rate; charging them at
				// the input rate is the easiest way to overstate an agent's cost by
				// several times over, since a long tool-calling run is mostly cache hits.
				double cost = 0d;
				cost += component(t.inputTokens, rates.input);
				cost += component(t.outputTokens, rates.output);
				cost += component(t.cacheReadTokens, rates.cacheRead);
				cost += component(t.cacheWriteTokens, rates.cacheWrite);

				// Thinking tokens are deliberately not charged separately: providers bill
				// them as output tokens and OUTPUT_TOKENS already includes them, so adding
				// them again would double-count.
				row.put("priced", true);
				row.put("cost", round(cost));
				row.put("rates", ratesToMap(rates));
				totalCost += cost;
				pricedModels++;
			}

			modelRows.add(row);

			totalCalls += t.llmCalls;
			totalInput += t.inputTokens;
			totalOutput += t.outputTokens;
			totalThinking += t.thinkingTokens;
			totalCacheRead += t.cacheReadTokens;
			totalCacheWrite += t.cacheWriteTokens;
		}

		Map<String, Object> totalsMap = new LinkedHashMap<>();
		totalsMap.put("llmCalls", totalCalls);
		totalsMap.put("inputTokens", totalInput);
		totalsMap.put("outputTokens", totalOutput);
		totalsMap.put("thinkingTokens", totalThinking);
		totalsMap.put("cacheReadTokens", totalCacheRead);
		totalsMap.put("cacheWriteTokens", totalCacheWrite);
		totalsMap.put("cost", pricedModels == 0 ? null : round(totalCost));
		totalsMap.put("currency", "USD");

		Map<String, Object> coverage = new LinkedHashMap<>();
		coverage.put("pricedModels", pricedModels);
		coverage.put("unpricedModels", unpricedModels);
		// The caller needs to know the total is partial, or they will read it as
		// complete. This is the flag that says so.
		coverage.put("complete", unpricedModels == 0 && pricedModels > 0);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("models", modelRows);
		result.put("totals", totalsMap);
		result.put("coverage", coverage);
		return result;
	}

	/**
	 * Pull the rates for a model out of its metadata.
	 *
	 * <p>
	 * The stored shape is a flat JSON <em>array</em>, not the nested
	 * {@code provider -> model -> rates} object the source catalog file uses:
	 * {@code StaticModelMetadataCatalog.buildOrderedPricing} flattens it into
	 * {@code [{servingProvider, modelId, input, output, cache_read, ...}, ...]} before
	 * it is written, and {@code SecurityModelMetadataUtils.parseStoredPricing}
	 * refuses anything that does not start with {@code [}. Reading it as the nested
	 * object silently prices nothing.
	 *
	 * <p>
	 * A model routinely carries several providers at different prices, and picking
	 * the wrong one produces a confidently wrong number, so {@code SERVINGPROVIDER} —
	 * which records who actually serves this engine — is the only thing trusted to
	 * choose between them. A single entry leaves nothing to get wrong, so that is
	 * used as-is; several entries with no serving provider recorded stays unpriced
	 * rather than guessing.
	 *
	 * <p>
	 * Matched case-insensitively: the column stores {@code "OLLAMA"} while the
	 * catalog writes provider keys lowercase, so an exact match never succeeds on
	 * real data.
	 */
	private static Rates resolveRates(Map<String, Object> metadata, String servingProvider) {
		if (metadata == null) {
			return null;
		}
		Object pricingObj = metadata.get("pricing");
		if (!(pricingObj instanceof List)) {
			return null;
		}
		List<?> pricing = (List<?>) pricingObj;
		if (pricing.isEmpty()) {
			return null;
		}

		Map<?, ?> chosen = null;
		if (servingProvider != null) {
			for (Object element : pricing) {
				if (!(element instanceof Map)) {
					continue;
				}
				Map<?, ?> entry = (Map<?, ?>) element;
				String entryProvider = asTrimmedString(entry.get("servingProvider"));
				if (entryProvider != null && entryProvider.equalsIgnoreCase(servingProvider)) {
					chosen = entry;
					break;
				}
			}
		}
		if (chosen == null && pricing.size() == 1 && pricing.get(0) instanceof Map) {
			chosen = (Map<?, ?>) pricing.get(0);
		}
		if (chosen == null) {
			classLogger.debug(
					"Model has {} pricing entries and no matching serving provider; leaving it unpriced rather than guessing",
					pricing.size());
			return null;
		}

		Rates rates = new Rates();
		rates.input = asDouble(chosen.get("input"));
		rates.output = asDouble(chosen.get("output"));
		rates.cacheRead = asDouble(chosen.get("cache_read"));
		rates.cacheWrite = asDouble(chosen.get("cache_write"));
		return rates;
	}

	private static Map<String, Object> ratesToMap(Rates rates) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("input", rates.input);
		map.put("output", rates.output);
		map.put("cacheRead", rates.cacheRead);
		map.put("cacheWrite", rates.cacheWrite);
		map.put("per", "1M tokens");
		return map;
	}

	/** A rate that is absent contributes nothing rather than costing nothing. */
	private static double component(long tokens, Double ratePerMillion) {
		if (ratePerMillion == null || tokens <= 0) {
			return 0d;
		}
		return (tokens / TOKENS_PER_RATE_UNIT) * ratePerMillion;
	}

	private static double round(double value) {
		double factor = Math.pow(10, COST_SCALE);
		return Math.round(value * factor) / factor;
	}

	private static Double asDouble(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}
		try {
			String text = String.valueOf(value).trim();
			return text.isEmpty() ? null : Double.valueOf(text);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String asTrimmedString(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}
}
