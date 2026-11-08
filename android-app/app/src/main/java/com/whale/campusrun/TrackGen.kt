/*
 * 轨迹生成：配速标定 / 电子围栏内缩 / 禁跑区避让 / 多尺度 GPS 噪声 / 毛刺突起 / 温和起伏
 * 作者：蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
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

/** 一次轨迹生成的结果，连同实测配速一起返回，方便界面说明"有没有被钳制"。 */
data class TrackResult(
    val points: List<TrackPoint>,
    /** 实测平均配速，秒/公里。 */
    val paceSecKm: Double,
    /** 目标配速加减容差得到的允许窗口。 */
    val window: ClosedFloatingPointRange<Double>,
    /** 采样点数是否为了压进窗口而被调整过。 */
    val adjusted: Boolean
) {
    val inWindow: Boolean get() = paceSecKm in window
}

/**
 * 轨迹生成器。
 *
 * 输入是从服务器拉下来的区域多边形（gcj02），输出是一条落在多边形内、
 * 配速精确的闭环轨迹。
 *
 * 两套路径：
 *  · `burrs = false`（默认，「轨迹毛刺」开关关闭）：`generateSmooth()`，
 *    保留 v1.10.1 的光滑轨迹
 *  · `burrs = true`（开关打开）：下面的多圈错位 + 毛刺 / 突起
 *
 * 毛刺模式的关键点：
 *  1. 环线由多边形向内缩放得到，先用「基准环是否合法」挑内缩比例，
 *     再叠多条整数圈正弦 + 局部凸起。整数圈保证环线闭合，凸起保证每一圈
 *     都不会像同一个模子刻出来的
 *  2. 采样点数由「配速 × 距离」锁死，二分反解地面速度，
 *     使**实测配速等于目标配速**（否则噪声会把距离撑长十几个百分点）
 *  3. 四层时间尺度的 AR(1) 噪声：几分钟的贴线漂移、几秒的摆臂晃动、
 *     一秒级的毛糙。它让每一圈都能错开几米，叠成真实 App 上那种"粗线"
 *  4. 稀疏的野点：一秒级的尖刺、两三秒的锯齿、几秒的三角形绕行，
 *     模拟多径 / 树荫 / 贴楼时 GPS 的突发跳点 —— 这就是图上的"毛刺/突起"
 *  5. 越界不硬拽：按「基准点 → 噪声点」方向二分回缩，只削掉越界的那一小段，
 *     保住毛刺的形状，同时保证 100% 在围栏内、绝不进禁跑区
 *  6. 逐秒速度带**温和起伏**（起步慢、中途数段快慢、越跑越累、尾声略冲，
 *     并叠入少量短暂停顿），**平均值仍锁死在目标配速上**
 *  7. 生成后校验平均配速是否落在「目标 ± 容差」内，越界就按比例微调采样点数重算
 */
object TrackGen {

    /** 平均配速相对目标值允许偏离多少秒/公里。 */
    const val DEFAULT_PACE_TOLERANCE_SEC = 20.0

    /** 最多重算几次来把平均配速压进窗口。 */
    private const val MAX_PACE_FIX_ATTEMPTS = 4

    /** 环线相对围栏的内缩比例：从外向里试，取第一个基准环完全合法的。 */
    private val RING_INSETS = doubleArrayOf(
        0.90, 0.88, 0.86, 0.84, 0.81, 0.77, 0.72, 0.66, 0.59, 0.51
    )

    /** 环线有机摆动的基准幅度（米）。 */
    private const val RING_WOBBLE_M = 4.5

    /** 环线加密步长（米）：太稀磨不出来，太密纯浪费时间。 */
    private const val RING_STEP_M = 7.0

