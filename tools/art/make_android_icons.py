#!/usr/bin/env python3
"""Generates the Android launcher icons from tools/art/out/app_icon_16.png.

Run tools/art/make_atlas.py first, then:  python3 tools/art/make_android_icons.py
Writes legacy mipmaps, the adaptive-icon foreground and the adaptive XML under app/src/main/res.
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), '..', '..')
SRC = os.path.join(ROOT, 'tools', 'art', 'out', 'app_icon_16.png')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
BG = (0x69, 0xa8, 0x3f, 255)
SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}


def main():
    icon = Image.open(SRC).convert('RGBA')
    for density, px in SIZES.items():
        d = os.path.join(RES, f'mipmap-{density}')
        os.makedirs(d, exist_ok=True)
        square = Image.new('RGBA', (px, px), BG)
        square.alpha_composite(icon.resize((px, px), Image.NEAREST))
        square.save(os.path.join(d, 'ic_launcher.png'))
        mask = Image.new('L', (px * 4, px * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
        round_icon = square.copy()
        round_icon.putalpha(mask.resize((px, px), Image.LANCZOS))
        round_icon.save(os.path.join(d, 'ic_launcher_round.png'))
    nodpi = os.path.join(RES, 'drawable-nodpi')
    os.makedirs(nodpi, exist_ok=True)
    fg = Image.new('RGBA', (432, 432), (0, 0, 0, 0))
    fg.alpha_composite(icon.resize((288, 288), Image.NEAREST), (72, 72))
    fg.save(os.path.join(nodpi, 'ic_launcher_foreground.png'))
    anydpi = os.path.join(RES, 'mipmap-anydpi-v26')
    os.makedirs(anydpi, exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@color/ic_launcher_background" />\n'
           '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
           '</adaptive-icon>\n')
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        with open(os.path.join(anydpi, name), 'w') as f:
            f.write(xml)
    with open(os.path.join(RES, 'values', 'ic_launcher_background.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#69a83f</color>\n</resources>\n')
    print('icons written')


if __name__ == '__main__':
    main()
