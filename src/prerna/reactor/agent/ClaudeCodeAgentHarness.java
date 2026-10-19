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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.engine.impl.model.ClaudeCodeManager;
import prerna.engine.impl.model.Room;
import prerna.reactor.agent.exceptions.AgentInputRequiredException;
import prerna.reactor.agent.run.AgentRunActionStore;
import prerna.reactor.agent.stream.AgentRunStreamService;
import prerna.reactor.agent.stream.AgentStreamItems;
import prerna.reactor.agent.sandbox.AgentSandboxConfig;
import prerna.reactor.agent.sandbox.SandboxPolicy;

/**
 * {@link IAgentHarness} that delegates to {@link ClaudeCodeManager} - spawns the
 * external {@code claude} CLI in the agent's working directory and parses its
 * transcript.
 *
 * <p>Reads from {@link AgentRunContext}:
 * <ul>
 *   <li>working dir - {@code ctx.getFilePath()} (raw - no {@code /client} suffix
 *       imposed; callers wanting that pass {@code subdir="client"} to {@code RunAgent})</li>
 *   <li>system prompt - {@code ctx.getRoom().getSystemPromptForModel()}</li>
 *   <li>engine id - {@code ctx.getRoom().getModelId()}</li>
 *   <li>MCP list - {@code ctx.getAgentConfig().getMcps()} (union of
 *       {@code WORKSPACE_RESOURCE} + {@code room.options.mcp[]})</li>
 *   <li>{@code allowed_tools}, {@code permission_mode} - read inline from paramMap</li>
 * </ul>
 *
 * <p>{@code ClaudeCodeManager} manages its own internal agentic loop, so this harness
 * returns {@code iterations = 0} and an empty tool-call trace.
 */
public class ClaudeCodeAgentHarness implements IAgentHarness {

    private static final Logger logger = LogManager.getLogger(ClaudeCodeAgentHarness.class);

    /** Registry name used by {@link AgentHarnessRegistry}. */
    public static final String NAME = "claude_code";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDisplayName() {
        return "Claude Code";
    }

    @Override
    public String getDescription() {
        return "Spawns a sandboxed Claude Code process with its own file, edit and shell tools. "
                + "Best for writing and refactoring code in a project. It brings its own toolset, "
                + "so the room's MCP toolboxes do not apply.";
    }

    @Override
    public ToolSource getToolSource() {
        return ToolSource.HARNESS_NATIVE;
    }

    @Override
    public AgentHarnessResult execute(AgentRunContext ctx) throws Exception {
        Room                room     = ctx.getRoom();
        Map<String, Object> params   = ctx.getParamMap();
        String              input    = ctx.getInput();
        String              filePath = ctx.getFilePath();

        String       engineId       = room.getModelId();
        if (engineId == null || engineId.trim().isEmpty()) {
            throw new IllegalArgumentException(NAME + ": room does not have a modelId set");
        }
        String       systemPrompt   = room.getSystemPromptForModel();
        if (systemPrompt == null) {
            systemPrompt = "";
        }
        String selectedEnginesPrompt = ctx.getAgentConfig().getSelectedEnginesPrompt();
        if (selectedEnginesPrompt != null && !selectedEnginesPrompt.isEmpty()) {
            systemPrompt = systemPrompt.isEmpty()
                    ? selectedEnginesPrompt
                    : systemPrompt + "\n\n" + selectedEnginesPrompt;
        }
        User         user           = ctx.getInsight().getUser();
        if (user == null) {
            throw new IllegalArgumentException(NAME + ": insight has no user");
        }
        List<String> allowedTools   = resolveAllowedTools(params, Collections.singletonList("*"));
        String       permissionMode = resolvePermissionMode(params);
        List<Map<String, String>> mcps = ctx.getAgentConfig().getMcps();

        logger.debug("[{}] pre-message: room={}, inputLen={}",
                NAME, room.getId(), input == null ? 0 : input.length());
        logger.debug("ClaudeCodeAgentHarness: engine={} filePath={} mcps={}",
                engineId, filePath, mcps == null ? 0 : mcps.size());

        ClaudeCodeManager manager = new ClaudeCodeManager();
        String approvalMode = ClaudeCodeApprovals.resolveMode();
        manager.setApprovalMode(approvalMode);

        // After a pause the user's decisions go back to Claude: the client allows an approved call once when it is
        // issued again, and the continuation tells Claude what was decided.
        List<Map<String, Object>> decided = Collections.emptyList();
        String prompt = input;
        if (ctx.isResumeMode() && ctx.getRunId() != null && !ctx.getRunId().trim().isEmpty()) {
            decided = ClaudeCodeApprovals.awaitingResume(AgentRunActionStore.getActionsForRun(ctx.getRunId()));
            if (!decided.isEmpty()) {
                prompt = ClaudeCodeApprovals.continuationPrompt(decided, input);
            }
        }
        String decisionsJson = decided.isEmpty() ? null : ClaudeCodeApprovals.decisionsJson(decided);

        String targetBinary = ClaudeCodeManager.resolveClaudeBinary();
        SandboxPolicy policy = AgentSandboxConfig.buildEffectivePolicy(
                room.getRoomFolderPath(), filePath, targetBinary, ctx.getSandboxPolicy());
        ClaudeCodeManager.QueryResult queryResult = manager.queryWithApprovals(
                ctx.getInsight(),
                user,
                engineId,
                filePath,
                prompt,
                systemPrompt,
                room.getId(),
                allowedTools,
                permissionMode,
                mcps,
                policy,
                decisionsJson);
        String output = queryResult.getOutput();

        // Claude has now been told; an old approval must not cover a later identical call.
        for (Map<String, Object> action : decided) {
            AgentRunActionStore.markToolStatus(String.valueOf(action.get("actionId")), ctx.getRunId(),
                    ClaudeCodeApprovals.TOOL_STATUS_APPLIED);
        }

        List<Map<String, Object>> pending = ClaudeCodeApprovals.parsePending(queryResult.getPendingApprovalsJson());
        if (!pending.isEmpty()) {
            pauseForApproval(ctx, room, pending);
        }

        AgentHarnessResult result = new AgentHarnessResult(output, 0, new ArrayList<>());
        logger.debug("[{}] post-message: room={}, iterations={}, finalTextLen={}",
                NAME, room.getId(), result.getIterations(),
                output == null ? 0 : output.length());
        return result;
    }

