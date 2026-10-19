"""The Claude Code client builds its SDK options from the approval mode without starting the CLI."""

import asyncio
import base64
import json

from genai_client.agents.claude_code.claude_code_client import DEFAULT_TOOLS, ClaudeCodeClient


def make(**overrides):
    kwargs = dict(
        model="m", cwd_path="/tmp", room_id="r1", access_key="a", secret_key="s", base_url="http://x",
        allowed_tools=["*"],
    )
    kwargs.update(overrides)
    return ClaudeCodeClient(**kwargs)


def test_off_keeps_bypass_permissions_and_every_default_tool():
    options = make(approval_mode="off").agent_options
    assert options.permission_mode == "bypassPermissions"
    assert options.can_use_tool is None
    # "*" is passed through as before; only the callback-less path exists in off mode
    assert "*" in options.allowed_tools


def test_default_mode_is_off():
    options = make().agent_options
    assert options.permission_mode == "bypassPermissions"
    assert options.can_use_tool is None


def test_mutating_uses_the_callback_and_does_not_auto_allow_gated_tools():
    options = make(approval_mode="mutating").agent_options
    assert options.permission_mode == "default"
    assert options.can_use_tool is not None
    for tool in ("Bash", "Write", "Edit", "MultiEdit", "NotebookEdit", "WebFetch"):
        assert tool not in options.allowed_tools
    assert "*" not in options.allowed_tools
    for tool in ("Read", "Glob", "Grep", "LS", "Skill", "Task", "WebSearch"):
        assert tool in options.allowed_tools


def test_mutating_keeps_mcp_wildcards_auto_allowed():
    options = make(approval_mode="mutating", mcps=[{"name": "My Tools", "url": "http://x/comms"}]).agent_options
    assert "mcp__my_tools__*" in options.allowed_tools


def test_all_auto_allows_nothing():
    options = make(approval_mode="all", mcps=[{"name": "t", "url": "http://x"}]).agent_options
    assert options.permission_mode == "default"
    assert options.allowed_tools == []


def test_unknown_mode_is_off():
    options = make(approval_mode="bogus").agent_options
    assert options.permission_mode == "bypassPermissions"
    assert options.can_use_tool is None


def decode(value):
    return json.loads(base64.b64decode(value).decode("utf-8"))


def test_pending_approvals_round_trip_as_base64_json():
    client = make(approval_mode="mutating")
    client.set_decisions(json.dumps([{"toolName": "Write", "toolArgs": {"file_path": "a"}, "decision": "approve"}]))
    result = asyncio.run(client.approval_gate.can_use_tool("Write", {"file_path": "a"}, type("C", (), {"tool_use_id": "c1"})()))
    assert result.behavior == "allow"
    asyncio.run(client.approval_gate.can_use_tool("Write", {"file_path": "z"}, type("C", (), {"tool_use_id": "c2"})()))
    assert decode(client.take_pending_approvals()) == [
        {"toolCallId": "c2", "toolName": "Write", "toolArgs": {"file_path": "z"}}
    ]
    assert decode(client.take_pending_approvals()) == []


def test_empty_decisions_string_is_fine():
    client = make(approval_mode="mutating")
    client.set_decisions("")
    client.set_decisions("[]")
    assert decode(client.take_pending_approvals()) == []
    assert DEFAULT_TOOLS  # exported for callers


def test_pending_values_with_awkward_characters_survive_the_encoding():
    client = make(approval_mode="mutating")
    command = 'echo "hi" > a.txt && cat <<EOF\nline\nEOF'
    asyncio.run(client.approval_gate.can_use_tool("Bash", {"command": command}, type("C", (), {"tool_use_id": "c9"})()))
    encoded = client.take_pending_approvals()
    assert encoded.isascii() and all(ch not in encoded for ch in '"<>\n ')
    assert decode(encoded)[0]["toolArgs"]["command"] == command


class FakeSdkClient:
    """Stands in for ClaudeSDKClient: replays a fixed list of messages."""

    def __init__(self, messages):
        self.messages = messages

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def query(self, prompt):
        return None

    async def receive_response(self):
        for message in self.messages:
            yield message


def streamed_events(monkeypatch, messages, **client_kwargs):
    from genai_client.agents.claude_code import claude_code_client as module

    events = []
    monkeypatch.setattr(module, "get_smss_stream", lambda: lambda event, **kw: events.append(event))
    client = make(**client_kwargs)
    client.sdk_client = FakeSdkClient(messages)
    client.query_cc("go")
    return [e for e in events if isinstance(e, dict) and e.get("event") in ("assistant", "tool_result")]


def test_a_tool_completion_names_the_tool_and_carries_its_arguments(monkeypatch):
    from claude_agent_sdk import AssistantMessage, ToolResultBlock, ToolUseBlock, UserMessage

    events = streamed_events(
        monkeypatch,
        [
            AssistantMessage(content=[ToolUseBlock(id="t1", name="Read", input={"file_path": "a.txt"})], model="m"),
            UserMessage(content=[ToolResultBlock(tool_use_id="t1", content="text", is_error=False)]),
        ],
        approval_mode="mutating",
    )
    result = [e for e in events if e["event"] == "tool_result"][0]["data"]
    assert result["toolUseId"] == "t1"
    assert result["toolName"] == "Read"
    assert result["arguments"] == {"file_path": "a.txt"}


def test_a_held_back_call_streams_neither_its_start_nor_its_denial(monkeypatch):
    from claude_agent_sdk import AssistantMessage, ToolResultBlock, ToolUseBlock, UserMessage

    events = streamed_events(
        monkeypatch,
        [
            AssistantMessage(content=[ToolUseBlock(id="t1", name="Write", input={"file_path": "a.txt"})], model="m"),
            UserMessage(content=[ToolResultBlock(tool_use_id="t1", content="denied", is_error=True)]),
        ],
        approval_mode="mutating",
    )
    assert events == []


def test_mode_off_streams_everything_with_the_tool_name_too(monkeypatch):
    from claude_agent_sdk import AssistantMessage, ToolResultBlock, ToolUseBlock, UserMessage

    events = streamed_events(
        monkeypatch,
        [
            AssistantMessage(content=[ToolUseBlock(id="t1", name="Write", input={"file_path": "a.txt"})], model="m"),
            UserMessage(content=[ToolResultBlock(tool_use_id="t1", content="ok", is_error=False)]),
        ],
        approval_mode="off",
    )
    assert [e["event"] for e in events] == ["assistant", "tool_result"]
    assert events[1]["data"]["toolName"] == "Write"
