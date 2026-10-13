/*
 * TrackGen 单元测试（用服务器真实场地多边形）
 * 作者：蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用服务器真实下发的场地多边形，验证 TrackGen 的行为。
 *
 * 这些多边形是从 /area/getAllArea 原样拿下来的（重邮实测，2026-10-13）。
 * 断言三条硬指标：
 *   1. 实测配速 == 目标配速（误差 ≤ 1 秒）
 *   2. 所有点落在电子围栏（dzwl）内
 *   3. 没有点落在禁跑区（jpq）内
 */
class TrackGenTest {

    /** T1001 风华运动场 电子围栏（20 顶点） */
    private val fengHuaFence = listOf(
        doubleArrayOf(29.532692, 106.608105), doubleArrayOf(29.532871, 106.608176),
        doubleArrayOf(29.533363, 106.608104), doubleArrayOf(29.533592, 106.608107),
        doubleArrayOf(29.533707, 106.607374), doubleArrayOf(29.533713, 106.607166),
        doubleArrayOf(29.533627, 106.607020), doubleArrayOf(29.533197, 106.607033),
        doubleArrayOf(29.532749, 106.607017), doubleArrayOf(29.532289, 106.607043),
        doubleArrayOf(29.532065, 106.607032), doubleArrayOf(29.531956, 106.607445),
        doubleArrayOf(29.531947, 106.607814), doubleArrayOf(29.531952, 106.607986),
        doubleArrayOf(29.532145, 106.608111), doubleArrayOf(29.532466, 106.608101),
        doubleArrayOf(29.532466, 106.608101), doubleArrayOf(29.532692, 106.608105)
    )

    /** T1001 风华运动场 禁跑区（jpq，17 顶点） */
    private val fengHuaJpq = listOf(
        doubleArrayOf(29.532769, 106.607824), doubleArrayOf(29.533107, 106.607843),
        doubleArrayOf(29.533293, 106.607835), doubleArrayOf(29.533417, 106.607734),
        doubleArrayOf(29.533403, 106.607527), doubleArrayOf(29.533345, 106.607373),
        doubleArrayOf(29.533219, 106.607323), doubleArrayOf(29.533012, 106.607303),
        doubleArrayOf(29.532650, 106.607330), doubleArrayOf(29.532462, 106.607380),
        doubleArrayOf(29.532362, 106.607479), doubleArrayOf(29.532323, 106.607624),
        doubleArrayOf(29.532385, 106.607769), doubleArrayOf(29.532528, 106.607824),
        doubleArrayOf(29.532717, 106.607841)
    )

    /** T1005 太极运动场 电子围栏（5 顶点） */
    private val taiJiFence = listOf(
        doubleArrayOf(29.533933, 106.608884), doubleArrayOf(29.531893, 106.608970),
        doubleArrayOf(29.532019, 106.610646), doubleArrayOf(29.533979, 106.610558)
    )

    /** T1014 宁静苑 电子围栏（6 顶点，无禁跑区） */
    private val ningJingFence = listOf(
        doubleArrayOf(29.530657, 106.606709), doubleArrayOf(29.530609, 106.608438),
        doubleArrayOf(29.529696, 106.608317), doubleArrayOf(29.529679, 106.606648),
        doubleArrayOf(29.530394, 106.606416)
    )

