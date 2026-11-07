# Build 166 — Build 165 compile-error fix

Build 165's unsafe row-buffer edit is removed. Build 166 is based on the
known-good Build 164 exporter so the Int/IntArray type errors and malformed
class scope cannot recur.

Preserved:
- Build 163's successful ~6 MB PNG encoding/filtering.
- Build 164's progress behavior (0% start, 100% only when finalized).
- Conservative CPU-aware worker count.
- No map-color or output-format changes.

The failed Build 165 optimization is intentionally NOT carried forward.
