"""Offline checks of the Claude Code approval policy and its can_use_tool callback."""

import asyncio
from types import SimpleNamespace

import pytest

from genai_client.agents.claude_code.approval_policy import (
    ApprovalGate,
    auto_allowed_tools,
    is_read_only_bash,
    needs_approval,
    normalize_mode,
)

DEFAULT_TOOLS = [
    "Skill", "Bash", "BashOutput", "KillBash", "Read", "Write", "Edit", "MultiEdit", "NotebookEdit",
    "Glob", "Grep", "LS", "WebSearch", "WebFetch", "Task",
]


def run(coro):
    return asyncio.run(coro)


def ctx(tool_use_id="call-1"):
    return SimpleNamespace(tool_use_id=tool_use_id)


@pytest.mark.parametrize(
    "command",
    [
        "echo hello",
        "ls -la",
        "pwd",
        "cat README.md | head -20",
        "git status",
        "git diff HEAD~1",
        "git log --oneline",
        "git branch --show-current",
        "git remote -v",
        "grep -rn foo src && ls",
        "find . -name '*.py'",
        "echo \"a b\"",
    ],
)
def test_read_only_bash_is_recognised(command):
    assert is_read_only_bash(command)


@pytest.mark.parametrize(
    "command",
    [
        "echo hi > approval-test.txt",
        "echo hi >> log",
        "cat a > b",
        "cat < input",
        "rm -rf build",
        "mkdir out",
        "touch x",
        "git commit -m x",
        "git push",
        "git branch newbranch",
        "git remote add origin x",
        "find . -delete",
        "find . -exec rm {} ;",
        "sort -o out.txt in.txt",
        "date -s 2020-01-01",
        "echo $(whoami)",
        "echo `whoami`",
        "ls & rm -rf x",
        "ls; rm -rf x",
        "ls | tee out",
        "curl http://example.com",
        "python script.py",
        "echo hi\nrm x",
        "",
        "   ",
    ],
)
def test_other_bash_is_not_read_only(command):
    assert not is_read_only_bash(command)


def test_non_string_command_is_not_read_only():
    assert not is_read_only_bash(None)
    assert not is_read_only_bash(["ls"])


def test_mode_off_never_asks():
    for tool in ("Bash", "Write", "Edit", "Read"):
        assert not needs_approval("off", tool, {"command": "rm -rf /"})


def test_mode_mutating_asks_for_writers_and_unsafe_bash_only():
    assert needs_approval("mutating", "Write", {"file_path": "a"})
    assert needs_approval("mutating", "Edit", {})
    assert needs_approval("mutating", "MultiEdit", {})
    assert needs_approval("mutating", "NotebookEdit", {})
    assert needs_approval("mutating", "WebFetch", {"url": "http://x"})
    assert needs_approval("mutating", "Bash", {"command": "echo hi > a.txt"})
    assert not needs_approval("mutating", "Bash", {"command": "echo hello"})
    for tool in ("Read", "Glob", "Grep", "LS", "WebSearch", "Skill", "Task", "BashOutput", "KillBash"):
        assert not needs_approval("mutating", tool, {})
    assert not needs_approval("mutating", "mcp__room__anything", {})


def test_mode_all_asks_for_everything():
    for tool in ("Read", "Bash", "Write", "mcp__room__anything"):
        assert needs_approval("all", tool, {"command": "ls"})


def test_unknown_mode_falls_back_to_off():
    assert normalize_mode("") == "off"
    assert normalize_mode(None) == "off"
    assert normalize_mode("bogus") == "off"
    assert normalize_mode(" Mutating ") == "mutating"
    assert not needs_approval("bogus", "Write", {})


def test_allowed_tools_exclude_anything_the_policy_may_gate():
    assert auto_allowed_tools("off", DEFAULT_TOOLS) == DEFAULT_TOOLS
    gated = auto_allowed_tools("mutating", DEFAULT_TOOLS + ["mcp__x__*"])
    for tool in ("Bash", "Write", "Edit", "MultiEdit", "NotebookEdit", "WebFetch"):
        assert tool not in gated
    for tool in ("Read", "Glob", "Grep", "LS", "Skill", "Task", "WebSearch", "mcp__x__*"):
        assert tool in gated
    assert auto_allowed_tools("all", DEFAULT_TOOLS) == []


def test_gate_allows_what_the_policy_allows():
    gate = ApprovalGate("mutating")
    result = run(gate.can_use_tool("Read", {"file_path": "a"}, ctx()))
    assert result.behavior == "allow"
    result = run(gate.can_use_tool("Bash", {"command": "echo hello"}, ctx()))
    assert result.behavior == "allow"
    assert gate.take_pending() == []


