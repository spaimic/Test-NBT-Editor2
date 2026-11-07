# Build 112

## Map renderer polish and pinch-stop fix

Build 112 keeps the fast 12 px/block renderer from Build 111 and makes two targeted changes:

- Replaces noisy per-source-pixel texture variation with clean deterministic solid map shades arranged in small pixel cells, closer to Blocktopograph's clean map appearance.
- Disables bitmap filtering/anti-aliasing for terrain tiles so scaled map pixels remain crisp instead of becoming blurry/grainy.
- Fixes the post-pinch camera drift: the first single-pointer move after a second finger is lifted is ignored, preventing the surviving finger's coordinate jump from being interpreted as a pan.

No change to the 12 px/block source resolution or Build 110/111 chunk caching and progressive loading behavior.
