#!/usr/bin/env python3
"""Build the optional "Minecraft" texture pack on YOUR machine from Minecraft files you have access to.

MineMaps does not ship any Mojang assets. This script reads them locally and writes a git-ignored pack that only ends
up in APKs you build yourself. Such builds are for personal use and must not be distributed.

    python3 tools/art/import_minecraft.py                      # official client jar of the pinned release (26.3), from Mojang
    python3 tools/art/import_minecraft.py --version 1.21.5     # a specific release
    python3 tools/art/import_minecraft.py --jar PATH.jar       # a jar you already have (~/.minecraft/versions/<v>/<v>.jar)
    python3 tools/art/import_minecraft.py --resource-pack P    # a resource pack (.zip or folder) layered on top
    python3 tools/art/import_minecraft.py --resource-pack P --offline   # pack only; gaps keep MineMaps art

Sources are searched in order: resource pack, then the client jar. Anything still missing keeps the original MineMaps
drawing. The jar is downloaded only from Mojang's launcher servers (piston-meta / piston-data) with its SHA-1 checked,
and cached in ~/.cache/minemaps-mc. Higher-resolution packs (32x, 64x, ...) are downscaled to 16x16.

Output: mapengine/src/main/resources/minemaps/packs/vanilla/{atlas.png, atlas.json, SOURCE.txt}, using the same
texture and icon names as the original MineMaps atlas, so the engine treats both packs identically.
"""
import argparse, hashlib, io, json, os, sys, urllib.request, zipfile
try:
    from PIL import Image
except ImportError:
    sys.exit('Pillow is required: python3 -m pip install --user pillow   (or: sudo apt install python3-pil)')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ORIGINAL = os.path.join(ROOT, 'mapengine/src/main/resources/minemaps')
OUT = os.path.join(ORIGINAL, 'packs/vanilla')
CACHE = os.path.expanduser('~/.cache/minemaps-mc')
MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
PINNED_VERSION = '26.3'  # same as MinecraftImporter.PINNED_VERSION in the app
PREFIX = 'assets/minecraft/textures/'
T, COLS = 16, 16
WATER = (0x3F, 0x76, 0xE4)
SWAMP_GRASS = (0x6A, 0x70, 0x39)


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'MineMaps texture importer'})
    with urllib.request.urlopen(req) as r:
        return r.read()


def get_jar(version):
    os.makedirs(CACHE, exist_ok=True)
    manifest = json.loads(fetch(MANIFEST))
    version = version or PINNED_VERSION
    entry = next((v for v in manifest['versions'] if v['id'] == version), None)
    if not entry:
        sys.exit(f'unknown Minecraft version {version}')
    client = json.loads(fetch(entry['url']))['downloads']['client']
    path = os.path.join(CACHE, f'client-{version}.jar')
    if not (os.path.exists(path) and hashlib.sha1(open(path, 'rb').read()).hexdigest() == client['sha1']):
        print(f'downloading the official Minecraft {version} client from Mojang ({client["size"] // 1_000_000} MB)...')
        data = fetch(client['url'])
        if hashlib.sha1(data).hexdigest() != client['sha1']:
            sys.exit('SHA-1 mismatch on the downloaded client jar')
        open(path, 'wb').write(data)
    return path, f'Minecraft {version} client jar (sha1 {client["sha1"]})'


class Source:
    """A zip/jar or a folder with the assets/minecraft/textures layout (a resource pack may nest it in one folder)."""

    def __init__(self, path):
        self.path, self.zip, self.base = path, None, ''
        if not os.path.exists(path):
            sys.exit(f'{path}: not found')
        if os.path.isdir(path):
            for root, dirs, _ in os.walk(path):
                if os.path.isdir(os.path.join(root, PREFIX)):
                    self.base = root; break
            else:
                sys.exit(f'{path}: no {PREFIX} folder inside')
        else:
            self.zip = zipfile.ZipFile(path)
            names = [n for n in self.zip.namelist() if PREFIX in n]
            if not names:
                sys.exit(f'{path}: no {PREFIX} inside; is this a Minecraft jar or resource pack?')
            self.base = names[0][:names[0].index(PREFIX)]

    def read(self, rel):
        name = self.base.rstrip('/') + '/' + PREFIX + rel + '.png' if self.base else PREFIX + rel + '.png'
        try:
            if self.zip:
                return self.zip.read(name.lstrip('/'))
            with open(os.path.join(self.base, PREFIX, rel + '.png'), 'rb') as f:
                return f.read()
        except (KeyError, FileNotFoundError):
            return None


