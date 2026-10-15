#!/usr/bin/env python3
"""MineMaps original pixel-art atlas generator.

All artwork here is original and produced deterministically from this file
(palettes, procedural patterns and hand-drawn icon grids). No third-party or
Minecraft assets are used. Re-run to regenerate:

    python3 tools/art/make_atlas.py [out_dir]

Outputs atlas.png (16x16 cells, 16 columns), atlas.json (name -> cells) and
preview.png (8x upscale for review).
"""
import json, os, random, sys
from PIL import Image

T = 16
COLS = 16


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (c[3],)


def new():
    return Image.new('RGBA', (T, T), (0, 0, 0, 0))


def speckle(seed, pal, weights=None, clump=0.0):
    """Noise texture; clump>0 makes neighbouring pixels share shades."""
    r = random.Random(seed)
    img = new(); px = img.load()
    cols = [hexc(p) for p in pal]
    for y in range(T):
        for x in range(T):
            if clump and x > 0 and r.random() < clump:
                px[x, y] = px[x - 1, y]
            elif clump and y > 0 and r.random() < clump:
                px[x, y] = px[x, y - 1]
            else:
                px[x, y] = r.choices(cols, weights=weights)[0]
    return img


# ---------------------------------------------------------------- terrain
GRASS = ['#5d9b37', '#69a83f', '#76b648', '#548f31', '#4b822b', '#82c052']
GRASS_W = [24, 26, 16, 16, 10, 4]


def grass(seed):
    return speckle(seed, GRASS, GRASS_W, clump=0.12)


def park_grass(seed):
    return speckle(seed, ['#6fb443', '#7cc24e', '#89cc5a', '#64a63c', '#5a9935'], [24, 26, 14, 18, 10], clump=0.12)


def flower(seed, petal, centre):
    img = park_grass(seed); px = img.load(); r = random.Random(seed * 7)
    for _ in range(2):
        cx, cy = r.randint(3, 12), r.randint(3, 11)
        px[cx, cy + 1] = hexc('#3f7a22'); px[cx, cy + 2] = hexc('#3f7a22')
        for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 0)):
            px[cx + dx, cy + dy - 1] = hexc(petal)
        px[cx, cy - 1] = hexc(centre)
    return img


def leaves(seed):
    r = random.Random(seed)
    img = speckle(seed, ['#2f6b1f', '#377a24', '#2a5e1b', '#418a2b', '#23501a'], [26, 22, 18, 10, 8], clump=0.25)
    px = img.load()
    for _ in range(7):  # canopy holes & highlights in 2x2 clumps
        x, y = r.randint(0, 14), r.randint(0, 14)
        c = hexc('#1a3d12') if r.random() < 0.55 else hexc('#56a336')
        for dx in range(2):
            for dy in range(2):
                if r.random() < 0.8:
                    px[x + dx, y + dy] = c
    return img


def water(seed):
    r = random.Random(seed)
    img = speckle(seed, ['#2f5fcf', '#3567d8', '#2b56bd', '#3a6fe0'], [30, 30, 20, 20], clump=0.45)
    px = img.load()
    for _ in range(4):  # short wave highlights
        x, y = r.randint(0, 11), r.randint(0, 15)
        for dx in range(r.randint(3, 5)):
            px[(x + dx) % T, y] = hexc('#5b8ff0')
        px[(x + 1) % T, (y + 1) % T] = hexc('#2549a6')
    return img


def sand(seed):
    return speckle(seed, ['#dcd197', '#e5dba6', '#d0c588', '#d8cc92', '#c4b97c'], [26, 22, 20, 22, 10])


def gravel(seed):
    r = random.Random(seed)
    img = speckle(seed, ['#7f7b78', '#8c8884', '#75706d', '#968f89'], clump=0.1); px = img.load()
    for _ in range(9):
        x, y = r.randint(0, 14), r.randint(0, 14)
        c = r.choice([hexc('#5e5a57'), hexc('#a7a29c'), hexc('#7b6a5c')])
        px[x, y] = c; px[x + 1, y] = c
        px[x, y + 1] = shade(c, 0.8)
    return img


def stone(seed):
    r = random.Random(seed)
    img = speckle(seed, ['#7d7d7d', '#858585', '#777777', '#8e8e8e', '#727272'], clump=0.3); px = img.load()
    for _ in range(3):
        x, y = r.randint(0, 12), r.randint(0, 15)
        for dx in range(r.randint(2, 4)):
            px[x + dx, y] = hexc('#626262')
    return img


def voronoi_stones(seed, pal, mortar, n=9):
    r = random.Random(seed)
    pts = [(r.uniform(0, T), r.uniform(0, T)) for _ in range(n)]
    cols = [hexc(r.choice(pal)) for _ in pts]
    img = new(); px = img.load()

    def owner(x, y):
        best, bi, second = 1e9, 0, 1e9
        for i, (pxx, pyy) in enumerate(pts):
            for ox in (-T, 0, T):
                for oy in (-T, 0, T):
                    d = (x - pxx - ox) ** 2 + (y - pyy - oy) ** 2
                    if d < best:
                        second, best, bi = best, d, i
                    elif d < second:
                        second = d
        return bi, second ** 0.5 - best ** 0.5
    for y in range(T):
        for x in range(T):
            i, edge = owner(x + .5, y + .5)
            if edge < 1.0:
                px[x, y] = hexc(mortar)
            else:
                c = cols[i]
                if r.random() < 0.18:
                    c = shade(c, 0.9 if r.random() < 0.5 else 1.08)
                px[x, y] = c
    return img


def cobble(seed):
    return voronoi_stones(seed, ['#8a8a8a', '#7a7a7a', '#9a9a9a', '#6e6e6e'], '#4a4a4a')


def asphalt(seed):
    return speckle(seed, ['#404045', '#48484d', '#3a3a3e', '#525257', '#36363a'], [26, 22, 22, 10, 20])