def test_gate_denies_with_interrupt_and_records_the_pending_call():
    gate = ApprovalGate("mutating")
    result = run(gate.can_use_tool("Bash", {"command": "echo hi > a.txt"}, ctx("call-9")))
    assert result.behavior == "deny" and result.interrupt is True
    assert gate.take_pending() == [
        {"toolCallId": "call-9", "toolName": "Bash", "toolArgs": {"command": "echo hi > a.txt"}}
    ]
    assert gate.take_pending() == []  # taken once


def test_gate_records_one_pending_entry_per_tool_call_id():
    gate = ApprovalGate("mutating")
    run(gate.can_use_tool("Write", {"file_path": "a"}, ctx("c1")))
    run(gate.can_use_tool("Write", {"file_path": "a"}, ctx("c1")))
    run(gate.can_use_tool("Write", {"file_path": "b"}, ctx("c2")))
    assert [p["toolCallId"] for p in gate.take_pending()] == ["c1", "c2"]


def test_gate_off_never_denies():
    gate = ApprovalGate("off")
    assert run(gate.can_use_tool("Write", {"file_path": "a"}, ctx())).behavior == "allow"
    assert gate.take_pending() == []


def test_approved_call_is_allowed_exactly_once():
    gate = ApprovalGate("mutating")
    args = {"command": "echo hi > a.txt"}
    gate.set_decisions([{"toolName": "Bash", "toolArgs": args, "editedArgs": None, "decision": "approve"}])
    first = run(gate.can_use_tool("Bash", dict(args), ctx("c1")))
    assert first.behavior == "allow" and first.updated_input is None
    second = run(gate.can_use_tool("Bash", dict(args), ctx("c2")))
    assert second.behavior == "deny" and second.interrupt is True  # a second identical call asks again


def test_edited_call_is_allowed_with_the_users_input():
    gate = ApprovalGate("mutating")
    gate.set_decisions(
        [
            {
                "toolName": "Bash",
                "toolArgs": {"command": "rm -rf build"},
                "editedArgs": {"command": "rm -rf build/tmp"},
                "decision": "edit",
            }
        ]
    )
    result = run(gate.can_use_tool("Bash", {"command": "rm -rf build"}, ctx()))
    assert result.behavior == "allow"
    assert result.updated_input == {"command": "rm -rf build/tmp"}


def test_the_edited_input_also_matches_when_claude_reissues_it():
    gate = ApprovalGate("mutating")
    gate.set_decisions(
        [
            {
                "toolName": "Write",
                "toolArgs": {"file_path": "a", "content": "x"},
                "editedArgs": {"file_path": "b", "content": "x"},
                "decision": "approve",
            }
        ]
    )
    result = run(gate.can_use_tool("Write", {"file_path": "b", "content": "x"}, ctx()))
    assert result.behavior == "allow"
    assert result.updated_input == {"file_path": "b", "content": "x"}


def test_an_approval_for_one_tool_does_not_cover_another():
    gate = ApprovalGate("mutating")
    gate.set_decisions(
        [{"toolName": "Write", "toolArgs": {"file_path": "a"}, "editedArgs": None, "decision": "approve"}]
    )
    assert run(gate.can_use_tool("Edit", {"file_path": "a"}, ctx())).behavior == "deny"
    assert run(gate.can_use_tool("Write", {"file_path": "other"}, ctx())).behavior == "deny"


def test_a_rejected_call_is_denied_without_pausing_again():
    gate = ApprovalGate("mutating")
    args = {"command": "rm -rf build"}
    gate.set_decisions([{"toolName": "Bash", "toolArgs": args, "editedArgs": None, "decision": "reject"}])
    for _ in range(2):  # a rejection is not consumed
        result = run(gate.can_use_tool("Bash", dict(args), ctx()))
        assert result.behavior == "deny" and not result.interrupt
    assert gate.take_pending() == []


def test_set_decisions_replaces_the_previous_ones_and_ignores_junk():
    gate = ApprovalGate("mutating")
    gate.set_decisions([{"toolName": "Write", "toolArgs": {}, "decision": "approve"}])
    gate.set_decisions(None)
    assert run(gate.can_use_tool("Write", {}, ctx())).behavior == "deny"
    gate.set_decisions(["junk", 3, {"toolName": "Write", "toolArgs": {}, "decision": "unknown"}])
    assert run(gate.can_use_tool("Write", {}, ctx())).behavior == "deny"


def assistant_event(*invocations, text=None):
    data = {"model": "m", "toolInvocations": list(invocations)}
    if text:
        data["texts"] = [{"text": text}]
    return {"event": "assistant", "data": data}