class Missing(Exception):
    pass


class Textures:
    def __init__(self, sources):
        self.sources = sources

    def has(self, rel):
        return any(s.read(rel) is not None for s in self.sources)

    def img(self, rel):
        for s in self.sources:
            data = s.read(rel)
            if data is not None:
                im = Image.open(io.BytesIO(data)).convert('RGBA')
                if rel.startswith(('block/', 'item/')) and im.width != T:  # HD packs: scale to 16 px wide
                    im = im.resize((T, max(T, im.height * T // im.width)), Image.BOX)
                return im
        raise Missing(rel)


def frame(im, i=0):
    """First (or i-th) square frame of an animated strip, as 16x16."""
    n = max(1, im.height // im.width)
    i %= n
    return im.crop((0, i * im.width, im.width, (i + 1) * im.width)).resize((T, T), Image.NEAREST)


def tint(im, rgb, f=1.0):
    px = im.load(); out = Image.new('RGBA', im.size); po = out.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            po[x, y] = (min(255, int(r * rgb[0] / 255 * f)), min(255, int(g * rgb[1] / 255 * f)),
                        min(255, int(b * rgb[2] / 255 * f)), a)
    return out


def over(base, top):
    out = frame(base).copy(); out.alpha_composite(frame(top)); return out


def flatten(im, bg):
    """Fill transparent pixels (e.g. leaf gaps) with a solid colour."""
    out = Image.new('RGBA', im.size, bg + (255,)); out.alpha_composite(im); return out


def biome(colormap, temp, humidity):
    humidity *= temp
    w, h = colormap.size
    return colormap.getpixel((int((1 - temp) * (w - 1)), int((1 - humidity) * (h - 1))))[:3]


def rotations(im, n=4):
    im = frame(im)
    return [im, im.transpose(Image.ROTATE_90), im.transpose(Image.ROTATE_180), im.transpose(Image.ROTATE_270)][:n]


def scale_icon(im):
    if im.height > im.width:
        im = frame(im)
    if im.size == (T, T):
        return im
    if im.width < T:
        k = T // im.width
        big = im.resize((im.width * k, im.height * k), Image.NEAREST)
        out = Image.new('RGBA', (T, T)); out.paste(big, ((T - big.width) // 2, (T - big.height) // 2)); return out
    return im.resize((T, T), Image.BOX)


def center_x(im):
    box = im.getchannel('A').getbbox()
    if not box:
        return im
    shift = round(T / 2 - (box[0] + box[2]) / 2)
    out = Image.new('RGBA', im.size)
    out.paste(im, (shift, 0))
    return out


def recipes(tx):
    """atlas name -> function returning a list of 16x16 variants (textures) or one image (icons)."""
    item = lambda n: tx.img('item/' + n)
    grass_c = lambda: biome(tx.img('colormap/grass'), 0.8, 0.4)    # plains
    park_c = lambda: biome(tx.img('colormap/grass'), 0.7, 0.8)     # lusher
    leaf_c = lambda: biome(tx.img('colormap/foliage'), 0.7, 0.8)
    grass = lambda: tint(frame(tx.img('block/grass_block_top')), grass_c())
    park = lambda: tint(frame(tx.img('block/grass_block_top')), park_c())

    def leaves():
        c = leaf_c()
        return flatten(tint(frame(tx.img('block/oak_leaves')), c), tuple(int(v * 0.35) for v in c))

    def grave():
        g = grass().copy(); g.paste(frame(tx.img('block/stone_bricks')).crop((0, 0, 4, 6)), (6, 5)); return g

    roof_stone = lambda: tx.img('block/polished_andesite' if tx.has('block/polished_andesite') else 'block/stone')
    textures = {
        'grass': lambda: rotations(grass()),
        'park': lambda: rotations(park(), 2) + [over(park(), tx.img('block/poppy')),
                                               over(park(), tx.img('block/dandelion'))],
        'leaves': lambda: rotations(leaves(), 3),
        'water': lambda: [tint(frame(tx.img('block/water_still'), i), WATER) for i in (0, 5, 11)],
        'sand': lambda: rotations(tx.img('block/sand'), 2),
        'gravel': lambda: rotations(tx.img('block/gravel'), 2),
        'stone': lambda: rotations(tx.img('block/smooth_stone'), 2),
        'cobble': lambda: rotations(tx.img('block/cobblestone'), 2),
        'asphalt': lambda: rotations(tx.img('block/gray_concrete'), 2),
        'stone_bricks': lambda: [frame(tx.img('block/stone_bricks')), frame(tx.img('block/cracked_stone_bricks'))],
        'dirt_path': lambda: rotations(tx.img('block/dirt_path_top'), 2),
        'farmland': lambda: [over(tx.img('block/farmland_moist'), tx.img('block/wheat_stage3')),
                             over(tx.img('block/farmland_moist'), tx.img('block/wheat_stage7'))],
        'snow': lambda: rotations(tx.img('block/snow'), 2),
        'wetland': lambda: [frame(tx.img('block/mud')), tint(frame(tx.img('block/grass_block_top')), SWAMP_GRASS)],
        'roof_brick': lambda: [frame(tx.img('block/bricks'))],
        'roof_oak': lambda: [frame(tx.img('block/oak_planks'))],
        'roof_spruce': lambda: [frame(tx.img('block/spruce_planks'))],
        'roof_stone': lambda: [frame(roof_stone())],
        'roof_concrete': lambda: [frame(tx.img('block/white_concrete'))],
        'roof_terracotta': lambda: [frame(tx.img('block/terracotta'))],
        'rail': lambda: [over(tx.img('block/gravel'), tx.img('block/rail'))],
        'grave': lambda: [grave()],
    }
    icons = {
        'apple': lambda: item('apple'), 'bread': lambda: item('bread'), 'meat': lambda: item('cooked_beef'),
        'emerald': lambda: item('emerald'), 'gold_ingot': lambda: item('gold_ingot'), 'coal': lambda: item('coal'),
        'potion': lambda: over(tint(item('potion_overlay'), (0xF8, 0x24, 0x23)), item('potion')),
        'book': lambda: item('book'), 'sapling': lambda: tx.img('block/oak_sapling'),
        'minecart': lambda: item('minecart'), 'mug': lambda: item('honey_bottle'),
        'pickaxe': lambda: item('iron_pickaxe'), 'compass': lambda: item('compass_00'), 'bell': lambda: item('bell'),
        'fish': lambda: item('cod'), 'pin': lambda: tx.img('map/decorations/red_banner'),
        'player': lambda: center_x(scale_icon(tx.img('map/decorations/player'))), 'diamond': lambda: item('diamond'),
        'iron_ingot': lambda: item('iron_ingot'), 'redstone': lambda: item('redstone'),
        'golden_apple': lambda: item('golden_apple'), 'cake': lambda: item('cake'), 'cookie': lambda: item('cookie'),
        'map': lambda: item('filled_map') if tx.has('item/filled_map') else item('map'),
        'clock': lambda: item('clock_00'), 'sword': lambda: item('iron_sword'), 'axe': lambda: item('iron_axe'),
        'shovel': lambda: item('iron_shovel'), 'bow': lambda: item('bow'), 'torch': lambda: tx.img('block/torch'),
        'lantern': lambda: item('lantern'), 'poppy': lambda: tx.img('block/poppy'), 'boat': lambda: item('oak_boat'),
        'bucket': lambda: item('bucket'), 'star': lambda: item('nether_star'),
        # 'bed' and 'chest' have no 2D item texture in the game (3D models), so they keep the MineMaps drawing.
    }
    return textures, icons


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--jar', help='Minecraft Java client jar you already have')
    ap.add_argument('--version', help=f'release to download from Mojang (default: {PINNED_VERSION}, the version the app pins)')
    ap.add_argument('--resource-pack', action='append', default=[], metavar='PATH',
                    help='resource pack .zip or folder searched before the jar (repeatable, first wins)')
    ap.add_argument('--offline', action='store_true', help='never download the client jar')
    a = ap.parse_args()

    sources, desc = [Source(p) for p in a.resource_pack], [f'resource pack {os.path.basename(p)}' for p in a.resource_pack]
    if a.jar:
        sources.append(Source(a.jar))
        desc.append(f'{os.path.basename(a.jar)} (sha1 {hashlib.sha1(open(a.jar, "rb").read()).hexdigest()})')
    elif not a.offline:
        path, d = get_jar(a.version)
        sources.append(Source(path)); desc.append(d)
    if not sources:
        sys.exit('nothing to import: pass --resource-pack and/or --jar, or drop --offline')
    tex_recipes, icon_recipes = recipes(Textures(sources))

    orig_meta = json.load(open(os.path.join(ORIGINAL, 'atlas.json')))
    orig_atlas = Image.open(os.path.join(ORIGINAL, 'atlas.png')).convert('RGBA')
    orig_cell = lambda c: orig_atlas.crop((c[0] * T, c[1] * T, c[0] * T + T, c[1] * T + T))
    kept, cells = [], []
    meta = {k: v for k, v in orig_meta.items() if k not in ('textures', 'icons', 'anchors')}
    meta.update(textures={}, icons={}, anchors={})

    def add(im):
        cells.append(im); return [(len(cells) - 1) % COLS, (len(cells) - 1) // COLS]

    for name, orig_cells in orig_meta['textures'].items():
        try:
            variants = [frame(v) for v in tex_recipes[name]()]
        except Missing as e:
            variants = [orig_cell(c) for c in orig_cells]; kept.append(f'{name} ({e})')
        meta['textures'][name] = [add(v) for v in variants]
    while len(cells) % COLS:
        cells.append(Image.new('RGBA', (T, T)))
    for name, cell in orig_meta['icons'].items():
        try:
            im = scale_icon(icon_recipes[name]()) if name in icon_recipes else None
        except Missing as e:
            im = None; kept.append(f'{name} ({e})')
        if im is None:
            im = orig_cell(cell)
            if name in orig_meta.get('anchors', {}):
                meta['anchors'][name] = orig_meta['anchors'][name]
            if name not in icon_recipes:
                kept.append(f'{name} (no 2D texture in the game)')
        elif name == 'pin':
            meta['anchors']['pin'] = [8, 16]  # banner: bottom of the pole
        meta['icons'][name] = add(im)

    atlas = Image.new('RGBA', (COLS * T, ((len(cells) + COLS - 1) // COLS) * T))
    for i, c in enumerate(cells):
        atlas.paste(c, ((i % COLS) * T, (i // COLS) * T))
    os.makedirs(OUT, exist_ok=True)
    atlas.save(os.path.join(OUT, 'atlas.png'))
    json.dump(meta, open(os.path.join(OUT, 'atlas.json'), 'w'), indent=1)
    with open(os.path.join(OUT, 'SOURCE.txt'), 'w') as f:
        f.write('Imported from: ' + '; '.join(desc) + '\n'
                'Minecraft textures (c) Mojang Studios. Personal use only: do not commit or distribute this folder or '
                'any APK built with it.\nKept MineMaps art for: ' + (', '.join(kept) or 'none') + '\n')
    prev = Image.new('RGBA', atlas.size, (60, 60, 70, 255)); prev.alpha_composite(atlas)
    os.makedirs(os.path.join(HERE, 'out'), exist_ok=True)
    prev.resize((atlas.width * 8, atlas.height * 8), Image.NEAREST).save(os.path.join(HERE, 'out/preview_vanilla.png'))
    print(f'Minecraft pack built from {"; ".join(desc)}\n  {len(meta["textures"])} textures, {len(meta["icons"])} icons'
          f' -> {os.path.relpath(OUT, ROOT)}')
    if kept:
        print('  kept MineMaps art for: ' + ', '.join(kept))


if __name__ == '__main__':
    main()
