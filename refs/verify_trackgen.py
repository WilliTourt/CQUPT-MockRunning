#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_trackgen.py —— 把 android-app 里 TrackGen.kt 的逻辑 1:1 回写成 Python 验证。

因为有 seed 的 PRNG 实现不同（Kotlin XorWow vs Python Mersenne），噪声的具体取值
不会一致，但统计行为和数值流程完全等价。所以这个验证对 Kotlin 端是有效的。
"""

import math
import random

R = 6371008.8


# ---------------------------------------------------------------- Geo（对应 Geo object）

def hav(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(min(1.0, math.sqrt(a)))


def bearing(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(x, y)) + 360.0) % 360.0


def offset(lat, lon, dist, brg_deg):
    p1, l1 = math.radians(lat), math.radians(lon)
    brg = math.radians(brg_deg)
    d = dist / R
    p2 = math.asin(math.sin(p1) * math.cos(d) + math.cos(p1) * math.sin(d) * math.cos(brg))
    l2 = l1 + math.atan2(math.sin(brg) * math.sin(d) * math.cos(p1), math.cos(d) - math.sin(p1) * math.sin(p2))
    return [math.degrees(p2), math.degrees(l2)]


def point_in_poly(lat, lon, poly):
    n = len(poly)
    if n < 3:
        return False
    inside = False
    for i in range(n):
        a = poly[i]
        b = poly[0] if i == n - 1 else poly[i + 1]
        la, lo = a[0], a[1]
        lb, lob = b[0], b[1]
        if (la <= lat < lb) or (lb <= lat < la):
            if abs(la - lb) > 0:
                if lo - (lo - lob) * (la - lat) / (la - lb) < lon:
                    inside = not inside
    return inside


# ---------------------------------------------------------------- TrackGen

def inset(poly, f):
    c_lat = sum(p[0] for p in poly) / len(poly)
    c_lon = sum(p[1] for p in poly) / len(poly)
    return [[c_lat + (p[0] - c_lat) * f, c_lon + (p[1] - c_lon) * f] for p in poly]


def organic(raw, amp, seed):
    """对应 TrackGen.organic：加密到 ~12m → 整数圈正弦扰动 → 循环滑动平均。"""
    dense = []
    pts = raw + [raw[0]]
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        d = hav(a[0], a[1], b[0], b[1])
        k = max(1, math.ceil(d / 12.0))
        for j in range(k):
            t = j / k
            dense.append([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t])
    n = len(dense)
    if n < 8:
        return raw

    rnd = random.Random(seed)
    p1, p2 = rnd.uniform(0, 2 * math.pi), rnd.uniform(0, 2 * math.pi)
    a1, a2 = amp * rnd.uniform(0.5, 1.0), amp * rnd.uniform(0.3, 0.7)

    cur = []
    for i in range(n):
        prev, nxt = dense[(i - 1) % n], dense[(i + 1) % n]
        hdg = bearing(prev[0], prev[1], nxt[0], nxt[1])
        u = i / n * 2 * math.pi
        off = a1 * math.sin(2 * u + p1) + a2 * math.sin(3 * u + p2)
        cur.append(offset(dense[i][0], dense[i][1], off, (hdg + 90.0) % 360.0))

    for _ in range(2):
        w = 2
        sm = []
        for i in range(n):
            sla = sum(cur[(i + k) % n][0] for k in range(-w, w + 1)) / (2 * w + 1)
            slo = sum(cur[(i + k) % n][1] for k in range(-w, w + 1)) / (2 * w + 1)
            sm.append([sla, slo])
        cur = sm
    return cur


def densify(ring, step_m=1.0):
    pts = ring + [ring[0]]
    out = []
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        d = hav(a[0], a[1], b[0], b[1])
        k = max(1, math.ceil(d / step_m))
        for j in range(k):
            t = j / k
            out.append([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t])
    cum = [0.0]
    for i in range(len(out)):
        a = out[i]
        b = out[0] if i == len(out) - 1 else out[i + 1]
        cum.append(cum[-1] + hav(a[0], a[1], b[0], b[1]))
    return out, cum


def point_at(dense, cum, dist):
    total = cum[-1]
    if total <= 0:
        return dense[0], 0.0
    d = ((dist % total) + total) % total
    lo, hi = 0, len(dense) - 1
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if cum[mid] <= d:
            lo = mid
        else:
            hi = mid - 1
    i = min(lo, len(dense) - 2)
    seg = cum[i + 1] - cum[i]
    t = 0.0 if seg <= 0 else (d - cum[i]) / seg
    a, b = dense[i], dense[i + 1]
    p = [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]
    return p, bearing(a[0], a[1], b[0], b[1])


def build(ring, n, nom_speed, target_m, noise, seed):
    dense, cum = densify(ring, 1.0)

    def emit(step):
        rnd = random.Random(seed)
        perr = aerr = 0.0
        sm = nom_speed
        out = []
        for i in range(n):
            p, hdg = point_at(dense, cum, i * step)
            perr = 0.9 * perr + rnd.uniform(-1, 1) * noise * 0.55
            aerr = 0.9 * aerr + rnd.uniform(-1, 1) * noise * 0.35
            o = offset(p[0], p[1], perr, (hdg + 90.0) % 360.0)
            o = offset(o[0], o[1], aerr, hdg)
            sm = 0.75 * sm + 0.25 * (nom_speed * (1.0 + rnd.uniform(-0.03, 0.03)))
            out.append({
                "lat": o[0], "lon": o[1], "sec": i,
                "speed": round(sm, 2),
                "bearing": round((hdg + rnd.uniform(-1.6, 1.6) + 360.0) % 360.0, 1),
                "accuracy": round(max(4.0, 5.0 + abs(perr) * 0.5 + rnd.uniform(-1.0, 2.0)), 1),
            })
        return out

    def measure(pts):
        return sum(hav(pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"])
                   for i in range(len(pts) - 1))

    if measure(emit(0.0)) >= target_m:
        return emit(0.0)
    lo, hi = 0.0, nom_speed * 2.0
    guard = 0
    while measure(emit(hi)) < target_m and hi < 1e5 and guard < 40:
        hi *= 1.5
        guard += 1
    for _ in range(48):
        mid = (lo + hi) / 2.0
        if measure(emit(mid)) < target_m:
            lo = mid
        else:
            hi = mid
    return emit((lo + hi) / 2.0)


def generate(poly, pace_sec_km, target_m, noise=0.6, seed=0):
    nom = 1000.0 / pace_sec_km
    n = max(2, round(target_m / nom))
    best, best_ratio = [], -1.0
    used_f = None
    for f in (0.90, 0.82, 0.74, 0.66, 0.58, 0.50):
        ring = organic(inset(poly, f), 3.0, seed)
        pts = build(ring, n, nom, target_m, noise, seed)
        ratio = sum(1 for p in pts if point_in_poly(p["lat"], p["lon"], poly)) / len(pts)
        if ratio > best_ratio:
            best_ratio, best, used_f = ratio, pts, f
        if ratio >= 0.995:
            break
    return best, best_ratio, used_f


# ---------------------------------------------------------------- 测试

# 用小程序 testPoints 里出现过的坐标量级，构造一个 ~110m x 100m 的操场多边形
POLY = [
    [29.533524848090277, 106.60718777126736],
    [29.533416883680555, 106.60806667751736],
    [29.532716742621528, 106.60790879991319],
    [29.532604437934026, 106.60711588541666],
]

print("区域多边形 %d 顶点" % len(POLY))
per = 0.0
for i in range(len(POLY)):
    a, b = POLY[i], POLY[(i + 1) % len(POLY)]
    per += hav(a[0], a[1], b[0], b[1])
print("  周长 %.1f m  (一圈不够，需要绕多圈)\n" % per)

print(f"{'目标配速':<10}{'点数':>6}{'实测距离':>11}{'实测配速':>11}{'区域内占比':>11}{'缩放系数':>10}")
print("-" * 62)

for pace in (210.0, 330.0, 465.0, 600.0, 840.0):
    pts, ratio, f = generate(POLY, pace, 2000.0, seed=12345)
    d = sum(hav(pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"])
            for i in range(len(pts) - 1))
    dur = len(pts) - 1
    real = dur / (d / 1000.0)
    m, s = divmod(int(real), 60)
    tm, ts = divmod(int(pace), 60)
    print(f"{tm}'{ts:02d}\"     {len(pts):>6}{d:>11.1f}{m}'{s:02d}\"      {ratio:>9.1%}{f:>10.2f}")

print()
print("=== 分批逻辑（每批 8 点，末点作下批首点）===")
pts, _, _ = generate(POLY, 330.0, 2000.0)
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
    print(f"  批{i+1}: sec {batches[i][0]['sec']} → {batches[i][-1]['sec']}  ({len(batches[i])} 点)")
ok = all(batches[i][-1]["sec"] == batches[i + 1][0]["sec"] for i in range(len(batches) - 1))
print("  末点=下批首点:", ok)

print()
print("=== 轨迹形态 ===")
pts, _, _ = generate(POLY, 330.0, 2000.0, seed=999)
br = sorted(set(round(p["bearing"]) for p in pts))
print(f"  不同航向取值: {len(br)} 个")
print(f"  精度字段: {min(p['accuracy'] for p in pts)} ~ {max(p['accuracy'] for p in pts)} m")
sp = [p["speed"] for p in pts]
print(f"  报告速度: {min(sp):.2f} ~ {max(sp):.2f} m/s (目标 {1000/330:.2f})")
ds = [hav(pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"]) for i in range(len(pts) - 1)]
print(f"  逐点间距: {min(ds):.2f} ~ {max(ds):.2f} m")
print(f"  首点 {pts[0]['lat']:.7f},{pts[0]['lon']:.7f}")
print(f"  末点 {pts[-1]['lat']:.7f},{pts[-1]['lon']:.7f}")
