#!/usr/bin/env python3
"""
Generate the seeded capability-pack projects under ``Semoss/project/``.

Why this exists
---------------
A capability pack is a bundle of reactors exposed to an agent as tools. The first
version of them lived in a TypeScript constant inside ``packages/harness``, which was
the wrong shape three ways: only the harness could see them, an admin could not
curate them without a frontend rebuild, and the ask/auto approval policy — a security
decision — sat in frontend code.

The platform already has a standard for this. Every ``platform__*`` project is a
SKILL project shipping ``public/SKILL.md`` plus an ``mcp/pixel_mcp.json`` toolbox, and
``mcp-selector.tsx`` already lists MCP-tagged projects in every client. So a pack
delivered as a seeded project needs no new UI, inherits RBAC, ownership and git
history, and can be edited by an operator.

The tool definitions are not hand-written. ``MakePixelMCP`` resolves each reactor
through ``ReactorFactory`` and calls ``asMcpTool()``, which derives the JSON schema
from the reactor's own ``keysToGet``/``keyRequired`` and its parameter descriptions.
Hand-maintaining that JSON would drift from the reactors the moment one changed a key.

How to run it
-------------
Needs a running instance and a scratch CODE project to generate against (the "forge"),
because ``MakePixelMCP`` writes into a project's assets::

    python3 generate-capability-packs.py --forge <projectId> --cookie /tmp/session.txt

Each pack is generated in turn and its own tools filtered out of the result.
``MakePixelMCP`` prunes only on a full package scan — a run narrowed to named
reactors accumulates on purpose — so the forge holds every pack generated so far and
the harvest must select by name rather than take the whole file.

What cannot go in a pack
------------------------
``ReactorFactory`` keeps a global ``reactorHash`` (352 names) *plus* per-frame-type
maps — ``rFrameHash``, ``pandasFrameHash``, ``h2FrameHash``, ``tinkerFrameHash``. 58
names live only in those maps, so resolving one requires an in-scope frame to dispatch
on and ``MakePixelMCP`` cannot build a schema for it at all. That rules out the whole
column-manipulation and profiling family: ``DescriptiveStats``, ``SummaryStats``,
``RunDataQuality``, ``Histogram``, ``AddColumn``, ``RenameColumn`` and friends. Adding
one to ``PACKS`` fails loudly at generation time rather than silently at run time.

Where a pack's description lives
-------------------------------
In the SKILL.md frontmatter this script writes, and nowhere else. It is deliberately
*not* also set as the project's ``description`` metadata, even though that is where
``MyProjects`` reads a description from and would be one fewer call for a client:
``SetProjectMetadata`` requires edit rights, and a seeded system project grants those
to nobody - ``global`` confers view only, and there is no admin bypass in
``SecurityUserProjectUtils.userCanEditProject``. Setting it would mean giving someone
edit rights on a system project, which is a bigger decision than a UI label deserves.
Clients read it through the pack's own ``ListSkillFiles`` tool instead, which parses
the frontmatter and needs no new state to keep in sync.

Pipeline-shaped reactors *are* fine, which is worth knowing because it is not obvious.
``FrameHeaders`` calls ``getFrame()`` and ``Import`` needs a query on the stack, so
they look like they need to be one expression with their setup. They do not: tool
calls in a run share the room's insight, and its ``VarStore`` persists between
executions — verified by creating a frame in one HTTP request and reading its headers
in the next.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

# Repo layout: this script lives in Semoss/dev-scripts/
SEMOSS_ROOT = Path(__file__).resolve().parent.parent
PROJECT_DIR = SEMOSS_ROOT / "project"

# Each pack: the reactors it exposes and whether each asks before running.
#
# The line for `ask`: anything that mutates state the agent's own file tools could not
# already reach, or that is outward-facing or hard to reverse. Reading a schema is
# auto; writing to shared cloud storage asks. SqlQuery asks because `commit=true`
# turns it into an UPDATE.
#
# `description` is reused two ways: verbatim as the pack's opening line, and lowercased
# after "Use when you need to ..." in the SKILL.md frontmatter that drives skill
# selection. So write it as an imperative verb phrase addressed to the agent — "Review
# the changes you have made", not "See and describe its own changes", which reads as
# nonsense once prefixed.
PACKS = [
    {
        "id": "pack-data",
        "name": "Data",
        "description": "Read a database's schema, turn a question into SQL, and run it.",
        "requires": "DATABASE",
        "seeAlso": "database",
        "reactors": [
            ("GetOwlDictionary", "auto"),
            ("GetDatabaseTableStructure", "auto"),
            ("TextToSQL", "auto"),
            ("SqlQuery", "ask"),
            ("Collect", "auto"),
        ],
    },
    {
        "id": "pack-knowledge",
        "name": "Knowledge",
        "description": "Search an embedded document store and add documents to it.",
        "requires": "VECTOR",
        "seeAlso": "vector",
        "reactors": [
            ("VectorDatabaseQuery", "auto"),
            ("ListDocumentsInVectorDatabase", "auto"),
            ("Embeddings", "auto"),
            ("CreateEmbeddingsFromDocuments", "ask"),
            ("RemoveDocumentFromVectorDatabase", "ask"),
        ],
    },
    {
        "id": "pack-frames",
        "name": "Frames",
        "description": "Load a file into an in-memory frame, inspect its columns, and read rows back.",
        "requires": None,
        "seeAlso": None,
        # No profiling tool here, deliberately. Every profiling reactor —
        # DescriptiveStats, SummaryStats, RunDataQuality, Histogram — is registered
        # only in ReactorFactory's per-frame-type maps, so it cannot be a pack tool
        # (see "What cannot go in a pack" above). The agent can still profile by
        # collecting rows and computing in the code tool.
        "reactors": [
            ("FileRead", "auto"),
            ("CreateFrame", "auto"),
            ("Import", "auto"),
            ("FrameHeaders", "auto"),
            ("FrameType", "auto"),
            ("Collect", "auto"),
        ],
    },
    {
        "id": "pack-git",
        "name": "Git",
        "description": "Review the changes you have made to a project and work on a branch.",
        "requires": None,
        "seeAlso": None,
        "reactors": [
            ("ProjectGitStatus", "auto"),
            ("ProjectGitDiff", "auto"),
            ("ProjectGitBranches", "auto"),
            ("ProjectGitCreateBranch", "ask"),
            ("ProjectGitStage", "ask"),
            ("ProjectGitMerge", "ask"),
        ],
    },
    {
        "id": "pack-publish",
        "name": "Publish",
        "description": "Save an app you have built and make it reachable.",
        "requires": None,
        "seeAlso": "build-and-publish",
        "reactors": [
            ("SaveAppBlocksJson", "ask"),
            ("PublishProject", "ask"),
            ("BuildAndPublishApp", "ask"),
        ],
    },
    {
        "id": "pack-storage",
        "name": "Storage",
        "description": "Pull a dataset in from S3, SharePoint, SFTP and friends — or push one back.",
        "requires": "STORAGE",
        "seeAlso": "storage",
        "reactors": [
            ("ListStoragePath", "auto"),
            ("ListStoragePathDetails", "auto"),
            ("PullFromStorage", "auto"),
            ("PushToStorage", "ask"),
            ("DeleteFromStorage", "ask"),
        ],
    },
    {
        "id": "pack-browser",
        "name": "Browser",
        "description": "Drive a real headless browser: open a page, look at it, click and type.",
        "requires": None,
        "seeAlso": "browser-automation",
        "reactors": [
            ("Session", "auto"),
            ("Screenshot", "auto"),
            ("ProbeElement", "auto"),
            ("ExtractElementsDataForLLM", "auto"),
            ("CheckNetworkIdle", "auto"),
            ("GetAllSteps", "auto"),
            ("Step", "ask"),
            ("SaveAll", "ask"),
            ("ReplayStep", "ask"),
        ],
    },
]

SMSS_TEMPLATE = """#Base Properties
PROJECT\t{pid}
PROJECT_ALIAS\tplatform
PROJECT_DISPLAY_NAME\t{pid}
PROJECT_TYPE\tprerna.project.impl.Project
PROJECT_ENUM_TYPE\tSKILL
RDBMS_INSIGHTS\tproject/@PROJECT@/insights_database
RDBMS_INSIGHTS_TYPE\tH2_DB
DRIVER\torg.h2.Driver
RDBMS_TYPE\tH2_DB
USERNAME\tsa
PASSWORD\t
CONNECTION_URL\tjdbc:h2:nio:@BaseFolder@/project/platform__{pid}/insights_database;query_timeout=180000;early_filter=true;query_cache_size=24;cache_size=32768
PIPELINE\tpipeline.json
"""


def skill_md(pack):
    """The agent-facing guidance shipped beside the tools.

    Deliberately distinct from the existing `platform__*` skills, which are
    app-code oriented: they teach an agent to call Pixel from `@semoss/sdk` inside
    an app it is building. This one is about the agent calling the tools itself,
    during the run, and cross-references the other where both apply.
    """
    asks = [r for r, mode in pack["reactors"] if mode == "ask"]
    autos = [r for r, mode in pack["reactors"] if mode == "auto"]
    lines = [
        "---",
        f"name: {pack['id']}",
        (
            f"description: Use when you need to {pack['description'][0].lower()}"
            f"{pack['description'][1:]} "
            f"Exposes these as tools you call directly: {', '.join(autos + asks)}."
        ),
        "---",
        "",
        f"# {pack['name']} pack",
        "",
        pack["description"],
        "",
        "These are tools **you** call during this run — not code to write into an app.",
        "",
    ]
    if pack["seeAlso"]:
        lines += [
            f"> Building an app that needs this at *its* runtime instead? Load the "
            f"`{pack['seeAlso']}` skill, which covers calling the same reactors from "
            f"`@semoss/sdk` in app code.",
            "",
        ]
    lines += ["## Tools", ""]
    for reactor, mode in pack["reactors"]:
        suffix = (
            " — **asks for approval first**"
            if mode == "ask"
            else ""
        )
        lines.append(f"- `{reactor}`{suffix}")
    lines += [
        "",
        "Each tool's parameters and their descriptions come from the reactor itself, so "
        "read the tool schema rather than guessing argument names.",
        "",
    ]
    if pack["requires"]:
        lines += [
            "## Prerequisite",
            "",
            f"An engine of type `{pack['requires']}` must be attached to this project. "
            "Attached engines are listed with their ids under **Selected Engines** in "
            "your instructions — use one of those ids. If none is listed, say so and "
            "ask for one rather than guessing an id.",
            "",
        ]
    if asks:
        lines += [
            "## Approvals",
            "",
            f"{', '.join(f'`{a}`' for a in asks)} pause for a human decision. That is "
            "expected, not an error — state what you are about to do and why before "
            "calling one, so the person approving has something to judge.",
            "",
        ]
    return "\n".join(lines)


def run_pixel(cookie, csrf, expression):
    """POST one Pixel expression, returning the first pixelReturn entry."""
    result = subprocess.run(
        [
            "curl", "-s", "-b", cookie,
            "-H", f"X-CSRF-Token: {csrf}",
            "-X", "POST", "http://localhost:9090/Monolith/api/engine/runPixel",
            "--data-urlencode", f"expression={expression}",
        ],
        capture_output=True, text=True, timeout=180,
    )
    return json.loads(result.stdout)["pixelReturn"][0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--forge", required=True, help="scratch CODE project id to generate into")
    parser.add_argument("--cookie", required=True, help="curl cookie jar with a logged-in session")
    parser.add_argument("--csrf", required=True)
    parser.add_argument("--container", default="semoss")
    args = parser.parse_args()

    for pack in PACKS:
        reactors = json.dumps([r for r, _ in pack["reactors"]])
        metadata = json.dumps([{"SMSS_MCP_EXECUTION": m} for _, m in pack["reactors"]])
        expression = (
            f'MakePixelMCP(project=["{args.forge}"], reactor={reactors}, '
            f"mcpMetadata={metadata});"
        )
        entry = run_pixel(args.cookie, args.csrf, expression)
        if "ERROR" in entry["operationType"]:
            print(f"  {pack['id']}: FAILED — {entry['output']}", file=sys.stderr)
            return 1

        # Filter to the reactors this pack asked for. MakePixelMCP only prunes on a
        # full package scan -- a named-reactor run deliberately accumulates, so
        # "must not prune what it did not look at" -- which means the forge still
        # holds every previous pack's tools. Filtering by name is correct
        # regardless of that, and does not depend on resetting the forge.
        expected = [r for r, _ in pack["reactors"]]
        by_name = {t["name"]: t for t in entry["output"].get("tools", [])}
        missing = [r for r in expected if r not in by_name]
        if missing:
            print(f"  {pack['id']}: reactors not generated: {missing}", file=sys.stderr)
            return 1
        generated = {"tools": [by_name[r] for r in expected]}
        names = expected

        target = PROJECT_DIR / f"platform__{pack['id']}"
        (target / "app_root" / "version" / "assets" / "mcp").mkdir(parents=True, exist_ok=True)
        (target / "app_root" / "version" / "assets" / "public").mkdir(parents=True, exist_ok=True)
        (target / "app_root" / "version" / "assets" / "mcp" / "pixel_mcp.json").write_text(
            json.dumps(generated, indent=2) + "\n"
        )
        (target / "app_root" / "version" / "assets" / "public" / "SKILL.md").write_text(
            skill_md(pack)
        )
        (PROJECT_DIR / f"platform__{pack['id']}.smss").write_text(
            SMSS_TEMPLATE.format(pid=pack["id"])
        )
        print(f"  {pack['id']}: {len(names)} tools -> {target.relative_to(SEMOSS_ROOT)}")

    print(f"\n{len(PACKS)} packs generated.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
