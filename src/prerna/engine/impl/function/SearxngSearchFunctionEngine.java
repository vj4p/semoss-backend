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
package prerna.engine.impl.function;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.http.HttpHeaders;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import prerna.engine.api.FunctionTypeEnum;
import prerna.security.HttpHelperUtility;
import prerna.util.Constants;
import prerna.util.Utility;

/**
 * Web search against a <a href="https://docs.searxng.org">SearXNG</a> instance.
 *
 * <p>
 * SearXNG is a self-hosted metasearch engine: it queries other engines and returns
 * the aggregate, so it needs no API key and no account. That is the point of
 * supporting it — an organisation can give agents web search without sending every
 * query to a commercial provider, and without egress beyond a host it controls.
 *
 * <h3>Why a dedicated engine rather than a generic REST one</h3>
 * <p>
 * {@link RESTFunctionEngine} can technically reach the same endpoint, but it builds
 * its query string by concatenating parameter values <em>without URL-encoding
 * them</em> and always joins with {@code "?"}, so a query containing a space or an
 * {@code &} breaks and {@code format=json} cannot be pinned in the configured URL.
 * The model would also have to know to pass {@code format=json} itself, and would
 * silently get HTML back when it forgot.
 *
 * <p>
 * More importantly, search is already a first-class engine kind here —
 * {@link BraveSearchFunctionEngine} and {@link BingSearchFunctionEngine} — with a
 * settled result shape of {@code {query, results:[{title, url, snippet}]}}. Matching
 * that shape means a tool, prompt or downstream consumer written against Brave works
 * against a self-hosted SearXNG with no changes.
 *
 * <h3>SMSS properties</h3>
 * <ul>
 * <li>{@code ENDPOINT} — required, e.g. {@code http://searxng.internal:8282}. The
 * {@code /search} path is appended.</li>
 * <li>{@code COUNT} — default number of results to return (SearXNG pages rather than
 * taking a count, so this trims client-side).</li>
 * <li>{@code SNIPPET_LENGTH} — characters of each result's content to keep.</li>
 * <li>{@code LANGUAGE} — SearXNG language filter, e.g. {@code en-US}, default
 * {@code all}.</li>
 * <li>{@code SAFE_SEARCH} — 0 off, 1 moderate, 2 strict.</li>
 * <li>{@code ENGINES} — comma-separated upstream engines to restrict to.</li>
 * <li>{@code CATEGORIES} — comma-separated categories, e.g. {@code general,news}.</li>
 * </ul>
 */
public class SearxngSearchFunctionEngine extends AbstractFunctionEngine {

	private static final Logger classLogger = LogManager.getLogger(SearxngSearchFunctionEngine.class);

	private static final String ENDPOINT_KEY = "ENDPOINT";
	private static final String COUNT_KEY = "COUNT";
	private static final String SNIPPET_LENGTH_KEY = "SNIPPET_LENGTH";
	private static final String LANGUAGE_KEY = "LANGUAGE";
	private static final String SAFE_SEARCH_KEY = "SAFE_SEARCH";
	private static final String ENGINES_KEY = "ENGINES";
	private static final String CATEGORIES_KEY = "CATEGORIES";

	private static final String QUERY_PARAM = "query";
	private static final String LIMIT_PARAM = "limit";
	private static final String PAGE_PARAM = "page";

	/**
	 * SearXNG has no server-side result cap, but an unbounded list is a context-window
	 * problem rather than a feature: a page of results is already more than a model
	 * reads carefully.
	 */
	private static final int MAX_COUNT = 20;
	private static final int DEFAULT_COUNT = 8;
	private static final int DEFAULT_SNIPPET_LENGTH = 400;

	private String endpoint;
	private int count = DEFAULT_COUNT;
	private int snippetLength = DEFAULT_SNIPPET_LENGTH;
	private String language = "all";
	private String safeSearch;
	private String engines;
	private String categories;

	@Override
	public void open(Properties smssProp) throws Exception {
		super.open(smssProp);

		this.endpoint = StringUtils.trimToNull(smssProp.getProperty(ENDPOINT_KEY));
		if (this.endpoint == null) {
			throw new IllegalArgumentException("Must have key " + ENDPOINT_KEY
					+ " in SMSS pointing at the SearXNG instance, e.g. http://searxng.internal:8282");
		}
		this.endpoint = StringUtils.removeEnd(this.endpoint, "/");

		// The same domain allowlist every other outbound engine honours. A no-op when
		// WHITE_LIST_DOMAINS is unset, which is the default.
		Utility.checkIfValidDomain(this.endpoint);

		this.count = clampCount(NumberUtils.toInt(smssProp.getProperty(COUNT_KEY), this.count));
		this.snippetLength = NumberUtils.toInt(smssProp.getProperty(SNIPPET_LENGTH_KEY), this.snippetLength);
		this.language = StringUtils.defaultIfEmpty(smssProp.getProperty(LANGUAGE_KEY), this.language);
		this.safeSearch = StringUtils.trimToNull(smssProp.getProperty(SAFE_SEARCH_KEY));
		this.engines = StringUtils.trimToNull(smssProp.getProperty(ENGINES_KEY));
		this.categories = StringUtils.trimToNull(smssProp.getProperty(CATEGORIES_KEY));
	}

