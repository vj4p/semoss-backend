"""
Which Claude Code tool calls need a person's approval, and the callback that enforces it.

The policy is pure so it can be tested without the SDK or a model. ``ApprovalGate`` wires it to the SDK's
``can_use_tool`` hook. Approval is stop and resume: a call that needs a person is denied with ``interrupt=True``
(the CLI ends the turn) and recorded as pending; the Java side persists it, pauses the run, and after a decision
resumes the same session. An approved call is allowed once when Claude issues it again.

Modes (``approval_mode``):
  off       nothing pauses (the previous behaviour, ``bypassPermissions``)
  mutating  Write, Edit, MultiEdit, NotebookEdit, WebFetch and any Bash command that is not clearly read-only ask
  all       every tool call asks
"""

import re
import shlex
from typing import Any, Optional

MODES = ("off", "mutating", "all")

# Tools that change files or reach the network. Bash is handled separately: read-only commands do not ask.
ALWAYS_ASK_WHEN_MUTATING = frozenset(
    {"Write", "Edit", "MultiEdit", "NotebookEdit", "WebFetch"}
)

# Commands that only read. Anything not listed asks.
_READ_ONLY_COMMANDS = frozenset(
    {
        "echo", "printf", "ls", "pwd", "cat", "head", "tail", "wc", "grep", "rg", "which", "whoami",
        "date", "uname", "stat", "file", "tree", "du", "df", "sort", "uniq", "cut", "tr", "diff",
        "basename", "dirname", "realpath", "id", "hostname", "true",
    }
)
_GIT_READ_ONLY = frozenset(
    {"status", "diff", "log", "show", "rev-parse", "ls-files", "blame", "describe"}
)
# Flags that turn an otherwise read-only command into a writer.
_WRITING_FLAGS = {
    "sort": ("-o", "--output"),
    "date": ("-s", "--set"),
    "tree": ("-o",),
    "find": ("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprintf", "-fls"),
}

_SEGMENT_SPLIT = re.compile(r"\|\||&&|\||;")


def normalize_mode(value: Optional[str]) -> str:
    """An unknown or empty value falls back to ``off`` so a typo never silently blocks every run."""
    mode = (value or "").strip().lower()
    return mode if mode in MODES else "off"


def _segment_is_read_only(segment: str) -> bool:
    try:
        tokens = shlex.split(segment)
    except ValueError:
        return False
    if not tokens:
        return False
    command = tokens[0]
    rest = tokens[1:]
    if command == "git":
        if not rest:
            return False
        sub = rest[0]
        if sub in _GIT_READ_ONLY:
            return True
        if sub == "branch":
            return all(t in ("-a", "-r", "-v", "-vv", "--list", "--show-current") for t in rest[1:])
        if sub == "remote":
            return rest[1:] in ([], ["-v"])
        return False
    if command == "find":
        return not any(t in _WRITING_FLAGS["find"] for t in rest)
    if command not in _READ_ONLY_COMMANDS:
        return False
    for flag in _WRITING_FLAGS.get(command, ()):
        if any(t == flag or t.startswith(flag + "=") for t in rest):
            return False
    return True


def is_read_only_bash(command: Any) -> bool:
    """True only when the whole command is clearly read-only. Anything unclear is False (it asks)."""
    if not isinstance(command, str) or not command.strip():
        return False
    # Redirection, substitution, backticks, newlines and background jobs can all write or run something else.
    if any(ch in command for ch in (">", "<", "`", "\n", "\r")) or "$(" in command:
        return False
    if re.search(r"(?<!&)&(?!&)", command):
        return False
    segments = _SEGMENT_SPLIT.split(command)
    return all(_segment_is_read_only(seg) for seg in segments)


def needs_approval(mode: str, tool_name: str, tool_input: Any) -> bool:
    mode = normalize_mode(mode)
    if mode == "off":
        return False
    if mode == "all":
        return True
    if tool_name == "Bash":
        command = tool_input.get("command") if isinstance(tool_input, dict) else None
        return not is_read_only_bash(command)
    return tool_name in ALWAYS_ASK_WHEN_MUTATING


def auto_allowed_tools(mode: str, tools: list[str]) -> list[str]:
    """
    ``allowed_tools`` entries are approved without calling ``can_use_tool``, so a tool the policy may gate must not be
    in the list. Bash stays out in ``mutating`` because read-only commands are allowed by the callback instead.
    """
    mode = normalize_mode(mode)
    if mode == "off":
        return list(tools)
    if mode == "all":
        return []
    return [t for t in tools if t not in ALWAYS_ASK_WHEN_MUTATING and t != "Bash"]