    /**
     * AR(1) 噪声的相关系数与强度（强度再乘 noise）。
     * 0.997 ≈ 几分钟级（每一圈的横向错位），0.97 / 0.85 ≈ 十几秒，
     * 0.55 ≈ 一秒级毛糙。分开叠而不是一个系数，才能既有大漂移又有小锯齿。
     */
    private val NOISE_RHO = doubleArrayOf(0.997, 0.970, 0.850, 0.550)
    private val NOISE_SIGMA = doubleArrayOf(0.50, 0.27, 0.27, 0.33)

    /**
     * @param poly             电子围栏多边形（dzwl，gcj02），元素 [lat, lon]
     * @param excludes         禁跑区多边形（jpq），轨迹必须完全避开
     * @param paceSecKm        目标配速，秒/公里
     * @param targetM          目标距离，米
     * @param noise            逐秒抖动强度，0.6 ≈ 开阔地手机滤波后的表现
     * @param seed             随机种子
     * @param paceToleranceSec 平均配速容差（秒/公里）
     * @param burrs            是否叠加毛刺 / 突起。App 里由「轨迹毛刺」开关控制，
     *                         默认关闭 = 走 v1.10.1 的光滑轨迹
     */
    fun generate(
        poly: List<DoubleArray>,
        excludes: List<List<DoubleArray>> = emptyList(),
        paceSecKm: Double,
        targetM: Double,
        noise: Double = 0.6,
        seed: Int = 0,
        paceToleranceSec: Double = DEFAULT_PACE_TOLERANCE_SEC,
        burrs: Boolean = false
    ): TrackResult {
        require(poly.size >= 3) { "区域多边形至少需要 3 个顶点" }
        require(paceSecKm > 0) { "配速必须为正数" }

        val fence = dedupe(poly)
        val bans = excludes.map { dedupe(it) }.filter { it.size >= 3 }
        val window = paceWindow(paceSecKm, paceToleranceSec)

        // 开关关闭：保持 v1.10.1 的光滑轨迹，一条线、没有多圈错位和野点。
        if (!burrs) return generateSmooth(fence, bans, paceSecKm, targetM, noise, seed, window)

        // 开关打开：多圈错位 + 毛刺 / 突起。
        // 先把环线定下来：基准环本身必须落得进去，噪声才有地方伸胳膊踢腿。
        val ring = chooseRing(fence, bans, seed)

        // 采样点数由「配速 × 距离」唯一确定（时长是硬约束）
        var n = maxOf(2, Math.round(targetM / (1000.0 / paceSecKm)).toInt())
        var adjusted = false
        var pts = emptyList<TrackPoint>()
        var pace = 0.0

        for (attempt in 0 until MAX_PACE_FIX_ATTEMPTS) {
            val weights = stepWeights(n, seed)
            val spurs = makeSpurs(n, seed, (noise / 0.6).coerceIn(0.0, 2.5))
            pts = build(ring, n, weights, spurs, targetM, noise, seed, fence, bans)
            pace = paceOf(pts)
            if (pace in window) break

            // 飘出窗口：按配速比例反推该用多少秒。配速偏慢（数值偏大）
            // 说明秒数多了，要减点；反之加点。
            val ratio = pace / paceSecKm
            val next = Math.round(n / ratio).toInt().coerceIn(2, n * 4 + 8)
            if (next == n) break
            n = next
            adjusted = true
        }

        return TrackResult(pts, pace, window, adjusted)
    }

    /** 平均配速允许窗口，单位秒/公里。 */
    fun paceWindow(paceSecKm: Double, tol: Double): ClosedFloatingPointRange<Double> {
        val t = abs(tol)
        return (paceSecKm - t).coerceAtLeast(60.0)..(paceSecKm + t)
    }

    /** 轨迹的实测平均配速（秒/公里）。时长按采样点跨度算，与 collectTime 一致。 */
    fun paceOf(pts: List<TrackPoint>): Double {
        if (pts.size < 2) return 0.0
        val d = distanceOf(pts)
        return if (d > 0) (pts.size - 1) / (d / 1000.0) else 0.0
    }

