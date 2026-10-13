/*
 * 轨迹生成：配速标定 / 电子围栏内缩 / 禁跑区避让 / AR(1) GPS 噪声
 * 作者：蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 几何工具。全部在 GCJ02 经纬度上做平面近似 —— 服务器和区域多边形都用 gcj02，
 * 保持一致可以彻底避开坐标系转换带来的偏移。
 */
object Geo {

    const val R = 6371008.8

    fun hav(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val x = sin(dl) * cos(p2)
        val y = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
    }

    /** 从 (lat,lon) 沿方位角 brgDeg 走 dist 米，返回 [lat, lon]。 */
    fun offset(lat: Double, lon: Double, dist: Double, brgDeg: Double): DoubleArray {
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val brg = Math.toRadians(brgDeg)
        val d = dist / R
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(brg))
        val l2 = l1 + atan2(sin(brg) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return doubleArrayOf(Math.toDegrees(p2), Math.toDegrees(l2))
    }

    /** 与小程序 IsPtInPoly 等价的射线法。poly 元素为 [lat, lon]。 */
    fun pointInPoly(lat: Double, lon: Double, poly: List<DoubleArray>): Boolean {
        val n = poly.size
        if (n < 3) return false
        var inside = false
        for (i in 0 until n) {
            val a = poly[i]
            val b = if (i == n - 1) poly[0] else poly[i + 1]
            val la = a[0]; val lo = a[1]
            val lb = b[0]; val lob = b[1]
            if ((lat >= la && lat < lb) || (lat >= lb && lat < la)) {
                if (abs(la - lb) > 0) {
                    if (lo - (lo - lob) * (la - lat) / (la - lb) < lon) inside = !inside
                }
            }
        }
        return inside
    }

    private fun mPerDegLat() = 110540.0
    private fun mPerDegLon(lat: Double) = 111320.0 * cos(Math.toRadians(lat))
}

/** 轨迹点：时间用相对起始的秒数表示，由调用方加上真实起始时刻。 */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val secOffset: Int,
    val speed: Double,
    val bearing: Double,
    val accuracy: Double
)

/**
 * 轨迹生成器。
 *
 * 输入是从服务器拉下来的区域多边形（gcj02），输出是一条落在多边形内、
 * 配速精确、带真实 GPS 噪声的闭环轨迹。
 *
 * 关键点：
 *  1. 环线由多边形向内缩放得到，并用 pointInPoly 自校验，不达标就继续缩
 *  2. 采样点数由「配速 × 距离」锁死，二分反解地面速度，
 *     使**实测配速等于目标配速**（否则噪声会把距离撑长十几个百分点）
 *  3. AR(1) 相关噪声，模拟真实 GPS 的低频漂移
 */
object TrackGen {

    /**
     * @param poly        电子围栏多边形（dzwl，gcj02），元素 [lat, lon]
     * @param excludes    禁跑区多边形（jpq），轨迹必须完全避开
     * @param paceSecKm   目标配速，秒/公里
     * @param targetM     目标距离，米
     * @param noise       逐秒抖动强度，0.6 ≈ 开阔地手机滤波后的表现
     * @param seed        随机种子
     */
    fun generate(
        poly: List<DoubleArray>,
        excludes: List<List<DoubleArray>> = emptyList(),
        paceSecKm: Double,
        targetM: Double,
        noise: Double = 0.6,
        seed: Int = 0
    ): List<TrackPoint> {
        require(poly.size >= 3) { "区域多边形至少需要 3 个顶点" }
        val fence = dedupe(poly)

        // 采样点数由配速和距离唯一确定
        val nomSpeed = 1000.0 / paceSecKm
        val n = maxOf(2, Math.round(targetM / nomSpeed).toInt())

        // 逐级向内缩放，挑选"围栏内最多 + 禁跑区最少"的一版
        var best: List<TrackPoint> = emptyList()
        var bestScore = -1.0

        for (f in doubleArrayOf(0.90, 0.85, 0.80, 0.74, 0.66, 0.58, 0.50)) {
            val ring = organic(inset(fence, f), 3.0, seed)
            val pts = build(ring, n, nomSpeed, targetM, noise, seed)
            val inside = pts.count { Geo.pointInPoly(it.lat, it.lon, fence) }
            val banned = pts.count { p -> excludes.any { Geo.pointInPoly(p.lat, p.lon, it) } }
            val score = (inside - banned).toDouble() / pts.size
            if (score > bestScore) {
                bestScore = score
                best = pts
            }
            if (banned == 0 && inside.toDouble() / pts.size >= 0.995) break
        }
        return best
    }

