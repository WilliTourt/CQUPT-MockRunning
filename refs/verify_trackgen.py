#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_trackgen.py —— TrackGen.kt 的 Python 镜像（配速标定 / 围栏判定）。

这里直接复用 verify_pace_profile.py 里逐行回写的实现，避免两份镜像各自漂移。
毛刺开关的两种模式（关闭 = v1.10.1 光滑轨迹，打开 = 多尺度噪声 + 野点）都会跑。
Kotlin 的 XorWow PRNG 和 Python 的 Mersenne 取值不同，噪声的具体数值不会一致，
但统计行为和数值流程等价 —— 所以这个验证对 Kotlin 端是有效的。

跑法（在仓库根目录）：
    python refs/verify_trackgen.py
"""

import math
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from verify_pace_profile import (          # noqa: E402
    hav, generate, point_in_poly, measure, fmt_pace,
)

# 用小程序 testPoints 里出现过的坐标量级，构造一个 ~110m x 100m 的操场多边形
POLY = [
    [29.533524848090277, 106.60718777126736],
    [29.533416883680555, 106.60806667751736],
    [29.532716742621528, 106.60790879991319],
    [29.532604437934026, 106.60711588541666],
]


def main():
    print("区域多边形 %d 顶点" % len(POLY))
    per = 0.0
    for i in range(len(POLY)):
        a, b = POLY[i], POLY[(i + 1) % len(POLY)]
        per += hav(a[0], a[1], b[0], b[1])
    print("  周长 %.1f m  (一圈不够，需要绕多圈)\n" % per)

    print(f"{'毛刺':<6}{'目标配速':<10}{'点数':>6}{'实测距离':>11}{'实测配速':>11}{'区域内占比':>11}")
    print("-" * 58)

    for label, burrs in (("关", False), ("开", True)):
        for pace in (210.0, 240.0, 330.0, 465.0, 600.0, 840.0):
            pts, fence, real, window, _ = generate(
                POLY, [], pace, 2000.0, seed=12345, burrs=burrs)
            d = measure(pts)
            inside = sum(1 for p in pts if point_in_poly(p["lat"], p["lon"], fence))
            print(f"{label:<6}{fmt_pace(pace):<10}{len(pts):>6}{d:>11.1f}"
                  f"{fmt_pace(real):>11}{inside / len(pts):>9.1%}")

    print()
    print("=== 分批逻辑（每批 8 点，末点作下批首点）===")
    pts, _, _, _, _ = generate(POLY, [], 330.0, 2000.0, seed=12345, burrs=True)
    batches, cur = [], []
    for p in pts:
        cur.append(p)
        if len(cur) >= 8:
            batches.append(cur)
            cur = [cur[-1]]
    if len(cur) > 1:
        batches.append(cur)
    print(f"  {len(pts)} 点 → {len(batches)} 批")
    for i in (0, 1, 2, len(batches) - 1):
        print(f"  批{i+1}: sec {batches[i][0]['sec']} → {batches[i][-1]['sec']}"
              f"  ({len(batches[i])} 点)")
    ok = all(batches[i][-1]["sec"] == batches[i + 1][0]["sec"]
             for i in range(len(batches) - 1))
    print("  末点=下批首点:", ok)

    print()
    print("=== 轨迹形态（毛刺开启）===")
    pts, _, _, _, _ = generate(POLY, [], 330.0, 2000.0, seed=999, burrs=True)
    headings = set()
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        headings.add(round(math.degrees(math.atan2(
            (b["lon"] - a["lon"]) * math.cos(math.radians(a["lat"])),
            b["lat"] - a["lat"])) % 360))
    print(f"  不同航向取值: {len(headings)} 个")
    print(f"  精度字段: {min(p['acc'] for p in pts)} ~ {max(p['acc'] for p in pts)} m")
    sp = [p["speed"] for p in pts]
    print(f"  报告速度: {min(sp):.2f} ~ {max(sp):.2f} m/s (目标 {1000 / 330:.2f})")
    ds = [p["step"] for p in pts]
    mean = sum(ds) / len(ds)
    print(f"  逐点间距: {min(ds):.2f} ~ {max(ds):.2f} m"
          f"（>1.5×均值 {sum(1 for x in ds if x > mean * 1.5)} 个，"
          f">5m {sum(1 for x in ds if x > 5.0)} 个）")
    print(f"  首点 {pts[0]['lat']:.7f},{pts[0]['lon']:.7f}")
    print(f"  末点 {pts[-1]['lat']:.7f},{pts[-1]['lon']:.7f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
