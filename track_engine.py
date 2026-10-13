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


def organic_loop(waypoints: list[tuple[float, float]],
                 seed: int = 20240101,
                 seg_m: float = 15.0,
                 wobble_m: float = 4.0) -> list[tuple[float, float]]:
    """把规则折线变成"人跑出来"的闭合环线。

    真实跑步不会沿标准矩形、更不会在角上做 90° 急转。这里做两件事：
      1. 低频正弦横向扰动（周期为整圈，保证闭合），模拟贴边、绕障碍、抄近道
      2. 循环滑动平均，把直角磨圆
    产出的航向是连续变化的，不会被"航向只有四个值"这类规则筛出来。
    """
    dense = densify(waypoints, seg_m)[:-1]        # 去掉重复的闭合点
    n = len(dense)
    if n < 8:
        return list(waypoints)

    rnd = random.Random(seed)
    k1, k2 = 2, 3                                  # 整数 => 整圈周期 => 自动闭合
    p1, p2 = rnd.uniform(0, math.tau), rnd.uniform(0, math.tau)
    a1, a2 = wobble_m * rnd.uniform(0.5, 1.0), wobble_m * rnd.uniform(0.3, 0.7)

    perturbed = []
    for i, pt in enumerate(dense):
        hdg = bearing(dense[(i - 1) % n], dense[(i + 1) % n])
        u = i / n * math.tau
        off = a1 * math.sin(k1 * u + p1) + a2 * math.sin(k2 * u + p2)
        perturbed.append(offset_point(pt, off, (hdg + 90.0) % 360.0))

    # 循环滑动平均，磨圆直角
    for _ in range(2):
        w = 3
        perturbed = [
            (sum(perturbed[(i + k) % n][0] for k in range(-w, w + 1)) / (2 * w + 1),
             sum(perturbed[(i + k) % n][1] for k in range(-w, w + 1)) / (2 * w + 1))
            for i in range(n)
        ]

    return perturbed


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
                     altitude=DEFAULT_ALTITUDE, start=None):
    """基于服务器下发的真实电子围栏 + 禁跑区生成轨迹。

    fence    : [[lat, lon], ...]   dzwl 电子围栏
    excludes : [[[lat, lon], ...]] jpq 禁跑区，轨迹要避开
    返回 (points, stats)
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
                "lat": round(lat, 7), "lon": round(lon, 7),
                "speed": round(sm, 2),
                "bearing": round((hdg + rnd.gauss(0, 1.6)) % 360.0, 1),
                "accuracy": round(max(4.0, 5.0 + abs(perr) * 0.5 + rnd.uniform(-1, 2)), 1),
                "altitude": round(altitude + rnd.uniform(-2, 2), 1),
            })
        return out

    def measure(pts):
        return sum(haversine((a["lat"], a["lon"]), (b["lat"], b["lon"]))
                   for a, b in zip(pts, pts[1:]))

    best, best_score, best_f = None, -1.0, None
    for f in (0.90, 0.85, 0.80, 0.74, 0.66, 0.58, 0.50):
        ring = inset_poly(fence, f)
        dense = densify(organic_loop(ring, seed=seed,
                                     seg_m=max(6.0, _perim(ring) / 64.0),
                                     wobble_m=3.0), 1.0)
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
        "duration": len(best) - 1,
    }
    return best, stats


def _perim(poly):
    return sum(haversine(poly[i], poly[(i + 1) % len(poly)]) for i in range(len(poly)))


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
                altitude: float = DEFAULT_ALTITUDE) -> list[TrackPoint]:
    """生成一条轨迹。"""
    # 有机化：把规则折线磨成"人跑出来"的环线，采样点够密才磨得动
    _wp = ROUTES[route]["waypoints"]
    _len = sum(haversine(a, b) for a, b in zip(_wp + [_wp[0]], _wp[1:] + [_wp[0]]))
    wp = organic_loop(_wp, seed=seed if seed is not None else 20240101,
                      seg_m=max(6.0, _len / 64.0), wobble_m=4.0)

    dense = densify(wp, 0.5)
    cum = cumulative(dense)

    nom_speed = 1000.0 / pace_s_per_km             # m/s，理想地面速度

    # 采样点数由"配速 × 距离"锁死：时长固定，速度才是待求量
    n = max(2, int(round(target_m / (nom_speed * sample_dt))))

    def emit(step_m: float) -> list[TrackPoint]:
        """固定点数 n，按给定地面步长铺点，再叠加相关噪声。

        同一个 seed 每次都重现同一噪声实现，因此 measure 是关于 step_m 的
        确定性单调递增函数，可以直接二分。
        """
        rnd = random.Random(seed)

        perr = 0.0
        aerr = 0.0
        sm_speed = nom_speed
        out: list[TrackPoint] = []

        for i in range(n):
            p, hdg = point_at(dense, cum, i * step_m)

            perr = drift_rho * perr + rnd.gauss(0.0, noise_sigma * 0.55)
            aerr = drift_rho * aerr + rnd.gauss(0.0, noise_sigma * 0.35)

            # 垂直航向做横向偏移，沿航向做纵向偏移
            lat0, lon0 = offset_point(p, perr, (hdg + 90.0) % 360.0)
            lat0, lon0 = offset_point((lat0, lon0), aerr, hdg)

            # 报告速度：真实速度上做低通，避免恒定值
            sm_speed = 0.75 * sm_speed + 0.25 * (nom_speed * (1.0 + rnd.uniform(-0.03, 0.03)))

            t = start + timedelta(seconds=i * sample_dt)
            glat, glon = transform((lat0, lon0), crs)

            out.append(TrackPoint(
                t=round(t.timestamp(), 3),
                lat=round(glat, 7),
                lon=round(glon, 7),
                speed=round(sm_speed, 2),
                bearing=round((hdg + rnd.gauss(0.0, 1.6)) % 360.0, 1),
                accuracy=round(max(3.0, 5.0 + abs(perr) * 0.5 + rnd.uniform(-1.0, 2.0)), 1),
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

    for _ in range(32):
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
                      crs=args.crs, seed=args.seed)

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
