# Build 173 — definitive fix for Builds 171/172 compiler errors

The previous renderer used a `Completed` wrapper in a way that left the
completion map expecting `ChunkResult`, producing repeated Kotlin type and
scope errors.

Build 173 replaces the whole `renderRows()` implementation with a clean,
compile-safe model:

- `CompletedChunk(x, z, result: ChunkResult)` is the only future result type.
- `ExecutorCompletionService<CompletedChunk>` is used consistently.
- `completedByCoord` stores only `ChunkResult`.
- `future.get()` returns `CompletedChunk`, and its fields are accessed only in
  that scope.
- The worker window remains bounded and spans the entire map.
- The successful PNG writer and progress/cancellation behavior are preserved.

No further patching of the old Completed/ChunkResult code remains.