def bricks(seed, pal, mortar, h=4, w=8, top_light=True):
    r = random.Random(seed)
    img = new(); px = img.load()
    for y in range(T):
        row = y // h
        off = (w // 2) * (row % 2)
        for x in range(T):
            bx = (x + off) % w
            if y % h == h - 1 or bx == w - 1:
                px[x, y] = hexc(mortar)
            else:
                base = hexc(pal[(row * 3 + (x + off) // w) % len(pal)])
                if top_light and y % h == 0:
                    base = shade(base, 1.12)
                if r.random() < 0.15:
                    base = shade(base, 0.92)
                px[x, y] = base
    return img


def stone_bricks(seed):
    return bricks(seed, ['#7f7f7f', '#888888', '#777777'], '#555555', h=8, w=8)


def dirt_path(seed):
    r = random.Random(seed)
    img = speckle(seed, ['#9a7843', '#8a6a3a', '#a6854f', '#7d5f33', '#b0905a'], [24, 24, 18, 20, 6], clump=0.15)
    px = img.load()
    for _ in range(3):
        x, y = r.randint(0, 15), r.randint(0, 15)
        px[x, y] = hexc('#6f6a62')
    return img


def farmland(seed, crop):
    r = random.Random(seed)
    img = speckle(seed, ['#6b4a2a', '#5e4024', '#76532f'], clump=0.2); px = img.load()
    for y in range(T):
        if y % 4 == 3:
            for x in range(T):
                px[x, y] = hexc('#4a321b')
        elif y % 4 == 1:
            for x in range(T):
                if r.random() < 0.55:
                    px[x, y] = hexc(crop) if r.random() < 0.7 else shade(hexc(crop), 0.8)
                    if r.random() < 0.4:
                        px[x, y - 1] = shade(hexc(crop), 1.1)
    return img


def snow(seed):
    return speckle(seed, ['#f4f8fb', '#e8eff5', '#dde7ef', '#ffffff'], [30, 30, 15, 25])


def wetland(seed):
    r = random.Random(seed)
    img = speckle(seed, ['#4f8a3a', '#5a9442', '#46803a', '#3f7535'], clump=0.2); px = img.load()
    for _ in range(2):
        cx, cy, rr = r.randint(2, 13), r.randint(2, 13), r.randint(2, 3)
        for y in range(T):
            for x in range(T):
                if (x - cx) ** 2 + (y - cy) ** 2 <= rr * rr:
                    px[x, y] = hexc('#3d6fa8') if r.random() < 0.85 else hexc('#5585bd')
    for _ in range(5):
        x, y = r.randint(0, 15), r.randint(1, 15)
        px[x, y] = hexc('#7fae4c'); px[x, y - 1] = hexc('#8fbe5a')
    return img


def planks(seed, pal, dark):
    r = random.Random(seed)
    img = new(); px = img.load()
    for y in range(T):
        board = y // 4
        seam = (board * 5 + 3) % T
        for x in range(T):
            c = hexc(pal[(board + (1 if x > seam else 0)) % len(pal)])
            if y % 4 == 3:
                c = hexc(dark)
            elif x == seam:
                c = hexc(dark)
            elif r.random() < 0.12:
                c = shade(c, 0.88)
            px[x, y] = c
    return img


def slab(seed, pal, border):
    img = speckle(seed, pal, clump=0.3); px = img.load()
    for i in range(T):
        px[i, 0] = shade(hexc(pal[0]), 1.1); px[0, i] = shade(hexc(pal[0]), 1.1)
        px[i, T - 1] = hexc(border); px[T - 1, i] = hexc(border)
    return img


def terracotta(seed):
    return speckle(seed, ['#a5532f', '#ad5a34', '#9c4d2b', '#b4643c'], clump=0.35)


def ballast(seed):
    img = gravel(seed); px = img.load(); r = random.Random(seed)
    for y in (2, 7, 12):
        for x in range(1, 15):
            if r.random() < 0.9:
                px[x, y] = hexc('#5a3e24'); px[x, y + 1] = hexc('#4a321d')
    for x in (4, 11):
        for y in range(T):
            px[x, y] = hexc('#9a9aa2')
        for y in range(T):
            px[x + 1, y] = hexc('#5f5f66')
    return img


def grave(seed):
    img = grass(seed); px = img.load()
    for y in range(5, 12):
        for x in range(6, 10):
            px[x, y] = hexc('#9a9a9a') if x < 9 else hexc('#707070')
    for x in range(6, 10):
        px[x, 4] = hexc('#b0b0b0')
    for x in range(5, 11):
        px[x, 12] = hexc('#3f6e27')
    return img


TERRAIN = {
    'grass': [grass(s) for s in (11, 12, 13, 14)],
    'park': [park_grass(21), park_grass(22), flower(23, '#d8312a', '#f2d23a'), flower(24, '#f2d23a', '#e89a1a')],
    'leaves': [leaves(s) for s in (31, 32, 33)],
    'water': [water(s) for s in (41, 42, 43)],
    'sand': [sand(51), sand(52)],
    'gravel': [gravel(61), gravel(62)],
    'stone': [stone(71), stone(72)],
    'cobble': [cobble(81), cobble(82)],
    'asphalt': [asphalt(91), asphalt(92)],
    'stone_bricks': [stone_bricks(101), stone_bricks(102)],
    'dirt_path': [dirt_path(111), dirt_path(112)],
    'farmland': [farmland(121, '#7cb342'), farmland(122, '#d8c04a')],
    'snow': [snow(131), snow(132)],
    'wetland': [wetland(141), wetland(142)],
    'roof_brick': [bricks(151, ['#9c4a3a', '#a85443', '#8e4233'], '#b8aca0', h=4, w=6)],
    'roof_oak': [planks(161, ['#a2824e', '#ad8c56', '#987747'], '#6e5432')],
    'roof_spruce': [planks(171, ['#6e4f2d', '#785734', '#654828'], '#463219')],
    'roof_stone': [slab(181, ['#a5a5a5', '#9d9d9d', '#acacac'], '#727272')],
    'roof_concrete': [slab(191, ['#d6d6d2', '#cfcfcb', '#dcdcd8'], '#9e9e9a')],
    'roof_terracotta': [terracotta(201)],
    'rail': [ballast(211)],
    'grave': [grave(221)],
}

# ---------------------------------------------------------------- v2: vanilla-faithful style
# Textures are grayscale detail maps multiplied by a tint (as the game does for
# grass/leaves/water), with value ranges and pixel-cluster sizes tuned to the
# vanilla look. Drawn from scratch; no game files are read or copied.

def tinted(seed, levels, weights, tint, clump=0.0, extra=None):
    r = random.Random(seed)
    lum = [[0.0] * T for _ in range(T)]
    for y in range(T):
        for x in range(T):
            if clump and x > 0 and r.random() < clump:
                lum[y][x] = lum[y][x - 1]
            elif clump and y > 0 and r.random() < clump:
                lum[y][x] = lum[y - 1][x]
            else:
                lum[y][x] = r.choices(levels, weights=weights)[0]
    if extra:
        extra(r, lum)
    img = new(); px = img.load(); tc = hexc(tint)
    for y in range(T):
        for x in range(T):
            px[x, y] = tuple(max(0, min(255, int(c * lum[y][x]))) for c in tc[:3]) + (255,)
    return img


def strokes(n, length, val, diag=False):
    def f(r, lum):
        for _ in range(n):
            x, y = r.randint(0, 15), r.randint(0, 15)
            for i in range(r.randint(*length)):
                lum[(y + (i if diag else 0)) % T][(x + i) % T] = val
    return f


def grass2(seed):
    return tinted(seed, [0.56, 0.63, 0.70, 0.77, 0.84, 0.92], [5, 14, 30, 30, 15, 6], '#8fc45a', clump=0.06,
                  extra=strokes(3, (1, 2), 0.50))


def park2(seed):
    return tinted(seed, [0.62, 0.70, 0.78, 0.86, 0.94], [8, 26, 34, 22, 10], '#9ccd5e', clump=0.06)


def flower2(seed, petal, centre):
    img = park2(seed); px = img.load(); r = random.Random(seed * 7)
    for _ in range(2):
        cx, cy = r.randint(3, 12), r.randint(3, 11)
        px[cx, cy + 1] = hexc('#3d7a1f'); px[cx, cy + 2] = hexc('#2f6318')
        for dx, dy in ((0, -1), (-1, 0), (1, 0)):
            px[cx + dx, cy + dy - 1] = hexc(petal)
        px[cx, cy - 1] = hexc(centre)
        px[cx + 1, cy] = shade(hexc(petal), 0.75)
    return img


def leaves2(seed):
    def holes(r, lum):
        for _ in range(9):
            x, y = r.randint(0, 14), r.randint(0, 14)
            v = 0.22 if r.random() < 0.6 else 1.0
            for dx, dy in ((0, 0), (1, 0), (0, 1)) if r.random() < 0.6 else ((0, 0),):
                lum[y + dy][x + dx] = v
    return tinted(seed, [0.40, 0.52, 0.64, 0.76, 0.88], [10, 22, 30, 24, 14], '#6fa83a', clump=0.18, extra=holes)


def water2(seed):
    def waves(r, lum):
        for _ in range(5):
            x, y = r.randint(0, 15), r.randint(0, 15)
            n = r.randint(3, 6)
            for i in range(n):
                lum[(y + i // 2) % T][(x + i) % T] = 0.98
                lum[(y + i // 2 + 1) % T][(x + i) % T] = 0.70
    return tinted(seed, [0.78, 0.82, 0.86], [30, 40, 30], '#3f76e4', clump=0.5, extra=waves)


def sand2(seed):
    return tinted(seed, [0.86, 0.90, 0.94, 0.97, 1.0], [10, 22, 30, 24, 14], '#e0d6a6', extra=strokes(4, (1, 1), 0.80))


def stone2(seed):
    def blot(r, lum):
        for _ in range(6):
            x, y = r.randint(0, 14), r.randint(0, 14)
            v = r.choice([0.42, 0.44, 0.60, 0.62])
            for dx in range(r.randint(1, 3)):
                lum[y][(x + dx) % T] = v
                if r.random() < 0.5:
                    lum[(y + 1) % T][(x + dx) % T] = v
    return tinted(seed, [0.47, 0.50, 0.53, 0.56], [20, 30, 30, 20], '#ffffff', clump=0.35, extra=blot)


def smooth_stone(seed):
    img = tinted(seed, [0.60, 0.62, 0.64], [30, 40, 30], '#ffffff', clump=0.5); px = img.load()
    for i in range(T):
        px[i, 0] = hexc('#a8a8a8'); px[0, i] = hexc('#a8a8a8')
        px[i, T - 1] = hexc('#6e6e6e'); px[T - 1, i] = hexc('#6e6e6e')
    return img


def concrete(seed, tint):
    return tinted(seed, [0.94, 0.97, 1.0], [25, 50, 25], tint, clump=0.55)


def gravel2(seed):
    r = random.Random(seed)
    img = tinted(seed, [0.46, 0.52, 0.58], [30, 40, 30], '#ffffff', clump=0.15); px = img.load()
    for _ in range(14):
        x, y = r.randint(0, 14), r.randint(0, 14)
        c = r.choice([hexc('#9b9592'), hexc('#6a6461'), hexc('#857168'), hexc('#a8a29d'), hexc('#4f4b49')])
        px[x, y] = c; px[x + 1, y] = c; px[x, y + 1] = shade(c, 0.82)
    return img


def dirt_path2(seed):
    return tinted(seed, [0.70, 0.76, 0.82, 0.88, 0.94], [10, 24, 30, 24, 12], '#b39158', clump=0.12,
                  extra=strokes(5, (1, 2), 0.60))


def snow2(seed):
    return tinted(seed, [0.93, 0.96, 0.98, 1.0], [15, 30, 30, 25], '#f4fcfc')


def terracotta2(seed):
    return tinted(seed, [0.90, 0.94, 0.97, 1.0], [20, 30, 30, 20], '#a4613f', clump=0.4)


TERRAIN.update({
    'grass': [grass2(s) for s in (11, 12, 13, 14)],
    'park': [park2(21), park2(22), flower2(23, '#d8312a', '#2f2f2f'), flower2(24, '#f5d73c', '#e89a1a')],
    'leaves': [leaves2(s) for s in (31, 32, 33)],
    'water': [water2(s) for s in (41, 42, 43)],
    'sand': [sand2(51), sand2(52)],
    'gravel': [gravel2(61), gravel2(62)],
    'stone': [smooth_stone(71), smooth_stone(72)],
    'asphalt': [concrete(91, '#4b4f55'), concrete(92, '#4b4f55')],
    'dirt_path': [dirt_path2(111), dirt_path2(112)],
    'snow': [snow2(131), snow2(132)],
    'roof_brick': [bricks(151, ['#966153', '#a0695a', '#8a5547'], '#a8a39a', h=4, w=8)],
    'roof_oak': [planks(161, ['#a2834f', '#ad8d57', '#9a7b48'], '#6b5430')],
    'roof_spruce': [planks(171, ['#735531', '#7c5c36', '#6a4e2c'], '#4a361e')],
    'roof_stone': [stone2(181)],
    'roof_concrete': [concrete(191, '#d0d6d7')],
    'roof_terracotta': [terracotta2(201)],
})


# ---------------------------------------------------------------- items / markers
K = '#1a1a1a'
ICONS = {
    'apple': ({'k': K, 'r': '#d22b2b', 'R': '#9e1c1c', 'w': '#ff8a80', 'g': '#4caf50', 'b': '#6d4c2f'}, [
        "................",
        "........kk......",
        ".......kbk.kk...",
        ".......kbkkggk..",
        "....kkkkbkgggk..",
        "...krrrrbrkkk...",
        "..krwwrrrrrrk...",
        "..krwrrrrrrrRk..",
        "..krrrrrrrrrRk..",
        "..krrrrrrrrrRk..",
        "..krrrrrrrrRRk..",
        "...krrrrrrRRk...",
        "...krrrrrRRRk...",
        "....krRkkRRk....",
        ".....kk..kk.....",
        "................"]),
    'bread': ({'k': K, 'a': '#e0a85a', 'b': '#c4843a', 'c': '#9a5f24', 'l': '#f3cf8a'}, [
        "................",
        "................",
        "................",
        "......kkkkk.....",
        "....kkllaaakk...",
        "...klaabaabaak..",
        "..klaabaabaabak.",
        ".klaabaabaabaak.",
        ".kaaaaaaaaaaabk.",
        ".kbaaaaaaaaabck.",
        "..kbbbbbbbbbcck.",
        "...kcccccccccck.",
        "....kkkkkkkkkk..",
        "................",
        "................",
        "................"]),
    'meat': ({'k': K, 'm': '#8d3b1f', 'n': '#b5562d', 'f': '#f0d9b5', 'b': '#e8e2d4'}, [
        "................",
        "................",
        ".....kkkkk......",
        "....knnnnnk.....",
        "...knnmnnnnk....",
        "..knnnnnmnnnk...",
        "..knmnnnnnnmk...",
        "..knnnnnmnnnk...",
        "..kfnnmnnnnnk...",
        "...kfnnnnnmk....",
        "....kffnnnkk....",
        ".....kkffkbbk...",
        "........kbbbbk..",
        ".........kbbk...",
        "..........kk....",
        "................"]),
    'emerald': ({'k': '#0b3d1f', 'e': '#17b85a', 'E': '#0e8a40', 'l': '#7cf0a8', 'w': '#e0fff0'}, [
        "................",
        "......kkkk......",
        ".....kllwek.....",
        "....kllweeek....",
        "...klleeeeeEk...",
        "..klleeeeeeeEk..",
        "..kleeeeeeeeEk..",
        "..keeeeeeeeeEk..",
        "..keeeeeeeeEEk..",
        "..keeeeeeeeEEk..",
        "...keeeeeeEEk...",
        "....keeeeEEk....",
        ".....keeEEk.....",
        "......kkkk......",
        "................",
        "................"]),
    'gold_ingot': ({'k': '#4a3500', 'g': '#f2c230', 'G': '#c99212', 'l': '#fff1a0'}, [
        "................",
        "................",
        "................",
        "................",
        "......kkkkkkk...",
        ".....kllllllgk..",
        "....klggggggGk..",
        "...klggggggGGk..",
        "..klggggggGGk...",
        "..kggggggGGk....",
        "..kGGGGGGGk.....",
        "...kkkkkkk......",
        "................",
        "................",
        "................",
        "................"]),
    'coal': ({'k': '#000000', 'c': '#2e2e33', 'C': '#1f1f22', 'l': '#6a6a75'}, [
        "................",
        "................",
        "................",
        ".......kkk......",
        ".....kkcclk.....",
        "....kccclcck....",
        "...kcclcccCck...",
        "...kclcccccCk...",
        "..kcccccclcCk...",
        "..kclcccccCCk...",
        "..kccccclCCk....",
        "...kCcccCCk.....",
        "....kkCCCk......",
        "......kkk.......",
        "................",
        "................"]),
    'potion': ({'k': K, 'g': '#cfe8f0', 'r': '#e53950', 'R': '#a3192e', 'c': '#8d6e4f', 'w': '#ffffff'}, [
        "................",
        "......kkkk......",
        "......kcck......",
        "......kkkk......",
        ".......kgk......",
        ".......kgk......",
        "......kgggk.....",
        ".....kgggggk....",
        "....kwrrrrrrk...",
        "....kwrrrrrRk...",
        "....krrrrrrRk...",
        "....krrrrrRRk...",
        ".....krrrRRk....",
        "......kkkkk.....",
        "................",
        "................"]),
    'book': ({'k': K, 'b': '#7a3b1e', 'B': '#5a2a14', 'p': '#f2ead8', 'g': '#e3b341'}, [
        "................",
        "................",
        "...kkkkkkkkkk...",
        "...kbbbbbbbbBk..",
        "...kbgggggbbBk..",
        "...kbbbbbbbbBk..",
        "...kbbgggbbbBk..",
        "...kbbbbbbbbBk..",
        "...kbbbbbbbbBk..",
        "...kbbbbbbbbBk..",
        "...kbbbbbbbbBk..",
        "...kBBBBBBBBBk..",
        "...kppppppppppk.",
        "....kkkkkkkkkk..",
        "................",
        "................"]),
    'sapling': ({'k': '#14300c', 'g': '#4caf50', 'G': '#2e7d32', 'l': '#9ae66e', 't': '#6d4c2f'}, [
        "................",
        "......kkk.......",
        ".....klggk......",
        "....klgggGk.kk..",
        "....kggGGGkkgGk.",
        "..kk.kgGGkkgGGk.",
        ".kgGk.kktkgGGk..",
        ".kgGGkk.tkkkk...",
        "..kGGGkktk......",
        "...kkk..tk......",
        "........tk......",
        ".......ktk......",
        "......kttk......",
        ".....kkkkkk.....",
        "................",
        "................"]),
    'minecart': ({'k': K, 'm': '#8a8f99', 'M': '#5b6069', 'l': '#c4c9d1', 'w': '#2b2b2b'}, [
        "................",
        "................",
        "................",
        "................",
        ".kkkkkkkkkkkkkk.",
        ".kllllllllllllk.",
        ".kmkkkkkkkkkkMk.",
        ".kmk........kMk.",
        ".kmkkkkkkkkkkMk.",
        ".kmmmmmmmmmmmMk.",
        "..kMMMMMMMMMMk..",
        "...kkkkkkkkkk...",
        "...kwwk..kwwk...",
        "...kwwk..kwwk...",
        "....kk....kk....",
        "................"]),
    'bed': ({'k': K, 'r': '#c62828', 'R': '#8e1c1c', 'w': '#f5f5f5', 'W': '#cfcfcf', 'b': '#7a5230'}, [
        "................",
        "................",
        "................",
        "................",
        "................",
        ".kkkkkkkkkkkkkk.",
        ".kwwwkrrrrrrrrk.",
        ".kwWWkrrrrrrrRk.",
        ".kkkkkkkkkkkkkk.",
        ".kRRRRRRRRRRRRk.",
        ".kbbbbbbbbbbbbk.",
        ".kbkkkkkkkkkkbk.",
        ".kbk........kbk.",
        ".kkk........kkk.",
        "................",
        "................"]),
    'mug': ({'k': K, 'g': '#d9d2c3', 'G': '#a89f8c', 'c': '#5a3418', 'f': '#efe6d0'}, [
        "................",
        "................",
        "................",
        "...kkkkkkkkk....",
        "...kfffffffk....",
        "...kcccccccGkkk.",
        "...kgggggggGk.k.",
        "...kgggggggGk.k.",
        "...kgggggggGk.k.",
        "...kgggggggGkkk.",
        "...kgggggggGk...",
        "...kGGGGGGGGk...",
        "....kkkkkkkk....",
        "................",
        "................",
        "................"]),
    'pickaxe': ({'k': K, 'i': '#c9d1d9', 'I': '#8a96a3', 's': '#8a5a2b', 'S': '#5e3c1b'}, [
        "................",
        "....kkkkkkkk....",
        "..kkiiiiiiiIkk..",
        ".kiiIkkkkkkIIIk.",
        ".kiIk.ksSk..kIk.",
        "kiIk..ksSk...kIk",
        "kIk...ksSk....kk",
        "kk....ksSk......",
        "......ksSk......",
        "......ksSk......",
        "......ksSk......",
        "......ksSk......",
        "......ksSk......",
        "......kSSk......",
        ".......kk.......",
        "................"]),
    'compass': ({'k': K, 'c': '#9aa0a8', 'C': '#666c74', 'f': '#efe8d6', 'r': '#e53935', 'b': '#3a3a3a'}, [
        "................",
        ".....kkkkkk.....",
        "....kccccccK....".replace('K', 'k'),
        "...kcffffffck...",
        "..kcfffrffffCk..",
        "..kcffrrffffCk..",
        "..kcfffrbfffCk..",
        "..kcffffbbffCk..",
        "..kcffffbfffCk..",
        "..kcffffffffCk..",
        "...kCffffffCk...",
        "....kCCCCCCk....",
        ".....kkkkkk.....",
        "................",
        "................",
        "................"]),
    'bell': ({'k': '#4a3500', 'g': '#f2c230', 'G': '#c99212', 'l': '#fff1a0', 'w': '#6d4c2f'}, [
        "................",
        ".......kk.......",
        "......kwwk......",
        ".....kkkkkk.....",
        "....klggggGk....",
        "....klggggGk....",
        "....klggggGk....",
        "...klgggggGGk...",
        "...klgggggGGk...",
        "..klggggggGGGk..",
        "..kGGGGGGGGGGk..",
        "...kkkkkkkkkk...",
        "......kGGk......",
        ".......kk.......",
        "................",
        "................"]),
    'fish': ({'k': K, 'f': '#5e9fc4', 'F': '#3b6f8f', 'l': '#bfe3f2', 'e': '#000000', 'w': '#ffffff'}, [
        "................",
        "................",
        "................",
        "................",
        "......kkkk......",
        "....kkffffkk..kk",
        "...kflllffFFkkFk",
        "..kfwefffFFFkFFk",
        "..kfeffffFFFFFk.",
        "...kfffffFFkkFFk",
        "....kkFFFFk..kFk",
        "......kkkk....kk",
        "................",
        "................",
        "................",
        "................"]),
    'pin': ({'k': K, 'r': '#e53935', 'R': '#a31f1c', 'w': '#ffffff', 's': '#6b6b6b'}, [
        ".....kkkkkk.....",
        "....krrrrrRk....",
        "...krrwwrrrRk...",
        "...krwwrrrrRk...",
        "...krrrrrrrRk...",
        "...krrrrrrRRk...",
        "....krrrrRRk....",
        "....krrrrRRk....",
        ".....krrRRk.....",
        ".....krrRRk.....",
        "......krRk......",
        "......krRk......",
        ".......kk.......",
        ".......ss.......",
        "......ssss......",
        "................"]),
    'player': ({'k': K, 'w': '#ffffff', 'r': '#ff4d3d', 'R': '#c4271a'}, [
        ".......kk.......",
        "......kwRk......",
        "......kwRk......",
        ".....kwrRRk.....",
        ".....kwrRRk.....",
        "....kwrrRRRk....",
        "....kwrrRRRk....",
        "...kwrrrRRRRk...",
        "...kwrrrRRRRk...",
        "..kwrrrrRRRRRk..",
        "..kwrrrkkRRRRk..",
        ".kwrrrk..kRRRRk.",
        ".kwrrk....kRRRk.",
        "kwrrk......kRRRk",
        "kkkk........kkkk",
        "................"]),
}


# Item-style outlines: a dark shade of the item colour rather than black.
for _name, _k in {'apple': '#4a0b0b', 'bread': '#4a2a0c', 'meat': '#3a170a', 'potion': '#26263a', 'book': '#341707',
                  'minecart': '#24272c', 'bed': '#3a1010', 'mug': '#3a2a1a', 'pickaxe': '#262626',
                  'compass': '#262626', 'fish': '#16303f'}.items():
    ICONS[_name][0]['k'] = _k

# Player marker: dart silhouette like the vanilla map marker. 'f' (white) and
# 's' (light grey) are multiplied by the user-selected tint at runtime; the
# outline stays dark.
ICONS['player'] = ({'k': '#141414', 'f': '#ffffff', 's': '#bdbdbd'}, [
    "................",
    ".......kk.......",
    "......kfsk......",
    "......kfsk......",
    ".....kffssk.....",
    ".....kffssk.....",
    "....kfffsssk....",
    "....kfffsssk....",
    "...kffffssssk...",
    "...kffffssssk...",
    "..kfffffsssssk..",
    "..kfffkkkksssk..",
    ".kfffk....ksssk.",
    ".kffk......kssk.",
    ".kkk........kkk.",
    "................"])

GOLD_ROWS = ICONS['gold_ingot'][1]
APPLE_ROWS = ICONS['apple'][1]
ICONS.update({
    'diamond': ({'k': '#0f4f4c', 'w': '#ffffff', 'l': '#a1fbe8', 'c': '#4aedd9', 'C': '#20c5b5', 'D': '#1a9e94'}, [
        "................",
        "................",
        "....kkkkkkkk....",
        "...kwllccccCk...",
        "..kwlcccccCCCk..",
        ".kkkkkkkkkkkkkk.",
        ".kcclcccccCCDDk.",
        "..kcclccccCDDk..",
        "...kcclcccDDk...",
        "....kcclcDDk....",
        ".....kcccDk.....",
        "......kcDk......",
        ".......kk.......",
        "................",
        "................",
        "................"]),
    'iron_ingot': ({'k': '#3a3a3a', 'l': '#ffffff', 'g': '#d8d8d8', 'G': '#a8a8a8'}, GOLD_ROWS),
    'golden_apple': ({'k': '#6a4a00', 'r': '#f2c230', 'R': '#c99212', 'w': '#fff1a0', 'g': '#4caf50', 'b': '#6d4c2f'},
                     APPLE_ROWS),
    'redstone': ({'k': '#3a0000', 'r': '#ff2a1a', 'R': '#a00d0d'}, [
        "................",
        "................",
        "......kk........",
        ".....krrk..kk...",
        "....krRrk.krrk..",
        "....kRrk..kRrk..",
        ".kk..kk.kk.kk...",
        "krrk...krrk.....",
        "kRrrk.krRrk.kk..",
        ".kRk..kRRk.krrk.",
        "..k....kk..kRrk.",
        ".....kk.....kk..",
        "....krrk........",
        "....kRRk........",
        ".....kk.........",
        "................"]),
    'cake': ({'k': '#3a2412', 'w': '#ffffff', 'W': '#e3e3e3', 'r': '#d8312a', 'b': '#b5763c', 'B': '#8a5426'}, [
        "................",
        "................",
        "................",
        "................",
        "....kkkkkkkk....",
        "..kkwwwwrwwwwkk.",
        ".kwwrwwwwwwrwwwk",
        ".kwwwwwwwwwwwwwk",
        ".kWwwwwwwwwwwwWk",
        ".kWWWWWWWWWWWWWk",
        ".kbbbbbbbbbbbbbk",
        ".kbbBbbbbBbbbbbk",
        ".kbbbbbbbbbbbBbk",
        ".kBBBBBBBBBBBBBk",
        "..kkkkkkkkkkkkk.",
        "................"]),
    'cookie': ({'k': '#4a2a0c', 'c': '#d6994a', 'C': '#a8702e', 'l': '#ecc07a', 'd': '#3a1f0c'}, [
        "................",
        "................",
        ".....kkkkkk.....",
        "....kccllcck....",
        "...kclcccdcck...",
        "..kccccccccCCk..",
        "..kcdcccccdcCk..",
        "..kccccdccccCk..",
        "..kcccccccdCCk..",
        "..kcdccccccCCk..",
        "...kcccdcCCCk...",
        "....kCCCCCCk....",
        ".....kkkkkk.....",
        "................",
        "................",
        "................"]),
    'map': ({'k': '#5a4a2a', 'p': '#e8dcb5', 'g': '#7fb238', 'w': '#4060d8', 's': '#9a9a9a', 'b': '#a85443'}, [
        "................",
        ".kkkkkkkkkkkkkk.",
        ".kppppppppppppk.",
        ".kpggwwggggggpk.",
        ".kpgwwggggbbgpk.",
        ".kpwwgggsgbbgpk.",
        ".kpwggggsggggpk.",
        ".kpsssssssssspk.",
        ".kpggggsggwwgpk.",
        ".kpgbbgsgwwggpk.",
        ".kpgbbgsgwgggpk.",
        ".kpggggsgggggpk.",
        ".kppppppppppppk.",
        ".kkkkkkkkkkkkkk.",
        "................",
        "................"]),
    'clock': ({'k': '#4a3500', 'g': '#f2c230', 'G': '#c99212', 'f': '#7ec8f0', 'y': '#fff07a', 'h': '#6d4c2f',
               'n': '#1f2a5a', 'w': '#ffffff'}, [
        "................",
        ".....kkkkkk.....",
        "...kkggggggkk...",
        "..kggffffffGGk..",
        ".kgffffyyffffGk.",
        ".kgfffyyyyfffGk.",
        ".kgffffyyffffGk.",
        ".kghhhhhhhhhhGk.",
        ".kgnnnnnnnnnnGk.",
        ".kgnnnwnnnnwnGk.",
        ".kgnnnnnnwnnnGk.",
        ".kgnnnnnnnnnnGk.",
        "..kGGnnnnnnGGk..",
        "...kkGGGGGGkk...",
        ".....kkkkkk.....",
        "................"]),
    'chest': ({'k': '#2e1d0c', 'w': '#a8702e', 'W': '#7a4f1e', 's': '#d8d8d8', 'S': '#3a3a3a'}, [
        "................",
        "................",
        ".kkkkkkkkkkkkkk.",
        ".kwwwwwwwwwwwwk.",
        ".kwWWWWWWWWWWwk.",
        ".kwwwwwwwwwwwwk.",
        ".kkkkkkssskkkkk.",
        ".kwwwwwsSswwwwk.",
        ".kwwwwwwwwwwwwk.",
        ".kwWWWWWWWWWWwk.",
        ".kwwwwwwwwwwwwk.",
        ".kWWWWWWWWWWWWk.",
        ".kkkkkkkkkkkkkk.",
        "................",
        "................",
        "................"]),
    'sword': ({'k': '#1f262b', 'w': '#eef6f8', 'l': '#b8c8d0', 'D': '#6f8590', 'g': '#4a3520', 'b': '#7a5230'}, [
        "............kkk.",
        "...........kwlk.",
        "..........kwlDk.",
        ".........kwlDk..",
        "........kwlDk...",
        ".......kwlDk....",
        "......kwlDk.....",
        "..kk.kwlDk......",
        "..kgkwlDk.......",
        "...kgkDk........",
        "....kgk.........",
        "...kbkgk........",
        "..kbk.kgk.......",
        ".kbk...kk.......",
        ".kk.............",
        "................"]),
    'axe': ({'k': '#1f262b', 'l': '#c9d1d9', 'D': '#7f8a96', 'b': '#7a5230'}, [
        "................",
        "..kkkkk.kkkk....",
        "..kllllDkbbk....",
        ".klllllDkbbk....",
        ".klllllDkbbk....",
        ".kllllDDkbbk....",
        "..kDDDDkkbbk....",
        "...kkkk.kbbk....",
        "........kbbk....",
        "........kbbk....",
        "........kbbk....",
        "........kbbk....",
        "........kbbk....",
        "........kbbk....",
        "........kkkk....",
        "................"]),
    'shovel': ({'k': '#1f262b', 'l': '#c9d1d9', 'D': '#7f8a96', 'b': '#7a5230'}, [
        "................",
        "......kkkk......",
        ".....klllDk.....",
        ".....klllDk.....",
        ".....kllDDk.....",
        "......kDDk......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        ".....kbbbbk.....",
        ".....kkkkkk.....",
        "................"]),
    'bow': ({'k': '#2e1d0c', 'b': '#8a5a2b', 's': '#e8e8e8'}, [
        "................",
        "....kkssssss....",
        "...kbbk....s....",
        "..kbbk.....s....",
        "..kbk......s....",
        ".kbbk......s....",
        ".kbk.......s....",
        ".kbk.......s....",
        ".kbk.......s....",
        ".kbk.......s....",
        ".kbbk......s....",
        "..kbk......s....",
        "..kbbk.....s....",
        "...kbbk....s....",
        "....kkssssss....",
        "................"]),
    'torch': ({'k': '#2e1d0c', 'y': '#fff07a', 'o': '#ff9a1f', 'b': '#7a5230'}, [
        "................",
        "................",
        ".......kk.......",
        "......kyyk......",
        "......kyok......",
        "......kook......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        "......kbbk......",
        "......kkkk......",
        "................"]),
    'lantern': ({'k': '#1f1f24', 's': '#55555f', 'g': '#3a3a44', 'y': '#ffd36a', 'o': '#ff9a1f'}, [
        "................",
        ".......kk.......",
        "......k..k......",
        ".....kkkkkk.....",
        ".....kssssk.....",
        "....kkkkkkkk....",
        "....kgyyyygk....",
        "....kgyooygk....",
        "....kgyooygk....",
        "....kgyyyygk....",
        "....kkkkkkkk....",
        ".....kssssk.....",
        ".....kkkkkk.....",
        "................",
        "................",
        "................"]),
    'poppy': ({'k': '#2a0a0a', 'r': '#e0302a', 'R': '#a01c18', 'b': '#2f2f2f', 'g': '#3d8a24'}, [
        "................",
        "................",
        ".....kkkk.......",
        "....krrRRk......",
        "...krrrrRRk.....",
        "...krrbbrRk.....",
        "...krrbbRRk.....",
        "....kRRRRk......",
        "......kgk.......",
        "...kk.kgk.......",
        "..kggkkgk.......",
        "...kggkgk.......",
        "....kkkgk.......",
        "......kgk.......",
        "......kkk.......",
        "................"]),
    'boat': ({'k': '#2e1d0c', 'b': '#a8702e', 'B': '#7a4f1e'}, [
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        ".k............k.",
        ".kbk........kbk.",
        ".kbbkkkkkkkkbbk.",
        ".kbbbbbbbbbbbbk.",
        "..kBBBBBBBBBBk..",
        "...kkkkkkkkkk...",
        "................",
        "................",
        "................"]),
    'bucket': ({'k': '#262626', 'd': '#3a3a3a', 'l': '#ffffff', 's': '#c9c9c9', 'D': '#8a8a8a'}, [
        "................",
        "................",
        "....kkkkkkkk....",
        "...k........k...",
        "..k..........k..",
        "..kkkkkkkkkkkk..",
        "..kddddddddddk..",
        "..klssssssssDk..",
        "...klsssssssk...",
        "...klssssssDk...",
        "...klssssssDk...",
        "....klssssDk....",
        "....kkkkkkkk....",
        "................",
        "................",
        "................"]),
    'star': ({'k': '#7a5a10', 'w': '#ffffff', 'y': '#fff4a0', 'Y': '#e6c84a'}, [
        "................",
        ".......kk.......",
        "......kwyk......",
        "......kwyk......",
        ".....kwwyyk.....",
        "....kwwwyyyk....",
        ".kkkwwwwyyyykkk.",
        "kwwwwwwwyyyyyyyk",
        "kyyyyyyyYYYYYYYk",
        ".kkkyyyyYYYYkkk.",
        "....kyyyYYYk....",
        ".....kyyYYk.....",
        "......kyYk......",
        "......kyYk......",
        ".......kk.......",
        "................"]),
})

# Icons offered for saved-place bookmarks, in picker order ('pin' = default).
BOOKMARK_ICONS = ['pin', 'bed', 'chest', 'diamond', 'emerald', 'gold_ingot', 'iron_ingot', 'redstone', 'apple',
                  'golden_apple', 'bread', 'cake', 'cookie', 'meat', 'fish', 'potion', 'book', 'map', 'compass',
                  'clock', 'bell', 'sword', 'pickaxe', 'axe', 'shovel', 'bow', 'torch', 'lantern', 'poppy', 'sapling',
                  'minecart', 'boat', 'bucket', 'star']


def draw_icon(pal, rows, name):
    assert len(rows) == T, f"{name}: {len(rows)} rows"
    img = new(); px = img.load()
    for y, row in enumerate(rows):
        assert len(row) == T, f"{name} row {y}: len {len(row)} '{row}'"
        for x, ch in enumerate(row):
            if ch != '.':
                px[x, y] = hexc(pal[ch])
    return img


APP_ICON = ({'g': '#69a83f', 'G': '#4b822b', 'l': '#82c052', 'w': '#3567d8', 'W': '#5b8ff0', 's': '#8a8a8a',
             'S': '#6e6e6e', 'b': '#a85443', 'B': '#7a3328', 'c': '#d6d6d2', 'C': '#9e9e9a', 'k': K,
             'r': '#e53935', 'R': '#a31f1c', 'h': '#ffffff'}, [
    "gglgggsSgggwWwgg",
    "gGggggsSggwWwggl",
    "ggbbBgsSgwWwgggg",
    "ggbbBgsSwWwggGgg",
    "lgBBBgsSWwgggggg",
    "ggggggsSwgg.....",
    "ssssssssssssssss",
    "SSSSSSSSSSSSSSSS",
    "gggwWwsSgccCgggg",
    "ggwWwgsSgccCgGgg",
    "gwWwggsSgCCCgggg",
    "wWwgGgsSggggglgg",
    "Wwgggg..........",
    "wggglg..........",
    "gggggg..........",
    "gglggg..........",
])
PIN_OVER = [  # drawn over the bottom-right of the app icon
    "..kkkk..",
    ".krhrrk.",
    "krhrrrRk",
    "krrrrrRk",
    ".krrrRk.",
    ".krrRRk.",
    "..krRk..",
    "...kk...",
]


def app_icon():
    pal, rows = APP_ICON
    rows = [r.replace('.', 'g') for r in rows]
    img = draw_icon(pal, rows, 'app_icon'); px = img.load()
    for y, row in enumerate(PIN_OVER):
        for x, ch in enumerate(row):
            if ch != '.':
                px[8 + x, 7 + y] = hexc(pal[ch])
    return img


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), 'out')
    os.makedirs(out, exist_ok=True)
    cells, meta = [], {'tileSize': T, 'columns': COLS, 'textures': {}, 'icons': {}, 'bookmarkIcons': BOOKMARK_ICONS,
                       'tintableIcons': ['player'], 'anchors': {'pin': [8, 13]}}
    assert all(b in ICONS for b in BOOKMARK_ICONS)
    for name, variants in TERRAIN.items():
        meta['textures'][name] = []
        for v in variants:
            i = len(cells); cells.append(v); meta['textures'][name].append([i % COLS, i // COLS])
    # icons start on a fresh row
    while len(cells) % COLS:
        cells.append(new())
    for name, (pal, rows) in ICONS.items():
        i = len(cells); cells.append(draw_icon(pal, rows, name)); meta['icons'][name] = [i % COLS, i // COLS]
    rows_n = (len(cells) + COLS - 1) // COLS
    atlas = Image.new('RGBA', (COLS * T, rows_n * T), (0, 0, 0, 0))
    for i, c in enumerate(cells):
        atlas.paste(c, ((i % COLS) * T, (i // COLS) * T))
    atlas.save(os.path.join(out, 'atlas.png'))
    with open(os.path.join(out, 'atlas.json'), 'w') as f:
        json.dump(meta, f, indent=1)
    icon = app_icon()
    icon.save(os.path.join(out, 'app_icon_16.png'))
    icon.resize((192, 192), Image.NEAREST).save(os.path.join(out, 'app_icon_192.png'))
    icon.resize((512, 512), Image.NEAREST).save(os.path.join(out, 'app_icon_512.png'))
    prev = Image.new('RGBA', atlas.size, (60, 60, 70, 255)); prev.alpha_composite(atlas)
    prev.resize((atlas.width * 8, atlas.height * 8), Image.NEAREST).save(os.path.join(out, 'preview.png'))
    print(f"atlas {atlas.size}, {len(meta['textures'])} textures, {len(meta['icons'])} icons -> {out}")


if __name__ == '__main__':
    main()
