# Build 93 — Path-Based Global NBT Search Navigation

## Fix
Replaced the previous RecyclerView-position/visible-row-based Global NBT Search jump mechanism.

### Global NBT Search
- Searches the complete NBT object graph, including collapsed Compound and List tags.
- Each search result now stores its exact NBT path.
- Each result also stores the exact ordered list of parent container paths.
- Clicking a result expands those recorded parents directly.
- The tree is rebuilt and the target is located by its stable NBT path, not by a stale RecyclerView position.
- Handles deeply nested compounds and list elements such as `Inventory[3]/tag/display/Name`.
- Performs a second posted scroll attempt after the RecyclerView layout pass.

This avoids the Build 91/92 failure where hidden ancestors were unavailable in the flattened visible-row list.
