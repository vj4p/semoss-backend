---
name: pack-storage
description: Use when you need to pull a dataset in from S3, SharePoint, SFTP and friends — or push one back. Exposes these as tools you call directly: ListStoragePath, ListStoragePathDetails, PullFromStorage, PushToStorage, DeleteFromStorage.
---

# Storage pack

Pull a dataset in from S3, SharePoint, SFTP and friends — or push one back.

These are tools **you** call during this run — not code to write into an app.

> Building an app that needs this at *its* runtime instead? Load the `storage` skill, which covers calling the same reactors from `@semoss/sdk` in app code.

## Tools

- `ListStoragePath`
- `ListStoragePathDetails`
- `PullFromStorage`
- `PushToStorage` — **asks for approval first**
- `DeleteFromStorage` — **asks for approval first**

Each tool's parameters and their descriptions come from the reactor itself, so read the tool schema rather than guessing argument names.

## Prerequisite

An engine of type `STORAGE` must be attached to this project. Attached engines are listed with their ids under **Selected Engines** in your instructions — use one of those ids. If none is listed, say so and ask for one rather than guessing an id.

## Approvals

`PushToStorage`, `DeleteFromStorage` pause for a human decision. That is expected, not an error — state what you are about to do and why before calling one, so the person approving has something to judge.
