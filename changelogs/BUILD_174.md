# Build 174 — actual root-cause fix for the Build 171-173 compiler error

Builds 171-173 all patched the wrong thing (the Completed/ChunkResult type
shape). The real bug was declaration order in `renderRows()`:

`ExecutorCompletionService<CompletedChunk>(executor)` was created *before*
the local `data class CompletedChunk` was declared, later in the same
function block. Kotlin resolves local classes top-to-bottom within a block
(unlike local functions, which can forward-reference each other), so
`CompletedChunk` was unresolved at that point. That single unresolved
reference then cascaded into an unrelated-looking "overload resolution
ambiguity" error further down on `(reasons[k] ?: 0) + v`, because the
compiler could no longer infer the type of `v` once `done.result` failed
to resolve.

Fix: moved `data class Job` and `data class CompletedChunk` above the
`ExecutorCompletionService` construction. No logic, type shapes, or PNG/
progress/cancellation behavior changed.
