# Build 191

## Experiment Mode
- Added a hidden five-tap activation gesture on the **My Worlds** header. Five taps within a short interval open **Activate Experiment Mode?**.
- Experiment Mode is OFF by default.
- Activation text:
  - "Experiment Mode let's you use these things: Deleting Chunks, Copying and Pasting Chunks. These features can be dangerous and should be used with caution. Use these features at your own risk as they can corrupt your world. Always make backups of worlds before proceed."
- Buttons are **NO** and **YES**.
- Once enabled, the setting is persistent and there is intentionally no in-app way to disable it. Clearing the app's data resets it to OFF.
- While OFF, the following are hidden:
  - Copy Chunk
  - Paste Chunk
  - Delete Chunk
  - Selection: Delete Area
- While ON, those features are shown normally.

## Open in Minecraft
- Added a non-cancelable **Opening in Minecraft…** dialog immediately when the action starts.
- The dialog remains visible while the .mcworld export is running, preventing the same world from being selected again while a large export is in progress.
- The dialog is dismissed when export finishes or fails.

## Compatibility
- Build 191 is based on the working Build 190 source and preserves the Build 190 whole-map preview fixes and the Build 188/187 ore finder functionality.
