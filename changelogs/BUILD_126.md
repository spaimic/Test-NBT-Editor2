# BUILD 126

Build 126 adds **Import Image to Map** for existing Bedrock `map_*` LevelDB entries.

## Features

- Adds `Import Image to Map` to the LevelDB entry editor when the opened key starts with `map_`.
- Picks an image using Android's document picker.
- Converts the image to a 128x128 canvas while preserving aspect ratio and centering it on a white background.
- Converts each pixel to a Bedrock-style map palette entry using weighted RGB matching and four map-color brightness shades.
- Writes the resulting 16,384-byte `colors` array into the map record.
- Supports maps that store `colors` at the root or inside a `data` compound.
- Keeps map metadata, map ID, decorations, and other tags untouched.
- Performs image processing off the UI thread.
- Changes remain in-memory until the normal **Save** action is used.
- Failed imports do not write anything to LevelDB.

The feature is implemented independently; it does not copy Nibnya's source code.

## Notes

This build intentionally implements the single-map image import first. Multi-map/puzzle image generation can be added later.
