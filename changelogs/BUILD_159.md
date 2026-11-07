# Build 159

## Entire Map Picture — PNG size and progress optimization

- Reduced the hard whole-map export limit to **1,000,000,000 total pixels** (strictly below the limit).
- Whole-map pixels/block remains **2 minimum** and **24 absolute maximum**; the slider maximum is calculated dynamically from the world bounds.
- Changed the streaming PNG rows to use the **PNG Sub filter**, which is highly effective for repeated pixels-per-block map rows and preserves the image losslessly.
- Kept normal zlib compression.
- Fixed progress reporting so it is monotonic and does not jump backward/overshoot.
- Preview progress now reaches **100% only after the preview PNG has been fully generated and decoded**.
- Save progress is clamped below 100% until the output is actually finalized; **100% is shown only after saving succeeds**.
- Existing cancel-only, non-dismissible progress dialogs remain in place.