    /** 去掉重复顶点（服务器下发的多边形经常有连续重复点）。 */
    private fun dedupe(poly: List<DoubleArray>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(poly.size)
        for (p in poly) {
            val last = out.lastOrNull()
            if (last == null || last[0] != p[0] || last[1] != p[1]) out.add(p)
        }
        if (out.size > 1 && out[0][0] == out.last()[0] && out[0][1] == out.last()[1]) {
            out.removeAt(out.size - 1)
        }
        return out
    }

    /** 统计结果：围栏内比例、禁跑区点数、总距离。 */
    fun audit(pts: List<TrackPoint>, fence: List<DoubleArray>,
              excludes: List<List<DoubleArray>>): String {
        val inside = pts.count { Geo.pointInPoly(it.lat, it.lon, fence) }
        val banned = pts.count { p -> excludes.any { Geo.pointInPoly(p.lat, p.lon, it) } }
        return "围栏内 ${inside}/${pts.size} = ${"%.2f".format(inside * 100.0 / pts.size)}%" +
                " | 禁跑区 $banned 点"
    }

    /** 向内缩放：每个顶点朝质心方向按比例靠拢。 */
    private fun inset(poly: List<DoubleArray>, f: Double): List<DoubleArray> {
        val cLat = poly.map { it[0] }.average()
        val cLon = poly.map { it[1] }.average()
        return poly.map {
            doubleArrayOf(cLat + (it[0] - cLat) * f, cLon + (it[1] - cLon) * f)
        }
    }

    /**
     * 有机化：把规则多边形磨成"人跑出来"的环线。
     *
     * 服务器给的区域大多是手采的多边形，内缩之后仍然是折线，航向只有几个固定值，
     * 一眼机器画的。这里做两件事：
     *   1. 低频正弦横向扰动（周期取整数圈，保证闭合），模拟贴边、绕障碍、抄近道
     *   2. 循环滑动平均，把直角磨圆
     * 产出航向连续变化。
     */
    private fun organic(raw: List<DoubleArray>, amp: Double, seed: Int): List<DoubleArray> {
        // 先加密到 ~12m 一个点，点太少磨不动
        val dense = ArrayList<DoubleArray>()
        val pts = raw + raw[0]
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val d = Geo.hav(a[0], a[1], b[0], b[1])
            val k = maxOf(1, Math.ceil(d / 12.0).toInt())
            for (j in 0 until k) {
                val t = j.toDouble() / k
                dense.add(doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            }
        }
        val n = dense.size
        if (n < 8) return raw

        val rnd = Random(seed)
        val p1 = rnd.nextDouble(0.0, 2 * Math.PI)
        val p2 = rnd.nextDouble(0.0, 2 * Math.PI)
        val a1 = amp * rnd.nextDouble(0.5, 1.0)
        val a2 = amp * rnd.nextDouble(0.3, 0.7)

        var cur = ArrayList<DoubleArray>(n)
        for (i in 0 until n) {
            val prev = dense[(i - 1 + n) % n]
            val next = dense[(i + 1) % n]
            val hdg = Geo.bearing(prev[0], prev[1], next[0], next[1])
            val u = i.toDouble() / n * 2 * Math.PI
            val off = a1 * sin(2 * u + p1) + a2 * sin(3 * u + p2)
            cur.add(Geo.offset(dense[i][0], dense[i][1], off, (hdg + 90.0) % 360.0))
        }

        repeat(2) {
            val w = 2
            val sm = ArrayList<DoubleArray>(n)
            for (i in 0 until n) {
                var sla = 0.0
                var slo = 0.0
                for (k in -w..w) {
                    val q = cur[(i + k + n) % n]
                    sla += q[0]
                    slo += q[1]
                }
                sm.add(doubleArrayOf(sla / (2 * w + 1), slo / (2 * w + 1)))
            }
            cur = sm
        }
        return cur
    }

