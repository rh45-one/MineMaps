# Map engine specification (`:mapengine`, pure Kotlin/JVM)

Authoritative spec for the block renderer. See ADR-001 for the rationale behind it.

## Coordinates
- World coords `wx, wy ∈ [0,1)` Web Mercator (y down). `lonToX = (lon+180)/360`,
  `latToY = (1 - ln(tan φ + sec φ)/π)/2`, latitude clamped to ±85.05112878.
- Block coords: `bx = floor(wx * 2^24)`. Metres per block at latitude φ: `40075016.686 * cos φ / 2^24`.
- Tile `(z,x,y)`: `x = floor(wx * 2^z)`. Render tiles: 256×256 px. Vector tiles: OpenMapTiles schema, `extent` read from the layer (usually 4096), max zoom read from TileJSON (14).

## Block classes (`Block` enum, stored as a byte)
| id | class | texture (atlas name) | source (OpenMapTiles) |
|---|---|---|---|
| 0 | GRASS | grass | default land; `landcover class=grass` (except park-like subclasses) |
| 1 | WATER | water | `water` polygons (skip `brunnel=tunnel`), `waterway` lines |
| 2 | SAND | sand | `landcover class=sand` (beach, sand, dune) |
| 3 | FOREST | leaves | `landcover class=wood` |
| 4 | PARK | park | `landcover class=grass` with subclass ∈ {park, garden, golf_course, recreation_ground, village_green, playground}; `landuse` ∈ {pitch, playground, stadium, theme_park, zoo} |
| 5 | FARMLAND | farmland | `landcover class=farmland` |
| 6 | WETLAND | wetland | `landcover class=wetland` |
| 7 | SNOW | snow | `landcover class=ice` |
| 8 | ROCK | stone | `landcover class=rock` |
| 9 | INDUSTRIAL | gravel | `landuse` ∈ {commercial, industrial, retail, railway, garages, quarry} |
| 10 | CEMETERY | grave (variant) + grass | `landuse=cemetery` |
| 11 | ROAD_MAJOR | asphalt | transportation motorway, trunk |
| 12 | ROAD | stone | primary, secondary, tertiary |
| 13 | ROAD_MINOR | cobble | minor, service, busway |
| 14 | PATH | dirt_path | track, path (except subclass pedestrian), bridleway |
| 15 | PEDESTRIAN | stone_bricks | path subclass pedestrian/footway polygons, pier |
| 16 | RAIL | rail | rail (subclass rail, narrow_gauge, light_rail, preserved); skip tram/subway |
| 17 | BUILDING | roof_* by aux material | `building` |
| 18 | RUNWAY | asphalt | `aeroway` runway, taxiway, apron (polygons or lines) |
| 19 | RESIDENTIAL | grass | `landuse` ∈ {residential, suburb, neighbourhood}; drawn in the landuse pass, landcover overwrites it. At z ≤ 13 (overview) colour = equal-weight average of the mip-1 colours of stone, roof_brick, roof_concrete and grass (with the usual per-cell variation); at z ≥ 14 it renders exactly like GRASS |

Draw order (later overwrites): landuse → landcover → water polygons → waterways → aeroway → transportation (by
width, widest first; `brunnel=tunnel` and `ferry` skipped) → building. Unknown classes: leave the cell as it is
(GRASS). Never invent features.

### Line widths (metres → cells = max(1, round(w / metresPerCell)))
motorway 16, trunk 14, primary 12, secondary 10, tertiary 9, minor 7, service 4, busway 6, track 3, path 2,
pedestrian line 4, rail 3, runway 40, taxiway 18; waterway river 10, canal 8, stream 2, drain/ditch 1.
Minimum zoom: motorway/trunk/primary/river all zooms; secondary/tertiary/canal z≥12; minor/rail/stream z≥14;
service/track/path/drain z≥15.

