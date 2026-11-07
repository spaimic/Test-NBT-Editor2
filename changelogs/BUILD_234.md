# Build 234

## MCStructure actor/digp diagnostics

Build 234 keeps Build 233's 64 KiB chunked LevelDB writes and does not change actor serialization or key generation.

Added post-write diagnostics for MCStructure placement:
- Verifies every expected `actorprefix` record is readable and byte-identical after the write.
- Verifies each actor has `UniqueID` and `identifier`.
- Verifies the `UniqueID` derives exactly the 8-byte storage key used by the `actorprefix` key.
- Verifies each actor's position maps it to the expected chunk.
- Verifies every placed actor storage key appears in that chunk's `digp` digest.
- Detects duplicate actor IDs in placed actors and in `digp`.
- Reports the number of actor-bearing chunks and, for every chunk, the number of placed actors, total digest IDs, and digest byte size.

This build is diagnostic-only for the actor/digest path. The Build 233 64 KiB batching strategy remains unchanged.

Android Gradle build was not run locally because the Android SDK is unavailable in this environment. Source/static checks and ZIP integrity checks were performed.
