#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_pace_profile.py —— 把 android-app 里 TrackGen.kt 的配速起伏与容差逻辑
1:1 回写成 Python 验证。

Kotlin 的 XorWow PRNG 和 Python 的 Mersenne 取值不同，所以噪声的具体数值不会一致，
但统计行为和数值流程等价 —— 和 refs/verify_trackgen.py 用的是同一套验证思路。

验证五件事（毛刺开 / 关两种模式都跑）：
  1. 围栏内 100%、禁跑区 0 点
  2. 实测距离 / 配速精确命中目标
  3. 平均配速始终落在「目标 ± 容差」窗口内（含不同 seed 与配速）
  4. 确实存在配速起伏与短暂停顿
  5. 毛刺开关必须真的改变轨迹：关闭为光滑轨迹（5m+ 跳点 0），打开后才有突起
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


def offset(lat, lon, dist, brg):
    p1, l1, brg = math.radians(lat), math.radians(lon), math.radians(brg)
    d = dist / R
    p2 = math.asin(math.sin(p1) * math.cos(d) + math.cos(p1) * math.sin(d) * math.cos(brg))
    l2 = l1 + math.atan2(math.sin(brg) * math.sin(d) * math.cos(p1),
                         math.cos(d) - math.sin(p1) * math.sin(p2))
    return math.degrees(p2), math.degrees(l2)


def point_in_poly(lat, lon, poly):
    n = len(poly)
    if n < 3:
        return False
    inside = False
    for i in range(n):
        a = poly[i]
        b = poly[0] if i == n - 1 else poly[i + 1]
        la, lo = a
        lb, lob = b
        if (la <= lat < lb) or (lb <= lat < la):
            if abs(la - lb) > 0:
                if lo - (lo - lob) * (la - lat) / (la - lb) < lon:
                    inside = not inside
    return inside


# ---------------------------------------------------------------- 环线

def dedupe(poly):
    out = []
    for p in poly:
        if not out or out[-1] != tuple(p):
            out.append(tuple(p))
    if len(out) > 1 and out[0] == out[-1]:
        out.pop()
    return out


INSETS = (0.90, 0.88, 0.86, 0.84, 0.81, 0.77, 0.72, 0.66, 0.59, 0.51)
RING_WOBBLE_M = 4.5
RING_STEP_M = 7.0


def inset(poly, f):
    clat = sum(p[0] for p in poly) / len(poly)
    clon = sum(p[1] for p in poly) / len(poly)
    return [[clat + (p[0] - clat) * f, clon + (p[1] - clon) * f] for p in poly]


def is_valid(lat, lon, fence, excludes):
    if not point_in_poly(lat, lon, fence):
        return False
    for e in excludes:
        if point_in_poly(lat, lon, e):
            return False
    return True


def pull_toward(base, p, fence, excludes):
    """对应 TrackGen.pullToward：沿 base -> p 收回来，只削扰动、不动基准环。"""
    if not is_valid(base[0], base[1], fence, excludes):
        return p
    lo, hi = 0.0, 1.0
    for _ in range(22):
        mid = (lo + hi) / 2.0
        q = (base[0] + (p[0] - base[0]) * mid, base[1] + (p[1] - base[1]) * mid)
        if is_valid(q[0], q[1], fence, excludes):
            lo = mid
        else:
            hi = mid
    t = min(1.0, max(0.0, lo * 0.99))
    return (base[0] + (p[0] - base[0]) * t, base[1] + (p[1] - base[1]) * t)


