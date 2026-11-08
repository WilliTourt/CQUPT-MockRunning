#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用服务器返回的真实多边形 + 真实 jpq 禁跑区，验证轨迹生成。

和 verify_trackgen.py 共用 verify_pace_profile.py 里的 Python 镜像，
不再自己维护第二份实现。毛刺开 / 关两种模式都跑，硬约束都要成立。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from verify_pace_profile import (      # noqa: E402
    dedupe, hav, generate, measure, point_in_poly, fmt_pace,
)

# ---- 服务器真实返回（/area/getAllArea）----
FIELDS = {
    "T1001 风华运动场": {
        "fence": [(29.532692, 106.608105), (29.532871, 106.608176),
                  (29.533363, 106.608104), (29.533592, 106.608107),
                  (29.533707, 106.607374), (29.533713, 106.607166),
                  (29.533627, 106.60702), (29.533197, 106.607033),
                  (29.532749, 106.607017), (29.532289, 106.607043),
                  (29.532065, 106.607032), (29.531956, 106.607445),
                  (29.531947, 106.607814), (29.531952, 106.607986),
                  (29.532145, 106.608111), (29.532466, 106.608101),
                  (29.532692, 106.608105)],
        "exclude": [[(29.532769, 106.607824), (29.533107, 106.607843),
                     (29.533293, 106.607835), (29.533417, 106.607734),
                     (29.533403, 106.607527), (29.533345, 106.607373),
                     (29.533219, 106.607323), (29.533012, 106.607303),
                     (29.53265, 106.60733), (29.532462, 106.60738),
                     (29.532362, 106.607479), (29.532323, 106.607624),
                     (29.532385, 106.607769), (29.532528, 106.607824),
                     (29.532717, 106.607841)]],
    },
    "T1005 太极运动场": {
        "fence": [(29.533933, 106.608884), (29.531893, 106.60897),
                  (29.532019, 106.610646), (29.533979, 106.610558)],
        "exclude": [[(29.533214, 106.609447), (29.533071, 106.609473),
                     (29.532917, 106.609468), (29.532626, 106.609486),
                     (29.532506, 106.609612), (29.532533, 106.610029),
                     (29.532691, 106.610093), (29.533146, 106.61006),
                     (29.533421, 106.610058), (29.533549, 106.60969),
                     (29.533457, 106.609455)]],
    },
    "T1014 宁静苑": {
        "fence": [(29.530657, 106.606709), (29.530609, 106.608438),
                  (29.529696, 106.608317), (29.529679, 106.606648),
                  (29.530394, 106.606416)],
        "exclude": [],
    },
}


def perimeter(poly):
    return sum(hav(poly[i][0], poly[i][1], poly[(i + 1) % len(poly)][0],
                   poly[(i + 1) % len(poly)][1]) for i in range(len(poly)))


def main():
    header = (f"{'毛刺':<4}{'场地':<20}{'围栏周长':>9}{'目标配速':>10}{'点数':>6}"
              f"{'实测距离':>10}{'实测配速':>10}{'围栏内':>8}{'禁跑区':>7}{'突起':>7}")
    print(header)
    print("-" * 98)
    for label, burrs in (("关", False), ("开", True)):
        for name, d in FIELDS.items():
            fence = dedupe(d["fence"])
            exc = [dedupe(e) for e in d["exclude"]]
            per = perimeter(fence)
            for pace in (240.0, 330.0, 345.0, 420.0):   # 4'00" / 5'30" / 5'45" / 7'00"
                pts, fence2, real, window, _ = generate(
                    fence, exc, pace, 2000.0, seed=20261013, burrs=burrs)
                dist = measure(pts)
                inside = sum(1 for p in pts if point_in_poly(p["lat"], p["lon"], fence2))
                banned = sum(1 for p in pts
                             if any(point_in_poly(p["lat"], p["lon"], e) for e in exc))
                steps = [p["step"] for p in pts]
                # 4'00" 的正常步长就有 4m 多，固定 5m 会把配速当突变；
                # 用「1.75 倍巡航步长，且不低于 5m」做阈值。
                spike_thr = max(5.0, 1.75 * 1000.0 / pace)
                spikes = sum(1 for x in steps if x > spike_thr)
                print(f"{label:<4}{name:<20}{per:>9.0f}{fmt_pace(pace):>10}{len(pts):>6}"
                      f"{dist:>10.1f}{fmt_pace(real):>10}"
                      f"{inside / len(pts):>8.1%}{banned:>7}{spikes:>7}")
            print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
