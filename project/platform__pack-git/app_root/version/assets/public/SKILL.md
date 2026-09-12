---
name: pack-git
description: Use when you need to review the changes you have made to a project and work on a branch. Exposes these as tools you call directly: ProjectGitStatus, ProjectGitDiff, ProjectGitBranches, ProjectGitCreateBranch, ProjectGitStage, ProjectGitMerge.
---

# Git pack

Review the changes you have made to a project and work on a branch.

These are tools **you** call during this run — not code to write into an app.

## Tools

- `ProjectGitStatus`
- `ProjectGitDiff`
- `ProjectGitBranches`
- `ProjectGitCreateBranch` — **asks for approval first**
- `ProjectGitStage` — **asks for approval first**
- `ProjectGitMerge` — **asks for approval first**

Each tool's parameters and their descriptions come from the reactor itself, so read the tool schema rather than guessing argument names.

## Approvals

`ProjectGitCreateBranch`, `ProjectGitStage`, `ProjectGitMerge` pause for a human decision. That is expected, not an error — state what you are about to do and why before calling one, so the person approving has something to judge.
