# Build 94

## Global NBT Search navigation rewrite

- Search results carry structured NBT paths using key/index path components.
- Parent compound/list paths are expanded before navigation.
- Navigation resolves the target after the RecyclerView dataset/layout is updated.
- Uses `scrollToPositionWithOffset` after posted layout passes.
- The selected result is highlighted.
- Failed navigation reports the unresolved path instead of silently doing nothing.
- List indices are preserved as structured path components.
