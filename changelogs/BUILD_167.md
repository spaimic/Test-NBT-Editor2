# Build 167 — fix Kotlin import placement

Build 167 fixes the Build 166 compilation failure:

`imports are only allowed in the beginning of file`

The timing constant/declaration is now placed after the complete import block,
where Kotlin allows top-level declarations.

No PNG compression, map rendering, progress, or exporter behavior is changed
by this fix.