def organic(raw, amp, seed, fence=(), excludes=()):
    """对应 TrackGen.organic：六条整数圈正弦 + 局部鼓包 + 一轮磨角 + 围栏内收。"""
    dense = []
    pts = list(raw) + [raw[0]]
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        d = hav(a[0], a[1], b[0], b[1])
        k = max(1, math.ceil(d / RING_STEP_M))
        for j in range(k):
            t = j / k
            dense.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    n = len(dense)
    if n < 16:
        return list(raw)

    per = 0.0
    for i in range(n):
        a, b = dense[i], dense[(i + 1) % n]
        per += hav(a[0], a[1], b[0], b[1])
    step_m = max(1.0, per / n)

    rnd = random.Random(seed * 1000003 + 97)
    harmonic = (2, 3, 5, 7, 11, 17)
    weight = (0.46, 0.34, 0.24, 0.17, 0.12, 0.07)
    phase = [rnd.uniform(0, 2 * math.pi) for _ in harmonic]
    wave_amp = [amp * weight[i] * rnd.uniform(0.55, 1.25) for i in range(len(harmonic))]

    bump_count = 3 + rnd.randint(0, 2)
    bumps = []
    for _ in range(bump_count):
        center = rnd.uniform(0.0, n)
        width_m = rnd.uniform(10.0, 38.0)
        bumps.append((center, max(1.0, width_m / step_m),
                      rnd.uniform(2.0, 6.2) * (1 if rnd.random() < 0.5 else -1)))

    cur = []
    for i in range(n):
        prev, nxt = dense[(i - 1) % n], dense[(i + 1) % n]
        hdg = bearing(prev[0], prev[1], nxt[0], nxt[1])
        u = i / n * 2 * math.pi
        off = sum(wave_amp[k] * math.sin(harmonic[k] * u + phase[k])
                  for k in range(len(harmonic)))
        for center, width, strength in bumps:
            di = abs(i - center)
            di = min(di, n - di)
            off += strength * math.exp(-0.5 * (di / width) ** 2)
        cur.append(offset(dense[i][0], dense[i][1], off, (hdg + 90.0) % 360.0))

    w = 2
    smooth = []
    for i in range(n):
        sla = sum(cur[(i + k) % n][0] for k in range(-w, w + 1)) / (2 * w + 1)
        slo = sum(cur[(i + k) % n][1] for k in range(-w, w + 1)) / (2 * w + 1)
        smooth.append((sla, slo))

    out = []
    for i in range(n):
        p = smooth[i]
        if is_valid(p[0], p[1], fence, excludes):
            out.append(p)
        else:
            out.append(pull_toward(dense[i], p, fence, excludes))
    return out


def organic_smooth(raw, amp, seed):
    """v1.10.1 的光滑环线：两条整数圈正弦 + 两轮磨角。

    「轨迹毛刺」开关关闭时走这里，和上面的有机化不是同一套。
    """
    dense = []
    pts = list(raw) + [raw[0]]
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        d = hav(a[0], a[1], b[0], b[1])
        k = max(1, math.ceil(d / 12.0))
        for j in range(k):
            t = j / k
            dense.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    n = len(dense)
    if n < 8:
        return list(raw)

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
        cur = [
            (sum(cur[(i + k) % n][0] for k in range(-w, w + 1)) / (2 * w + 1),
             sum(cur[(i + k) % n][1] for k in range(-w, w + 1)) / (2 * w + 1))
            for i in range(n)
        ]
    return cur


def bad_points(poly, fence, excludes):
    bad = 0
    for i in range(len(poly)):
        a, b = poly[i], poly[(i + 1) % len(poly)]
        d = hav(a[0], a[1], b[0], b[1])
        k = max(1, math.ceil(d / RING_STEP_M))
        for j in range(k):
            t = j / k
            if not is_valid(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t,
                            fence, excludes):
                bad += 1
    return bad


def choose_ring(fence, excludes, seed):
    fallback = None
    fallback_bad = 10 ** 9
    for f in INSETS:
        raw = inset(fence, f)
        bad = bad_points(raw, fence, excludes)
        if bad < fallback_bad:
            fallback_bad, fallback = bad, raw
        if bad == 0:
            return organic(raw, RING_WOBBLE_M, seed, fence, excludes)
    return organic(fallback, RING_WOBBLE_M, seed, fence, excludes)


def densify(ring, step_m):
    pts = list(ring) + [ring[0]]
    out = []
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        d = hav(a[0], a[1], b[0], b[1])
        k = max(1, math.ceil(d / step_m))
        for j in range(k):
            t = j / k
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
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
    p = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    return p, bearing(a[0], a[1], b[0], b[1])


