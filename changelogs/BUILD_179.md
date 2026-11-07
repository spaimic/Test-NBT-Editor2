# Build 179 — fix ChunkKeys import

Build 178's optimized whole-map LevelDB scan referenced `ChunkKeys` from
`LevelDbBridge.kt` without importing its package.

Fixed:
- Added `import com.spaimic.nbttool.chunk.ChunkKeys`.
- No rendering/PNG behavior changed.
- The Build 178 performance changes remain intact.

This is specifically a compile fix for the Build 178 error.
