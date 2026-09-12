---
name: pack-publish
description: Use when you need to save an app you have built and make it reachable. Exposes these as tools you call directly: SaveAppBlocksJson, PublishProject, BuildAndPublishApp.
---

# Publish pack

Save an app you have built and make it reachable.

These are tools **you** call during this run — not code to write into an app.

> Building an app that needs this at *its* runtime instead? Load the `build-and-publish` skill, which covers calling the same reactors from `@semoss/sdk` in app code.

## Tools

- `SaveAppBlocksJson` — **asks for approval first**
- `PublishProject` — **asks for approval first**
- `BuildAndPublishApp` — **asks for approval first**

Each tool's parameters and their descriptions come from the reactor itself, so read the tool schema rather than guessing argument names.

## Approvals

`SaveAppBlocksJson`, `PublishProject`, `BuildAndPublishApp` pause for a human decision. That is expected, not an error — state what you are about to do and why before calling one, so the person approving has something to judge.
