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

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.google.gson.Gson;

import prerna.reactor.AbstractReactor;
import prerna.reactor.agent.run.AgentRunActionStore;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;

/**
 * List every agent action awaiting the logged-in user's decision, across all of
 * their rooms.
 *
 * {@link GetAgentRunActionReactor} answers "what is this one action?" and
 * {@code AgentRunActionStore.getPendingActions(runId)} answers "what is this one
 * run waiting on?". Neither answers "what is waiting on me?" — which is what a
 * notification surface needs. Without this a client would have to enumerate
 * rooms and query each one, and would still miss any room it had not loaded.
 *
 * Scoped to the caller's own user id from the insight, never a client-supplied
 * one, so this cannot be used to read another user's queue.
 */
public class GetPendingAgentActionsReactor extends AbstractReactor {

	private static final String LIMIT_KEY = "limit";
	private static final int DEFAULT_LIMIT = 50;
	private static final Gson GSON = new Gson();

	public GetPendingAgentActionsReactor() {
		this.keysToGet = new String[] { LIMIT_KEY };
		this.keyRequired = new int[] { 0 };
	}

	@Override
	public NounMetadata execute() {
		organizeKeys();
		String userId = this.insight != null ? this.insight.getUserId() : null;
		if (userId == null || userId.trim().isEmpty() || "-1".equals(userId)) {
			throw new SecurityException("Must be logged in to list agent actions");
		}

		int limit = DEFAULT_LIMIT;
		String rawLimit = StringUtils.trimToNull(this.keyValue.get(LIMIT_KEY));
		if (rawLimit != null) {
			try {
				limit = Integer.parseInt(rawLimit);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("limit must be a number, received '" + rawLimit + "'");
			}
		}

		List<Map<String, Object>> actions = AgentRunActionStore.getPendingActionsForUser(userId, limit);
		// Hand back real maps rather than embedded JSON strings, matching what
		// GetAgentRunAction does for a single row.
		for (Map<String, Object> action : actions) {
			action.put("toolArgs", parseJson(action.get("toolArgs")));
			action.put("toolMeta", parseJson(action.get("toolMeta")));
			action.put("editedArgs", parseJson(action.get("editedArgs")));
		}
		return new NounMetadata(actions, PixelDataType.VECTOR, PixelOperationType.OPERATION);
	}

	private Object parseJson(Object value) {
		if (value instanceof String && !((String) value).trim().isEmpty()) {
			try {
				return GSON.fromJson((String) value, Object.class);
			} catch (Exception e) {
				return value;
			}
		}
		return value;
	}

	@Override
	public String getReactorDescription() {
		return "List the logged-in user's pending HITL agent actions across every room, newest first.";
	}

	@Override
	protected String getDescriptionForKey(String key) {
		if (key.equals(LIMIT_KEY)) {
			return "Maximum number of pending actions to return. Defaults to 50, capped at 500.";
		}
		return super.getDescriptionForKey(key);
	}
}
