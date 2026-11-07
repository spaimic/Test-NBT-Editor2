# Build 95

Fixes the Build 94 Kotlin compilation error in `NbtTreeView.kt`.

`scrollToPositionWithOffset()` belongs to `LinearLayoutManager`, not the generic `RecyclerView.LayoutManager`. Build 95 safely casts the attached layout manager to `LinearLayoutManager` before calling the method.

No other Build 94 behavior was intentionally changed.
