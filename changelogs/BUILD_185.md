# Build 185

## Fix: Whole-map preview export Kotlin type errors

Build 184 introduced `exportFromPreview()` but mixed `Long` `WholeMapBounds` dimensions with Android/Kotlin `Int` bitmap and array APIs. This caused GitHub Actions `compileDebugKotlin` failures in `WholeMapExporter.kt`.

Build 185 fixes the type boundaries explicitly:
- Derived preview pixels-per-block is converted from `Long` to `Int`.
- Preview dimension validation compares `Long` with `Long`.
- Block width/height are converted to `Int` only after an explicit `Int.MAX_VALUE` safety check.
- Bitmap row coordinates and array indexes therefore receive `Int` values as required.

No rendering algorithm or PNG behavior is changed by this fix.