    private fun densify(ring: List<DoubleArray>, stepM: Double): Pair<List<DoubleArray>, DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val pts = ring + ring[0]
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val d = Geo.hav(a[0], a[1], b[0], b[1])
            val k = maxOf(1, Math.ceil(d / stepM).toInt())
            for (j in 0 until k) {
                val t = j.toDouble() / k
                out.add(doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            }
        }
        val cum = DoubleArray(out.size + 1)
        for (i in 0 until out.size) {
            val a = out[i]
            val b = if (i == out.size - 1) out[0] else out[i + 1]
            cum[i + 1] = cum[i] + Geo.hav(a[0], a[1], b[0], b[1])
        }
        return Pair(out, cum)
    }

    private fun pointAt(dense: List<DoubleArray>, cum: DoubleArray, dist: Double): Pair<DoubleArray, Double> {
        val total = cum[cum.size - 1]
        if (total <= 0.0) return Pair(dense[0], 0.0)
        val d = ((dist % total) + total) % total
        var lo = 0
        var hi = dense.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= d) lo = mid else hi = mid - 1
        }
        val i = min(lo, dense.size - 2)
        val seg = cum[i + 1] - cum[i]
        val t = if (seg <= 0) 0.0 else (d - cum[i]) / seg
        val a = dense[i]
        val b = dense[i + 1]
        val p = doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        return Pair(p, Geo.bearing(a[0], a[1], b[0], b[1]))
    }

    private fun build(
        ring: List<DoubleArray>,
        n: Int,
        nomSpeed: Double,
        targetM: Double,
        noise: Double,
        seed: Int
    ): List<TrackPoint> {
        val (dense, cum) = densify(ring, 1.0)

        fun emit(step: Double): List<TrackPoint> {
            val rnd = Random(seed)
            var perr = 0.0
            var aerr = 0.0
            var smSpeed = nomSpeed
            val out = ArrayList<TrackPoint>(n)
            for (i in 0 until n) {
                val (p, hdg) = pointAt(dense, cum, i * step)
                perr = 0.9 * perr + rnd.nextDouble(-1.0, 1.0) * noise * 0.55
                aerr = 0.9 * aerr + rnd.nextDouble(-1.0, 1.0) * noise * 0.35

                var o = Geo.offset(p[0], p[1], perr, (hdg + 90.0) % 360.0)
                o = Geo.offset(o[0], o[1], aerr, hdg)

                smSpeed = 0.75 * smSpeed + 0.25 * (nomSpeed * (1.0 + rnd.nextDouble(-0.03, 0.03)))

                out.add(
                    TrackPoint(
                        lat = o[0],
                        lon = o[1],
                        secOffset = i,
                        speed = Math.round(smSpeed * 100.0) / 100.0,
                        bearing = Math.round(((hdg + rnd.nextDouble(-1.6, 1.6) + 360.0) % 360.0) * 10.0) / 10.0,
                        accuracy = Math.round(maxOf(4.0, 5.0 + abs(perr) * 0.5 + rnd.nextDouble(-1.0, 2.0)) * 10.0) / 10.0
                    )
                )
            }
            return out
        }

        fun measure(pts: List<TrackPoint>): Double {
            var s = 0.0
            for (i in 0 until pts.size - 1) s += Geo.hav(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
            return s
        }

        // 噪声按秒注入、与步长无关，会把实测距离撑长。二分反解地面步长，
        // 让输出轨迹的实测距离 == targetM，配速才准。
        if (measure(emit(0.0)) >= targetM) return emit(0.0)

        var lo = 0.0
        var hi = nomSpeed * 2.0
        while (measure(emit(hi)) < targetM && hi < 1e5) hi *= 1.5
        repeat(48) {
            val mid = (lo + hi) / 2.0
            if (measure(emit(mid)) < targetM) lo = mid else hi = mid
        }
        return emit((lo + hi) / 2.0)
    }

    /** 结果统计，用于界面上给用户一个交代。 */
    fun summarize(pts: List<TrackPoint>): String {
        var d = 0.0
        for (i in 0 until pts.size - 1) d += Geo.hav(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        val dur = pts.size - 1
        val pace = if (d > 0) dur / (d / 1000.0) else 0.0
        val m = (pace / 60).toInt()
        val s = (pace % 60).toInt()
        return "点数 ${pts.size} | 距离 ${"%.1f".format(d)} m | 时长 ${dur / 60}分${dur % 60}秒 | " +
                "实测配速 $m'${"%02d".format(s)}\"/km"
    }
}
