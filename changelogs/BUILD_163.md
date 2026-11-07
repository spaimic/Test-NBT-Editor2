# Build 163 — Whole Map PNG performance + progress overhaul

- Reworked the PNG row filtering: repeated rows use PNG Up filtering, while
  changing rows use Sub filtering. This exploits the repeated pixels-per-block
  rows without changing any map colors or using a hardcoded palette.
- Increased chunk decode parallelism from 5 to 6 workers.
- Kept standard RGB PNG output so arbitrary MapShading colors remain valid.
- Reworked progress so chunk-loading accounts for 0–20% and final row
  rendering/encoding accounts for 20–99%; 100% is only emitted after the
  complete PNG stream is closed/finalized by the caller.
- Saving dialog now describes the percentage as the complete generation + save
  operation instead of implying a separate 90% rendering stage.
- Dynamic 2..maximum px/block limit and 1-billion-pixel safety limit preserved.
