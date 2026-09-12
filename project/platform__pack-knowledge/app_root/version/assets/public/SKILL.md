---
name: pack-knowledge
description: Use when you need to search an embedded document store and add documents to it. Exposes these as tools you call directly: VectorDatabaseQuery, ListDocumentsInVectorDatabase, Embeddings, CreateEmbeddingsFromDocuments, RemoveDocumentFromVectorDatabase.
---

# Knowledge pack

Search an embedded document store and add documents to it.

These are tools **you** call during this run — not code to write into an app.

> Building an app that needs this at *its* runtime instead? Load the `vector` skill, which covers calling the same reactors from `@semoss/sdk` in app code.

## Tools

- `VectorDatabaseQuery`
- `ListDocumentsInVectorDatabase`
- `Embeddings`
- `CreateEmbeddingsFromDocuments` — **asks for approval first**
- `RemoveDocumentFromVectorDatabase` — **asks for approval first**

Each tool's parameters and their descriptions come from the reactor itself, so read the tool schema rather than guessing argument names.

## Prerequisite

An engine of type `VECTOR` must be attached to this project. Attached engines are listed with their ids under **Selected Engines** in your instructions — use one of those ids. If none is listed, say so and ask for one rather than guessing an id.

## Approvals

`CreateEmbeddingsFromDocuments`, `RemoveDocumentFromVectorDatabase` pause for a human decision. That is expected, not an error — state what you are about to do and why before calling one, so the person approving has something to judge.
