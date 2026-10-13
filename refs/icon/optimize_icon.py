#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
图标压缩：插画图标 PNG 太大（432px 前景 400KB），
用调色板量化压一下，视觉上无损，包体小一半。
在 make_icon_art.py 之后跑一次。
"""
import os, glob
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))


def compress(path: str, colors: int = 256):
    before = os.path.getsize(path)
    im = Image.open(path).convert("RGBA")
    # 保留 alpha，用调色板量化
    alpha = im.getchannel("A")
    q = im.convert("RGB").quantize(colors=colors, method=Image.MEDIANCUT, dither=Image.FLOYDSTEINBERG)
    out = q.convert("RGBA")
    out.putalpha(alpha)
    out.save(path, optimize=True)
    after = os.path.getsize(path)
    return before, after


if __name__ == "__main__":
    tb = ta = 0
    for p in sorted(glob.glob(os.path.join(HERE, "mipmap-*", "*.png"))):
        b, a = compress(p)
        tb += b
        ta += a
    print("压缩 %.0f KB -> %.0f KB" % (tb / 1024, ta / 1024))
