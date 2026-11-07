# Build 92

## Fixes
- Fixed Global NBT Search result navigation so tapping a result reliably expands the required parent compound/list nodes and jumps the RecyclerView directly to the matching tag.
- Fixed searches targeting list elements such as `Inventory[3]` by expanding the containing list before jumping.
- The jump is posted until the RecyclerView has applied the refreshed tree, preventing the scroll from being lost during adapter refresh.

## Base
- Build 91