    /** 轨迹总距离（米）。 */
    fun distanceOf(pts: List<TrackPoint>): Double {
        var s = 0.0
        for (i in 0 until pts.size - 1) {
            s += Geo.hav(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        }
        return s
    }

    /**
     * 选一圈基准环。
     *
     * 以前是「先铺满噪声、再看落进去多少点」逐档试内缩，但采样阶段会把越界点
     * 硬拽回来，于是每一档看起来都"全部落进去了"，永远停在第一档，实际却把
     * 毛刺压平在围栏边。现在先检查未加噪声的内缩环本身：完全落进围栏、且不碰
     * 禁跑区才用它；不合适就往里缩一档。环线合法了，后面的噪声才敢往外冒。
     *
     * 万一所有档位都非法（大面积禁跑区 / 凹围栏），退回到违例最少的一档，
     * 采样阶段再做逐点回缩兜底。
     */
    private fun chooseRing(
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>,
        seed: Int
    ): List<DoubleArray> {
        var fallback: List<DoubleArray> = emptyList()
        var fallbackBad = Int.MAX_VALUE

        for (f in RING_INSETS) {
            val raw = inset(fence, f)
            val bad = badPoints(raw, fence, excludes)
            if (bad < fallbackBad) {
                fallbackBad = bad
                fallback = raw
            }
            if (bad == 0) return organic(raw, RING_WOBBLE_M, seed, fence, excludes)
        }
        return organic(fallback, RING_WOBBLE_M, seed, fence, excludes)
    }

    /** 多边形的边界（按 7m 加密）有多少点掉出围栏或压进禁跑区。 */
    private fun badPoints(
        poly: List<DoubleArray>,
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>
    ): Int {
        var bad = 0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val d = Geo.hav(a[0], a[1], b[0], b[1])
            val k = maxOf(1, Math.ceil(d / RING_STEP_M).toInt())
            for (j in 0 until k) {
                val t = j.toDouble() / k
                val lat = a[0] + (b[0] - a[0]) * t
                val lon = a[1] + (b[1] - a[1]) * t
                if (!isValid(lat, lon, fence, excludes)) bad++
            }
        }
        return bad
    }