# ---------------------------------------------------------------- 配速形态（对应 TrackGen）

def slowness_profile(n, seed):
    out = [1.0] * n
    rnd = random.Random(seed * 7919 + 13)

    seg_len = 40 + rnd.randint(0, 30)
    i = 0
    while i < n:
        end = min(n, i + seg_len)
        f = 0.85 + rnd.random() * 0.60
        for k in range(i, end):
            out[k] = f
        i = end

    for k in range(n):
        if k < 25:
            out[k] *= 1.08
        if k >= n - 25:
            out[k] *= 0.94
        out[k] *= 1.0 + 0.10 * k / n

    # 停顿：约 7% 的时间点落在里面，2 公里大概是 2~3 次
    pauses = int(n * 0.07 / 6.0)
    pauses = max(1, min(5, pauses))
    for _ in range(pauses):
        ln = 4 + rnd.randint(0, 5)
        span = max(1, n - ln - 1)
        at = int(span * (0.12 + rnd.random() * 0.76))
        for k in range(at, min(n, at + ln)):
            out[k] = 22.0
    return out


def random_walk_segments(n, seg_len, jitter, seed, salt):
    rnd = random.Random(seed * 131 + salt * 977 + 17)
    out = [1.0] * n
    i = 0
    while i < n:
        g = max(1, seg_len // 2 + rnd.randint(0, seg_len))
        v = 1.0 + rnd.uniform(-jitter, jitter)
        end = min(n, i + g)
        for k in range(i, end):
            out[k] = v
        i = end
    return out


def step_weights(n, seed):
    slow = slowness_profile(n, seed)
    if n < 24:
        return [1.0] * n
    terrain = random_walk_segments(n, 260, 0.06, seed, 1)
    jitter = random_walk_segments(n, 11, 0.045, seed, 2)
    w = [1.0 / (slow[i] * terrain[i] * jitter[i]) for i in range(n)]
    mean = sum(w) / len(w)
    if mean > 0:
        w = [x / mean for x in w]
    return w


def clamp_to_fence(lat, lon, fence):
    """对应 TrackGen.clampToFence：把越界点沿"点→质心"连线拉回围栏内。"""
    if len(fence) < 3:
        return lat, lon
    if point_in_poly(lat, lon, fence):
        return lat, lon
    clat = sum(p[0] for p in fence) / len(fence)
    clon = sum(p[1] for p in fence) / len(fence)
    if not point_in_poly(clat, clon, fence):
        return lat, lon
    lo, hi = 0.0, 1.0
    for _ in range(24):
        mid = (lo + hi) / 2.0
        if point_in_poly(clat + (lat - clat) * mid, clon + (lon - clon) * mid, fence):
            lo = mid
        else:
            hi = mid
    t = min(1.0, max(0.0, lo * 0.995))
    return clat + (lat - clat) * t, clon + (lon - clon) * t


def make_spurs(n, seed, intensity=1.0):
    """对应 TrackGen.makeSpurs：大跳 / 绕行包 / 硬毛刺 / 小毛刺四档。"""
    lateral = [0.0] * n
    forward = [0.0] * n
    if n < 80:
        return lateral, forward

    rnd = random.Random(seed * 1000003 + 77)
    used = []

    def free(at, gap):
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
                forward[at + k] = a * share * math.sin(2 * math.pi * sv)
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
                lateral[at + 1] = -a * 0.55 * sign if rnd.random() < 0.5 else a * 0.3 * sign
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


def tolerate(base, q, fence, excludes):
    """对应 TrackGen.tolerate：沿 base -> q 二分回缩，保住毛刺方向。"""
    if is_valid(q[0], q[1], fence, excludes):
        return q
    if not is_valid(base[0], base[1], fence, excludes):
        return clamp_to_fence(q[0], q[1], fence)
    lo, hi = 0.0, 1.0
    for _ in range(22):
        mid = (lo + hi) / 2.0
        p = (base[0] + (q[0] - base[0]) * mid, base[1] + (q[1] - base[1]) * mid)
        if is_valid(p[0], p[1], fence, excludes):
            lo = mid
        else:
            hi = mid
    t = min(1.0, max(0.0, lo * 0.99))
    return base[0] + (q[0] - base[0]) * t, base[1] + (q[1] - base[1]) * t


NOISE_RHO = (0.997, 0.970, 0.850, 0.550)
NOISE_SIGMA = (0.50, 0.27, 0.27, 0.33)


def build(ring, n, weights, spurs, target_m, noise, seed, fence=(), excludes=()):
    """对应 TrackGen.build：四层 AR(1) + 稀疏野点 + step 二分标定。"""
    dense, cum = densify(ring, 1.0)
    spur_lat, spur_fwd = spurs

    def emit(step):
        rnd = random.Random(seed)
        rnd_acc = random.Random(seed * 31 + 7)
        perr = [0.0] * len(NOISE_RHO)
        aerr = [0.0] * len(NOISE_RHO)
        travelled = 0.0
        lat = [0.0] * n
        lon = [0.0] * n
        pew = [0.0] * n
        for i in range(n):
            p, hdg = point_at(dense, cum, travelled)
            perr_t = spur_lat[i]
            aerr_t = spur_fwd[i]
            for c in range(len(NOISE_RHO)):
                perr[c] = NOISE_RHO[c] * perr[c] + rnd.uniform(-1.0, 1.0) * noise * NOISE_SIGMA[c]
                aerr[c] = NOISE_RHO[c] * aerr[c] + rnd.uniform(-1.0, 1.0) * noise * NOISE_SIGMA[c]
                perr_t += perr[c]
                aerr_t += aerr[c] * 0.45
            o1 = offset(p[0], p[1], perr_t, (hdg + 90.0) % 360.0)
            q = offset(o1[0], o1[1], aerr_t, hdg)
            lat[i], lon[i] = tolerate(p, q, fence, excludes)
            pew[i] = perr_t
            travelled += step * weights[i]

        out = []
        for i in range(n):
            j = i + 1 if i < n - 1 else i - 1
            seg = hav(lat[i], lon[i], lat[j], lon[j])
            acc = 4.5 + abs(pew[i]) * 0.30 + rnd_acc.uniform(-1.0, 2.2)
            out.append({
                "lat": lat[i], "lon": lon[i], "sec": i,
                "speed": round(seg, 2),
                "acc": round(min(18.0, max(3.0, acc)), 1),
                "step": seg,
            })
        return out

    def measure(pts):
        return sum(hav(pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"])
                   for i in range(len(pts) - 1))

    if measure(emit(0.0)) >= target_m:
        return emit(0.0)
    lo, hi = 0.0, 5.0
    while measure(emit(hi)) < target_m and hi < 1e5:
        hi *= 1.5
    for _ in range(46):
        mid = (lo + hi) / 2.0
        if measure(emit(mid)) < target_m:
            lo = mid
        else:
            hi = mid
    return emit((lo + hi) / 2.0)


def build_smooth(ring, n, weights, target_m, noise, seed, fence=(), excludes=()):
    """v1.10.1 的光滑采样：单个 AR(1) 噪声 + clamp_to_fence。"""
    dense, cum = densify(ring, 1.0)

    def emit(step):
        rnd = random.Random(seed)
        rnd_acc = random.Random(seed * 31 + 7)
        perr = aerr = 0.0
        travelled = 0.0
        lat = [0.0] * n
        lon = [0.0] * n
        pew = [0.0] * n
        for i in range(n):
            p, hdg = point_at(dense, cum, travelled)
            perr = 0.9 * perr + rnd.uniform(-1.0, 1.0) * noise * 0.55
            aerr = 0.9 * aerr + rnd.uniform(-1.0, 1.0) * noise * 0.35
            o1 = offset(p[0], p[1], perr, (hdg + 90.0) % 360.0)
            o2 = offset(o1[0], o1[1], aerr, hdg)
            lat[i], lon[i] = clamp_to_fence(o2[0], o2[1], fence)
            pew[i] = perr
            travelled += step * weights[i]

        out = []
        for i in range(n):
            j = i + 1 if i < n - 1 else i - 1
            seg = hav(lat[i], lon[i], lat[j], lon[j])
            acc = 4.5 + abs(pew[i]) * 0.45 + rnd_acc.uniform(-1.0, 2.2)
            out.append({
                "lat": lat[i], "lon": lon[i], "sec": i,
                "speed": round(seg, 2),
                "acc": round(min(18.0, max(3.0, acc)), 1),
                "step": seg,
            })
        return out

    def measure(pts):
        return sum(hav(pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"])
                   for i in range(len(pts) - 1))

    if measure(emit(0.0)) >= target_m:
        return emit(0.0)
    lo, hi = 0.0, 5.0
    while measure(emit(hi)) < target_m and hi < 1e5:
        hi *= 1.5
    for _ in range(44):
        mid = (lo + hi) / 2.0
        if measure(emit(mid)) < target_m:
            lo = mid
        else:
            hi = mid
    return emit((lo + hi) / 2.0)


def pace_window(pace_s_km, tol):
    """对应 TrackGen.paceWindow。"""
    t = abs(tol)
    return (max(60.0, pace_s_km - t), pace_s_km + t)


def pace_of(pts):
    d = measure(pts)
    return (len(pts) - 1) / (d / 1000.0) if d > 0 else 0.0


def generate_smooth(fence, excludes, pace_s_km, target_m, noise, seed, window):
    """v1.10.1 的光滑轨迹，对应 TrackGen.generateSmooth。"""
    nom = 1000.0 / pace_s_km
    n = max(2, round(target_m / nom))
    adjusted = False
    pts = []

    for _ in range(4):
        weights = step_weights(n, seed)
        best, best_score = [], -1.0
        for f in (0.90, 0.85, 0.80, 0.74, 0.66, 0.58, 0.50):
            ring = organic_smooth(inset(fence, f), 3.0, seed)
            cand = build_smooth(ring, n, weights, target_m, noise, seed, fence, excludes)
            inside = sum(1 for q in cand if point_in_poly(q["lat"], q["lon"], fence))
            banned = sum(1 for q in cand
                         if any(point_in_poly(q["lat"], q["lon"], e) for e in excludes))
            score = (inside - banned) / len(cand)
            if score > best_score:
                best_score, best = score, cand
            if banned == 0 and inside == len(cand):
                break
        pts = best
        pace = pace_of(pts)
        if window[0] <= pace <= window[1]:
            break
        ratio = pace / pace_s_km if pace_s_km > 0 else 1.0
        nxt = int(round(n / ratio)) if ratio > 0 else n
        nxt = max(2, min(nxt, n * 4 + 8))
        if nxt == n:
            break
        n = nxt
        adjusted = True
    return pts, fence, pace_of(pts), window, adjusted


def generate(poly, excludes, pace_s_km, target_m, noise=0.6, seed=0,
             pace_tol=20.0, burrs=False):
    """对应 TrackGen.generate：burrs=False 走 v1.10.1 光滑轨迹，True 走毛刺模式。"""
    fence = dedupe(poly)
    excludes = [dedupe(e) for e in excludes if len(e) >= 3]
    window = pace_window(pace_s_km, pace_tol)

    if not burrs:
        return generate_smooth(fence, excludes, pace_s_km, target_m, noise, seed, window)

    ring = choose_ring(fence, excludes, seed)
    nom = 1000.0 / pace_s_km
    n = max(2, round(target_m / nom))
    adjusted = False
    pts = []

    for _ in range(4):
        weights = step_weights(n, seed)
        spurs = make_spurs(n, seed, min(2.5, max(0.0, noise / 0.6)))
        pts = build(ring, n, weights, spurs, target_m, noise, seed, fence, excludes)
        pace = pace_of(pts)
        if window[0] <= pace <= window[1]:
            break
        ratio = pace / pace_s_km if pace_s_km > 0 else 1.0
        nxt = int(round(n / ratio)) if ratio > 0 else n
        nxt = max(2, min(nxt, n * 4 + 8))
        if nxt == n:
            break
        n = nxt
        adjusted = True
    return pts, fence, pace_of(pts), window, adjusted


# ---------------------------------------------------------------- 真实场地

FENG_HUA = [
    (29.532692, 106.608105), (29.532871, 106.608176), (29.533363, 106.608104),
    (29.533592, 106.608107), (29.533707, 106.607374), (29.533713, 106.607166),
    (29.533627, 106.607020), (29.533197, 106.607033), (29.532749, 106.607017),
    (29.532289, 106.607043), (29.532065, 106.607032), (29.531956, 106.607445),
    (29.531947, 106.607814), (29.531952, 106.607986), (29.532145, 106.608111),
    (29.532466, 106.608101), (29.532692, 106.608105),
]
FENG_HUA_JPQ = [[
    (29.532769, 106.607824), (29.533107, 106.607843), (29.533293, 106.607835),
    (29.533417, 106.607734), (29.533403, 106.607527), (29.533345, 106.607373),
    (29.533219, 106.607323), (29.533012, 106.607303), (29.532650, 106.607330),
    (29.532462, 106.607380), (29.532362, 106.607479), (29.532323, 106.607624),
    (29.532385, 106.607769), (29.532528, 106.607824), (29.532717, 106.607841),
]]
TAI_JI = [
    (29.533933, 106.608884), (29.531893, 106.608970),
    (29.532019, 106.610646), (29.533979, 106.610558),
]
NING_JING = [
    (29.530657, 106.606709), (29.530609, 106.608438), (29.529696, 106.608317),
    (29.529679, 106.606648), (29.530394, 106.606416),
]


def window_pace_range(pts, win=15):
    if len(pts) <= win + 1:
        return 0.0, 0.0
    fastest, slowest = float("inf"), 0.0
    for i in range(0, len(pts) - win):
        seg = sum(pts[k]["step"] for k in range(i, i + win))
        if seg <= 0.1:
            continue
        p = win / (seg / 1000.0)
        fastest = min(fastest, p)
        slowest = max(slowest, p)
    if fastest == float("inf"):
        return 0.0, 0.0
    return fastest, slowest


def fmt_pace(s):
    if s <= 0 or s > 3600:
        return "--"
    return "%d'%02d\"" % (int(s / 60), int(s % 60))


def measure(pts):
    return sum(hav(pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"])
               for i in range(len(pts) - 1))


def main():
    print("=" * 78)
    print("TrackGen 配速起伏与容差验证（python 镜像，对应 TrackGen.kt）")
    print("=" * 78)

    target = 2000.0
    paces = [240.0, 330.0, 345.0, 420.0]
    tol = 20.0
    fails = []
    modes = (("关", False), ("开", True))

    print("\n【1】【2】【3】三个场地 × 四种配速 × 三个 seed × 毛刺开关：")
    print("           围栏内 / 禁跑区 / 距离 / 平均配速是否落在容差窗口")
    print("-" * 86)
    print("%-4s %-10s %-8s %6s %10s %9s %9s %7s %s" %
          ("毛刺", "场地", "配速", "点数", "实测距离", "实测配速", "围栏内", "禁跑区", "窗口"))
    for label, burrs in modes:
        for name, fence0, jpq in (("风华", FENG_HUA, FENG_HUA_JPQ),
                                  ("太极", TAI_JI, []),
                                  ("宁静苑", NING_JING, [])):
            for pace in paces:
                for seed in (12345, 777, 20261013):
                    pts, fence, real, window, _ = generate(
                        fence0, jpq, pace, target, seed=seed, pace_tol=tol, burrs=burrs)
                    d = measure(pts)
                    inside = sum(1 for q in pts if point_in_poly(q["lat"], q["lon"], fence))
                    banned = sum(1 for q in pts
                                 if any(point_in_poly(q["lat"], q["lon"], e) for e in jpq))
                    ok_d = abs(d - target) <= target * 0.01
                    ok_p = abs(real - pace) <= tol + 0.5
                    ok_i = inside == len(pts)
                    ok_b = banned == 0
                    inwin = window[0] <= real <= window[1]
                    tag = "" if (ok_d and ok_p and ok_i and ok_b and inwin) else "  <<< 失败"
                    if tag:
                        fails.append((label, name, pace, seed, d, real, inside,
                                      len(pts), banned, inwin))
                    print("%-4s %-10s %-8s %6d %10.1f %9s %9s %7d %s%s" %
                          (label, name, fmt_pace(pace), len(pts), d, fmt_pace(real),
                           "%.1f%%" % (inside * 100.0 / len(pts)), banned,
                           "✓" if inwin else "✗", tag))
        print()

    print("\n【4】配速起伏：应当有明显快慢差异，并出现短暂停顿")
    print("-" * 78)
    print("%-4s %-10s %12s %12s %10s %10s" %
          ("毛刺", "场地", "最快分段", "最慢分段", "步长跨度", "停顿点"))
    for label, burrs in modes:
        for name, fence0, jpq in (("风华", FENG_HUA, FENG_HUA_JPQ),
                                  ("太极", TAI_JI, []),
                                  ("宁静苑", NING_JING, [])):
            pts, _, _, _, _ = generate(fence0, jpq, 345.0, target, seed=2026, burrs=burrs)
            lo, hi = window_pace_range(pts)
            steps = [p["step"] for p in pts]
            span = max(steps) - min(steps)
            paused = sum(1 for x in steps if x < 0.4)
            print("%-4s %-10s %12s %12s %10.2f %10d" %
                  (label, name, fmt_pace(lo), fmt_pace(hi), span, paused))
            if not (hi - lo > 40):
                fails.append(("起伏不足", label, name, lo, hi))
            if paused == 0:
                fails.append(("没有停顿点", label, name))

    print("\n【5】容差 0 时也应贴近目标配速")
    print("-" * 78)
    for label, burrs in modes:
        for pace in (330.0, 345.0):
            pts, _, real, window, _ = generate(
                FENG_HUA, FENG_HUA_JPQ, pace, target, seed=42, pace_tol=0.0,
                burrs=burrs)
            dev = abs(real - pace)
            print("  毛刺%s  目标 %s → 实测 %s（偏差 %.1f 秒）" %
                  (label, fmt_pace(pace), fmt_pace(real), dev))
            if dev > 2.0:
                fails.append(("容差0偏差过大", label, pace, real))

    print("\n【6】毛刺开关必须真的改变轨迹")
    print("-" * 78)
    smooth, _, _, _, _ = generate(FENG_HUA, FENG_HUA_JPQ, 345.0, target,
                                  seed=555, burrs=False)
    burr, _, _, _, _ = generate(FENG_HUA, FENG_HUA_JPQ, 345.0, target,
                                seed=555, burrs=True)

    def jump_stats(pts):
        steps = [p["step"] for p in pts]
        return sum(1 for x in steps if x > 5.0), max(steps)

    s_big, s_max = jump_stats(smooth)
    b_big, b_max = jump_stats(burr)
    print("  关闭：5m+ 跳点 %2d 个，最大步长 %.2f m" % (s_big, s_max))
    print("  开启：5m+ 跳点 %2d 个，最大步长 %.2f m" % (b_big, b_max))
    if s_big != 0 or s_max >= 5.0:
        fails.append(("关闭毛刺后仍然有突起", s_big, s_max))
    if b_big < 8:
        fails.append(("开启毛刺后 5m+ 跳点太少", b_big))
    if all(abs(a["lat"] - b["lat"]) < 1e-12 and abs(a["lon"] - b["lon"]) < 1e-12
           for a, b in zip(smooth, burr)):
        fails.append(("开关切换后轨迹没有变化",))

    print("\n" + "=" * 78)
    if fails:
        print("✗ 有 %d 项未通过：" % len(fails))
        for f in fails:
            print("   ", f)
        return 1
    print("✓ 全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
