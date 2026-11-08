#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CampusRun 轨迹引擎  (campusrun/track_engine.py)

按指定配速生成"拟真"GPS 轨迹，供上位注入层消费：

    iOS  : pymobiledevice3 simulate-location play track.gpx
    Android: LSPosed 模块读取 track.json 进程内注入
    API  : replay.py 直接把轨迹 POST 给后端

核心特性
    * 严格配速控制（米/秒 与 分'秒"/km 双向换算）
    * 路径按弧长重采样，逐秒出点，点间距 == 配速 × 1s
    * 相关噪声模型 AR(1)：模拟真实 GPS 的低频漂移，而不是白噪声抖动
    * 可选毛刺/突起（--burrs）：默认关闭，和 App 的「轨迹毛刺」开关一致
    * 速度/航向平滑，避免出现瞬时跳变被"合理性算法"抓到
    * WGS84 <-> GCJ02 双向转换（微信 wx.getLocation 默认 gcj02）
    * 输出 JSON（注入层用）与 GPX（iOS 回放用）

用法
    python track_engine.py list
    python track_engine.py gen --route A --pace 5:30 --dist 2.0 --out track.json --gpx track.gpx
"""

from __future__ import annotations

import argparse
import bisect
import json
import math
import random
import sys
from dataclasses import dataclass, asdict
from datetime import datetime, timedelta, timezone

# ---------------------------------------------------------------- 常量

R_EARTH = 6371008.8          # 平均地球半径 (m)
A_WGS = 6378245.0            # 克拉索夫斯基椭球长半轴（GCJ02 变换用）
EE_WGS = 0.00669342162296594323

DEFAULT_ALTITUDE = 260.0     # 重庆主城海拔量级，占位


# ---------------------------------------------------------------- 基础几何

def haversine(a: tuple[float, float], b: tuple[float, float]) -> float:
    """两 WGS84 点球面距离 (m)。"""
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    h = (math.sin((lat2 - lat1) / 2) ** 2
         + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2)
    return 2 * R_EARTH * math.asin(min(1.0, math.sqrt(h)))


def bearing(a: tuple[float, float], b: tuple[float, float]) -> float:
    """a -> b 的初始方位角 (0-360)。"""
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    dlon = lon2 - lon1
    x = math.sin(dlon) * math.cos(lat2)
    y = (math.cos(lat1) * math.sin(lat2)
         - math.sin(lat1) * math.cos(lat2) * math.cos(dlon))
    return (math.degrees(math.atan2(x, y)) + 360.0) % 360.0


def offset_point(p: tuple[float, float], dist_m: float, brg_deg: float) -> tuple[float, float]:
    """从 p 沿 brg_deg 方向走 dist_m 米（大圆推算）。"""
    lat1, lon1 = math.radians(p[0]), math.radians(p[1])
    brg = math.radians(brg_deg)
    d = dist_m / R_EARTH
    lat2 = math.asin(math.sin(lat1) * math.cos(d)
                     + math.cos(lat1) * math.sin(d) * math.cos(brg))
    lon2 = lon1 + math.atan2(math.sin(brg) * math.sin(d) * math.cos(lat1),
                             math.cos(d) - math.sin(lat1) * math.sin(lat2))
    return (math.degrees(lat2), math.degrees(lon2))


def lerp(a: tuple[float, float], b: tuple[float, float], t: float) -> tuple[float, float]:
    """短距离线性插值，够用且快。"""
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


# ---------------------------------------------------------------- 坐标系

def _tf_lat(x: float, y: float) -> float:
    ret = (-100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
           + 0.2 * math.sqrt(abs(x)))
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (160.0 * math.sin(y / 12.0 * math.pi) + 320.0 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return ret


def _tf_lon(x: float, y: float) -> float:
    ret = (300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
           + 0.1 * math.sqrt(abs(x)))
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return ret


def _out_of_china(lat: float, lon: float) -> bool:
    return not (73.66 < lon < 135.05 and 3.86 < lat < 53.55)


def wgs84_to_gcj02(lat: float, lon: float) -> tuple[float, float]:
    if _out_of_china(lat, lon):
        return (lat, lon)
    dlat = _tf_lat(lon - 105.0, lat - 35.0)
    dlon = _tf_lon(lon - 105.0, lat - 35.0)
    radlat = lat / 180.0 * math.pi
    magic = math.sin(radlat)
    magic = 1 - EE_WGS * magic * magic
    sqrtmagic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((A_WGS * (1 - EE_WGS)) / (magic * sqrtmagic) * math.pi)
    dlon = (dlon * 180.0) / (A_WGS / sqrtmagic * math.cos(radlat) * math.pi)
    return (lat + dlat, lon + dlon)


def gcj02_to_wgs84(lat: float, lon: float) -> tuple[float, float]:
    """反解：迭代逼近，3 次足够到厘米级。"""
    wlat, wlon = lat, lon
    for _ in range(5):
        glat, glon = wgs84_to_gcj02(wlat, wlon)
        wlat += lat - glat
        wlon += lon - glon
    return (wlat, wlon)


def transform(pt: tuple[float, float], crs: str) -> tuple[float, float]:
    return wgs84_to_gcj02(*pt) if crs == "gcj02" else pt


# ---------------------------------------------------------------- 路线

def rect_loop(center: tuple[float, float], w_m: float, h_m: float,
              ccw: bool = False) -> list[tuple[float, float]]:
    """以 center 为中心生成一个矩形闭环（WGS84）。"""
    dlat = h_m / 2.0 / 110900.0
    dlon = w_m / 2.0 / (111320.0 * math.cos(math.radians(center[0])))
    lat, lon = center
    pts = [(lat + dlat, lon - dlon), (lat + dlat, lon + dlon),
           (lat - dlat, lon + dlon), (lat - dlat, lon - dlon)]
    if ccw:
        pts.reverse()
    return pts


def poly_loop(waypoints: list[tuple[float, float]]) -> list[tuple[float, float]]:
    return list(waypoints)


def _is_valid(lat: float, lon: float, fence, excludes) -> bool:
    if fence and not point_in_poly(lat, lon, fence):
        return False
    for e in excludes or ():
        if point_in_poly(lat, lon, e):
            return False
    return True


def _pull_toward(base, p, fence, excludes):
    """沿 base → p 二分回缩，只削扰动、不动基准环。"""
    if not _is_valid(base[0], base[1], fence, excludes):
        return p
    lo, hi = 0.0, 1.0
    for _ in range(22):
        mid = (lo + hi) / 2.0
        if _is_valid(base[0] + (p[0] - base[0]) * mid,
                     base[1] + (p[1] - base[1]) * mid, fence, excludes):
            lo = mid
        else:
            hi = mid
    t = max(0.0, min(1.0, lo * 0.99))
    return (base[0] + (p[0] - base[0]) * t, base[1] + (p[1] - base[1]) * t)


def organic_loop_smooth(waypoints: list[tuple[float, float]],
                       seed: int = 20240101,
                       seg_m: float = 15.0,
                       wobble_m: float = 4.0) -> list[tuple[float, float]]:
    """v1.10.1 的光滑环线：两条整数圈正弦 + 两轮磨角。"""
    dense = densify(waypoints, seg_m)[:-1]
    n = len(dense)
    if n < 8:
        return list(waypoints)

    rnd = random.Random(seed)
    k1, k2 = 2, 3
    p1, p2 = rnd.uniform(0, math.tau), rnd.uniform(0, math.tau)
    a1, a2 = wobble_m * rnd.uniform(0.5, 1.0), wobble_m * rnd.uniform(0.3, 0.7)

    perturbed = []
    for i, pt in enumerate(dense):
        hdg = bearing(dense[(i - 1) % n], dense[(i + 1) % n])
        u = i / n * math.tau
        off = a1 * math.sin(k1 * u + p1) + a2 * math.sin(k2 * u + p2)
        perturbed.append(offset_point(pt, off, (hdg + 90.0) % 360.0))

    for _ in range(2):
        w = 3
        perturbed = [
            (sum(perturbed[(i + k) % n][0] for k in range(-w, w + 1)) / (2 * w + 1),
             sum(perturbed[(i + k) % n][1] for k in range(-w, w + 1)) / (2 * w + 1))
            for i in range(n)
        ]
    return perturbed


def organic_loop(waypoints: list[tuple[float, float]],
                 seed: int = 20240101,
                 seg_m: float = 7.0,
                 wobble_m: float = 4.5,
                 fence=None,
                 excludes=()) -> list[tuple[float, float]]:
    """把规则折线变成"人跑出来"的闭合环线。

    和 App 里的 TrackGen.organic 同构：
      1. 六条整数圈正弦（k=2/3/5/7/11/17），整数圈保证闭合，高频项揉出小波浪
      2. 3~5 处局部鼓包（宽度 10~38m、幅度 ±2~6m），模拟贴内道 / 绕人 / 抄近道
      3. 一轮循环滑动平均，只磨掉最尖的折角
      4. 给了围栏时，把顶出去的点沿"基准点 → 扰动点"收回来
    """
    dense = densify(waypoints, seg_m)[:-1]        # 去掉重复的闭合点
    n = len(dense)
    if n < 16:
        return list(waypoints)

    per = 0.0
    for i in range(n):
        per += haversine(dense[i], dense[(i + 1) % n])
    step_m = max(1.0, per / n)

    s = 20240101 if seed is None else seed
    rnd = random.Random(s * 1000003 + 97)

    harmonic = (2, 3, 5, 7, 11, 17)
    weight = (0.46, 0.34, 0.24, 0.17, 0.12, 0.07)
    phase = [rnd.uniform(0, math.tau) for _ in harmonic]
    wave_amp = [wobble_m * weight[i] * rnd.uniform(0.55, 1.25)
                for i in range(len(harmonic))]

    bumps = []
    for _ in range(3 + rnd.randint(0, 2)):
        center = rnd.uniform(0.0, n)
        width_m = rnd.uniform(10.0, 38.0)
        strength = rnd.uniform(2.0, 6.2) * (1 if rnd.random() < 0.5 else -1)
        bumps.append((center, max(1.0, width_m / step_m), strength))

    perturbed = []
    for i, pt in enumerate(dense):
        hdg = bearing(dense[(i - 1) % n], dense[(i + 1) % n])
        u = i / n * math.tau
        off = sum(wave_amp[k] * math.sin(harmonic[k] * u + phase[k])
                  for k in range(len(harmonic)))
        for center, width, strength in bumps:
            di = abs(i - center)
            di = min(di, n - di)
            off += strength * math.exp(-0.5 * (di / width) ** 2)
        perturbed.append(offset_point(pt, off, (hdg + 90.0) % 360.0))

    # 只磨一遍、窗口 2：再磨就回到"光滑椭圆"了
    w = 2
    smoothed = []
    for i in range(n):
        smoothed.append((
            sum(perturbed[(i + k) % n][0] for k in range(-w, w + 1)) / (2 * w + 1),
            sum(perturbed[(i + k) % n][1] for k in range(-w, w + 1)) / (2 * w + 1),
        ))

    if fence:
        smoothed = [
            p if _is_valid(p[0], p[1], fence, excludes)
            else _pull_toward(dense[i], p, fence, excludes)
            for i, p in enumerate(smoothed)
        ]
    return smoothed


def _bad_points(poly, fence, excludes) -> int:
    bad = 0
    for i in range(len(poly)):
        a, b = poly[i], poly[(i + 1) % len(poly)]
        d = haversine(a, b)
        k = max(1, int(math.ceil(d / 7.0)))
        for j in range(k):
            t = j / k
            if not _is_valid(a[0] + (b[0] - a[0]) * t,
                             a[1] + (b[1] - a[1]) * t, fence, excludes):
                bad += 1
    return bad


def _choose_ring(fence, excludes, seed, wobble_m=4.5):
    """挑第一个完全合法的内缩环；全都非法时退回违例最少的一档。"""
    fallback, fallback_bad = None, 10 ** 9
    for f in (0.90, 0.88, 0.86, 0.84, 0.81, 0.77, 0.72, 0.66, 0.59, 0.51):
        raw = inset_poly(fence, f)
        bad = _bad_points(raw, fence, excludes)
        if bad < fallback_bad:
            fallback, fallback_bad = raw, bad
        if bad == 0:
            return organic_loop(raw, seed, 7.0, wobble_m, fence, excludes), f
    return organic_loop(fallback, seed, 7.0, wobble_m, fence, excludes), 0.0


NOISE_RHO = (0.997, 0.970, 0.850, 0.550)
NOISE_SIGMA = (0.50, 0.27, 0.27, 0.33)


def _make_spurs(n: int, seed: int, intensity: float = 1.0):
    """对应 TrackGen.makeSpurs：大跳 / 绕行包 / 硬毛刺 / 小毛刺四档。"""
    lateral = [0.0] * n
    forward = [0.0] * n
    if n < 80:
        return lateral, forward

    rnd = random.Random(seed * 1000003 + 77)
    used: list[int] = []

    def free(at: int, gap: int) -> bool:
        return all(abs(at - u) >= gap for u in used)

    lo = max(2, int(n * 0.04))
    hi = min(n - 4, int(n * 0.96))

    for _ in range(max(1, n // 450)):
        for _try in range(60):
            at = rnd.randrange(lo, hi)
            if not free(at, 14):
                continue
            used.append(at)
            a = rnd.uniform(9.0, 16.0) * intensity * (1 if rnd.random() < 0.5 else -1)
            lateral[at] = a
            forward[at] = a * rnd.uniform(-0.25, 0.25)
            break

    for _ in range(max(2, n // 230)):
        for _try in range(80):
            at = rnd.randrange(lo, hi)
            span = rnd.randint(4, 9)
            if at + span >= n or not free(at, span + 10):
                continue
            used.append(at)
            a = rnd.uniform(5.0, 11.0) * intensity * (1 if rnd.random() < 0.5 else -1)
            share = rnd.uniform(-0.25, 0.25)
            for k in range(span + 1):
                sv = k / span
                lateral[at + k] = a * math.sin(math.pi * sv)
                forward[at + k] = a * share * math.sin(math.tau * sv)
            break

    for _ in range(max(4, n // 75)):
        for _try in range(80):
            at = rnd.randrange(lo, hi)
            if not free(at, 8):
                continue
            used.append(at)
            a = rnd.uniform(3.0, 8.0) * intensity
            sign = 1.0 if rnd.random() < 0.5 else -1.0
            share = rnd.uniform(-0.35, 0.35)
            shape = rnd.random()
            if shape < 0.45:
                lateral[at] = a * sign
                forward[at] = a * share
            elif shape < 0.72:
                lateral[at] = a * sign
                forward[at] = a * share
                lateral[at + 1] = (-a * 0.55 * sign if rnd.random() < 0.5
                                   else a * 0.3 * sign)
            else:
                span = rnd.randint(2, 4)
                peak = rnd.randint(1, span - 1)
                for k in range(span + 1):
                    denom = max(1.0, float(peak if k <= peak else span - peak))
                    frac = max(0.0, 1.0 - abs(k - peak) / denom)
                    lateral[at + k] = a * 1.2 * sign * frac
            break

    for _ in range(max(6, n // 17)):
        for _try in range(60):
            at = rnd.randrange(2, n - 2)
            if not free(at, 4):
                continue
            used.append(at)
            a = rnd.uniform(0.8, 2.6) * intensity * (1 if rnd.random() < 0.5 else -1)
            lateral[at] = a
            forward[at] = rnd.uniform(-0.5, 0.5) * a * 0.4
            break
    return lateral, forward


def _tolerate(base, q, fence, excludes):
    """对应 TrackGen.tolerate：沿 base → q 回缩，保住毛刺方向。"""
    if _is_valid(q[0], q[1], fence, excludes):
        return q
    if not _is_valid(base[0], base[1], fence, excludes):
        return q
    lo, hi = 0.0, 1.0
    for _ in range(22):
        mid = (lo + hi) / 2.0
        if _is_valid(base[0] + (q[0] - base[0]) * mid,
                     base[1] + (q[1] - base[1]) * mid, fence, excludes):
            lo = mid
        else:
            hi = mid
    t = max(0.0, min(1.0, lo * 0.99))
    return (base[0] + (q[0] - base[0]) * t, base[1] + (q[1] - base[1]) * t)


# 三个跑步区域 —— 坐标为占位值，替换成学校实际允许区域内的闭环即可
ROUTES: dict[str, dict] = {
    "A": {
        "name": "区域A-田径场外环",
        "waypoints": rect_loop((29.53820, 106.60530), 190.0, 130.0),
    },
    "B": {
        "name": "区域B-教学楼环线",
        "waypoints": rect_loop((29.53725, 106.60700), 260.0, 180.0, ccw=True),
    },
    "C": {
        "name": "区域C-图书馆广场",
        "waypoints": rect_loop((29.53950, 106.60620), 160.0, 220.0),
    },
}


# ---------------------------------------------------------------- 路径重采样

def densify(waypoints: list[tuple[float, float]], step_m: float = 0.5) -> list[tuple[float, float]]:
    """把折线按 ~step_m 弧长加密，首尾闭合。"""
    pts = list(waypoints) + [waypoints[0]]
    dense = [pts[0]]
    for a, b in zip(pts, pts[1:]):
        d = haversine(a, b)
        n = max(1, int(math.ceil(d / step_m)))
        for i in range(1, n + 1):
            dense.append(lerp(a, b, i / n))
    return dense


def cumulative(dense: list[tuple[float, float]]) -> list[float]:
    cum = [0.0]
    for a, b in zip(dense, dense[1:]):
        cum.append(cum[-1] + haversine(a, b))
    return cum


def point_at(dense: list[tuple[float, float]], cum: list[float],
             dist: float) -> tuple[tuple[float, float], float]:
    """按累计弧长定位点，返回 (点, 该处航向)。环形绕圈，超出自动取模。"""
    total = cum[-1]
    d = dist % total
    i = bisect.bisect_right(cum, d) - 1
    i = max(0, min(i, len(dense) - 2))
    seg = cum[i + 1] - cum[i]
    t = 0.0 if seg <= 0 else (d - cum[i]) / seg
    p = lerp(dense[i], dense[i + 1], t)
    return p, bearing(dense[i], dense[i + 1])


# ---------------------------------------------------------------- 真实多边形

def dedupe(poly: list) -> list:
    """服务器下发的多边形经常有连续重复点，去掉。"""
    out = []
    for p in poly:
        if not out or out[-1] != tuple(p):
            out.append(tuple(p))
    if len(out) > 1 and out[0] == out[-1]:
        out.pop()
    return out


def point_in_poly(lat: float, lon: float, poly) -> bool:
    """与小程序 IsPtInPoly 等价的射线法。"""
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


def inset_poly(poly, f: float):
    """每个顶点朝质心方向按比例靠拢，把环线缩进围栏内部。"""
    clat = sum(p[0] for p in poly) / len(poly)
    clon = sum(p[1] for p in poly) / len(poly)
    return [(clat + (p[0] - clat) * f, clon + (p[1] - clon) * f) for p in poly]


def build_track_poly(fence, excludes, pace_s_per_km, target_m,
                     sample_dt=1.0, noise_sigma=0.6, seed=20261013,
                     altitude=DEFAULT_ALTITUDE, start=None, burrs=False):
    """基于服务器下发的真实电子围栏 + 禁跑区生成轨迹。

    fence    : [[lat, lon], ...]   dzwl 电子围栏
    excludes : [[[lat, lon], ...]] jpq 禁跑区，轨迹要避开
    burrs    : 是否叠加毛刺 / 突起；False = v1.10.1 光滑轨迹（和 App 默认一致）
    返回 (points, stats)
    """
    if not burrs:
        return build_track_poly_smooth(
            fence, excludes, pace_s_per_km, target_m, sample_dt, noise_sigma,
            seed, altitude, start)

    fence = dedupe(fence)
    excludes = [dedupe(e) for e in (excludes or []) if len(e) >= 3]
    if start is None:
        start = datetime.now(timezone.utc)

    nom = 1000.0 / pace_s_per_km
    n = max(2, int(round(target_m / (nom * sample_dt))))
    ring, inset_f = _choose_ring(fence, excludes, seed)
    dense = densify(ring, 1.0)
    cum = cumulative(dense)
    spur_lat, spur_fwd = _make_spurs(n, seed, min(2.5, max(0.0, noise_sigma / 0.6)))

    def emit(step):
        rnd = random.Random(seed)
        rnd_acc = random.Random(seed * 31 + 7)
        perr = [0.0] * len(NOISE_RHO)
        aerr = [0.0] * len(NOISE_RHO)
        latl = [0.0] * n
        lonl = [0.0] * n
        pew = [0.0] * n
        for i in range(n):
            p, hdg = point_at(dense, cum, i * step * sample_dt)
            perr_t, aerr_t = spur_lat[i], spur_fwd[i]
            for c in range(len(NOISE_RHO)):
                perr[c] = (NOISE_RHO[c] * perr[c]
                           + rnd.gauss(0.0, 1.0) * noise_sigma * NOISE_SIGMA[c])
                aerr[c] = (NOISE_RHO[c] * aerr[c]
                           + rnd.gauss(0.0, 1.0) * noise_sigma * NOISE_SIGMA[c])
                perr_t += perr[c]
                aerr_t += aerr[c] * 0.45
            o1 = offset_point(p, perr_t, (hdg + 90.0) % 360.0)
            q = offset_point(o1, aerr_t, hdg)
            latl[i], lonl[i] = _tolerate(p, q, fence, excludes)
            pew[i] = perr_t

        out = []
        for i in range(n):
            j = i + 1 if i < n - 1 else i - 1
            seg = haversine((latl[i], lonl[i]), (latl[j], lonl[j]))
            brg = (bearing((latl[i], lonl[i]), (latl[j], lonl[j]))
                   if i < n - 1 else bearing((latl[j], lonl[j]), (latl[i], lonl[i])))
            acc = 4.5 + abs(pew[i]) * 0.30 + rnd_acc.uniform(-1.0, 2.2)
            out.append({
                "t": (start + timedelta(seconds=i * sample_dt)).timestamp(),
                "lat": latl[i], "lon": lonl[i],
                "speed": round(seg / sample_dt, 2),
                "bearing": round(brg, 1),
                "accuracy": round(max(3.0, min(18.0, acc)), 1),
                "altitude": round(altitude + rnd.uniform(-2.0, 2.0), 1),
            })
        return out

    def measure(pts):
        return sum(haversine((a["lat"], a["lon"]), (b["lat"], b["lon"]))
                   for a, b in zip(pts, pts[1:]))

    if measure(emit(0.0)) < target_m:
        lo, hi = 0.0, nom
        for _ in range(24):
            if measure(emit(hi)) >= target_m:
                break
            hi *= 1.5
        for _ in range(48):
            mid = (lo + hi) / 2
            if measure(emit(mid)) < target_m:
                lo = mid
            else:
                hi = mid
        pts = emit((lo + hi) / 2)
    else:
        pts = emit(0.0)

    stats = {
        "n": len(pts),
        "distance": round(measure(pts), 1),
        "inside": sum(1 for p in pts if point_in_poly(p["lat"], p["lon"], fence)),
        "banned": sum(1 for p in pts
                      if any(point_in_poly(p["lat"], p["lon"], e) for e in excludes)),
        "inset": inset_f,
        "duration": int(round((len(pts) - 1) * sample_dt)),
    }
    return pts, stats


def build_track_poly_smooth(fence, excludes, pace_s_per_km, target_m,
                            sample_dt=1.0, noise_sigma=0.6, seed=20261013,
                            altitude=DEFAULT_ALTITUDE, start=None):
    """v1.10.1 的光滑轨迹：单个 AR(1) 噪声，逐档挑内缩系数。

    和 App 里「轨迹毛刺」关闭时的 TrackGen.generateSmooth 对应。
    """
    fence = dedupe(fence)
    excludes = [dedupe(e) for e in (excludes or []) if len(e) >= 3]
    if start is None:
        start = datetime.now(timezone.utc)

    nom = 1000.0 / pace_s_per_km
    n = max(2, int(round(target_m / (nom * sample_dt))))

    def emit(step):
        rnd = random.Random(seed)
        perr = aerr = 0.0
        sm = nom
        out = []
        for i in range(n):
            p, hdg = point_at(dense, cum, i * step)
            perr = 0.9 * perr + rnd.gauss(0, 1) * noise_sigma * 0.55
            aerr = 0.9 * aerr + rnd.gauss(0, 1) * noise_sigma * 0.35
            lat, lon = offset_point(p, perr, (hdg + 90.0) % 360.0)
            lat, lon = offset_point((lat, lon), aerr, hdg)
            sm = 0.75 * sm + 0.25 * (nom * (1 + rnd.uniform(-0.03, 0.03)))
            out.append({
                "t": (start + timedelta(seconds=i * sample_dt)).timestamp(),
                "lat": lat, "lon": lon,
                "speed": round(sm, 2),
                "bearing": round((hdg + rnd.gauss(0, 1.6)) % 360.0, 1),
                "accuracy": round(max(4.0, 5.0 + abs(perr) * 0.5
                                      + rnd.uniform(-1, 2)), 1),
                "altitude": round(altitude + rnd.uniform(-2, 2), 1),
            })
        return out

    def measure(pts):
        return sum(haversine((a["lat"], a["lon"]), (b["lat"], b["lon"]))
                   for a, b in zip(pts, pts[1:]))

    best, best_score, best_f = None, -1.0, None
    for f in (0.90, 0.85, 0.80, 0.74, 0.66, 0.58, 0.50):
        ring = inset_poly(fence, f)
        ring_len = sum(haversine(ring[i], ring[(i + 1) % len(ring)])
                       for i in range(len(ring)))
        dense = densify(organic_loop_smooth(
            ring, seed=seed, seg_m=max(6.0, ring_len / 64.0), wobble_m=3.0), 1.0)
        cum = cumulative(dense)

        pts = emit(nom * sample_dt)
        if measure(emit(0.0)) < target_m:
            lo, hi = 0.0, nom * sample_dt
            for _ in range(24):
                if measure(emit(hi)) >= target_m:
                    break
                hi *= 1.5
            for _ in range(48):
                mid = (lo + hi) / 2
                if measure(emit(mid)) < target_m:
                    lo = mid
                else:
                    hi = mid
            pts = emit((lo + hi) / 2)

        inside = sum(1 for p in pts if point_in_poly(p["lat"], p["lon"], fence))
        banned = sum(1 for p in pts
                     if any(point_in_poly(p["lat"], p["lon"], e) for e in excludes))
        score = (inside - banned) / len(pts)
        if score > best_score:
            best_score, best, best_f = score, pts, f
        if banned == 0 and inside / len(pts) >= 0.995:
            break

    stats = {
        "n": len(best),
        "distance": round(measure(best), 1),
        "inside": sum(1 for p in best if point_in_poly(p["lat"], p["lon"], fence)),
        "banned": sum(1 for p in best
                      if any(point_in_poly(p["lat"], p["lon"], e) for e in excludes)),
        "inset": best_f,
        "duration": int(round((len(best) - 1) * sample_dt)),
    }
    return best, stats


# ---------------------------------------------------------------- 轨迹生成

@dataclass
class TrackPoint:
    t: float          # Unix 时间戳 (s)
    lat: float
    lon: float
    speed: float      # m/s
    bearing: float    # deg
    accuracy: float   # m
    altitude: float   # m


def parse_pace(v: str) -> float:
    """'5:30' -> 330 秒/公里；'5.5' -> 330；'330' -> 330。"""
    v = v.strip()
    if ":" in v:
        m, s = v.split(":")
        return float(m) * 60 + float(s)
    f = float(v)
    return f if f > 60 else f * 60


def build_track(route: str,
                pace_s_per_km: float,
                target_m: float,
                start: datetime,
                sample_dt: float = 1.0,
                noise_sigma: float = 0.6,
                drift_rho: float = 0.90,
                crs: str = "gcj02",
                seed: int | None = 20240101,
                altitude: float = DEFAULT_ALTITUDE,
                burrs: bool = False) -> list[TrackPoint]:
    """生成一条轨迹（静态区域，主要用于 iOS / GPX 回放与 CLI 自测）。

    burrs=False（默认）：v1.10.1 的光滑轨迹；True：多尺度噪声 + 毛刺 / 突起。
    """
    # seed=None 时只摇一次，后面所有噪声共用它；否则每次 emit 的噪声都不同，
    # 二分的 measure(step) 就不确定了。
    s = seed if seed is not None else random.randrange(1, 1 << 30)

    # 有机化：把规则折线磨成"人跑出来"的环线，采样点够密才磨得动
    _wp = ROUTES[route]["waypoints"]
    if burrs:
        wp = organic_loop(_wp, seed=s, seg_m=7.0, wobble_m=4.5)
    else:
        _len = sum(haversine(a, b) for a, b in zip(_wp + [_wp[0]], _wp[1:] + [_wp[0]]))
        wp = organic_loop_smooth(_wp, seed=s, seg_m=max(6.0, _len / 64.0), wobble_m=4.0)

    dense = densify(wp, 1.0)
    cum = cumulative(dense)

    nom_speed = 1000.0 / pace_s_per_km             # m/s，理想地面速度

    # 采样点数由"配速 × 距离"锁死：时长固定，速度才是待求量
    n = max(2, int(round(target_m / (nom_speed * sample_dt))))
    spur_lat, spur_fwd = _make_spurs(
        n, s, min(2.5, max(0.0, noise_sigma / 0.6)))

    def emit(step_m: float) -> list[TrackPoint]:
        """固定点数 n，按给定地面步长铺点，再叠加多尺度噪声与野点。

        同一个 seed 每次都重现同一噪声实现，因此 measure 关于 step_m 近似单调，
        可以直接二分。
        """
        rnd = random.Random(s)
        rnd_acc = random.Random(s * 31 + 7)
        if burrs:
            perr = [0.0] * len(NOISE_RHO)
            aerr = [0.0] * len(NOISE_RHO)
        else:
            perr, aerr = [0.0], [0.0]
        lat = [0.0] * n
        lon = [0.0] * n
        pew = [0.0] * n

        for i in range(n):
            p, hdg = point_at(dense, cum, i * step_m)
            if burrs:
                perr_t, aerr_t = spur_lat[i], spur_fwd[i]
                for c in range(len(NOISE_RHO)):
                    perr[c] = (NOISE_RHO[c] * perr[c]
                               + rnd.gauss(0.0, 1.0) * noise_sigma * NOISE_SIGMA[c])
                    aerr[c] = (NOISE_RHO[c] * aerr[c]
                               + rnd.gauss(0.0, 1.0) * noise_sigma * NOISE_SIGMA[c])
                    perr_t += perr[c]
                    aerr_t += aerr[c] * 0.45
            else:
                perr[0] = (drift_rho * perr[0]
                           + rnd.gauss(0.0, 1.0) * noise_sigma * 0.55)
                aerr[0] = (drift_rho * aerr[0]
                           + rnd.gauss(0.0, 1.0) * noise_sigma * 0.35)
                perr_t, aerr_t = perr[0], aerr[0]
            o1 = offset_point(p, perr_t, (hdg + 90.0) % 360.0)
            o2 = offset_point(o1, aerr_t, hdg)
            lat[i], lon[i] = o2
            pew[i] = perr_t

        out: list[TrackPoint] = []
        for i in range(n):
            j = i + 1 if i < n - 1 else i - 1
            seg = haversine((lat[i], lon[i]), (lat[j], lon[j]))
            brg = (bearing((lat[i], lon[i]), (lat[j], lon[j])) if i < n - 1
                   else bearing((lat[j], lon[j]), (lat[i], lon[i])))
            acc = 4.5 + abs(pew[i]) * 0.30 + rnd_acc.uniform(-1.0, 2.2)
            t = start + timedelta(seconds=i * sample_dt)
            glat, glon = transform((lat[i], lon[i]), crs)
            out.append(TrackPoint(
                t=round(t.timestamp(), 3),
                lat=glat,
                lon=glon,
                speed=round(seg / sample_dt, 2),
                bearing=round(brg, 1),
                accuracy=round(max(3.0, min(18.0, acc)), 1),
                altitude=round(altitude + rnd.uniform(-2.0, 2.0), 1),
            ))
        return out

    def measure(pts: list[TrackPoint]) -> float:
        return sum(haversine((a.lat, a.lon), (b.lat, b.lon))
                   for a, b in zip(pts, pts[1:]))

    # 噪声按秒注入、与步长无关，会把"实测距离"撑长。
    # 反解地面步长，使输出轨迹的实测距离 == target_m，
    # 这样后端按点算出来的配速才恰好等于目标配速。
    pivot = measure(emit(0.0))
    if pivot >= target_m:
        print(f"[!] 警告：噪声下限已达 {pivot:.0f} m > 目标 {target_m:.0f} m，"
              f"配速无法精确标定。请调小 --noise 或缩短距离。", file=sys.stderr)
        return emit(0.0)

    lo, hi = 0.0, nom_speed * sample_dt
    for _ in range(24):
        if measure(emit(hi)) >= target_m:
            break
        hi *= 1.5

    for _ in range(40):
        mid = (lo + hi) / 2.0
        if measure(emit(mid)) < target_m:
            lo = mid
        else:
            hi = mid
    return emit((lo + hi) / 2.0)


# ---------------------------------------------------------------- 输出

def to_json(pts: list[TrackPoint], crs: str) -> str:
    return json.dumps({
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "crs": crs,
        "count": len(pts),
        "points": [asdict(p) for p in pts],
    }, ensure_ascii=False, indent=1)


def to_gpx(pts: list[TrackPoint]) -> str:
    head = ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<gpx version="1.1" creator="campusrun" '
            'xmlns="http://www.topografix.com/GPX/1/1">\n'
            ' <trk><name>campusrun</name><trkseg>\n')
    body = []
    for p in pts:
        ts = datetime.fromtimestamp(p.t, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        body.append(f'  <trkpt lat="{p.lat}" lon="{p.lon}">'
                    f'<ele>{p.altitude}</ele><time>{ts}</time></trkpt>')
    return head + "\n".join(body) + "\n </trkseg></trk>\n</gpx>\n"


def summarize(pts: list[TrackPoint], pace_s_per_km: float) -> str:
    total = 0.0
    for a, b in zip(pts, pts[1:]):
        total += haversine((a.lat, a.lon), (b.lat, b.lon))
    dur = pts[-1].t - pts[0].t
    real_pace = dur / (total / 1000.0) if total else 0
    m, s = divmod(int(real_pace), 60)
    return (f"点数 {len(pts)} | 距离 {total:.1f} m | 时长 {dur/60:.2f} min | "
            f"实测配速 {m}'{s:02d}\"/km (目标 {pace_s_per_km:.0f}s/km)")


# ---------------------------------------------------------------- CLI

def cmd_list(_args) -> int:
    for k, v in ROUTES.items():
        length = 0.0
        wp = v["waypoints"]
        for a, b in zip(wp + [wp[0]], wp[1:] + [wp[0]]):
            length += haversine(a, b)
        print(f"  {k}  {v['name']:<20} 单圈 {length:6.0f} m")
    return 0


def cmd_gen(args) -> int:
    if args.route not in ROUTES:
        print(f"未知区域 {args.route}，可选：{', '.join(ROUTES)}", file=sys.stderr)
        return 2

    pace = parse_pace(args.pace)
    start = (datetime.fromisoformat(args.since).astimezone(timezone.utc)
             if args.since else datetime.now(timezone.utc))

    pts = build_track(args.route, pace, args.dist * 1000.0, start,
                      sample_dt=args.dt, noise_sigma=args.noise,
                      crs=args.crs, seed=args.seed, burrs=args.burrs)
    print(f"[i] 轨迹模式：{'毛刺 / 突起' if args.burrs else '光滑（毛刺关闭）'}")

    for p in (args.out, args.gpx):
        if p:
            import os
            d = os.path.dirname(os.path.abspath(p))
            os.makedirs(d, exist_ok=True)

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(to_json(pts, args.crs))
        print(f"[+] JSON -> {args.out}")
    if args.gpx:
        with open(args.gpx, "w", encoding="utf-8") as f:
            f.write(to_gpx(pts))
        print(f"[+] GPX  -> {args.gpx}")

    print("[i]", summarize(pts, pace))
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="track_engine",
                                 description="CampusRun 轨迹引擎")
    sub = ap.add_subparsers(dest="cmd")

    p_list = sub.add_parser("list", help="列出可用跑步区域")
    p_list.set_defaults(func=cmd_list)

    p_gen = sub.add_parser("gen", help="生成轨迹")
    p_gen.add_argument("--route", default="A", help="区域 A/B/C")
    p_gen.add_argument("--pace", default="5:30", help="配速，如 5:30 或 5.5")
    p_gen.add_argument("--dist", type=float, default=2.0, help="目标距离 (km)")
    p_gen.add_argument("--dt", type=float, default=1.0, help="采样间隔 (s)")
    p_gen.add_argument("--noise", type=float, default=0.6,
                       help="GPS 逐秒抖动强度；0.6 约等于真实手机在开阔地经滤波后的表现")
    p_gen.add_argument("--crs", default="gcj02", choices=["wgs84", "gcj02"])
    p_gen.add_argument("--seed", type=int, default=20240101, help="随机种子，-1 为随机")
    p_gen.add_argument("--burrs", action="store_true",
                       help="叠加毛刺 / 突起（默认关闭，和 App 开关一致）")
    p_gen.add_argument("--since", default=None, help="起始时间 ISO8601，默认现在")
    p_gen.add_argument("--out", default="track.json", help="JSON 输出路径")
    p_gen.add_argument("--gpx", default=None, help="GPX 输出路径")
    p_gen.set_defaults(func=cmd_gen)

    args = ap.parse_args(argv)
    if not getattr(args, "func", None):
        ap.print_help()
        return 1
    if getattr(args, "seed", 0) == -1:
        args.seed = None
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
