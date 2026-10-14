/*
 * AreaStore 单元测试：场地缓存的状态机。
 *
 * 这块逻辑是为「填完 openid 主页不刷新」修的（v1.9.1），但它同时决定
 * 「什么时候该自动补拉、什么时候不该」。纯逻辑、不碰 Android，
 * 所以直接在 JVM 上把几种组合钉死，免得以后改回去。
 *
 * 作者：WilliTourt的蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaStoreTest {

    /** AreaStore 是单例，测试之间必须清干净，否则互相污染。 */
    @After
    fun tearDown() = AreaStore.invalidate()

    private fun area(code: String) =
        Area("场地$code", code, listOf(doubleArrayOf(0.0, 0.0)), emptyList())

    private val list = listOf(area("T1001"), area("T1002"))

    /**
     * Area 里装的是 `List<DoubleArray>`，而 `DoubleArray` 只按引用比较，
     * 直接 assertEquals 比的是同一引用、验不出东西。这里比 placeCode。
     */
    private fun codes() = AreaStore.snapshot().map { it.placeCode }

    @Test
    fun `没有 openid 时不算有结果`() {
        // 空 openid 永远当作"没拉过"，否则主页会以为已经拉完了、什么都不做
        assertFalse(AreaStore.hasResultFor(""))
        assertFalse(AreaStore.hasAreasFor(""))
        AreaStore.publish("", list)
        assertFalse(AreaStore.hasAreasFor(""))
    }

    @Test
    fun `发布后同一个 openid 能取到场地`() {
        AreaStore.publish("oA", list)
        assertTrue(AreaStore.hasAreasFor("oA"))
        assertEquals(listOf("T1001", "T1002"), codes())
    }

    @Test
    fun `空列表也算拉取成功`() {
        // 服务器确实一个可跑场地都没有 —— 这是"有结果"，不是"没拉过"。
        // 不区分的话主页每次 onResume 都会重拉一遍。
        AreaStore.publish("oA", emptyList())
        assertTrue(AreaStore.hasResultFor("oA"))
        assertFalse(AreaStore.hasAreasFor("oA"))
    }

    @Test
    fun `换 openid 后旧结果不算数`() {
        AreaStore.publish("oA", list)
        assertFalse(AreaStore.hasResultFor("oB"))
        assertTrue(AreaStore.hasAreasFor("oA"))
    }

    @Test
    fun `每次发布都会推进 revision`() {
        val before = AreaStore.revision
        AreaStore.publish("oA", list)
        assertTrue(AreaStore.revision > before)

        val afterFirst = AreaStore.revision
        AreaStore.publish("oA", list)
        assertTrue("重复发布同一份也要推进，否则主页不会重绑", AreaStore.revision > afterFirst)
    }

    @Test
    fun `网络失败保留同一个 openid 已有的场地`() {
        // 一次超时不该让用户已经能用的场地凭空消失
        AreaStore.publish("oA", list)
        AreaStore.markNetFailed("oA")
        assertTrue(AreaStore.hasAreasFor("oA"))
    }

    @Test
    fun `网络失败会丢掉别的 openid 的场地`() {
        // 换了账号还显示上一个人的场地是不能接受的
        AreaStore.publish("oA", list)
        AreaStore.markNetFailed("oB")
        assertFalse(AreaStore.hasAreasFor("oB"))
        assertEquals(emptyList<Area>(), AreaStore.snapshot())
    }

    @Test
    fun `网络失败后进入冷却`() {
        AreaStore.markNetFailed("oA")
        assertTrue(AreaStore.inCooldown("oA"))
        // 冷却是按 openid 记的，别把别的账号也一起冻住
        assertFalse(AreaStore.inCooldown("oB"))
    }

    @Test
    fun `没有失败过就不在冷却里`() {
        assertFalse(AreaStore.inCooldown("oA"))
    }

    @Test
    fun `无效 openid 会被记住`() {
        // openid 无效重试多少次都一样，主页据此跳过自动补拉
        AreaStore.markBadOpenid("oA")
        assertTrue(AreaStore.isBadOpenid("oA"))
        assertFalse(AreaStore.isBadOpenid("oB"))
        assertTrue(AreaStore.inCooldown("oA"))
    }

    @Test
    fun `重新发布能解除无效标记`() {
        AreaStore.markBadOpenid("oA")
        AreaStore.publish("oA", list)
        assertFalse("换回可用的 openid 之后必须能恢复", AreaStore.isBadOpenid("oA"))
    }

    @Test
    fun `invalidate 清空全部状态`() {
        AreaStore.publish("oA", list)
        AreaStore.invalidate()
        assertFalse(AreaStore.hasResultFor("oA"))
        assertEquals("", AreaStore.publishedOpenid)
        assertEquals(emptyList<Area>(), AreaStore.snapshot())
    }

    @Test
    fun `发布前 isBadOpenid 对空串为假`() {
        assertFalse(AreaStore.isBadOpenid(""))
        assertFalse(AreaStore.inCooldown(""))
    }
}