    /** 点是否同时满足「在围栏内」和「不在任何禁跑区」。 */
    private fun isValid(
        lat: Double,
        lon: Double,
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>
    ): Boolean {
        if (!Geo.pointInPoly(lat, lon, fence)) return false
        for (e in excludes) if (Geo.pointInPoly(lat, lon, e)) return false
        return true
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
     * 三层时间尺度叠加：
     *   · 疲劳（整场，单向变慢）
     *   · 地形 / 路况（一两分钟量级）
     *   · 步频波动（十几秒量级）—— 纯模型噪声，与传感器无关
     */
    private fun stepWeights(n: Int, seed: Int): DoubleArray {
        if (n < 24) return DoubleArray(n) { 1.0 }

        val slow = slownessProfile(n, seed)
        val terrain = randomWalkSegments(n, 260, 0.06, seed, 1)
        val jitter = randomWalkSegments(n, 11, 0.045, seed, 2)

        val w = DoubleArray(n)
        for (i in 0 until n) w[i] = 1.0 / (slow[i] * terrain[i] * jitter[i])

        // 归一化：均值 = 1。标定只负责把总距离校准到 targetM，
        // 形态不参与配速计算，因此两者不会互相干扰。
        val mean = w.average()
        if (mean > 0) for (i in 0 until n) w[i] /= mean
        return w
    }

    /**
     * "慢"的倍数：1.0 = 目标配速，越大越慢，22 ≈ 停下来系鞋带。
     *
     * 这是"温和起伏"：起步慢、中途数段快慢、越跑越累、尾声略冲，
     * 并叠入少量短暂停顿。全程不停的那种匀速跑是不存在的。
     */
    private fun slownessProfile(n: Int, seed: Int): DoubleArray {
        val out = DoubleArray(n) { 1.0 }
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

        // 停顿：约 7% 的时间点落在里面，2 公里大概是 2~3 次
        val pauses = (n * 0.07 / 6.0).toInt().coerceIn(1, 5)
        repeat(pauses) {
            val len = 4 + rnd.nextInt(6)                     // 4 ~ 9 秒
            val span = maxOf(1, n - len - 1)
            val at = (span * (0.12 + rnd.nextDouble() * 0.76)).toInt()
            for (k in at until min(n, at + len)) out[k] = 22.0
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
     * 一眼机器画的。这里叠三类扰动：
     *   1. 六条整数圈正弦（k = 2/3/5/7/11/17）。整数圈保证绕一圈后首尾自动闭合，
     *      高频项负责把长直边揉出小波浪
     *   2. 三到五处局部鼓包：位置随机、宽度 10~38m、幅度 ±2~6m，
     *      对应真实跑者贴内道 / 绕人 / 抄近道留下的不对称
     *   3. 一轮循环滑动平均，只磨掉最尖的折角，不用磨平上面的高频细节
     *
     * 鼓包和正弦可能把某几个点顶出围栏，这里立刻沿"该点 → 未扰动基准点"
     * 二分收回来（只收回越界的那一小段），所以环线本身 100% 合法。
     */
    private fun organic(
        raw: List<DoubleArray>,
        amp: Double,
        seed: Int,
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>
    ): List<DoubleArray> {
        // 加密到 ~7m 一个点，点太少磨不出高频细节
        val dense = ArrayList<DoubleArray>()
        val pts = raw + raw[0]
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val d = Geo.hav(a[0], a[1], b[0], b[1])
            val k = maxOf(1, Math.ceil(d / RING_STEP_M).toInt())
            for (j in 0 until k) {
                val t = j.toDouble() / k
                dense.add(doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            }
        }
        val n = dense.size
        if (n < 16) return raw

        var per = 0.0
        for (i in 0 until n) {
            val a = dense[i]
            val b = dense[(i + 1) % n]
            per += Geo.hav(a[0], a[1], b[0], b[1])
        }
        val stepM = maxOf(1.0, per / n)
        val rnd = Random(seed * 1_000_003 + 97)

        // 整数圈正弦：频率就是圈数，k 越大波长越短
        val harmonic = intArrayOf(2, 3, 5, 7, 11, 17)
        val weight = doubleArrayOf(0.46, 0.34, 0.24, 0.17, 0.12, 0.07)
        val phase = DoubleArray(harmonic.size) { rnd.nextDouble(0.0, 2 * PI) }
        val waveAmp = DoubleArray(harmonic.size) {
            amp * weight[it] * rnd.nextDouble(0.55, 1.25)
        }

        // 局部鼓包：宽度按节点数折算，中心用环上节点下标表示（绕圈取最短距离）
        val bumpCount = 3 + rnd.nextInt(3)          // 3 ~ 5 处
        val bumpCenter = DoubleArray(bumpCount)
        val bumpWidth = DoubleArray(bumpCount)
        val bumpAmp = DoubleArray(bumpCount)
        for (b in 0 until bumpCount) {
            bumpCenter[b] = rnd.nextDouble(0.0, n.toDouble())
            val widthM = rnd.nextDouble(10.0, 38.0)
            bumpWidth[b] = maxOf(1.0, widthM / stepM)
            bumpAmp[b] = rnd.nextDouble(2.0, 6.2) *
                    if (rnd.nextBoolean()) 1.0 else -1.0
        }

        var cur = ArrayList<DoubleArray>(n)
        for (i in 0 until n) {
            val prev = dense[(i - 1 + n) % n]
            val next = dense[(i + 1) % n]
            val hdg = Geo.bearing(prev[0], prev[1], next[0], next[1])
            val u = i.toDouble() / n * 2 * PI

            var off = 0.0
            for (k in harmonic.indices) off += waveAmp[k] * sin(harmonic[k] * u + phase[k])
            for (b in 0 until bumpCount) {
                var di = abs(i - bumpCenter[b])
                di = min(di, n - di)
                off += bumpAmp[b] * exp(-0.5 * (di / bumpWidth[b]).pow(2))
            }
            cur.add(Geo.offset(dense[i][0], dense[i][1], off, (hdg + 90.0) % 360.0))
        }

        // 只磨一遍、窗口 2：再磨就回到"光滑椭圆"了
        val w = 2
        val smooth = ArrayList<DoubleArray>(n)
        for (i in 0 until n) {
            var sla = 0.0
            var slo = 0.0
            for (k in -w..w) {
                val q = cur[(i + k + n) % n]
                sla += q[0]
                slo += q[1]
            }
            smooth.add(doubleArrayOf(sla / (2 * w + 1), slo / (2 * w + 1)))
        }

        // 收掉顶出围栏 / 碰进禁跑区的少数点
        val out = ArrayList<DoubleArray>(n)
        for (i in 0 until n) {
            val p = smooth[i]
            out.add(
                if (isValid(p[0], p[1], fence, excludes)) {
                    p
                } else {
                    pullToward(dense[i], p, fence, excludes)
                }
            )
        }
        return out
    }

    /**
     * 沿 base → p 连线二分，找最大的、仍然合法的内插点。
     *
     * 和 clampToFence（拉向质心）的区别：这里只收"扰动"本身，不动基准环。
     * 所以毛刺被沿着它自己的方向削短，而不是被抹平在围栏边。
     */
    private fun pullToward(
        base: DoubleArray,
        p: DoubleArray,
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>
    ): DoubleArray {
        if (!isValid(base[0], base[1], fence, excludes)) return p
        var lo = 0.0
        var hi = 1.0
        repeat(22) {
            val mid = (lo + hi) / 2.0
            val mlat = base[0] + (p[0] - base[0]) * mid
            val mlon = base[1] + (p[1] - base[1]) * mid
            if (isValid(mlat, mlon, fence, excludes)) lo = mid else hi = mid
        }
        val t = (lo * 0.99).coerceIn(0.0, 1.0)
        return doubleArrayOf(base[0] + (p[0] - base[0]) * t, base[1] + (p[1] - base[1]) * t)
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

    // ------------------------------------------------------------ 毛刺 / 突起

    /** 逐秒的额外横向 / 纵向偏移（米），叠加在环线的局部坐标系上。 */
    private class Spurs(val lateral: DoubleArray, val forward: DoubleArray)

    /**
     * 稀疏野点：模拟真实 GPS 在多径、树荫、贴楼时突然"跳一下"。
     *
     * 四档叠加，尺度都按总时长换算，所以 2km 和 5km 的密度一致：
     *   · 小毛刺：1 个点、0.8~2.6m，约每 15 秒一次 —— 图上线边那一圈碎锯齿
     *   · 硬毛刺：1~2 个点、3~8m，约每 45 秒一次 —— 明显的尖角和分叉
     *   · 绕行包：4~9 个点、5~11m，约每 3 分钟一次 —— 大三角 / 半岛状突起
     *   · 大跳：1 个点、9~16m，整场 1~2 次 —— 偶尔出现的长刺，最像"GPS 抽风"
     *
     * 全部只依赖下标和 seed，和 step 无关。这样二分标定时 measure(step) 仍然单调，
     * 配速标定不会因为野点的位置漂移而失准。
     */
    private fun makeSpurs(n: Int, seed: Int, intensity: Double): Spurs {
        val lateral = DoubleArray(n)
        val forward = DoubleArray(n)
        if (n < 80) return Spurs(lateral, forward)

        val rnd = Random(seed * 1_000_003 + 77)
        val used = ArrayList<Int>()

        fun free(at: Int, gap: Int): Boolean {
            for (u in used) if (abs(u - at) < gap) return false
            return true
        }

        val lo = maxOf(2, (n * 0.04).toInt())
        val hi = minOf(n - 4, (n * 0.96).toInt())

        // 大跳：先占位，免得被密集的小毛刺挤掉
        repeat(maxOf(1, n / 450)) {
            var tries = 0
            while (tries++ < 60) {
                val at = rnd.nextInt(lo, hi)
                if (!free(at, 14)) continue
                used.add(at)
                val a = rnd.nextDouble(9.0, 16.0) * intensity * if (rnd.nextBoolean()) 1.0 else -1.0
                lateral[at] = a
                forward[at] = a * rnd.nextDouble(-0.25, 0.25)
                break
            }
        }

        // 大绕行包：一两处，走成一个不对称的三角形
        repeat(maxOf(2, n / 230)) {
            var tries = 0
            while (tries++ < 80) {
                val at = rnd.nextInt(lo, hi)
                val span = rnd.nextInt(4, 10)          // 4 ~ 9 秒
                if (at + span >= n || !free(at, span + 10)) continue
                used.add(at)
                val a = rnd.nextDouble(5.0, 11.0) * intensity * if (rnd.nextBoolean()) 1.0 else -1.0
                val fwdShare = rnd.nextDouble(-0.25, 0.25)
                for (k in 0..span) {
                    val s = k.toDouble() / span
                    lateral[at + k] = a * sin(PI * s)
                    forward[at + k] = a * fwdShare * sin(2 * PI * s)
                }
                break
            }
        }

        // 硬毛刺：一秒尖刺 / 两秒来回 / 两三秒的小三角
        repeat(maxOf(4, n / 75)) {
            var tries = 0
            while (tries++ < 80) {
                val at = rnd.nextInt(lo, hi)
                if (!free(at, 8)) continue
                used.add(at)
                val a = rnd.nextDouble(3.0, 8.0) * intensity
                val sign = if (rnd.nextBoolean()) 1.0 else -1.0
                val share = rnd.nextDouble(-0.35, 0.35)
                val shape = rnd.nextDouble()
                if (shape < 0.45) {
                    lateral[at] = a * sign
                    forward[at] = a * share
                } else if (shape < 0.72) {
                    lateral[at] = a * sign
                    forward[at] = a * share
                    lateral[at + 1] = if (rnd.nextBoolean()) -a * 0.55 * sign else a * 0.3 * sign
                } else {
                    val span = rnd.nextInt(2, 5)          // 2 ~ 4 秒
                    val peak = rnd.nextInt(1, span)
                    for (k in 0..span) {
                        val denom = maxOf(1.0, (if (k <= peak) peak else span - peak).toDouble())
                        val frac = (1.0 - abs(k - peak) / denom).coerceAtLeast(0.0)
                        lateral[at + k] = a * 1.2 * sign * frac
                    }
                }
                break
            }
        }

        // 小毛刺：频率高、幅度小，负责把线边揉毛
        repeat(maxOf(6, n / 17)) {
            var tries = 0
            while (tries++ < 60) {
                val at = rnd.nextInt(2, n - 2)
                if (!free(at, 4)) continue
                used.add(at)
                val a = rnd.nextDouble(0.8, 2.6) * intensity * if (rnd.nextBoolean()) 1.0 else -1.0
                lateral[at] = a
                forward[at] = rnd.nextDouble(-0.5, 0.5) * a * 0.4
                break
            }
        }
        return Spurs(lateral, forward)
    }

    // ------------------------------------------------------------ 采样

    /**
     * 把噪声点收回到合法范围，但只收噪声那一截。
     *
     * 真实 GPS 漂到围栏外时，App 并不会把轨迹裁成边界线，它只是下一个点又回到
     * 场内 —— 所以这里沿"基准点 → 噪声点"方向二分，保留毛刺的方向，只削减长度。
     *
     * 基准点本身就不合法（凹围栏 / 极端禁跑区）时退回 clampToFence，保证不出界。
     */
    private fun tolerate(
        base: DoubleArray,
        q: DoubleArray,
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>
    ): DoubleArray {
        if (isValid(q[0], q[1], fence, excludes)) return q
        if (!isValid(base[0], base[1], fence, excludes)) {
            return clampToFence(q[0], q[1], fence)
        }

        var lo = 0.0
        var hi = 1.0
        repeat(22) {
            val mid = (lo + hi) / 2.0
            val mlat = base[0] + (q[0] - base[0]) * mid
            val mlon = base[1] + (q[1] - base[1]) * mid
            if (isValid(mlat, mlon, fence, excludes)) lo = mid else hi = mid
        }
        val t = (lo * 0.99).coerceIn(0.0, 1.0)
        return doubleArrayOf(base[0] + (q[0] - base[0]) * t, base[1] + (q[1] - base[1]) * t)
    }

    /**
     * 固定点数 n，按权重把每一秒该走的路程铺开，再叠加四层相关噪声和稀疏野点。
     *
     * 同一个 seed 每次都重现同一噪声实现，且野点位置只跟下标有关，
     * 因此 measure 关于 step 仍近似单调，可以直接二分。
     */
    private fun build(
        ring: List<DoubleArray>,
        n: Int,
        weights: DoubleArray,
        spurs: Spurs,
        targetM: Double,
        noise: Double,
        seed: Int,
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>
    ): List<TrackPoint> {
        val (dense, cum) = densify(ring, 1.0)
        val levels = NOISE_RHO.size

        fun emit(step: Double): List<TrackPoint> {
            val rnd = Random(seed)
            val rndAcc = Random(seed * 31 + 7)
            val perr = DoubleArray(levels)
            val aerr = DoubleArray(levels)
            var travelled = 0.0

            // 第一遍：先只按权重铺出真实坐标
            val lat = DoubleArray(n)
            val lon = DoubleArray(n)
            val pew = DoubleArray(n)
            for (i in 0 until n) {
                val (p, hdg) = pointAt(dense, cum, travelled)

                // 野点是"事件"，AR 噪声是"底噪"，先加野点再加底噪
                var perrT = spurs.lateral[i]
                var aerrT = spurs.forward[i]
                for (c in 0 until levels) {
                    perr[c] = NOISE_RHO[c] * perr[c] +
                            rnd.nextDouble(-1.0, 1.0) * noise * NOISE_SIGMA[c]
                    aerr[c] = NOISE_RHO[c] * aerr[c] +
                            rnd.nextDouble(-1.0, 1.0) * noise * NOISE_SIGMA[c]
                    perrT += perr[c]
                    aerrT += aerr[c] * 0.45
                }

                val o1 = Geo.offset(p[0], p[1], perrT, (hdg + 90.0) % 360.0)
                val q = Geo.offset(o1[0], o1[1], aerrT, hdg)
                val c = tolerate(p, q, fence, excludes)
                lat[i] = c[0]
                lon[i] = c[1]
                pew[i] = perrT
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
                val acc = 4.5 + abs(pew[i]) * 0.30 + rndAcc.nextDouble(-1.0, 2.2)

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

        fun measure(pts: List<TrackPoint>): Double = distanceOf(pts)

        // 噪声按秒注入、与步长无关，会把实测距离撑长。二分反解地面步长，
        // 让输出轨迹的实测距离 == targetM，配速才准。
        if (measure(emit(0.0)) >= targetM) return emit(0.0)

        var lo = 0.0
        var hi = 5.0                       // 5 m/s 起步，2000m 的量级足够越过目标
        while (measure(emit(hi)) < targetM && hi < 1e5) hi *= 1.5
        repeat(46) {
            val mid = (lo + hi) / 2.0
            if (measure(emit(mid)) < targetM) lo = mid else hi = mid
        }
        return emit((lo + hi) / 2.0)
    }

    // ------------------------------------------------------------ 光滑模式（v1.10.1 兼容）

    /**
     * 「轨迹毛刺」开关关闭时的轨迹。
     *
     * 完全保留 v1.10.1 的算法：两条低频正弦 + 两轮磨角，
     * 单个 AR(1) 噪声，不复用上面的多尺度噪声 / 野点 / 环线自检。
     * 这样用户关掉开关看到的就是熟悉的光滑轨迹，而不是"少了一点毛刺"的半成品。
     */
    private fun generateSmooth(
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>,
        paceSecKm: Double,
        targetM: Double,
        noise: Double,
        seed: Int,
        window: ClosedFloatingPointRange<Double>
    ): TrackResult {
        // 采样点数由「配速 × 距离」唯一确定（时长是硬约束）
        var n = maxOf(2, Math.round(targetM / (1000.0 / paceSecKm)).toInt())
        var adjusted = false
        var pts = emptyList<TrackPoint>()
        var pace = 0.0

        for (attempt in 0 until MAX_PACE_FIX_ATTEMPTS) {
            val weights = stepWeights(n, seed)
            pts = generateForCountSmooth(fence, excludes, n, weights, targetM, noise, seed)
            pace = paceOf(pts)
            if (pace in window) break

            val ratio = pace / paceSecKm
            val next = Math.round(n / ratio).toInt().coerceIn(2, n * 4 + 8)
            if (next == n) break
            n = next
            adjusted = true
        }

        return TrackResult(pts, pace, window, adjusted)
    }

    /**
     * 光滑模式：逐档挑内缩系数 → 铺点 → 返回得分最高的一版。
     * 得分 = （围栏内点数 − 禁跑区点数）/ 总点数。
     */
    private fun generateForCountSmooth(
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>,
        n: Int,
        weights: DoubleArray,
        targetM: Double,
        noise: Double,
        seed: Int
    ): List<TrackPoint> {
        var best: List<TrackPoint> = emptyList()
        var bestScore = -1.0

        for (f in doubleArrayOf(0.90, 0.85, 0.80, 0.74, 0.66, 0.58, 0.50)) {
            val ring = organicSmooth(inset(fence, f), 3.0, seed)
            val pts = buildSmooth(ring, n, weights, targetM, noise, seed, fence)
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

    /**
     * 有机化：把规则多边形磨成"人跑出来"的环线。
     *
     * v1.10.1 的光滑版本：两条整数圈正弦 + 两轮滑动平均，磨成干净的椭圆。
     * 只给「轨迹毛刺」开关关闭时用；开关打开走上面的 organic()。
     */
    private fun organicSmooth(raw: List<DoubleArray>, amp: Double, seed: Int): List<DoubleArray> {
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

    /**
     * 光滑模式：单个 AR(1) 低频漂移，越界点直接 clampToFence 拉回。
     * 保持 v1.10.1 的行为不变，和上面的多尺度 build() 分开。
     */
    private fun buildSmooth(
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

        fun measure(pts: List<TrackPoint>): Double = distanceOf(pts)

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
        val d = distanceOf(pts)
        val dur = pts.size - 1
        val pace = if (d > 0) dur / (d / 1000.0) else 0.0

        val (lo, hi) = windowPaceRange(pts, 15)
        val spread = if (lo > 0 && hi > 0) " | 分段配速 ${fmtPace(lo)}~${fmtPace(hi)}" else ""
        return "点数 ${pts.size} | 距离 ${"%.1f".format(d)} m | 时长 ${dur / 60}分${dur % 60}秒 | " +
                "实测配速 ${fmtPace(pace)}$spread"
    }

    /** 15 秒滚动窗口的配速 (最快, 最慢)，秒/公里。 */
    fun windowPaceRange(pts: List<TrackPoint>, win: Int): Pair<Double, Double> {
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
