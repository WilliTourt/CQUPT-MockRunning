/*
 * 轨迹生成：配速标定 / 电子围栏内缩 / 禁跑区避让 / AR(1) GPS 噪声 / 配速动态起伏
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
 * 配速变化风格。
 *
 * 三种都**不会改变总时长和总里程** —— 采样点数只由「配速 × 距离」决定，
 * 形态只决定这些秒里的速度怎么分配，所以达标判定不受影响。
 */
enum class PaceProfile {
    /** 恒定配速：每步等距。真实跑步不存在，只留作回归对照。 */
    STEADY,

    /** 温和起伏：起步慢、中途有几段快慢、越跑越累、尾声略冲，全程不停。 */
    NATURAL,

    /** 拟真起伏：在 [NATURAL] 上再叠入「系鞋带 / 等让路」式的短暂停顿。 */
    REALISTIC
}

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
 *  4. 逐秒速度按 [PaceProfile] 起伏；汇报的 speed / bearing 由**最终坐标反推**，
 *     和轨迹本身严格自洽（真人跑出来的数据本来就是这样）
 */
object TrackGen {

    const val PACE_STEADY = "steady"
    const val PACE_NATURAL = "natural"
    const val PACE_REALISTIC = "realistic"

    /** 界面上的字符串 → 风格枚举。未知值退回 [PaceProfile.NATURAL]。 */
    fun toProfile(key: String?): PaceProfile = when (key) {
        PACE_STEADY -> PaceProfile.STEADY
        PACE_REALISTIC -> PaceProfile.REALISTIC
        else -> PaceProfile.NATURAL
    }

