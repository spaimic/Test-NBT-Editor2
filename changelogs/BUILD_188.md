# Build 188 — Kotlin generic type fix for ore finder

Build 188 is a compile-fix release for Build 187.

## Fix
`MapViewerActivity.showOreSearchResult()` used an unconstrained Kotlin generic `T`, while helper functions accepted `Any`. Kotlin allows an unconstrained `T` to be nullable, so the Build 187 code failed compilation at the sorting/formatting calls.

The result type parameter is now explicitly constrained as `T : Any`, matching the concrete ore coordinate types used by the diamond and ancient-debris search results.

## Behavior
No ore-finder behavior was changed. Build 188 retains Build 187's:
- hamburger-menu diamond and ancient-debris searches
- player-centered 9×9 chunk square (81 chunks)
- 3D face/edge/corner cluster merging
- nearest-group and nearest-coordinate sorting
- coordinate copy action
- Nether/Overworld coordinate conversion behavior
