# Build 172 — fix Build 171 renderer result types

Fixes the Kotlin type mismatch in Build 171's whole-map renderer.
The Completed wrapper is now stored and retrieved consistently.
No performance or PNG behavior changes are introduced.