    /**
     * @param poly        电子围栏多边形（dzwl，gcj02），元素 [lat, lon]
     * @param excludes    禁跑区多边形（jpq），轨迹必须完全避开
     * @param paceSecKm   目标配速，秒/公里
     * @param targetM     目标距离，米
     * @param noise       逐秒抖动强度，0.6 ≈ 开阔地手机滤波后的表现
     * @param seed        随机种子
     * @param profile     配速变化风格
     */
    fun generate(
        poly: List<DoubleArray>,
        excludes: List<List<DoubleArray>> = emptyList(),
        paceSecKm: Double,
        targetM: Double,
        noise: Double = 0.6,
        seed: Int = 0,
        profile: PaceProfile = PaceProfile.NATURAL
    ): List<TrackPoint> {
        require(poly.size >= 3) { "区域多边形至少需要 3 个顶点" }
        val fence = dedupe(poly)

        // 采样点数只由配速和距离唯一确定 —— 时长是硬约束，形态在这之内的自由分配。
        val nomSpeed = 1000.0 / paceSecKm
        val n = maxOf(2, Math.round(targetM / nomSpeed).toInt())
        val weights = stepWeights(n, profile, seed)

        // 逐级向内缩放，挑选"围栏内最多 + 禁跑区最少"的一版
        var best: List<TrackPoint> = emptyList()
        var bestScore = -1.0

        for (f in doubleArrayOf(0.90, 0.85, 0.80, 0.74, 0.66, 0.58, 0.50)) {
            val ring = organic(inset(fence, f), 3.0, seed)
            val pts = build(ring, n, weights, targetM, noise, seed, fence)
            val inside = pts.count { Geo.pointInPoly(it.lat, it.lon, fence) }
            val banned = pts.count { p -> excludes.any { Geo.pointInPoly(p.lat, p.lon, it) } }
            val score = (inside - banned).toDouble() / pts.size
            if (score > bestScore) {
                bestScore = score
                best = pts
            }
            if (banned == 0 && inside == pts.size) break
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

    // ------------------------------------------------------------ 配速形态

    /**
     * 逐秒的"速度权重"。均值恒为 1，所以整体配速仍然锚在目标上；
     * 它只负责把匀速拆成有快有慢。
     *
     * 真实跑者身上有三种时间尺度叠加：
     *   · 疲劳（整场，单向变慢）
     *   · 地形 / 路况（一两分钟量级）
     *   · 步频波动（十几秒量级）
     * 这里按同样的层次叠，再乘性归一化。
     */
    private fun stepWeights(n: Int, profile: PaceProfile, seed: Int): DoubleArray {
        val slow = slownessProfile(n, profile, seed)
        if (n < 24) return DoubleArray(n) { 1.0 }

        // 地形 / 路况：分钟量级
        val terrain = randomWalkSegments(n, 260, 0.06, seed, 1)
        // 步频波动：十几秒量级
        val jitter = randomWalkSegments(n, 11, 0.045, seed, 2)

        val w = DoubleArray(n)
        for (i in 0 until n) w[i] = 1.0 / (slow[i] * terrain[i] * jitter[i])

        // 归一化：均值 = 1。标定只负责把总距离校准到 targetM，
        // 形态不参与配速计算，因此不会互相干扰。
        val mean = w.average()
        if (mean > 0) for (i in 0 until n) w[i] /= mean
        return w
    }

    /**
     * "慢"的倍数：1.0 = 目标配速，越大越慢，22 ≈ 停下来系鞋带。
     */
    private fun slownessProfile(n: Int, profile: PaceProfile, seed: Int): DoubleArray {
        val out = DoubleArray(n) { 1.0 }
        if (profile == PaceProfile.STEADY) return out

        val rnd = Random(seed * 7919 + 13)

        // 配速段：每 40~70 秒换一档，模拟"这一段跑快些、那一段拖着"
        val segLen = 40 + rnd.nextInt(31)
        var i = 0
        while (i < n) {
            val end = min(n, i + segLen)
            val f = 0.85 + rnd.nextDouble() * 0.60          // 0.85 ~ 1.45
            for (k in i until end) out[k] = f
            i = end
        }

        for (k in 0 until n) {
            // 起跑要加速到位
            if (k < 25) out[k] *= 1.08
            // 尾声通常会冲一下
            if (k >= n - 25) out[k] *= 0.94
            // 疲劳：越到后面越慢
            out[k] *= 1.0 + 0.10 * k / n
        }

        if (profile == PaceProfile.REALISTIC) {
            // 停顿：约 7% 的时间点落在里面，2 公里大概是 2~3 次
            val pauses = (n * 0.07 / 6.0).toInt().coerceIn(1, 5)
            repeat(pauses) {
                val len = 4 + rnd.nextInt(6)                 // 4 ~ 9 秒
                val span = maxOf(1, n - len - 1)
                val at = (span * (0.12 + rnd.nextDouble() * 0.76)).toInt()
                for (k in at until min(n, at + len)) out[k] = 22.0
            }
        }
        return out
    }

    /**
     * 分段常数的乘性扰动：每段随机长度、随机幅度。
     *
     * 用它而不是逐点白噪声，是因为真实的速度变化是**成簇**的
     * （一段路跑快、一段路跑慢），逐点独立抖动看起来反而像仪器噪声。
     */
    private fun randomWalkSegments(
        n: Int, segLen: Int, jitter: Double, seed: Int, salt: Int
    ): DoubleArray {
        val rnd = Random(seed * 131 + salt * 977 + 17)
        val out = DoubleArray(n) { 1.0 }
        var i = 0
        while (i < n) {
            val g = maxOf(1, segLen / 2 + rnd.nextInt(segLen + 1))
            val v = 1.0 + rnd.nextDouble(-jitter, jitter)
            val end = min(n, i + g)
            for (k in i until end) out[k] = v
            i = end
        }
        return out
    }

    // ------------------------------------------------------------ 环线

    /** 向内缩放：每个顶点朝质心方向按比例靠拢。 */
    private fun inset(poly: List<DoubleArray>, f: Double): List<DoubleArray> {
        val cLat = poly.map { it[0] }.average()
        val cLon = poly.map { it[1] }.average()
        return poly.map {
            doubleArrayOf(cLat + (it[0] - cLat) * f, cLon + (it[1] - cLon) * f)
        }
    }

    /**
     * 把落到围栏外的点拉回围栏内 —— 沿"该点 → 质心"的连线二分逼近边界。
     *
     * 为什么需要它：内缩系数是离散的档位（0.90 / 0.85 / …），而 GPS 噪声
     * 会把点横向推出十几米。某些 seed / 配速组合下，档位挑得再准也总有几个点
     * 被噪声顶到围栏外（实测风华运动场 330s/km + seed=555 会有 2 个点出界）。
     * 出界的点服务端不计里程，于是"实测配速"就会被拉偏，达标判定跟着遭殃。
     *
     * 这里只动越界的那几个点，而且位移极小（刚好回到边界内侧），
     * 对整体形态和配速标定的影响可以忽略。
     */
    private fun clampToFence(
        lat: Double,
        lon: Double,
        fence: List<DoubleArray>
    ): DoubleArray {
        if (fence.size < 3) return doubleArrayOf(lat, lon)
        if (Geo.pointInPoly(lat, lon, fence)) return doubleArrayOf(lat, lon)

        val cLat = fence.map { it[0] }.average()
        val cLon = fence.map { it[1] }.average()
        // 质心本身都不在围栏里（凹多边形）就没法这么拉，原样返回
        if (!Geo.pointInPoly(cLat, cLon, fence)) return doubleArrayOf(lat, lon)

        // 二分：t=0 在质心（内），t=1 是原点（外），找最大的"仍在内部"的 t
        var lo = 0.0
        var hi = 1.0
        repeat(24) {
            val mid = (lo + hi) / 2.0
            val mlat = cLat + (lat - cLat) * mid
            val mlon = cLon + (lon - cLon) * mid
            if (Geo.pointInPoly(mlat, mlon, fence)) lo = mid else hi = mid
        }
        // 往回让一丁点，避免正好压在边界线上被判出界
        val t = (lo * 0.995).coerceIn(0.0, 1.0)
        return doubleArrayOf(cLat + (lat - cLat) * t, cLon + (lon - cLon) * t)
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

    // ------------------------------------------------------------ 采样

    /**
     * 固定点数 n，按权重把每一秒该走的路程铺开，再叠加相关噪声。
     *
     * 同一个 seed 每次都重现同一噪声实现，因此 measure 是关于 step 的
     * 确定性单调递增函数，可以直接二分。
     */
    private fun build(
        ring: List<DoubleArray>,
        n: Int,
        weights: DoubleArray,
        targetM: Double,
        noise: Double,
        seed: Int,
        fence: List<DoubleArray> = emptyList()
    ): List<TrackPoint> {
        val (dense, cum) = densify(ring, 1.0)

        fun emit(step: Double): List<TrackPoint> {
            val rnd = Random(seed)
            val rndAcc = Random(seed * 31 + 7)
            var perr = 0.0
            var aerr = 0.0
            var travelled = 0.0

            // 第一遍：先只按权重铺出真实坐标
            val lat = DoubleArray(n)
            val lon = DoubleArray(n)
            val pew = DoubleArray(n)
            for (i in 0 until n) {
                val (p, hdg) = pointAt(dense, cum, travelled)
                perr = 0.9 * perr + rnd.nextDouble(-1.0, 1.0) * noise * 0.55
                aerr = 0.9 * aerr + rnd.nextDouble(-1.0, 1.0) * noise * 0.35
                val o1 = Geo.offset(p[0], p[1], perr, (hdg + 90.0) % 360.0)
                val o2 = Geo.offset(o1[0], o1[1], aerr, hdg)
                val c = clampToFence(o2[0], o2[1], fence)
                lat[i] = c[0]
                lon[i] = c[1]
                pew[i] = perr
                travelled += step * weights[i]
            }

            // 第二遍：speed / bearing 从**最终坐标**反推。
            // 这样上报的字段和轨迹严格自洽，不会出现"速度写着 3.0 但两点间距 1.2m"。
            val out = ArrayList<TrackPoint>(n)
            for (i in 0 until n) {
                val j = if (i < n - 1) i + 1 else i - 1
                val seg = Geo.hav(lat[i], lon[i], lat[j], lon[j])
                val brg = if (i < n - 1) Geo.bearing(lat[i], lon[i], lat[j], lon[j])
                else if (n > 1) Geo.bearing(lat[j], lon[j], lat[i], lon[i]) else 0.0

                // 精度：多数时候 4~8m，背阴 / 贴楼时偶尔跳到十几米
                val acc = 4.5 + abs(pew[i]) * 0.45 + rndAcc.nextDouble(-1.0, 2.2)

                out.add(
                    TrackPoint(
                        lat = lat[i],
                        lon = lon[i],
                        secOffset = i,
                        speed = Math.round(seg * 100.0) / 100.0,
                        bearing = Math.round(brg * 10.0) / 10.0,
                        accuracy = Math.round(acc.coerceIn(3.0, 18.0) * 10.0) / 10.0
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
        var hi = 5.0                       // 5 m/s 起步，2000m 的量级足够越过目标
        while (measure(emit(hi)) < targetM && hi < 1e5) hi *= 1.5
        repeat(44) {
            val mid = (lo + hi) / 2.0
            if (measure(emit(mid)) < targetM) lo = mid else hi = mid
        }
        return emit((lo + hi) / 2.0)
    }

    // ------------------------------------------------------------ 统计

    /**
     * 结果统计。配速区间按 15 秒滚动窗口算 —— 逐秒的瞬时配速在停顿处会趋于无穷，
     * 直接取极值没有意义，滚动窗口才对应"跑者感受到的配速"。
     */
    fun summarize(pts: List<TrackPoint>): String {
        var d = 0.0
        for (i in 0 until pts.size - 1) d += Geo.hav(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        val dur = pts.size - 1
        val pace = if (d > 0) dur / (d / 1000.0) else 0.0

        val (lo, hi) = windowPaceRange(pts, 15)
        val spread = if (lo > 0 && hi > 0) " | 分段配速 ${fmtPace(lo)}~${fmtPace(hi)}" else ""
        return "点数 ${pts.size} | 距离 ${"%.1f".format(d)} m | 时长 ${dur / 60}分${dur % 60}秒 | " +
                "实测配速 ${fmtPace(pace)}$spread"
    }

    /** 15 秒滚动窗口的配速 (最快, 最慢)，秒/公里。 */
    private fun windowPaceRange(pts: List<TrackPoint>, win: Int): Pair<Double, Double> {
        if (pts.size <= win + 1) return Pair(0.0, 0.0)
        var fastest = Double.MAX_VALUE
        var slowest = 0.0
        for (i in 0..pts.size - 1 - win) {
            var seg = 0.0
            for (k in i until i + win) {
                seg += Geo.hav(pts[k].lat, pts[k].lon, pts[k + 1].lat, pts[k + 1].lon)
            }
            if (seg <= 0.1) continue
            val p = win / (seg / 1000.0)
            if (p < fastest) fastest = p
            if (p > slowest) slowest = p
        }
        if (fastest == Double.MAX_VALUE) return Pair(0.0, 0.0)
        return Pair(fastest, slowest)
    }

    fun fmtPace(secPerKm: Double): String {
        if (secPerKm <= 0 || secPerKm > 3600) return "--"
        val m = (secPerKm / 60).toInt()
        val s = (secPerKm % 60).toInt()
        return "$m'${"%02d".format(s)}\""
    }
}