	@Override
	public Object execute(Map<String, Object> parameterValues) {
		if (parameterValues == null) {
			parameterValues = new HashMap<>();
		}
		// the insight rides along on the parameter map but is not a search parameter
		parameterValues.remove(Constants.INSIGHT);

		validateRequiredParameters(parameterValues);

		String query = getParameterValue(parameterValues, QUERY_PARAM, null);
		if (query == null) {
			throw new IllegalArgumentException("Must define the " + QUERY_PARAM + " parameter to run a web search");
		}

		int runTimeCount = clampCount(getIntParameterValue(parameterValues, LIMIT_PARAM, this.count));
		String url = buildSearchUrl(query, parameterValues);
		classLogger.info("Running a searxng web search for '{}' against {}", query, this.endpoint);

		Map<String, String> headers = new HashMap<>();
		headers.put(HttpHeaders.ACCEPT, "application/json");
		String response = HttpHelperUtility.getRequest(url, headers, null, null, null);

		if (response == null || response.trim().isEmpty()) {
			return emptyResult(query);
		}
		return parseResponse(query, response, runTimeCount);
	}

	/**
	 * Build the request url, applying SMSS defaults for anything the caller omitted.
	 *
	 * <p>
	 * {@code format=json} is pinned here rather than left to the caller: SearXNG
	 * answers with a rendered HTML page when it is missing, which fails as an
	 * unparseable response rather than as a clear error.
	 */
	private String buildSearchUrl(String query, Map<String, Object> parameterValues) {
		Map<String, String> queryParams = new LinkedHashMap<>();
		queryParams.put("q", query);
		queryParams.put("format", "json");

		int runTimePage = getIntParameterValue(parameterValues, PAGE_PARAM, 0);
		if (runTimePage > 1) {
			queryParams.put("pageno", Integer.toString(runTimePage));
		}

		String runTimeLanguage = getParameterValue(parameterValues, "language", this.language);
		if (StringUtils.isNotEmpty(runTimeLanguage)) {
			queryParams.put("language", runTimeLanguage);
		}
		if (this.safeSearch != null) {
			queryParams.put("safesearch", this.safeSearch);
		}
		if (this.engines != null) {
			queryParams.put("engines", this.engines);
		}
		if (this.categories != null) {
			queryParams.put("categories", this.categories);
		}

		StringBuilder url = new StringBuilder(this.endpoint).append("/search?");
		boolean first = true;
		for (Map.Entry<String, String> entry : queryParams.entrySet()) {
			if (!first) {
				url.append('&');
			}
			// Encoded, unlike RESTFunctionEngine — a search query is the one input
			// guaranteed to contain spaces and punctuation.
			url.append(entry.getKey()).append('=')
					.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
			first = false;
		}
		return url.toString();
	}

	/**
	 * Reshape SearXNG's response into the same {@code {query, results}} envelope the
	 * other search engines return.
	 *
	 * <p>
	 * SearXNG returns one entry per upstream engine that matched, so the same page can
	 * appear several times. Duplicates are collapsed by url, keeping the first — which
	 * is the highest-scoring — because a model given the same result five times will
	 * treat it as five pieces of evidence.
	 */
	private Map<String, Object> parseResponse(String query, String response, int limit) {
		JSONObject json = new JSONObject(response);
		JSONArray items = json.optJSONArray("results");

		List<Map<String, Object>> results = new ArrayList<>();
		Set<String> seenUrls = new LinkedHashSet<>();
		if (items != null) {
			for (int i = 0; i < items.length() && results.size() < limit; i++) {
				JSONObject item = items.optJSONObject(i);
				if (item == null) {
					continue;
				}
				String itemUrl = StringUtils.trimToNull(item.optString("url", null));
				if (itemUrl == null || !seenUrls.add(itemUrl)) {
					continue;
				}
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("title", item.optString("title", null));
				result.put("url", itemUrl);
				result.put("snippet", trimSnippet(item.optString("content", null)));
				putIfPresent(result, "publishedDate", item.optString("publishedDate", null));
				// Which upstream engines produced the hit — useful for judging a result
				// and unique to a metasearch engine.
				JSONArray engineList = item.optJSONArray("engines");
				if (engineList != null && !engineList.isEmpty()) {
					List<String> engineNames = new ArrayList<>();
					for (int e = 0; e < engineList.length(); e++) {
						engineNames.add(engineList.optString(e));
					}
					result.put("engines", engineNames);
				}
				results.add(result);
			}
		}

		Map<String, Object> output = new LinkedHashMap<>();
		output.put("query", query);
		JSONArray suggestions = json.optJSONArray("suggestions");
		if (suggestions != null && !suggestions.isEmpty()) {
			List<String> suggested = new ArrayList<>();
			for (int i = 0; i < suggestions.length(); i++) {
				suggested.add(suggestions.optString(i));
			}
			output.put("suggestions", suggested);
		}
		output.put("results", results);
		return output;
	}

	private String trimSnippet(String snippet) {
		if (snippet == null) {
			return null;
		}
		String trimmed = snippet.trim();
		if (this.snippetLength > 0 && trimmed.length() > this.snippetLength) {
			return trimmed.substring(0, this.snippetLength);
		}
		return trimmed;
	}

	private static void putIfPresent(Map<String, Object> map, String key, String value) {
		if (StringUtils.isNotEmpty(value)) {
			map.put(key, value);
		}
	}

	private static Map<String, Object> emptyResult(String query) {
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("query", query);
		output.put("results", new ArrayList<>());
		return output;
	}

	private static int clampCount(int requested) {
		if (requested < 1) {
			return 1;
		}
		return Math.min(requested, MAX_COUNT);
	}

	@Override
	public String getCatalogSubType(Properties smssProp) {
		return FunctionTypeEnum.SEARXNG_SEARCH.name();
	}

	@Override
	public void close() throws IOException {
		// nothing is held open between searches
	}

	/**
	 * Exposed for the reactor layer so a tool can advertise how many results it is
	 * allowed to ask for.
	 *
	 * @return the largest limit a single search accepts
	 */
	public static int getMaxCount() {
		return MAX_COUNT;
	}
}
