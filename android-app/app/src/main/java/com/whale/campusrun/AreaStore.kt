/*
 * 场地缓存：谁拉到了场地都写进来，主页 onResume 直接取用。
 *
 * 为什么需要它：v1.9.0 把 openid 挪进了设置页，于是「填 openid → 拉场地」和
 * 「选场地 → 开始打卡」分在了两个 Activity 里。两个 Activity 各持一份 RunEngine，
 * 设置页拉到的那份 List<Area> 只留在它自己的局部变量里，返回时随着 Activity
 * 一起丢掉 —— 主页的 areas 还是空的，非要点一次【开始打卡】才会去补拉一次。
 * 换主题同理：Activity 重建，主页的场地列表也会没。
 *
 * 所以把场地提到单例上：设置页拉完写进来，主页一比对 revision 就知道要不要
 * 重绑下拉框，全程不需要多打一次网络。
 *
 * 作者：WilliTourt的蓝色大肥鱼 🐟
 */
package com.whale.campusrun

object AreaStore {

    /**
     * 拉取失败后的重试冷却。
     *
     * 不能一失败就每次都补拉：网络不通时，用户每从设置页返回主页一次就会再白发一遍
     * 请求、日志里多刷一段。冷却期内主页安静地显示空列表，用户想重试可以走设置页的
     * 按钮，或者直接点【开始打卡】（那条路一定会给出明确提示）。
     */
    private const val RETRY_COOLDOWN_MS = 5000L

    private val lock = Any()

    private var cached: List<Area> = emptyList()

    /**
     * 最近一次**成功**发布（哪怕发布的是空列表）对应的 openid。
     *
     * 空列表也算"有结果"：服务器确实一个可跑场地都没有，和"还没拉过"是两回事 ——
     * 不区分的话会把空结果当成没拉过，每次回主页都重拉一遍。
     */
    @Volatile
    var publishedOpenid: String = ""
        private set

    /** 每次写入 +1。主页用它判断要不要重绑下拉框，不必比对整个列表。 */
    @Volatile
    var revision: Long = 0L
        private set

    /** 最近一次**尝试**（不论成败）的 openid 与时刻，用于冷却。 */
    @Volatile
    private var attemptedOpenid: String = ""

    @Volatile
    private var attemptedAtMs: Long = 0L

    /**
     * 已确认无效（未绑定）的 openid。
     *
     * 这类失败重试多少次结果都一样，所以**不做自动补拉** —— 否则用户填错一个
     * openid，每次切回主页都会白发一遍请求、往日志里刷一段。换 openid 自动解除。
     */
    @Volatile
    private var badOpenid: String = ""

    fun snapshot(): List<Area> = cached

    /** 这个 openid 是否已经有拉取结果（可能是空列表）。有的话主页直接用，不再动网络。 */
    fun hasResultFor(oid: String): Boolean = oid.isNotEmpty() && publishedOpenid == oid

    /** 这个 openid 是否已经有可用场地。 */
    fun hasAreasFor(oid: String): Boolean = hasResultFor(oid) && cached.isNotEmpty()

    /** 拉取成功。空列表照样发布 —— 那是「服务器就是没有可跑场地」，不是失败。 */
    fun publish(oid: String, list: List<Area>) {
        synchronized(lock) {
            cached = list
            publishedOpenid = oid
            attemptedOpenid = oid
            attemptedAtMs = nowMs()
            badOpenid = ""
            revision++
        }
    }

    /**
     * 网络层失败（连不上服务器）。
     *
     * 刻意**不清掉同一个 openid 已有的场地**：一次超时不该让用户已经能用的场地凭空
     * 消失。只有换了 openid 才必须丢，因为那份是别人的。
     */
    fun markNetFailed(oid: String) {
        synchronized(lock) {
            if (oid != publishedOpenid) cached = emptyList()
            attemptedOpenid = oid
            attemptedAtMs = nowMs()
            revision++
        }
    }

    /**
     * 业务层失败（openid 无效 / 未绑定）。
     *
     * 和网络无关，重试没意义，所以直接拉黑这个 openid，不再自动补拉。
     */
    fun markBadOpenid(oid: String) {
        synchronized(lock) {
            if (oid != publishedOpenid) cached = emptyList()
            attemptedOpenid = oid
            attemptedAtMs = nowMs()
            badOpenid = oid
            revision++
        }
    }

    /** 这个 openid 已知是坏的，别再去试。 */
    fun isBadOpenid(oid: String): Boolean = oid.isNotEmpty() && badOpenid == oid

    /** 刚刚才失败过（网络或业务），先别急着自动再拉一次。 */
    fun inCooldown(oid: String): Boolean =
        oid.isNotEmpty() && attemptedOpenid == oid && nowMs() - attemptedAtMs < RETRY_COOLDOWN_MS

    /**
     * 彻底清空（换账号时用）。
     *
     * 这里**不能**图省事在「本来就是空的」时提前 return —— 失败标记（[attemptedOpenid] /
     * [badOpenid]）可以在没有任何场地的情况下独立存在，漏清就会让下一个账号莫名其妙
     * 继承上一个账号的冷却和"openid 无效"判定。
     */
    fun invalidate() {
        synchronized(lock) {
            cached = emptyList()
            publishedOpenid = ""
            attemptedOpenid = ""
            attemptedAtMs = 0L
            badOpenid = ""
            revision++
        }
    }

    /** 用 nanoTime 而不是 SystemClock：纯 JVM，单测里也不会碰 "not mocked"。 */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