def call(tool_use_id, name, arguments):
    return {"toolUseId": tool_use_id, "toolName": name, "arguments": arguments}


def test_a_call_that_will_pause_is_held_back_from_the_stream():
    gate = ApprovalGate("mutating")
    event = gate.hold_back_pending_calls(assistant_event(call("t1", "Bash", {"command": "echo hi > a.txt"})))
    assert event is None  # nothing else in the event, so nothing is sent


def test_the_other_parts_of_the_event_still_stream():
    gate = ApprovalGate("mutating")
    event = gate.hold_back_pending_calls(
        assistant_event(
            call("t1", "Write", {"file_path": "a"}),
            call("t2", "Read", {"file_path": "a"}),
            text="working on it",
        )
    )
    assert [c["toolUseId"] for c in event["data"]["toolInvocations"]] == ["t2"]
    assert event["data"]["texts"][0]["text"] == "working on it"
    only_text = gate.hold_back_pending_calls(assistant_event(call("t3", "Edit", {}), text="hello"))
    assert "toolInvocations" not in only_text["data"] and only_text["data"]["texts"]


def test_calls_that_run_are_never_held_back():
    gate = ApprovalGate("mutating")
    original = assistant_event(call("t1", "Read", {"file_path": "a"}), call("t2", "Bash", {"command": "ls"}))
    event = gate.hold_back_pending_calls(original)
    assert [c["toolUseId"] for c in event["data"]["toolInvocations"]] == ["t1", "t2"]
    assert not gate.should_hide_result({"data": {"toolUseId": "t1"}})


def test_an_approved_call_streams_normally_when_it_is_issued_again():
    gate = ApprovalGate("mutating")
    args = {"command": "echo hi > a.txt"}
    gate.set_decisions([{"toolName": "Bash", "toolArgs": args, "editedArgs": None, "decision": "approve"}])
    event = gate.hold_back_pending_calls(assistant_event(call("t9", "Bash", dict(args))))
    assert event is not None and event["data"]["toolInvocations"][0]["toolUseId"] == "t9"
    # checking does not use up the approval; the permission callback still gets it
    assert run(gate.can_use_tool("Bash", dict(args), ctx("t9"))).behavior == "allow"


def test_the_denial_result_of_a_held_back_call_is_not_streamed():
    gate = ApprovalGate("mutating")
    gate.hold_back_pending_calls(assistant_event(call("t1", "Write", {"file_path": "a"})))
    assert gate.should_hide_result({"data": {"toolUseId": "t1"}})
    assert not gate.should_hide_result({"data": {"toolUseId": "other"}})
    assert not gate.should_hide_result(None)


def test_mode_off_changes_nothing_in_the_stream():
    gate = ApprovalGate("off")
    original = assistant_event(call("t1", "Write", {"file_path": "a"}))
    assert gate.hold_back_pending_calls(original) is original
    assert not gate.should_hide_result({"data": {"toolUseId": "t1"}})


def test_an_approval_ignores_the_wording_of_the_description():
    gate = ApprovalGate("mutating")
    gate.set_decisions(
        [
            {
                "toolName": "Bash",
                "toolArgs": {"command": "echo hi > a.txt", "description": "Create a file named a.txt"},
                "editedArgs": None,
                "decision": "approve",
            }
        ]
    )
    reissued = {"command": "echo hi > a.txt", "description": "Write hi into a.txt"}
    assert run(gate.can_use_tool("Bash", reissued, ctx("t2"))).behavior == "allow"


def test_an_approval_ignores_the_timeout_but_not_the_command():
    gate = ApprovalGate("mutating")
    gate.set_decisions(
        [{"toolName": "Bash", "toolArgs": {"command": "make build"}, "editedArgs": None, "decision": "approve"}]
    )
    assert run(gate.can_use_tool("Bash", {"command": "make build", "timeout": 120000}, ctx("t1"))).behavior == "allow"
    gate.set_decisions(
        [{"toolName": "Bash", "toolArgs": {"command": "make build"}, "editedArgs": None, "decision": "approve"}]
    )
    assert run(gate.can_use_tool("Bash", {"command": "make clean"}, ctx("t2"))).behavior == "deny"


def test_a_rejection_also_ignores_the_description():
    gate = ApprovalGate("mutating")
    gate.set_decisions(
        [
            {
                "toolName": "Write",
                "toolArgs": {"file_path": "a", "content": "x", "description": "first wording"},
                "editedArgs": None,
                "decision": "reject",
            }
        ]
    )
    result = run(gate.can_use_tool("Write", {"file_path": "a", "content": "x", "description": "other"}, ctx()))
    assert result.behavior == "deny" and not result.interrupt