### Buildings
- Drawn at z≥14. `aux` byte = `material | (heightBucket << 4)`.
- material 0..5 = roof_brick, roof_oak, roof_spruce, roof_stone, roof_concrete, roof_terracotta. It is chosen by a stable hash
  of the feature id (or of its first vertex if id=0), as `percent = hash % 100`:
  - footprint > 1500 m²: stone 40%, concrete 40%, brick 20%.
  - otherwise: brick 30%, oak 20%, spruce 15%, terracotta 20%, stone 15%.
- heightBucket from `render_height`: <8 m → 0, <20 → 1, <50 → 2, else 3 (missing = 0).

## Block grids
- `BlockGrid(size = 256 + 2*M, M = 4)`: `cls: ByteArray`, `aux: ByteArray`. Cell (0,0) of the tile is at index (M,M).
  The margin lets shading and shadows cross tile edges without seams.
- Grid for render level z ≤ 16: cell = `2^(16-z)` blocks, rasterized from vector tile `vz = min(z, maxVz)`,
  sampling at cell centres. For z ≥ 17 the z16 grid of the ancestor tile is used.
- Polygons: even-odd scanline fill over all rings of a feature, at cell centres. Lines: a cell is set when its centre
  lies within halfWidth of a segment (capsule test). Only in-bounds cells are written.
- Margin cells come from the same vector tile: features in the MVT buffer reach slightly past the tile edge, so this is enough.

## Composition → `IntArray(256*256)` ARGB
- Mips: for each texture variant, box-averaged versions at s ∈ {16, 8, 4, 2, 1} px.
- z ≤ 16 (overview): pixel = mip-1 colour of variant 0 × per-cell variation `0.94..1.06` (hash of global cell, z).
- z ≥ 17: `s = 2^(z-16)` px per block; render tile covers `256/s` blocks of the z16 grid; pixel samples mip-s of
  the block's variant: `variant = hash(bx,by) % n`. PARK uses flower variants (2,3) when `hash%100 < 6`.
  CEMETERY uses grave on
  `hash%100 < 12`, grass otherwise.
- Shading, applied per cell (in blocks at z≥17, in cells at z≤16):
  - BUILDING: south neighbour not building → ×0.72; east neighbour not building → ×0.85; north neighbour not building → ×1.12.
  - Shadow (z≥16 only): non-building, non-water cell with a building at (x−d, y−d), d ∈ 1..(1+heightBucket) → ×0.78.
  - WATER depth shading: d = Chebyshev distance to the nearest non-water cell, searched up to 4 (the grid margin is 4, so tiles stay seamless). d=1 → ×1.10, d=2 → ×1.00, d=3 → ×0.93, d≥4 → ×0.87. Same in overview and textured modes (distance counted in cells).
- Night: `r×0.42, g×0.48, b×0.68` (clamped). Night tiles are a separate cache key.

## Labels & POIs (per vector tile, drawn by the platform renderer, never baked into tiles)
- `place` points: country (minZoom 5), state/province/region (6), city (6), town (9), village (11), hamlet (13), suburb (12), quarter and neighbourhood (14), island (10) → Label(kind, rank).
- `water_name` (class lake: minZoom 15; ocean 0, sea 3, others 12) points/lines, `transportation_name` lines: label at the midpoint of the longest segment, angle
  normalised to (−90°, 90°]; minor roads z≥16, others z≥14.
- `poi` → icon by class/subclass:
  meat {restaurant, fast_food, food_court}, bread {bakery}, apple {grocery, supermarket, greengrocer, convenience},
  mug {cafe, bar, pub, beer, ice_cream}, emerald {shop, clothing_store, gift, jewelry, mall, department_store},
  gold_ingot {bank, atm, money}, coal {fuel}, potion {pharmacy, hospital, doctors, dentist, clinic},
  book {school, college, university, library, kindergarten}, sapling {park, garden, playground, zoo},
  minecart {railway, bus, parking, car, bicycle_rental, ferry_terminal}, bed {lodging, hotel, hostel, motel},
  pickaxe {hardware, doityourself, car_repair}, compass {attraction, museum, monument, viewpoint, castle, art_gallery},
  bell {place_of_worship}, fish {seafood, marina, harbor}. Unmapped → no icon.
