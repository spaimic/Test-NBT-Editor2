# Build 247

## Fix
- **Wrong biome names for the newer biomes.** My id table numbered the 1.16+/1.18+ biomes too low. Bedrock's real ids: Soul Sand Valley 178, Crimson Forest 179, Warped Forest 180, Basalt Deltas 181, Jagged Peaks 182, Frozen Peaks 183, Snowy Slopes 184, Grove 185, Meadow 186, Lush Caves 187, Dripstone Caves 188, Stony Peaks 189, Deep Dark 190, Mangrove Swamp 191, Cherry Grove 192, Pale Garden 193. This fixes the reported mismatches (Frozen Peaks shown as Mangrove Swamp, Snowy Slopes as Cherry Grove, Jagged Peaks as Deep Dark, Grove as Pale Garden) and the Nether biomes.
- Any id the table does not know (for example biomes added in newer game versions) still shows as "Biome #N" with a generated color.

## Validation
- Source-level check only. Not compiled; run the GitHub Actions build and test on device.
