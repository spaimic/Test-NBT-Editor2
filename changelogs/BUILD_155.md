# Build 155

## Entire Map Picture export
- Fixed Android 10+ PNG saving to `Download` by using `MediaStore.Downloads` instead of `MediaStore.Images` with `RELATIVE_PATH=Download`.
- Added strict fallback: if Download creation/writing fails, retry in `Pictures` using `MediaStore.Images`.
- If both destinations fail, temporary MediaStore entries are deleted and the export reports an error; no successful file is left behind by the exporter.
- Selection Tool PNG exports use the same fallback save path.
- The success toast reports the actual destination (`Download` or `Pictures`).

## Progress UI
- Replaced the indeterminate/circular save indicator for entire-map export with a horizontal 0–100% progress bar.
- Added a numeric percentage below the bar.
- Progress is updated from the row-rendering pipeline while the PNG is generated.

Build 154's entire-map renderer and map output format are otherwise unchanged.
