# Build 243

## Fix
- Restored the resource files that Build 242's push deleted from the repo (`evoker`, `illusioner`, `mooshroom`, `question_mark`, `silver_fish`, `steve`, `zoglin`, `zombified_piglin` PNGs). They are now included in the zip, so the build compiles again.
- No source changes compared with Build 242 (Y-level prefetch, Nether speedup, 2× minimum zoom, merged info text).

## Packaging
- The zip now carries only the new build note in `changelogs/`.
- Copying into the repo must *merge* (never delete `app/src`): the repo can contain files that older zips never had.
