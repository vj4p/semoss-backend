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

/**
 * Pluggable harness interface for the generic agent loop.
 *
 * <p>Implementations receive a resolved {@link AgentRunContext} and drive the
 * model/tool loop to a final response. Built-ins are registered through
 * {@link AgentHarnessRegistry}, and custom harnesses can be added at startup.
 */
public interface IAgentHarness {

    /**
     * Unique registry key for this harness (e.g. {@code "semoss"}, {@code "claude_code"}).
     * Must be stable across JVM restarts.
     */
    String getName();

    /**
     * @return true when this harness can attach current-turn media to the initial
     *         user message.
     */
    default boolean supportsMediaInput() {
        return false;
    }

    /**
     * Execute the agentic loop and return a rich result.
     *
     * @param ctx fully-resolved context containing Room, model engine, insight, and parameters
     * @return result with final text, iteration count, and per-tool-call trace
     * @throws Exception on unrecoverable errors (callers should wrap and surface to the user)
     */
    AgentHarnessResult execute(AgentRunContext ctx) throws Exception;

    /**
     * Where this harness's tools come from. A caller switching harnesses needs
     * this: the available tools change with the harness, and without saying so
     * the capability change looks like a defect rather than a consequence.
     */
    enum ToolSource {
        /**
         * Tools are platform reactors, executed in-process through
         * {@code HarnessToolExecutor} and selected via MCP / capability packs.
         */
        PLATFORM,
        /**
         * The harness spawns its own runtime, which brings its own tools. The
         * platform's MCP selection does not apply.
         */
        HARNESS_NATIVE,
        /** Not declared by the harness. */
        UNSPECIFIED
    }

    /**
     * Human-readable label for pickers and status lines.
     *
     * @return the display label; defaults to {@link #getName()} so it is never
     *         null and an undeclared harness still renders.
     */
    default String getDisplayName() {
        return getName();
    }

    /**
     * One sentence on what this harness is, for a picker that has to explain
     * the choice rather than just list keys.
     *
     * @return the description, or an empty string when not declared
     */
    default String getDescription() {
        return "";
    }

    /**
     * @return where this harness's tools come from; {@link ToolSource#UNSPECIFIED}
     *         when not declared
     */
    default ToolSource getToolSource() {
        return ToolSource.UNSPECIFIED;
    }

    /**
     * Whether a harness picker should offer this harness by default.
     *
     * <p>
     * A registered harness is not automatically one a user should be handed: it
     * may be reachable by explicit request while still not belonging in a
     * picker. Declaring that here keeps the decision in one place - each client
     * that filtered its own list is how the same list came to be hardcoded in
     * several packages, each drifting separately.
     *
     * <p>
     * Selectability is advisory, not authorization. It shapes what a picker
     * offers; it does not stop {@code RunAgent} honouring an explicit
     * harnessType, so existing callers keep working.
     *
     * @return true when the harness should appear in pickers; defaults to true
     */
    default boolean isSelectable() {
        return true;
    }
}
