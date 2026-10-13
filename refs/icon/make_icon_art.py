#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
鲸鲸约跑 图标生成器

来源：refs/icon/art/source.png（整张插画，不裁脸不放大）

自适应图标的正确理解：
    画布 108×108，系统只渲染**中间 72×72 那一块**并套蒙版。
    所以预览时蒙版直径必须是画布的 2/3，不能拿整块画布当圆 —— 我第一版就错在这，
    导致"看到一个方框"，其实是预览比真实情况多露了一圈。

取景（IMG_SCALE）：
    整图缩到 72/108 以上，图片方块的边就落在可见区之外，bleed 永远看不到，
    也就不会出现接缝。0.74 时可见区几乎正好容纳整张图，人物一点不切。

输出：refs/icon/mipmap-*/ + preview_*.png
"""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "art", "source.png")

SS = 4
V = 108.0
VISIBLE = 72.0 / 108.0       # 自适应图标实际渲染的区域
IMG_SCALE = 70.0 / 108.0     # 整图占画布比例（略大于 VISIBLE，保证不露边）


def load() -> Image.Image:
    return Image.open(SRC).convert("RGB")


def bleed(W: int) -> Image.Image:
    """bleed 底：整图铺满 + 重度模糊。只在蒙版外可见，用来兜底不露白。"""
    im = load().resize((W, W), Image.LANCZOS)
    return im.filter(ImageFilter.GaussianBlur(W * 0.07))


def art_layer(W: int, scale: float) -> Image.Image:
    """整图等比缩到 scale，居中。"""
    nw = max(1, int(W * scale))
    out = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    out.paste(load().resize((nw, nw), Image.LANCZOS).convert("RGBA"),
              ((W - nw) // 2, (W - nw) // 2))
    return out


def adaptive(size: int):
    """返回 (background, foreground)，尺寸都按 108 比例算好。"""
    W = int(size * V / 48.0)
    return bleed(W).convert("RGBA"), art_layer(W, IMG_SCALE)


def mask_shape(W: int, kind: str, is_adaptive: bool) -> Image.Image:
    """按真实尺寸生成蒙版。
    is_adaptive=True 时只取中间 VISIBLE 那块（系统就是这么裁的）。"""
    m = Image.new("L", (W, W), 0)
    md = ImageDraw.Draw(m)
    if is_adaptive:
        d = int(W * VISIBLE)
        off = (W - d) // 2
        box = [off, off, off + d - 1, off + d - 1]
    else:
        box = [0, 0, W - 1, W - 1]

    if kind == "round":
        md.ellipse(box, fill=255)
    elif kind == "squircle":
        md.rounded_rectangle(box, radius=int((box[2] - box[0]) * 0.30), fill=255)
    else:
        md.rectangle(box, fill=255)
    return m


def preview(size: int, kind: str, scale: float = IMG_SCALE) -> Image.Image:
    """模拟系统渲染自适应图标后的样子。"""
    W = size * SS
    bg = bleed(W).convert("RGBA")
    fg = art_layer(W, scale)
    comp = Image.alpha_composite(bg, fg)
    out = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    out.paste(comp, (0, 0), mask_shape(W, kind, True))
    return out.resize((size, size), Image.LANCZOS)


def legacy(size: int, kind: str) -> Image.Image:
    """普通图标（API 24/25）：整图满铺，直接套圆角/圆形。"""
    W = size * SS
    im = load().resize((W, W), Image.LANCZOS).convert("RGBA")
    out = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask_shape(W, kind, False))
    return out.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    variants = [0.667, 0.70, 0.74, 0.80, 0.90]
    sheet = Image.new("RGBA", (200 * len(variants) + 20, 220), (18, 24, 36, 255))
    for i, s in enumerate(variants):
        sheet.alpha_composite(preview(180, "round", s), (20 + i * 200, 20))
    sheet.save(os.path.join(HERE, "preview_scale_compare.png"))

    preview(432, "round").save(os.path.join(HERE, "preview_adaptive_circle.png"))
    preview(432, "squircle").save(os.path.join(HERE, "preview_adaptive_squircle.png"))
    legacy(432, "round").save(os.path.join(HERE, "preview_round.png"))
    legacy(432, "square").save(os.path.join(HERE, "preview_square.png"))

    DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in DENS.items():
        dd = os.path.join(HERE, "mipmap-" + name)
        os.makedirs(dd, exist_ok=True)
        bg, fg = adaptive(px)
        legacy(px, "square").save(os.path.join(dd, "ic_launcher.png"))
        legacy(px, "round").save(os.path.join(dd, "ic_launcher_round.png"))
        bg.convert("RGB").save(os.path.join(dd, "ic_launcher_background.png"))
        fg.save(os.path.join(dd, "ic_launcher_foreground.png"))

    sizes = [(48, "mdpi"), (72, "hdpi"), (96, "xhdpi"), (144, "xxhdpi"), (192, "xxxhdpi")]
    w = sum(s for s, _ in sizes) + 20 * (len(sizes) + 1)
    sh = Image.new("RGBA", (w, 212), (18, 24, 36, 255))
    x = 20
    for s, dn in sizes:
        sh.alpha_composite(Image.open(os.path.join(HERE, "mipmap-" + dn, "ic_launcher.png")), (x, 10))
        x += s + 20
    sh.save(os.path.join(HERE, "preview_sizes.png"))

    print("取景对比 -> preview_scale_compare.png（蒙版按真实的 72/108 算）")
    print("各密度图标 ->", HERE)
