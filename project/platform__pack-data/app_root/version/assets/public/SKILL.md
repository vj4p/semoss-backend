---
name: pack-data
description: Use when you need to read a database's schema, turn a question into SQL, and run it. Exposes these as tools you call directly: GetOwlDictionary, GetDatabaseTableStructure, TextToSQL, Collect, SqlQuery.
---

# Data pack

Read a database's schema, turn a question into SQL, and run it.

These are tools **you** call during this run — not code to write into an app.

> Building an app that needs this at *its* runtime instead? Load the `database` skill, which covers calling the same reactors from `@semoss/sdk` in app code.

## Tools

- `GetOwlDictionary`
- `GetDatabaseTableStructure`
- `TextToSQL`
- `SqlQuery` — **asks for approval first**
- `Collect`

Each tool's parameters and their descriptions come from the reactor itself, so read the tool schema rather than guessing argument names.

## Prerequisite

An engine of type `DATABASE` must be attached to this project. Attached engines are listed with their ids under **Selected Engines** in your instructions — use one of those ids. If none is listed, say so and ask for one rather than guessing an id.

## Approvals

`SqlQuery` pause for a human decision. That is expected, not an error — state what you are about to do and why before calling one, so the person approving has something to judge.
