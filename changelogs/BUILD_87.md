# Build 87 — Dimension-safe Chunk Clipboard

Build 87 fixes the major Nether/The End chunk clipboard problem found in Build 86.

## Changes

- Chunk copy now uses the currently selected map dimension instead of silently defaulting to Overworld.
- Chunk paste now uses the currently selected map dimension.
- Chunk clipboard entries now store their dimension (Overworld / Nether / The End).
- Pasting a clipboard entry into a different dimension is rejected instead of writing records into the wrong dimension.
- Nether and The End `digp` entity keys are now recognized and copied with their dimension ID.
- Destination chunk records and dimension-specific entity lists are cleared using the selected dimension.
- Copied entities receive fresh global actor IDs to avoid duplicate `actorprefix` IDs when a chunk is pasted elsewhere.
- Common `UniqueID` and `ActorUniqueID` NBT fields are rewritten to the new actor ID.
- Unparseable actor records are skipped rather than creating a key/identity mismatch.
- Chunk clipboard labels now show the source dimension.
- Existing terrain and block-entity records are still copied as raw bytes and re-keyed to the target X/Z.

## Important testing

Test on a backup/test world first:

- Nether → Nether
- The End → The End
- Overworld → Overworld
- Attempt Nether → Overworld / End → Overworld and verify the app rejects the paste.

The Nether map surface-selection issue is intentionally separate from this build; Build 87 focuses on making chunk transfer dimension-safe.
