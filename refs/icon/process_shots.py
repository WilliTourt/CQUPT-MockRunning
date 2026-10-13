#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把手机截图处理成 README 用的图。

- 原图 1224×26xx / 250~490 KB，直接扔进仓库又大又糊
- 缩到 720 宽（显示宽度约 360，手机上看着也清晰）
- 调色板量化：UI 截图颜色少，量化后体积掉一大截且肉眼无损

用法：
    python process_shots.py <截图目录>

截图目录里放手机原图（文件名带时间戳即可，见下面的 MAP）。
"""
import os
import sys
import glob
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
DST = os.path.normpath(os.path.join(HERE, "..", "..", "docs", "img"))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "shots")

# 原文件名前缀（时间戳）-> 目标名
MAP = {
    "231328": "01-run",           # 打卡页，未连接，红电池
    "231340": "03-help",          # 取码页
    "231412": "02-stats",         # 统计页（数字已打码）
    "231522": "05-battery-bad",   # 电池条 红
    "231535": "04-running",       # 跑动中，上半屏（绿电池 + 仪表盘）
    "231547": "06-battery-ok",    # 电池条 绿
    "231607": "07-log",           # 跑动中，日志部分
}

TARGET_W = 720
WIDE_STRIP_W = 900     # 窄条按更高分辨率出


def process(src: str, dst: str, width: int) -> tuple:
    im = Image.open(src).convert("RGB")
    w, h = im.size

    # 窄条（电池提示）按更宽的尺寸出，其余按 720
    target = WIDE_STRIP_W if h < 500 else width
    if w != target:
        im = im.resize((target, int(h * target / w)), Image.LANCZOS)

    # 调色板量化
    q = im.quantize(colors=256, method=Image.MEDIANCUT, dither=Image.NONE)
    q.save(dst, optimize=True)

    return im.size, os.path.getsize(src), os.path.getsize(dst)


if __name__ == "__main__":
    os.makedirs(DST, exist_ok=True)

    # 先清掉之前的占位图
    for f in glob.glob(os.path.join(DST, "*.png")):
        os.remove(f)

    total_before = total_after = 0
    print("%-16s %-12s %-12s %s" % ("目标名", "原图", "新图", "压缩"))
    print("-" * 60)

    for key, name in sorted(MAP.items(), key=lambda kv: kv[1]):
        matches = glob.glob(os.path.join(SRC, "*%s*.jpg" % key))
        if not matches:
            print("  [!] 找不到 %s" % key)
            continue
        size, before, after = process(matches[0], os.path.join(DST, name + ".png"), TARGET_W)
        total_before += before
        total_after += after
        print("  %-14s %-12s %-12s -%.0f%%   %dx%d"
              % (name + ".png",
                 "%.0f KB" % (before / 1024),
                 "%.0f KB" % (after / 1024),
                 (1 - after / before) * 100,
                 size[0], size[1]))

    print("-" * 60)
    print("  合计 %.2f MB -> %.2f MB" % (total_before / 1048576, total_after / 1048576))