    /**
     * Records the calls Claude stopped on and pauses the run. The run becomes INPUT_REQUIRED, Harness and Code show
     * the calls as waiting for approval, and a decision resumes it.
     */
    private static void pauseForApproval(AgentRunContext ctx, Room room, List<Map<String, Object>> pending) {
        String runId = ctx.getRunId();
        if (runId == null || runId.trim().isEmpty()) {
            throw new IllegalStateException(NAME + ": cannot pause for approval without a run id");
        }
        String userId = ctx.getUserId();
        if (userId == null || userId.trim().isEmpty()) {
            userId = room.getUserId();
        }
        List<Map<String, Object>> rows = ClaudeCodeApprovals.toActionRows(pending);
        if (rows.isEmpty()) {
            return;
        }
        AgentRunActionStore.insertPendingActions(runId, room.getId(), userId, rows);
        for (Map<String, Object> row : rows) {
            Map<String, Object> patch = new HashMap<>();
            patch.put("status", AgentStreamItems.TOOL_INPUT_REQUIRED);
            AgentRunStreamService.get().publishToolUpdated(runId, String.valueOf(row.get("toolCallId")), patch);
        }
        logger.info("[{}] run {} paused for approval of {} call(s)", NAME, runId, rows.size());
        throw new AgentInputRequiredException(null, ClaudeCodeApprovals.toPauseToolCalls(rows));
    }

    @SuppressWarnings("unchecked")
    private static List<String> resolveAllowedTools(Map<String, Object> params, List<String> defaults) {
        if (params == null) {
            return defaults;
        }
        Object o = params.get("allowed_tools");
        if (o instanceof List) {
            return (List<String>) o;
        }
        return defaults;
    }

    /**
     * The CLI's permission mode, accepting either spelling of the key.
     *
     * <p>
     * Only {@code permission_mode} was read originally, but every caller in the
     * product sends {@code permissionMode} — the workbench assistant builds it in
     * camelCase and a repo-wide search for the snake_case spelling finds no sender
     * at all. So plan mode, {@code acceptEdits} and {@code bypassPermissions} were
     * silently no-ops in this harness: the value arrived, missed the lookup, and
     * every run fell through to "default".
     *
     * <p>
     * Accepting both here rather than renaming on the client fixes every existing
     * caller at once, and costs nothing — the two spellings cannot disagree in
     * practice, and snake_case wins if they ever do, since that is the name the CLI
     * itself uses.
     */
    private static String resolvePermissionMode(Map<String, Object> params) {
        if (params == null) {
            return "default";
        }
        Object value = params.containsKey("permission_mode") ? params.get("permission_mode")
                : params.get("permissionMode");
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return "default";
        }
        return String.valueOf(value).trim();
    }
}
