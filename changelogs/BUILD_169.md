# Build 169 — fix Build 168 compilation error

Build 168 failed because the pipeline's `minOf()` call received incompatible
numeric types (for example Int vs Long). Build 169 makes the chunk-width
worker bound explicitly Int so Kotlin resolves the correct `minOf(Int, Int)`
overload.

No rendering, PNG compression, progress, or pipeline behavior is otherwise
changed.