- Names: `NameResolver` (package `labels`) picks the text for places, water, roads, POIs and the inspector. LOCAL mode uses `name`. ENGLISH mode takes the first of:
  `name:en`; `name_en` if Latin; `name_int` if Latin (for places only the part before ", "); `name` if Latin; `name:latin`; the longest run of Latin words (>= 2 letters) in a mixed `name`;
  a platform `Transliterator` (Android: ICU `Any-Latin; Latin-ASCII`, Android 10+, title-cased, LRU-cached); finally `name`. "Latin" = every letter is Latin/Common/Inherited script.
- `LabelFilter` hides ROAD and ROAD_MINOR labels when street names are switched off (applied in the renderer, no re-extraction).

## Decorative trees (FOREST and PARK blocks)
Trees are decorative: they are only placed on blocks that the data already classifies as FOREST or PARK, and are not real tree positions.
- Tree centres live on the global block grid divided into cells of 4×4 blocks (FOREST) or 9×9 (PARK). Each cell has at most one tree at a hash-jittered position; it exists with probability 0.85 (FOREST) / 0.35 (PARK). Canopy radius is 1 or 2 blocks (hash) for FOREST, 1 for PARK. Canopy = blocks with `dx²+dy² ≤ r²+1` from the centre.
- A block is canopy if it lies in the disc of a tree from its own cell or one of the 8 neighbouring cells AND its own class is FOREST/PARK. Everything is derived from global block coordinates (no grid storage), so tiles are seamless.
- Canopy blocks use the `leaves` texture (variant by hash). Shading: north or west neighbour not canopy → ×1.12; south or east neighbour not canopy → ×0.80.
- Non-canopy FOREST blocks use `grass` ×0.80 ("forest floor"), and ×0.75 more when the block at (x−1, y−1) is canopy. Non-canopy PARK blocks are unchanged.
- Applied in textured mode (z17–z20) and in the z16 overview (1 px per block). At z ≤ 15 the average class colours are used.

## Map rotation (phone)
`Viewport.bearingDeg` is the clockwise angle of the map: the screen's up points to compass bearing `bearingDeg`. World to screen rotates the offset from the centre by -bearing (screen to world by +bearing), so x and y are coupled and callers must use `worldToScreen` / `screenToWorld`.
`visibleTiles` / `visibleTileSlots` use the axis-aligned world bounds of the four rotated screen corners. The renderer draws tiles with `canvas.rotate(-bearing)` using an unrotated viewport copy; labels, icons, bookmarks, pins and the player marker use the rotated viewport and stay upright.
Road/water-line labels (`Label.oriented`) get `normalize(angle - bearing)` into (-90, 90] inside `LabelLayout`, so collision boxes match. The car renders north-up (bearing 0).
Gestures: a two-finger twist only engages after 12 degrees of accumulated rotation (`RotationGesture`), then follows the fingers 1:1; the camera bearing is persisted (0 when absent).

## Place search index
`PlaceIndex` (package `search`) keeps up to 50,000 named features (places, streets, water, POIs) from every decoded vector tile (`TileRepository.onDecoded`), with the English name and the local name. Entries nearest the index centre survive; streets are de-duplicated within 300 m (places 1-5 km).
Query: every token must be a prefix of a name token (English or local), accents and case folded. Score = match (exact 4, starts-with 3, token prefixes 2) x kind weight x proximity to the map centre x rank bonus. `PlacePrefetcher` fills the index from z12 tiles within 20 km (about 26 tiles, re-run after moving more than 10 km, skipped offline) and from up to 300 z14 tiles already in the disk cache.

## Label obstacles
Bookmarks suppress POI icons (obstacles added after place labels). The player arrow (arrow box + 4 dp) and the search pin are priority obstacles, registered before any label, so place/water/road labels and icons never sit under them; the layout key includes the marker positions. Search ranking: exact name, then all-token matches (local before network), then partial matches; local POIs are weighted by OMT rank and landmark classes.
