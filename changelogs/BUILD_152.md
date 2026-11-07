# Build 152 — Build 151 compile fix

Fixed `MapViewerActivity.kt` unresolved references:
- `R`
- `View`
- `ViewGroup`
- `layoutParams`
- `setBackgroundColor`

Added the missing Android View imports and the generated application `R` import.
No feature behavior was intentionally changed.
