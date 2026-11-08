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

    /** T1005 太极运动场 禁跑区（jpq，11 顶点） */
    private val taiJiJpq = listOf(
        doubleArrayOf(29.533214, 106.609447), doubleArrayOf(29.533071, 106.609473),
        doubleArrayOf(29.532917, 106.609468), doubleArrayOf(29.532626, 106.609486),
        doubleArrayOf(29.532506, 106.609612), doubleArrayOf(29.532533, 106.610029),
        doubleArrayOf(29.532691, 106.610093), doubleArrayOf(29.533146, 106.610060),
        doubleArrayOf(29.533421, 106.610058), doubleArrayOf(29.533549, 106.609690),
        doubleArrayOf(29.533457, 106.609455)
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
        for (burrs in listOf(false, true)) {
            for (pace in paces) {
                val pts = TrackGen.generate(
                    fengHuaFence, listOf(fengHuaJpq), pace, 2000.0, seed = 12345, burrs = burrs
                ).points
                val d = measure(pts)
                val rp = realPace(pts)
                assertEquals("burrs=$burrs 距离 target=${pace}s/km", 2000.0, d, 2000.0 * 0.005)
                assertTrue("burrs=$burrs 配速 target=${pace}s/km real=${rp}s/km", Math.abs(rp - pace) <= 1.0)
            }
        }
    }

    @Test
    fun `所有点必须落在电子围栏内`() {
        for (burrs in listOf(false, true)) {
            for (pace in paces) {
                val pts = TrackGen.generate(
                    fengHuaFence, listOf(fengHuaJpq), pace, 2000.0, seed = 7, burrs = burrs
                ).points
                val inside = pts.count { Geo.pointInPoly(it.lat, it.lon, fengHuaFence) }
                assertEquals("burrs=$burrs pace=$pace 有点跑到围栏外", pts.size, inside)
            }
        }
    }

    @Test
    fun `不能有任何点落在禁跑区`() {
        for (burrs in listOf(false, true)) {
            for (pace in paces) {
                val pts = TrackGen.generate(
                    fengHuaFence, listOf(fengHuaJpq), pace, 2000.0, seed = 99, burrs = burrs
                ).points
                val banned = pts.count { Geo.pointInPoly(it.lat, it.lon, fengHuaJpq) }
                assertEquals("burrs=$burrs pace=$pace 有 $banned 个点落在禁跑区，这部分不计里程", 0, banned)
            }
        }
    }

    @Test
    fun `太极运动场与宁静苑同样达标`() {
        val fields = listOf(
            Triple("太极运动场", taiJiFence, listOf(taiJiJpq)),
            Triple("宁静苑", ningJingFence, emptyList())
        )
        for (burrs in listOf(false, true)) {
            for ((name, fence, jpq) in fields) {
                for (pace in listOf(330.0, 345.0)) {
                    val pts = TrackGen.generate(
                        fence, jpq, pace, 2000.0, seed = 2026, burrs = burrs
                    ).points
                    val rp = realPace(pts)
                    assertTrue("burrs=$burrs $name pace=$pace 配速 ${rp}",
                        Math.abs(rp - pace) <= 1.0)
                    val inside = pts.count { Geo.pointInPoly(it.lat, it.lon, fence) }
                    assertEquals("burrs=$burrs $name pace=$pace 围栏外有点", pts.size, inside)
                    val banned = pts.count { p -> jpq.any { Geo.pointInPoly(p.lat, p.lon, it) } }
                    assertEquals("burrs=$burrs $name pace=$pace 有 $banned 个点落在禁跑区", 0, banned)
                }
            }
        }
    }

    @Test
    fun `轨迹形态要像人跑的，不是机器画的`() {
        for (burrs in listOf(false, true)) {
            val pts = TrackGen.generate(
                fengHuaFence, listOf(fengHuaJpq), 345.0, 2000.0, seed = 555, burrs = burrs
            ).points

            // 航向不能只有几个固定值（矩形会只有 4 个）
            val headings = pts.map { Math.round(it.bearing) }.toSet()
            assertTrue("burrs=$burrs 航向取值只有 ${headings.size} 个，太规则了", headings.size > 40)

            // 速度不能是恒定的
            val speeds = pts.map { it.speed }
            val span = speeds.max() - speeds.min()
            assertTrue("burrs=$burrs 速度波动只有 $span，太假", span > 0.05)

            // 逐点间距要有自然抖动
            val steps = (0 until pts.size - 1).map {
                Geo.hav(pts[it].lat, pts[it].lon, pts[it + 1].lat, pts[it + 1].lon)
            }
            assertTrue("burrs=$burrs 逐点间距完全没有波动", steps.max() - steps.min() > 0.3)

            // 精度字段要在合理区间
            assertTrue("burrs=$burrs 精度字段异常", pts.all { it.accuracy in 3.0..20.0 })
        }
    }

    @Test
    fun `时间轴必须逐秒递增`() {
        val pts = TrackGen.generate(ningJingFence, emptyList(), 345.0, 2000.0, seed = 1).points
        for (i in pts.indices) {
            assertEquals(i, pts[i].secOffset)
        }
        // 2000m @ 5'45"/km ≈ 690 秒
        assertTrue("时长 ${pts.size - 1}s 不合理", Math.abs((pts.size - 1) - 690) <= 3)
    }

    // ------------------------------------------------------------ 配速形态

    /** 起伏再大，围栏和禁跑区这两条硬约束也不能破。 */
    @Test
    fun `各种配速与种子下都不能越界`() {
        for (burrs in listOf(false, true)) {
            for (pace in paces) {
                for (seed in listOf(1, 99, 555, 2026)) {
                    val pts = TrackGen.generate(
                        fengHuaFence, listOf(fengHuaJpq), pace, 2000.0,
                        seed = seed, burrs = burrs
                    ).points
                    val inside = pts.count { Geo.pointInPoly(it.lat, it.lon, fengHuaFence) }
                    val banned = pts.count { Geo.pointInPoly(it.lat, it.lon, fengHuaJpq) }
                    assertEquals("burrs=$burrs pace=$pace seed=$seed 有点跑到围栏外", pts.size, inside)
                    assertEquals("burrs=$burrs pace=$pace seed=$seed 有 $banned 个点落在禁跑区", 0, banned)
                }
            }
        }
    }

    /**
     * 路线要有"毛刺 / 突起 / 不规则"，不能只是把椭圆磨圆。
     *
     * 用几何量而不是内部参数来断言，这样以后重写噪声模型也依然有效：
     *   · 逐秒步长里要有一批明显大于均值的跳点（野点尖刺）
     *   · 最大跳点要到 5m 以上（肉眼在地图上看得出突起）
     *   · 每秒转向角的均值 / 大角度折返次数要显著高于光滑环线
     *     （原来那版是 8° 左右、几乎没有一秒内的折返）
     */
    @Test
    fun `路线要有毛刺和突起，不能是光滑椭圆`() {
        val pts = TrackGen.generate(
            fengHuaFence, listOf(fengHuaJpq), 345.0, 2000.0, seed = 555, burrs = true
        ).points

        val steps = (0 until pts.size - 1).map {
            Geo.hav(pts[it].lat, pts[it].lon, pts[it + 1].lat, pts[it + 1].lon)
        }
        val meanStep = steps.average()
        val bigJumps = steps.count { it > meanStep * 1.5 }
        assertTrue("跳点只有 $bigJumps 个，路线太光滑", bigJumps >= 8)
        assertTrue("最大跳点 ${"%.2f".format(steps.max())}m，看不出突起", steps.max() > 5.0)

        val turns = (2 until pts.size).map {
            val a = Geo.bearing(pts[it - 2].lat, pts[it - 2].lon, pts[it - 1].lat, pts[it - 1].lon)
            val b = Geo.bearing(pts[it - 1].lat, pts[it - 1].lon, pts[it].lat, pts[it].lon)
            Math.abs((b - a + 540.0) % 360.0 - 180.0)
        }
        val meanTurn = turns.average()
        assertTrue("平均每秒转向 ${"%.1f".format(meanTurn)}°，太平滑", meanTurn > 12.0)
        assertTrue(
            "大角度折返只有 ${turns.count { it > 45.0 }} 个",
            turns.count { it > 45.0 } >= 20
        )
    }

    /**
     * 「轨迹毛刺」开关的行为：
     *   · 关闭（默认）→ v1.10.1 的光滑轨迹：没有一秒级跳点，最大步长 < 5m
     *   · 打开 → 跳点显著增多，肉眼能看出突起
     *   · 两种模式的坐标必须真的不同，不能只是同一个轨迹换个标注
     */
    @Test
    fun `关闭毛刺走光滑轨迹，开启后才有突起`() {
        val smooth = TrackGen.generate(
            fengHuaFence, listOf(fengHuaJpq), 345.0, 2000.0, seed = 555
        ).points
        val burr = TrackGen.generate(
            fengHuaFence, listOf(fengHuaJpq), 345.0, 2000.0, seed = 555, burrs = true
        ).points

        fun steps(pts: List<TrackPoint>) = (0 until pts.size - 1).map {
            Geo.hav(pts[it].lat, pts[it].lon, pts[it + 1].lat, pts[it + 1].lon)
        }
        val smoothSteps = steps(smooth)
        val burrSteps = steps(burr)
        // 用"5m 以上的一秒级跳点"作为毛刺的判据：
        // 光滑轨迹靠配速起伏也可能超过 1.5× 均值，但不该出现 5m 的尖刺。
        val smoothOver5 = smoothSteps.count { it > 5.0 }
        val burrOver5 = burrSteps.count { it > 5.0 }

        assertTrue("关闭毛刺后仍有 $smoothOver5 个 5m+ 跳点", smoothOver5 == 0)
        assertTrue("关闭毛刺后最大步长 ${"%.2f".format(smoothSteps.max())}m，不够光滑",
            smoothSteps.max() < 5.0)
        assertTrue("开启毛刺后 5m+ 跳点只有 $burrOver5 个（关闭时 $smoothOver5）", burrOver5 >= 8)

        val same = smooth.indices.all {
            smooth[it].lat == burr[it].lat && smooth[it].lon == burr[it].lon
        }
        assertTrue("开关切换后轨迹没有变化", !same)
    }

    /** 温和起伏：逐秒步长必须有明显快慢差异，且出现短暂停顿。 */
    @Test
    fun `温和起伏既要有快慢差异也要有停顿`() {
        for (burrs in listOf(false, true)) {
            val pts = TrackGen.generate(
                fengHuaFence, listOf(fengHuaJpq), 345.0, 2000.0, seed = 777, burrs = burrs
            ).points
            val steps = (0 until pts.size - 1).map {
                Geo.hav(pts[it].lat, pts[it].lon, pts[it + 1].lat, pts[it + 1].lon)
            }
            val span = steps.max() - steps.min()
            assertTrue("burrs=$burrs 步长跨度只有 $span，起伏太平", span > 1.0)
            assertTrue("burrs=$burrs 没有任何停顿点，不像真人", steps.count { it < 0.4 } > 0)
        }
    }

    /** 配速窗口的计算。 */
    @Test
    fun `配速窗口计算正确`() {
        val w = TrackGen.paceWindow(345.0, 20.0)
        assertEquals(325.0, w.start, 1e-9)
        assertEquals(365.0, w.endInclusive, 1e-9)
        // 窗口下限不能被压到 60 秒以下（不合理配速）
        val w2 = TrackGen.paceWindow(70.0, 30.0)
        assertEquals(60.0, w2.start, 1e-9)
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
