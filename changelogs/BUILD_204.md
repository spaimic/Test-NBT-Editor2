# Build 204

## Fix
Fixed the Build 203 Kotlin compilation error in `McStructureModel.kt`.

`NbtTag.asIntList()` used an expression-body `when` but contained a bare `return null` inside the `ListTag` branch. Kotlin does not allow that form with an expression body.

The function now uses a block body and returns the `when` result, preserving the intended behavior for IntArray, numeric ListTag values, and unsupported list element types.

No MCStructure functionality or existing Build 202 features were intentionally changed.
