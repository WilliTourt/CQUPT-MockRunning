#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用服务器返回的真实多边形 + 真实 jpq 禁跑区，验证轨迹生成。"""
import sys, math, random
sys.path.insert(0, HERE)
from verify_trackgen import hav, bearing, offset, point_in_poly, inset, organic, build
import os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# ---- 服务器真实返回（/area/getAllArea）----
FIELDS = {
    "T1001 风华运动场": {
        "fence": [(29.532692,106.608105),(29.532871,106.608176),(29.533363,106.608104),
                  (29.533592,106.608107),(29.533707,106.607374),(29.533713,106.607166),
                  (29.533627,106.60702),(29.533197,106.607033),(29.532749,106.607017),
                  (29.532289,106.607043),(29.532065,106.607032),(29.531956,106.607445),
                  (29.531947,106.607814),(29.531952,106.607986),(29.532145,106.608111),
                  (29.532466,106.608101),(29.532466,106.608101),(29.532692,106.608105)],
        "exclude": [[(29.532769,106.607824),(29.533107,106.607843),(29.533293,106.607835),
                     (29.533417,106.607734),(29.533403,106.607527),(29.533345,106.607373),
                     (29.533219,106.607323),(29.533012,106.607303),(29.53265,106.60733),
                     (29.532462,106.60738),(29.532362,106.607479),(29.532323,106.607624),
                     (29.532323,106.607624),(29.532385,106.607769),(29.532528,106.607824),
                     (29.532717,106.607841),(29.532769,106.607824)]],
    },
    "T1005 太极运动场": {
        "fence": [(29.533933,106.608884),(29.531893,106.60897),(29.532019,106.610646),
                  (29.533979,106.610558),(29.533933,106.608884)],
        "exclude": [[(29.533214,106.609447),(29.533071,106.609473),(29.532917,106.609468),
                     (29.532626,106.609486),(29.532506,106.609612),(29.532533,106.610029),
                     (29.532691,106.610093),(29.533146,106.61006),(29.533421,106.610058),
                     (29.533549,106.60969),(29.533457,106.609455),(29.533214,106.609447)]],
    },
    "T1014 宁静苑": {
        "fence": [(29.530657,106.606709),(29.530609,106.608438),(29.529696,106.608317),
                  (29.529679,106.606648),(29.530394,106.606416),(29.530657,106.606709)],
        "exclude": [],
    },
}

def dedupe(poly):
    out = []
    for p in poly:
        if not out or out[-1] != p:
            out.append(p)
    if len(out) > 1 and out[0] == out[-1]:
        out.pop()
    return out

def perimeter(poly):
    return sum(hav(poly[i][0],poly[i][1],poly[(i+1)%len(poly)][0],poly[(i+1)%len(poly)][1])
               for i in range(len(poly)))

def generate(fence, excludes, pace_sec_km, target_m, noise=0.6, seed=0):
    fence = dedupe(fence)
    nom = 1000.0/pace_sec_km
    n = max(2, round(target_m/nom))
    best, best_score, used_f = [], -1.0, None
    for f in (0.90, 0.85, 0.80, 0.74, 0.66, 0.58, 0.50):
        ring = organic(inset(fence, f), 3.0, seed)
        pts = build(ring, n, nom, target_m, noise, seed)
        inside = sum(1 for p in pts if point_in_poly(p['lat'], p['lon'], fence))
        banned = sum(1 for p in pts
                     if any(point_in_poly(p['lat'], p['lon'], e) for e in excludes))
        score = (inside - banned) / len(pts)
        if score > best_score:
            best_score, best, used_f = score, pts, f
        if banned == 0 and inside/len(pts) >= 0.995:
            break
    return best, best_score, used_f

print(f"{'场地':<20}{'围栏周长':>9}{'目标配速':>10}{'点数':>6}{'实测距离':>10}{'实测配速':>10}{'围栏内':>8}{'禁跑区':>7}{'缩放':>6}")
print("-" * 92)
for name, d in FIELDS.items():
    fence, exc = d["fence"], d["exclude"]
    per = perimeter(dedupe(fence))
    for pace in (240.0, 330.0, 345.0, 420.0):   # 4'00" / 5'30" / 5'45" / 7'00"
        pts, score, f = generate(fence, exc, pace, 2000.0, seed=20261013)
        dist = sum(hav(pts[i]['lat'],pts[i]['lon'],pts[i+1]['lat'],pts[i+1]['lon'])
                   for i in range(len(pts)-1))
        dur = len(pts)-1
        real = dur/(dist/1000.0)
        m, s = divmod(int(real), 60)
        tm, ts = divmod(int(pace), 60)
        inside = sum(1 for p in pts if point_in_poly(p['lat'], p['lon'], fence))/len(pts)
        banned = sum(1 for p in pts if any(point_in_poly(p['lat'], p['lon'], e) for e in exc))
        print(f"{name:<20}{per:>9.0f}{tm:>7}'{ts:02d}\"{len(pts):>6}{dist:>10.1f}{m:>7}'{s:02d}\"{inside:>8.1%}{banned:>7}{f:>6.2f}")
    print()
