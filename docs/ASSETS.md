# Assets

All artwork is original and generated reproducibly; no Minecraft files are used.

## Pipeline

- `python3 tools/art/make_atlas.py` writes `tools/art/out/atlas.png`, `atlas.json`, `preview.png` (8x), and `app_icon_16.png`
  (+192/512 px previews). `tools/art/out/` is git-ignored.
- Copy `atlas.png` and `atlas.json` to `mapengine/src/main/resources/minemaps/` (that copy is what ships).
- `python3 tools/art/make_android_icons.py` builds the launcher icons from `app_icon_16.png` (a 32×32 isometric voxel terrain block with river, road, dirt/stone strata and a gold-rimmed ruby map pin): legacy `mipmap-*/ic_launcher(.png|_round.png)`
  at 48/72/96/144/192 px (nearest neighbour, dark slate background `#27292c`), the adaptive icon (`mipmap-anydpi-v26`, background colour
  `@color/ic_launcher_background`, foreground `drawable-nodpi/ic_launcher_foreground.png` = the 32 px icon scaled 8× to 256 px,
  centred in 432x432, inside the 66 dp safe zone). Brand assets (`logo-icon.png`, `logo-text.png`, `logo-combined.png`) live in `docs/images/`.
- Font: Monocraft (OFL 1.1), `mapengine/src/main/resources/minemaps/fonts/Monocraft.ttf` with `OFL.txt` beside it.

## Texture packs

| pack | resources / storage | source |
|---|---|---|
| `ORIGINAL` | `minemaps/atlas.png` + `atlas.json` (classpath) | `tools/art/make_atlas.py` (original art, committed) |
| `VANILLA` | `minemaps/packs/vanilla/` (classpath, local self-builds) or `filesDir/packs/vanilla/` (imported in-app) | `MinecraftImporter` on device (pinned client jar 26.3 or user file) or `tools/art/import_minecraft.py` locally (never committed or shipped in releases) |

Both atlases use the same texture names, icon names and JSON keys; variant counts may differ. `MapEngine` resolves `VANILLA` from the bundled classpath copy first, then from the validated pack in `filesDir/packs/vanilla/`, and falls back to `ORIGINAL` when neither is present (in which case Settings offers *Include Minecraft textures* / *Import from file…*).

The importer (`MinecraftImporter` in `:mapengine` and `tools/art/import_minecraft.py`) feeds the atlas names from these game files (`assets/minecraft/textures/...`): grass/park: `block/grass_block_top` tinted from `colormap/grass`
(plains / lusher biome), park flowers also `block/poppy`, `block/dandelion`; leaves: `block/oak_leaves` tinted from `colormap/foliage`; water: `block/water_still`
frames tinted; sand `block/sand`; gravel `block/gravel`; stone `block/smooth_stone`; cobble `block/cobblestone`; asphalt `block/gray_concrete`;
stone_bricks `block/stone_bricks` + `cracked_stone_bricks`; dirt_path `block/dirt_path_top`; farmland `block/farmland_moist` + `wheat_stage3/7`; snow `block/snow`;
wetland `block/mud` + swamp-tinted grass; roofs: `bricks`, `oak_planks`, `spruce_planks`, `polished_andesite` (or `stone`), `white_concrete`, `terracotta`;
rail: gravel + `block/rail`; grave: grass + stone_bricks headstone. Icons come from `item/*` (apple, bread, cooked_beef, emerald, gold_ingot, coal, potion + overlay, book,
minecart, honey_bottle, iron_pickaxe/axe/sword/shovel, compass_00, bell, cod, diamond, iron_ingot, redstone, golden_apple, cake, cookie, filled_map/map, clock_00, bow, lantern,
oak_boat, bucket, nether_star), `block/oak_sapling`, `block/torch`, `block/poppy`; `pin` = `map/decorations/red_banner`, `player` = `map/decorations/player` (horizontally centred in its 16×16 cell).
`bed` and `chest` have no 2D item texture in the game and keep the MineMaps drawing (see `SOURCE.txt`). Exact mapping: `recipes()` in `tools/art/import_minecraft.py` and `MinecraftImporter.kt`.

## Anchors

`atlas.json` has `"anchors": {icon: [x, y]}`: pixel coordinates inside the 16x16 icon cell (0..16) that sit on the geographic point. Icons without an anchor are centred.
Original: `pin` [8,13]; vanilla: `pin` [8,16] (the banner pole bottom). Renderers draw every icon with its anchor on the point; the player arrow stays centred and rotates about its centre.

## Atlas layout