    private fun measure(pts: List<TrackPoint>): Double {
        var d = 0.0
        for (i in 0 until pts.size - 1) {
            d += Geo.hav(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        }
        return d
    }

    private fun realPace(pts: List<TrackPoint>): Double {
        val dur = (pts.size - 1).toDouble()
        return dur / (measure(pts) / 1000.0)
    }

    // 学校规则实测：4'00" ~ 7'30" / km
    private val paces = listOf(240.0, 330.0, 345.0, 420.0)

    @Test
    fun `风华运动场 各配速都精确命中`() {
        for (pace in paces) {
            val pts = TrackGen.generate(fengHuaFence, listOf(fengHuaJpq), pace, 2000.0, seed = 12345)
            val d = measure(pts)
            val rp = realPace(pts)
            assertEquals("距离 target=${pace}s/km", 2000.0, d, 2000.0 * 0.005)
            assertTrue("配速 target=${pace}s/km real=${rp}s/km", Math.abs(rp - pace) <= 1.0)
        }
    }

    @Test
    fun `所有点必须落在电子围栏内`() {
        for (pace in paces) {
            val pts = TrackGen.generate(fengHuaFence, listOf(fengHuaJpq), pace, 2000.0, seed = 7)
            val inside = pts.count { Geo.pointInPoly(it.lat, it.lon, fengHuaFence) }
            assertEquals("pace=$pace 有点跑到围栏外", pts.size, inside)
        }
    }

    @Test
    fun `不能有任何点落在禁跑区`() {
        for (pace in paces) {
            val pts = TrackGen.generate(fengHuaFence, listOf(fengHuaJpq), pace, 2000.0, seed = 99)
            val banned = pts.count { Geo.pointInPoly(it.lat, it.lon, fengHuaJpq) }
            assertEquals("pace=$pace 有 $banned 个点落在禁跑区，这部分不计里程", 0, banned)
        }
    }

    @Test
    fun `太极运动场与宁静苑同样达标`() {
        for ((name, fence) in listOf("太极运动场" to taiJiFence, "宁静苑" to ningJingFence)) {
            for (pace in listOf(330.0, 345.0)) {
                val pts = TrackGen.generate(fence, emptyList(), pace, 2000.0, seed = 2026)
                val rp = realPace(pts)
                assertTrue("$name pace=$pace", Math.abs(rp - pace) <= 1.0)
                val inside = pts.count { Geo.pointInPoly(it.lat, it.lon, fence) }
                assertEquals("$name pace=$pace 围栏外有点", pts.size, inside)
            }
        }
    }

    @Test
    fun `轨迹形态要像人跑的，不是机器画的`() {
        val pts = TrackGen.generate(fengHuaFence, listOf(fengHuaJpq), 345.0, 2000.0, seed = 555)

        // 航向不能只有几个固定值（矩形会只有 4 个）
        val headings = pts.map { Math.round(it.bearing) }.toSet()
        assertTrue("航向取值只有 ${headings.size} 个，太规则了", headings.size > 40)

        // 速度不能是恒定的
        val speeds = pts.map { it.speed }
        val span = speeds.max() - speeds.min()
        assertTrue("速度波动只有 $span，太假", span > 0.05)

        // 逐点间距要有自然抖动
        val steps = (0 until pts.size - 1).map {
            Geo.hav(pts[it].lat, pts[it].lon, pts[it + 1].lat, pts[it + 1].lon)
        }
        assertTrue("逐点间距完全没有波动", steps.max() - steps.min() > 0.3)

        // 精度字段要在合理区间
        assertTrue("精度字段异常", pts.all { it.accuracy in 3.0..20.0 })
    }

    @Test
    fun `时间轴必须逐秒递增`() {
        val pts = TrackGen.generate(ningJingFence, emptyList(), 345.0, 2000.0, seed = 1)
        for (i in pts.indices) {
            assertEquals(i, pts[i].secOffset)
        }
        // 2000m @ 5'45"/km ≈ 690 秒
        assertTrue("时长 ${pts.size - 1}s 不合理", Math.abs((pts.size - 1) - 690) <= 3)
    }

    @Test
    fun `点在多边形内的判定与射线法一致`() {
        // 场内
        assertTrue(Geo.pointInPoly(29.5328, 106.6076, fengHuaFence))
        // 场外
        assertTrue(!Geo.pointInPoly(29.5400, 106.6076, fengHuaFence))
        assertTrue(!Geo.pointInPoly(29.5328, 106.6200, fengHuaFence))
        // 禁跑区内的点应当被 jpq 多边形判中
        assertTrue(Geo.pointInPoly(29.5329, 106.6076, fengHuaJpq))
    }
}