# Fields that describe a call without changing what it does. Claude words its own description differently each time
# it issues the same command, so an approval must not depend on them.
_COSMETIC_INPUT_KEYS = frozenset({"description", "timeout"})


def _comparable(tool_input: Any) -> Any:
    if isinstance(tool_input, dict):
        return {k: v for k, v in tool_input.items() if k not in _COSMETIC_INPUT_KEYS}
    return tool_input


class ApprovalGate:
    """Holds this run's decisions and pending calls, and answers the SDK's ``can_use_tool`` callback."""

    DENY_WAITING = (
        "This action needs the user's approval and has been sent for review. "
        "Stop now and wait; do not try another way to do the same thing."
    )
    DENY_DECLINED = (
        "The user declined this action. Do not run it again; choose another approach or explain what you need."
    )

    def __init__(self, mode: str):
        self.mode = normalize_mode(mode)
        self._approved: list[dict] = []
        self._rejected: list[dict] = []
        self._pending: list[dict] = []
        self._held_back: set = set()

    def set_decisions(self, decisions: Optional[list[dict]]) -> None:
        """Decisions made on the previous pause: each ``{toolName, toolArgs, editedArgs, decision, toolCallId}``."""
        self._approved, self._rejected = [], []
        for d in decisions or []:
            if not isinstance(d, dict):
                continue
            kind = str(d.get("decision", "")).lower()
            if kind in ("approve", "edit"):
                self._approved.append(d)
            elif kind == "reject":
                self._rejected.append(d)

    def take_pending(self) -> list[dict]:
        pending, self._pending = self._pending, []
        return pending

    def would_allow(self, tool_name: str, tool_input: Any) -> bool:
        """Whether this call would run now: it needs no approval, or it matches a decision already approved."""
        if not needs_approval(self.mode, tool_name, tool_input):
            return True
        return any(self._matches(entry, tool_name, tool_input) for entry in self._approved)

    def hold_back_pending_calls(self, event: Optional[dict]) -> Optional[dict]:
        """
        Remove, from an assistant stream event, the tool calls that are about to be paused for approval.

        Claude announces a tool call before the permission check runs. Left in the stream, a paused call would appear
        and then end as "failed" (the denial), and the UI would never show it as waiting for approval. SEMOSS native
        never streams an item for a call awaiting a decision; the approval row is built from the pending action, so
        this does the same. The matching result is held back too (``should_hide_result``). Returns None when nothing
        is left to show.
        """
        if not event or self.mode == "off":
            return event
        data = event.get("data") or {}
        invocations = data.get("toolInvocations") or []
        if not invocations:
            return event
        kept = []
        for invocation in invocations:
            if self.would_allow(invocation.get("toolName"), invocation.get("arguments") or {}):
                kept.append(invocation)
            else:
                self._held_back.add(invocation.get("toolUseId"))
        if len(kept) == len(invocations):
            return event
        if kept:
            data["toolInvocations"] = kept
        else:
            data.pop("toolInvocations", None)
        if not (data.get("thinking") or data.get("texts") or data.get("toolInvocations")):
            return None
        return event

    def should_hide_result(self, event: Optional[dict]) -> bool:
        """True for the tool result of a call that was held back (its denial is not a failure to show)."""
        if not event or not self._held_back:
            return False
        return (event.get("data") or {}).get("toolUseId") in self._held_back

    @staticmethod
    def _matches(entry: dict, tool_name: str, tool_input: Any) -> bool:
        if entry.get("toolName") != tool_name:
            return False
        wanted = _comparable(tool_input)
        if wanted == _comparable(entry.get("toolArgs")):
            return True
        edited = entry.get("editedArgs")
        return edited is not None and wanted == _comparable(edited)

    async def can_use_tool(self, tool_name: str, tool_input: dict, context: Any):
        from claude_agent_sdk import PermissionResultAllow, PermissionResultDeny

        if not needs_approval(self.mode, tool_name, tool_input):
            return PermissionResultAllow()

        for entry in self._approved:
            if self._matches(entry, tool_name, tool_input):
                self._approved.remove(entry)  # approved once, not for every later call
                edited = entry.get("editedArgs")
                return PermissionResultAllow(updated_input=edited if edited is not None else None)

        if any(self._matches(entry, tool_name, tool_input) for entry in self._rejected):
            return PermissionResultDeny(message=self.DENY_DECLINED)

        tool_call_id = getattr(context, "tool_use_id", None)
        if not any(p.get("toolCallId") == tool_call_id for p in self._pending):
            self._pending.append(
                {"toolCallId": tool_call_id, "toolName": tool_name, "toolArgs": tool_input}
            )
        return PermissionResultDeny(message=self.DENY_WAITING, interrupt=True)