- 16x16 px cells, 16 columns (256 px wide); textures first (several variants per name), then icons starting on a fresh row.
- `atlas.json`: `{"tileSize":16,"columns":16,"textures":{name:[[col,row],...]},"icons":{name:[col,row]},"bookmarkIcons":[names in picker order, 'pin' first = default],"tintableIcons":["player"]}`.
- The engine builds mips at 16/8/4/2/1 px per variant (box average). Decoders are supplied by the platform (BitmapFactory, ImageIO).
- Scaling: nearest neighbour everywhere. Textured tiles use the mip that matches the pixels per block (2/4/8/16 at z17..z20);
  item icons and pins are drawn at an integer factor (`round(density*1.5)`, minimum 2, cars scaled by the profile).
  Display zoom is not snapped, so non-integer zoom scales tiles with nearest neighbour.
- No animations in v1.

## Feature -> block -> texture

| id | block | texture | source |
|---|---|---|---|
| 0 | GRASS | grass | default land, landcover grass |
| 1 | WATER | water | water polygons, waterways |
| 2 | SAND | sand | landcover sand |
| 3 | FOREST | leaves | landcover wood |
| 4 | PARK | park (variants 2-3 are flowers) | park-like grass, pitch/playground/stadium/theme_park/zoo |
| 5 | FARMLAND | farmland | landcover farmland |
| 6 | WETLAND | wetland | landcover wetland |
| 7 | SNOW | snow | landcover ice |
| 8 | ROCK | stone | landcover rock |
| 9 | INDUSTRIAL | gravel | commercial, industrial, retail, railway, garages, quarry |
| 10 | CEMETERY | grave on 12% of blocks, else grass | cemetery |
| 11 | ROAD_MAJOR | asphalt | motorway, trunk |
| 12 | ROAD | stone | primary, secondary, tertiary |
| 13 | ROAD_MINOR | cobble | minor, service, busway |
| 14 | PATH | dirt_path | track, path, bridleway |
| 15 | PEDESTRIAN | stone_bricks | pedestrian paths and areas, pier |
| 16 | RAIL | rail | rail, narrow gauge, light rail, preserved |
| 17 | BUILDING | roof_brick / roof_oak / roof_spruce / roof_stone / roof_concrete / roof_terracotta | building (material by stable hash, see spec) |
| 18 | RUNWAY | asphalt | aeroway |
| 19 | RESIDENTIAL | grass (tinted average colour at z <= 13) | landuse residential, suburb, neighbourhood |

Exact classes, widths, zoom limits and shading are in `ENGINE-SPEC.md`.

## POI -> icon

| icon | OpenMapTiles class / subclass |
|---|---|
| meat | restaurant, fast_food, food_court |
| bread | bakery |
| apple | grocery, supermarket, greengrocer, convenience |
| mug | cafe, bar, pub, beer, ice_cream |
| emerald | shop, clothing_store, gift, jewelry, mall, department_store |
| gold_ingot | bank, atm, money |
| coal | fuel |
| potion | pharmacy, hospital, doctors, dentist, clinic |
| book | school, college, university, library, kindergarten |
| sapling | park, garden, playground, zoo |
| minecart | railway, bus, parking (subclass parking only), car, bicycle_rental, ferry_terminal |
| bed | lodging, hotel, hostel, motel |
| pickaxe | hardware, doityourself, car_repair |
| compass | attraction, museum, monument, viewpoint, castle, art_gallery |
| bell | place_of_worship |
| fish | seafood, marina, harbor |

Excluded subclasses: bus_stop, platform, stop_position, tram_stop, bicycle_parking, motorcycle_parking, bench, waste_basket,
vending_machine, toilets, taxi. `pin` (saved/search places) and `player` (location arrow) are overlay icons, `app_icon` is the launcher icon.

## Tinting and bookmark icons

- `player` is the dart-shaped location arrow, drawn white/light grey with a dark outline so it can be multiply-tinted. Tinting is a per-channel multiply of every pixel (`TextureAtlas.tintIcon`), so the outline stays dark. The tinted bitmap is cached per colour. Settings offers nine presets (White default, Red, Orange, Yellow, Lime, Cyan, Blue, Purple, Pink), stored as an ARGB int; phone and car both use it.
- Saved places carry an icon name from `bookmarkIcons`: pin, bed, chest, diamond, emerald, gold_ingot, iron_ingot, redstone, apple, golden_apple, bread, cake, cookie, meat, fish, potion, book, map, compass, clock, bell, sword, pickaxe, axe, shovel, bow, torch, lantern, poppy, sapling, minecart, boat, bucket, star. Old saved places without an icon use `pin`. `pin` is anchored with its tip on the point; other items are centred, with a small dark ellipse shadow. Names are drawn under the icon (phone z >= 15, car z >= 16). In the car list the icon is nearest-scaled to 64x64.
- Forest/park trees and water depth shading are renderer effects, not atlas content (see ENGINE-SPEC).

## Provenance

Textures, item icons, pins, the player arrow and the app icon are original MineMaps artwork produced by `tools/art/make_atlas.py`
(palettes, procedural patterns, hand-drawn icon grids). They are drawn after the vanilla Minecraft style (tinted top-down terrain, outlined items) without using any game files or extracted assets. The label font is Monocraft by Idrees Hassan under the SIL Open Font License 1.1.
