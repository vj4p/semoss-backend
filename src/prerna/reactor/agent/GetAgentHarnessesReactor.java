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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import prerna.reactor.AbstractReactor;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.nounmeta.NounMetadata;

/**
 * Lists the agent harnesses this instance can run.
 *
 * <p>
 * Exists so a client can present the choice instead of hardcoding it. The
 * harnesses live in {@link AgentHarnessRegistry}, which is extensible at
 * startup via {@link AgentHarnessRegistry#register}, so any list compiled into
 * a UI is wrong the moment a deployment registers its own. Reading the registry
 * means a custom harness appears without a frontend release.
 *
 * <p>
 * Each entry carries what a picker needs to explain the choice rather than just
 * name it: a display label, a description, and where the harness gets its tools.
 * That last field matters because switching harness changes the available tools
 * - {@code PLATFORM} harnesses use the room's MCP toolboxes and capability
 * packs, {@code HARNESS_NATIVE} ones bring their own - and without saying so the
 * change looks like a defect.
 *
 * <p>
 * Ordering is stable: the default harness first, then alphabetically by name, so
 * a picker does not reshuffle between calls (the registry is a map and its
 * iteration order is not guaranteed).
 */
public class GetAgentHarnessesReactor extends AbstractReactor {

	public GetAgentHarnessesReactor() {
		this.keysToGet = new String[] {};
	}

	@Override
	public NounMetadata execute() {
		organizeKeys();

		List<IAgentHarness> harnesses = AgentHarnessRegistry.all();
		harnesses.sort(Comparator
				.comparing((IAgentHarness h) -> !AgentHarnessRegistry.DEFAULT_HARNESS.equals(h.getName()))
				.thenComparing(IAgentHarness::getName));

		List<Map<String, Object>> output = new ArrayList<>(harnesses.size());
		for (IAgentHarness harness : harnesses) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("name", harness.getName());
			entry.put("displayName", harness.getDisplayName());
			entry.put("description", harness.getDescription());
			entry.put("toolSource", harness.getToolSource().name());
			entry.put("supportsMediaInput", harness.supportsMediaInput());
			entry.put("isSelectable", harness.isSelectable());
			entry.put("isDefault", AgentHarnessRegistry.DEFAULT_HARNESS.equals(harness.getName()));
			output.add(entry);
		}

		return new NounMetadata(output, PixelDataType.VECTOR, PixelOperationType.OPERATION);
	}

	@Override
	public String getReactorDescription() {
		return "List the agent harnesses available on this instance, default first, each with its "
				+ "display name, description, tool source (PLATFORM or HARNESS_NATIVE), whether it "
				+ "accepts media input, and whether a picker should offer it (isSelectable). Use this "
				+ "to populate a harness picker rather than hardcoding one.";
	}
}
