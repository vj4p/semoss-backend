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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityModelMetadataUtils;
import prerna.engine.impl.model.inferencetracking.ModelInferenceLogsUtils;
import prerna.reactor.AbstractReactor;
import prerna.reactor.model.cost.ModelCostCalculator.ModelTokenTotals;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.execptions.SemossPixelException;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Utility;

/**
 * What a room's — or a user's — model usage actually cost.
 *
 * <p>
 * Pixel: {@code GetModelCost(roomId=["..."])} or
 * {@code GetModelCost(userId=["..."], startDate=["..."], endDate=["..."])}.
 *
 * <p>
 * Joins recorded token counts against published per-million-token rates; see
 * {@link ModelCostCalculator} for why unpriced models are reported rather than
 * treated as free.
 *
 * <h3>Scope</h3>
 * <p>
 * Room and user, not run. {@code MESSAGE} has no {@code RUN_ID} column — a run's
 * messages are tagged with an {@code agentRunId} ornament inside the message
 * payload, not a queryable column — so per-run cost cannot be summed without
 * parsing every message body. A room is the natural unit anyway, since it is what
 * the effectiveness and audit panels already report on.
 *
 * <h3>Access</h3>
 * <p>
 * A non-admin may only ask about themselves. Token counts are a usage record, and
 * one user's spend is not another's to read; room scope is additionally filtered
 * to the caller's own messages for the same reason.
 */
public class GetModelCostReactor extends AbstractReactor {

	private static final Logger classLogger = LogManager.getLogger(GetModelCostReactor.class);

	private static final String ROOM_ID = ReactorKeysEnum.ROOM_ID.getKey();
	private static final String USER_ID = "userId";
	private static final String START_DATE = ReactorKeysEnum.START_DATE.getKey();
	private static final String END_DATE = ReactorKeysEnum.END_DATE.getKey();

	public GetModelCostReactor() {
		this.keysToGet = new String[] { ROOM_ID, USER_ID, START_DATE, END_DATE };
		this.keyRequired = new int[] { 0, 0, 0, 0 };
	}

	@Override
	public NounMetadata execute() {
		organizeKeys();

		User user = this.insight.getUser();
		if (AbstractSecurityUtils.anonymousUsersEnabled() && user.isAnonymous()) {
			throwAnonymousUserError();
		}
		if (!Utility.isModelInferenceLogsEnabled()) {
			throw new SemossPixelException(
					"Model inference logging is disabled on this instance, so there are no token counts to price.");
		}

		String roomId = trimToNull(this.keyValue.get(ROOM_ID));
		String requestedUserId = trimToNull(this.keyValue.get(USER_ID));
		if (roomId == null && requestedUserId == null) {
			throw new SemossPixelException("Must pass in either a roomId or a userId");
		}

		String callerId = user.getAccessToken(user.getPrimaryLogin()).getId();
		boolean isAdmin = SecurityAdminUtils.userIsAdmin(user);
		if (requestedUserId != null && !requestedUserId.equals(callerId) && !isAdmin) {
			throw new SemossPixelException("Only an admin may read another user's model cost");
		}

		// Room scope is still filtered to the caller unless they are an admin: a room
		// can be shared, and someone else's token spend in it is not the caller's to
		// read.
		String effectiveUserId = requestedUserId != null ? requestedUserId : (isAdmin ? null : callerId);

		Map<String, ModelTokenTotals> totals = queryTokenTotalsByModel(roomId, effectiveUserId,
				trimToNull(this.keyValue.get(START_DATE)), trimToNull(this.keyValue.get(END_DATE)));

		Map<String, Map<String, Object>> metadata = totals.isEmpty() ? new LinkedHashMap<>()
				: SecurityModelMetadataUtils.getModelMetadata(totals.keySet());

		Map<String, Object> result = ModelCostCalculator.price(totals, metadata);
		Map<String, Object> scope = new LinkedHashMap<>();
		scope.put("roomId", roomId);
		scope.put("userId", effectiveUserId);
		scope.put("startDate", trimToNull(this.keyValue.get(START_DATE)));
		scope.put("endDate", trimToNull(this.keyValue.get(END_DATE)));
		result.put("scope", scope);

		return new NounMetadata(result, PixelDataType.MAP);
	}

	/**
	 * Pull per-model token totals and shape them for the calculator.
	 *
	 * <p>
	 * The SQL itself lives in {@code ModelInferenceLogsUtils} because
	 * {@code SystemEngineRegistry} restricts the model-inference-logs database to a
	 * short package allowlist that this reactor's package is deliberately not on.
	 * Calling through the owning class respects that boundary instead of widening it.
	 */
	private Map<String, ModelTokenTotals> queryTokenTotalsByModel(String roomId, String userId, String startDate,
			String endDate) {
		Map<String, ModelTokenTotals> byModel = new LinkedHashMap<>();
		for (Map<String, Object> row : ModelInferenceLogsUtils.getTokenTotalsByModel(roomId, userId, startDate,
				endDate)) {
			String modelId = String.valueOf(row.get("modelId"));
			ModelTokenTotals totals = new ModelTokenTotals(modelId);
			totals.llmCalls = asLong(row.get("llmCalls"));
			totals.inputTokens = asLong(row.get("inputTokens"));
			totals.outputTokens = asLong(row.get("outputTokens"));
			totals.thinkingTokens = asLong(row.get("thinkingTokens"));
			totals.cacheReadTokens = asLong(row.get("cacheReadTokens"));
			totals.cacheWriteTokens = asLong(row.get("cacheWriteTokens"));
			totals.modelName = resolveModelName(modelId);
			byModel.put(modelId, totals);
		}
		return byModel;
	}

	private static long asLong(Object value) {
		return value instanceof Number ? ((Number) value).longValue() : 0L;
	}

	/** Best-effort display name; the id is always the source of truth. */
	private static String resolveModelName(String modelId) {
		try {
			return SecurityEngineUtils.getEngineAliasForId(modelId);
		} catch (Exception e) {
			return modelId;
		}
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	@Override
	public String getReactorDescription() {
		return "Prices recorded model token usage for a room or a user against published per-million-token rates. "
				+ "Returns per-model token totals always, and cost only for models that have published pricing.";
	}

	@Override
	protected String getDescriptionForKey(String key) {
		if (key.equals(ROOM_ID)) {
			return "The room to price; either this or userId is required";
		} else if (key.equals(USER_ID)) {
			return "The user to price; non-admins may only pass their own id";
		} else if (key.equals(START_DATE)) {
			return "Optional inclusive lower bound on message date";
		} else if (key.equals(END_DATE)) {
			return "Optional inclusive upper bound on message date";
		}
		return super.getDescriptionForKey(key);
	}
}
