---
name: pack-browser
description: Use when you need to drive a real headless browser: open a page, look at it, click and type. Exposes these as tools you call directly: Session, Screenshot, ProbeElement, ExtractElementsDataForLLM, CheckNetworkIdle, GetAllSteps, Step, SaveAll, ReplayStep.
---

# Browser pack

Drive a real headless browser: open a page, look at it, click and type.

These are tools **you** call during this run — not code to write into an app.

> Building an app that needs this at *its* runtime instead? Load the `browser-automation` skill, which covers calling the same reactors from `@semoss/sdk` in app code.

## Tools

- `Session`
- `Screenshot`
- `ProbeElement`
- `ExtractElementsDataForLLM`
- `CheckNetworkIdle`
- `GetAllSteps`
- `Step` — **asks for approval first**
- `SaveAll` — **asks for approval first**
- `ReplayStep` — **asks for approval first**

Each tool's parameters and their descriptions come from the reactor itself, so read the tool schema rather than guessing argument names.

## Approvals

`Step`, `SaveAll`, `ReplayStep` pause for a human decision. That is expected, not an error — state what you are about to do and why before calling one, so the person approving has something to judge.
