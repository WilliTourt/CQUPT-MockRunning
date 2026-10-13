/*
 * 打卡流程编排：token → 规则 → 区域 → start2 → 分批上传 → end
 * 作者：蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一个可跑步的场地。
 *
 * 服务器 /area/getAllArea 会把同一 placeCode 拆成多条记录：
 *   areaFuncCode = "dzwl" 电子围栏 —— 轨迹必须落在这个多边形内
 *   areaFuncCode = "jpq"  禁跑区   —— 轨迹必须避开（禁跑区里的点不计里程）
 *   areaFuncCode = "dkq"  打卡区
 *
 * 注意：/area/getAllArea 里混着学校数据库的测试残留（00001 上海成、00002 石桥铺），
 * 它们不在 /place/getPlayground 里。必须做交集，否则会列出一堆跑不了的场地。
 */
data class Area(
    val placeName: String,
    val placeCode: String,
    val fence: List<DoubleArray>,
    val excludes: List<List<DoubleArray>>
) {
    override fun toString(): String = "$placeName ($placeCode)"
}

data class Rule(val minPace: Double, val maxPace: Double, val raceMile: Double) {
    /** 取区间中段，别贴边 —— 这是唯一会被人工复查的地方。 */
    fun suggest(): Double = (minPace + maxPace) / 2.0
}

/** 打卡过程中的实时状态，驱动界面上的仪表盘。 */
data class RunState(
    val phase: String = "空闲",
    val pointsDone: Int = 0,
    val pointsTotal: Int = 0,
    val metersDone: Double = 0.0,
    val metersTotal: Double = 0.0,
    val elapsedSec: Int = 0,
    val etaSec: Int = -1,
    val paceSecKm: Double = 0.0,
    val paceLo: Double = 0.0,
    val paceHi: Double = 0.0,
    val batchesDone: Int = 0,
    val batchesTotal: Int = 0,
    val serverMileage: Double = -1.0,
    val serverSeconds: Int = -1,
    val finished: Boolean = false,
    val failed: Boolean = false
)

/** 统计页的一项。 */
data class StatRow(
    val placeName: String,
    val distanceKm: Double,
    val durationSec: Int,
    val timeText: String,
    val valid: Boolean,
    val reason: String
)

data class Stats(
    val total: Int,
    val validCount: Int,
    val invalidCount: Int,
    val totalKm: Double,
    val avgPaceSecKm: Double,
    val byPlace: List<Pair<String, Int>>,
    val rows: List<StatRow>
)

interface RunListener {
    fun log(line: String)
    fun onState(s: RunState)
}

/**
 * 打卡流程编排。
 *
 *   ① checkBinding(openid) → token
 *   ② setting/getRule      → 配速区间
 *   ③ place/getPlayground + area/getAllArea → 可跑步场地及其多边形
 *   ④ sport/start2         → sportRecordNo
 *   ⑤ point/saveListByNo   → 分批上传
 *   ⑥ sport/end/{no}
 *
 * 全程不碰 GPS，不需要定位权限。
 */
class RunEngine(private val api: Api, private val l: RunListener) {

    @Volatile private var cancel = false

    /** 连接器层面的硬失败（服务器不可达）。界面据此弹「无法连接到服务器」。 */
    @Volatile var netFail = false
        private set

    fun cancel() {
        cancel = true
    }

    // ---------------------------------------------------------------- ①

    /**
     * 换 token。
     *
     * 这里的失败分两种，界面要区别对待：
     *   · 网络层失败（连不上服务器）→ 弹「无法连接到服务器」
     *   · 业务层失败（openid 无效 / 未绑定）→ 提示去小程序重新绑定
     * 原来的实现把"服务器不可达"和"openid 不对"都塞进 [lastError]，
     * 界面上根本分不出来。
     */
    fun connect(openid: String): Boolean {
        api.reset(openid)
        netFail = false
        l.log("① 换取 token ...")
        val j = api.checkBinding(openid)
        if (j == null) {
            l.log("   ✗ 无法连接到服务器：${api.lastError}")
            l.log("     检查手机网络 / 校园网是否可达 sport.cqupt.edu.cn")
            netFail = true
            return false
        }
        val r = Resp.of(j)
        if (!r.ok) {
            l.log("   ✗ code=${r.code} ${r.msg}")
            return false
        }
        val d = j.optJSONObject("data")
        if (d == null || d.optString("isBind") != "true") {
            l.log("   ✗ openid 未绑定校园跑身份（isBind=${d?.optString("isBind")}）")
            l.log("     请先在手机小程序里完成一次绑定，再回来复制 openid")
            return false
        }
        api.token = d.optString("token")
        l.log("   ✓ token = ${api.mask(api.token)}")
        return true
    }

    // ---------------------------------------------------------------- ②③

    fun fetchRule(): Rule? {
        l.log("② 拉取跑步规则 ...")
        val j = api.getRule()
        if (j == null) {
            l.log("   ✗ 无法连接到服务器：${api.lastError}")
            return null
        }
        val r = Resp.of(j)
        if (!r.ok) {
            l.log("   ✗ code=${r.code} ${r.msg}")
            return null
        }
        val d = r.data as? JSONObject ?: return null
        val min = d.optString("minPace").toDoubleOrNull() ?: 0.0
        val max = d.optString("maxPace").toDoubleOrNull() ?: 0.0
        val mile = d.optString("raceMile").toDoubleOrNull() ?: 2000.0
        l.log("   配速区间 ${fmtPace(min * 60)} ~ ${fmtPace(max * 60)}/km，"
                + "建议 ${fmtPace((min + max) / 2 * 60)}")
        l.log("   达标里程 ${mile.toInt()} m")
        return Rule(min * 60, max * 60, mile)
    }

    /**
     * 拉区域。
     *
     * 关键一步是跟 /place/getPlayground 做交集：/area/getAllArea 里混着学校的
     * 测试残留（00001 上海成、00002 石桥铺），它们不在场地表里，跑不了。
     */
    fun fetchAreas(): List<Area> {
        l.log("③ 拉取场地 ...")
        netFail = false

        // 可跑步场地：sportsItem == "0"（跑步），其余是球馆/游泳馆
        val runnable = LinkedHashMap<String, String>()
        val pj = api.getPlayground()
        if (pj == null) {
            l.log("   ✗ 无法连接到服务器：${api.lastError}")
            l.log("     检查手机网络 / 校园网是否可达 sport.cqupt.edu.cn")
            netFail = true
            return emptyList()
        }
        val pr = Resp.of(pj)
        if (pr.ok) {
            val arr = pr.data as? JSONArray ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                if (p.optString("sportsItem") == "0") {
                    runnable[p.optString("placeCode")] = p.optString("placeName")
                }
            }
            l.log("   可跑步场地 ${runnable.size} 个")
        } else {
            l.log("   ⚠ 场地表拉取失败（${pr.code} ${pr.msg}），不过滤")
        }

        val aj = api.getAllArea()
        if (aj == null) {
            l.log("   ✗ 无法连接到服务器：${api.lastError}")
            netFail = true
            return emptyList()
        }
        val r = Resp.of(aj)
        if (!r.ok) {
            l.log("   ✗ code=${r.code} ${r.msg}")
            return emptyList()
        }
        val arr = r.data as? JSONArray ?: return emptyList()

        val fences = LinkedHashMap<String, Pair<String, List<DoubleArray>>>()
        val bans = LinkedHashMap<String, MutableList<List<DoubleArray>>>()
        val skipped = ArrayList<String>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("placeCode")
            val name = o.optString("placeName")
            val func = o.optString("areaFuncCode")

            if (runnable.isNotEmpty() && code !in runnable) {
                if (code !in skipped) skipped.add("$name ($code)")
                continue
            }

            val pl = o.optJSONArray("areaPointList") ?: continue
            val pts = ArrayList<DoubleArray>()
            for (k in 0 until pl.length()) {
                val p = pl.optJSONObject(k) ?: continue
                pts.add(doubleArrayOf(p.optDouble("latitude"), p.optDouble("longitude")))
            }
            if (pts.size < 3) continue

            when (func) {
                "dzwl" -> if (!fences.containsKey(code)) {
                    fences[code] = Pair(runnable[code] ?: name, pts)
                }
                "jpq" -> bans.getOrPut(code) { ArrayList() }.add(pts)
            }
        }

        if (skipped.isNotEmpty()) {
            l.log("   已过滤测试残留 ${skipped.size} 个：${skipped.joinToString("、")}")
        }

        val out = fences.map { (code, v) -> Area(v.first, code, v.second, bans[code] ?: emptyList()) }
        l.log("   可用场地 ${out.size} 个")
        out.forEach {
            l.log("     · ${it.placeName} / ${it.placeCode} / 围栏 ${it.fence.size} 顶点"
                    + if (it.excludes.isEmpty()) "" else " / 禁跑区 ${it.excludes.size} 块")
        }
        return out
    }

    // ---------------------------------------------------------------- 统计

    fun fetchStats(): Stats? {
        val j = api.getSportsResult()
        if (j == null) {
            l.log("✗ 无法连接到服务器：${api.lastError}")
            return null
        }
        val r = Resp.of(j)
        if (!r.ok) {
            l.log("✗ 统计拉取失败 code=${r.code} ${r.msg}")
            return null
        }
        val arr = r.data as? JSONArray ?: return null
        val rows = ArrayList<StatRow>()
        val byPlace = LinkedHashMap<String, Int>()
        var totalKm = 0.0
        var totalSec = 0.0
        var validCount = 0
        var invalidCount = 0

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val km = str(o, "distance").toDoubleOrNull()?.div(1000.0) ?: 0.0
            val dur = str(o, "duration").toDoubleOrNull()?.toInt() ?: 0
            val valid = str(o, "isValid") == "1"
            val place = str(o, "placeName")
            val reason = str(o, "reason")

            rows.add(StatRow(place, km, dur, fmtDuration(dur), valid, reason))
            if (valid) {
                validCount++
                totalKm += km
                totalSec += dur
                byPlace[place] = (byPlace[place] ?: 0) + 1
            } else {
                invalidCount++
            }
        }

        val avgPace = if (totalKm > 0) totalSec / totalKm else 0.0
        return Stats(
            total = rows.size,
            validCount = validCount,
            invalidCount = invalidCount,
            totalKm = totalKm,
            avgPaceSecKm = avgPace,
            byPlace = byPlace.entries.sortedByDescending { it.value }.map { it.key to it.value },
            rows = rows
        )
    }

    // ---------------------------------------------------------------- 全部

    fun runAll(
        openid: String,
        area: Area,
        paceSecKm: Double,
        targetM: Double,
        realtime: Boolean,
        noise: Double,
        seed: Int,
        profile: PaceProfile = PaceProfile.NATURAL,
        validate: Boolean = true
    ) {
        cancel = false
        var state = RunState(phase = "初始化", paceSecKm = paceSecKm, metersTotal = targetM)
        fun push() = l.onState(state)

        push()

        if (!connect(openid)) {
            l.onState(state.copy(phase = "失败：token", failed = true))
            return
        }

        val rule = fetchRule()
        if (rule != null && (paceSecKm < rule.minPace || paceSecKm > rule.maxPace)) {
            l.log("   ⚠ 配速 ${fmtPace(paceSecKm)} 落在允许区间之外！建议 ${fmtPace(rule.suggest())}")
        }

        l.log("④ 生成轨迹（区域：${area.placeName}）")
        state = state.copy(phase = "生成轨迹")
        push()

        val t0 = System.currentTimeMillis()
        val track = TrackGen.generate(
            area.fence, area.excludes, paceSecKm, targetM, noise, seed, profile
        )
        l.log("   ${TrackGen.summarize(track)}  (耗时 ${System.currentTimeMillis() - t0} ms)")
        val paceRange = paceRangeOf(track)

        if (validate) {
            val inside = track.count { Geo.pointInPoly(it.lat, it.lon, area.fence) }
            val banned = track.count { p -> area.excludes.any { Geo.pointInPoly(p.lat, p.lon, it) } }
            l.log("   自检：围栏内 $inside/${track.size} = "
                    + "${"%.2f".format(inside * 100.0 / track.size)}% | 禁跑区 $banned 点")
            if (inside < track.size * 0.99) {
                l.log("   ✗ 轨迹有大量点落在围栏外，换一个场地试试")
                l.onState(state.copy(phase = "失败：轨迹越界", failed = true))
                return
            }
            if (banned > 0) {
                l.log("   ⚠ 有 $banned 个点落在禁跑区，这部分不计入里程")
            }
        }

        l.log("⑤ 开始跑步 start2 ...")
        state = state.copy(phase = "开始打卡")
        push()
        val sj = api.startSport(area.placeName, area.placeCode)
        if (sj == null) {
            l.log("   ✗ 网络失败：${api.lastError}")
            l.onState(state.copy(phase = "失败：start2 网络", failed = true))
            return
        }
        val sr = Resp.of(sj)
        if (!sr.ok) {
            l.log("   ✗ code=${sr.code} ${sr.msg}")
            l.onState(state.copy(phase = "失败：start2", failed = true))
            return
        }
        var recordNo = sr.data?.toString()?.trim()?.trim('"') ?: ""
        if (recordNo.isEmpty() || recordNo == "null") {
            l.log("   ✗ 未拿到 sportRecordNo：${sj.toString().take(200)}")
            l.onState(state.copy(phase = "失败：无记录号", failed = true))
            return
        }
        l.log("   ✓ sportRecordNo = $recordNo")

        // 服务端的 start2 会把「上次没结束的那条记录」原样接续下来。
        // 不处理的话，里程和配速会跟上次的混在一起，界面一进去就跳到一半。
        // 这里检测到残留就把它结束掉，再开一条干净的。
        val preInfo = (Resp.of(api.getRecordInfo(recordNo)).data as? JSONObject)
        val prePts = preInfo?.optJSONArray("points")?.length() ?: 0
        if (prePts > 0) {
            val preKm = preInfo?.optString("mileage") ?: "?"
            l.log("   ⚠ 这条是接续上次没结束的记录（已有 $prePts 点 / $preKm km）")
            l.log("     先把它结束，再开一条干净的 …")
            Resp.of(api.endSport(recordNo))
            val sj2 = api.startSport(area.placeName, area.placeCode)
            val r2 = Resp.of(sj2)
            val no2 = r2.data?.toString()?.trim()?.trim('"') ?: ""
            if (r2.ok && no2.isNotEmpty() && no2 != "null" && no2 != recordNo) {
                recordNo = no2
                l.log("   ✓ 新 sportRecordNo = $recordNo")
            } else {
                l.log("   ✗ 重新开一条失败（${r2.code}），仍用原记录")
            }
        } else {
            l.log("   记录干净，从 0 开始")
        }

        l.log("⑥ 上传轨迹点 ...")
        val startMs = System.currentTimeMillis()
        val startWall = SystemClock.elapsedRealtime()
        val valid = validFlags(track, area)
        val banned = valid.count { !it }
        val points = buildPoints(track, valid, recordNo, area, startMs)
        val batches = batch(points, Api.POINT_BATCH)
        val totalSec = track.size - 1
        l.log("   共 ${points.size} 点 → ${batches.size} 批（每批 ${Api.POINT_BATCH}，末点作下批首点）")
        l.log("   轨迹时长 ${fmtDuration(totalSec)}（服务端按 collectTime 跨度算时长），"
                + "退出 App 不会中断，但别划掉它")
        if (banned > 0) {
            l.log("   ⚠ 其中 $banned 点落在禁跑区，已按小程序语义标记 isValid=\"2\"（不计里程）")
        }

        var uploadedPoints = 0
        var uploadedMeters: Double
        var serverMileage = -1.0
        var serverSeconds = -1

        // 轨迹的累计里程，用于界面上"已跑"的估算
        val cum = DoubleArray(track.size)
        for (k in 1 until track.size) {
            cum[k] = cum[k - 1] + Geo.hav(
                track[k - 1].lat, track[k - 1].lon, track[k].lat, track[k].lon
            )
        }

        state = state.copy(
            phase = "上传中",
            pointsTotal = points.size,
            batchesTotal = batches.size,
            paceLo = paceRange.first,
            paceHi = paceRange.second
        )
        push()

        for ((i, b) in batches.withIndex()) {
            if (cancel) {
                l.log("   已取消")
                l.onState(state.copy(phase = "已取消", failed = true))
                return
            }

            val uj = api.uploadPoints(recordNo, b)
            if (uj == null) {
                l.log("   批次 ${i + 1}/${batches.size} ✗ ${api.lastError}")
            } else {
                val ur = Resp.of(uj)
                val dd = ur.data as? JSONObject
                if (dd != null) {
                    val m = dd.optString("mileage").toDoubleOrNull()
                    val s = dd.optString("timeConsuming").toDoubleOrNull()?.toInt()
                    if (m != null) serverMileage = m
                    if (s != null) serverSeconds = s
                }
                if (!ur.ok) l.log("   批次 ${i + 1}/${batches.size} ✗ code=${ur.code} ${ur.msg}")
            }

            // 首批吃掉全部点，之后每批只有 (size-1) 个是新点（首点是上批末点）
            uploadedPoints += if (i == 0) b.size else b.size - 1
            if (uploadedPoints > track.size) uploadedPoints = track.size
            uploadedMeters = cum[(uploadedPoints - 1).coerceIn(0, track.size - 1)]

            // 进展写一条日志，否则成功批次不吭声，看起来像卡死了
            if ((i + 1) % 10 == 0 || i == batches.size - 1) {
                l.log("   批次 ${i + 1}/${batches.size}　已跑 %.2f/%.2f km　用时 %s"
                    .format(uploadedMeters / 1000.0, targetM / 1000.0,
                            fmtDuration(((SystemClock.elapsedRealtime() - startWall) / 1000).toInt())))
            }

            if (realtime && i < batches.size - 1) {
                // 这一批要"占掉"的时长 = **本批末点的 secOffset**，不是批大小。
                // 相邻两批共享一个点（末点作下批首点），所以两批之间只跨 size-1 秒；
                // 按批大小等会把整轮拖长约 14%（690 点 / 每批 8 时多等 95 秒），
                // 服务端按 collectTime 跨度算时长，多出来的等待全都会记到配速里。
                //
                // 关键：用 elapsedRealtime 量真实时间，而不是"睡了几次 × 250ms"。
                // 息屏 / Doze 时 Thread.sleep 会睡过头，按次数算的话整轮会被拖长一倍多 ——
                // 表现就是"进度条不动，只有用时在涨"。
                val target = startWall + track[uploadedPoints - 1].secOffset * 1000L
                var lastPush = SystemClock.elapsedRealtime()
                while (!cancel) {
                    val remainMs = target - SystemClock.elapsedRealtime()
                    if (remainMs <= 0) break
                    Thread.sleep(minOf(remainMs, 200L))
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPush >= 900) {
                        lastPush = now
                        val e = ((now - startWall) / 1000).toInt()
                        state = state.copy(elapsedSec = e, etaSec = maxOf(0, totalSec - e))
                        push()
                    }
                }
            }

            val elapsed = ((SystemClock.elapsedRealtime() - startWall) / 1000).toInt()
            state = state.copy(
                pointsDone = uploadedPoints,
                metersDone = if (serverMileage >= 0) serverMileage * 1000 else uploadedMeters,
                elapsedSec = elapsed,
                etaSec = maxOf(0, totalSec - elapsed),
                batchesDone = i + 1,
                serverMileage = serverMileage,
                serverSeconds = serverSeconds
            )
            push()
        }

        if (cancel) {
            l.onState(state.copy(phase = "已取消", failed = true))
            return
        }

        l.log("⑦ 结束跑步 end ...")
        state = state.copy(phase = "结束打卡")
        push()
        val ej = api.endSport(recordNo)
        if (ej == null) {
            l.log("   ✗ ${api.lastError}")
            l.onState(state.copy(phase = "失败：end 网络", failed = true))
            return
        }
        val er = Resp.of(ej)
        l.log("   ${if (er.ok) "✓" else "✗"} code=${er.code} ${er.msg}")

        val info = Resp.of(api.getRecordInfo(recordNo))
        if (info.ok) {
            val d = info.data as? JSONObject
            val km = d?.optString("mileage") ?: "-"
            val sec = d?.optString("timeConsuming") ?: "-"
            val pts = (d?.optJSONArray("points"))?.length() ?: 0
            val forbidden = d?.optString("expiredCountInForbiddenArea") ?: "0"
            l.log("   记录：$km km / ${sec}s / $pts 点 / 禁跑区 $forbidden")
        }
        l.log("完成。共耗时 ${(System.currentTimeMillis() - startMs) / 1000} 秒")

        l.onState(state.copy(
            phase = if (er.ok) "已完成" else "已结束但返回异常",
            pointsDone = points.size,
            elapsedSec = ((System.currentTimeMillis() - startMs) / 1000).toInt(),
            etaSec = 0,
            finished = true
        ))
    }

    // ---------------------------------------------------------------- 工具

    /** optString 对 JSON null 会返回字符串 "null"，统一清掉。 */
    private fun str(o: JSONObject, key: String): String {
        val v = o.optString(key, "")
        return if (v == "null") "" else v
    }

    /**
     * 每个点该填的 isValid。小程序语义（见 refs/FINDINGS.md 4.1）：
     *   "1" = 正常
     *   "2" = 落在禁跑区（jpq），服务端 calcDistance 返回 0，不计里程
     *
     * 原来这里无脑写 "1"，和上面自检打出来的 banned 计数自相矛盾。
     * 轨迹内缩后 banned 正常就是 0，但真出现时不应该瞒着服务端。
     */
    private fun validFlags(track: List<TrackPoint>, area: Area): BooleanArray =
        BooleanArray(track.size) { i ->
            val p = track[i]
            !area.excludes.any { Geo.pointInPoly(p.lat, p.lon, it) }
        }

    private fun buildPoints(
        track: List<TrackPoint>,
        valid: BooleanArray,
        recordNo: String,
        area: Area,
        startMs: Long
    ): List<JSONObject> {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        return track.mapIndexed { i, p ->
            JSONObject().apply {
                put("sportRecordNo", recordNo)
                put("longitude", p.lon)
                put("latitude", p.lat)
                put("placeName", area.placeName)
                put("placeCode", area.placeCode)
                put("collectTime", fmt.format(Date(startMs + p.secOffset * 1000L)))
                put("isValid", if (valid[i]) "1" else "2")
            }
        }
    }

    /**
     * 15 秒滚动窗口的配速区间 (最快, 最慢)，秒/公里。
     *
     * 逐秒的瞬时配速在停顿处趋于无穷，取极值没意义；滚动窗口才对应
     * "跑者感受到的配速"。界面用它显示"分段配速 X ~ Y"。
     */
    private fun paceRangeOf(track: List<TrackPoint>, win: Int = 15): Pair<Double, Double> {
        if (track.size <= win + 1) return Pair(0.0, 0.0)
        var fastest = Double.MAX_VALUE
        var slowest = 0.0
        for (i in 0..track.size - 1 - win) {
            var seg = 0.0
            for (k in i until i + win) {
                seg += Geo.hav(track[k].lat, track[k].lon, track[k + 1].lat, track[k + 1].lon)
            }
            if (seg <= 0.1) continue
            val p = win / (seg / 1000.0)
            if (p < fastest) fastest = p
            if (p > slowest) slowest = p
        }
        if (fastest == Double.MAX_VALUE) return Pair(0.0, 0.0)
        return Pair(fastest, slowest)
    }

    /** 与小程序一致：每 n 个点一批，并把上一批的末点作为下一批的首点。 */
    private fun batch(pts: List<JSONObject>, n: Int): List<List<JSONObject>> {
        val out = ArrayList<List<JSONObject>>()
        var cur = ArrayList<JSONObject>()
        for (p in pts) {
            cur.add(p)
            if (cur.size >= n) {
                out.add(cur)
                cur = arrayListOf(cur.last())
            }
        }
        if (cur.size > 1) out.add(cur)
        return out
    }

    companion object {
        /** "5:30" → 330，"5.5" → 330，"330" → 330 */
        fun parsePace(s: String): Double? {
            val t = s.trim()
            if (t.isEmpty()) return null
            if (t.contains(":")) {
                val a = t.split(":")
                if (a.size != 2) return null
                val m = a[0].toDoubleOrNull() ?: return null
                val sec = a[1].toDoubleOrNull() ?: return null
                return m * 60 + sec
            }
            val v = t.toDoubleOrNull() ?: return null
            return if (v > 60) v else v * 60
        }

        fun fmtPace(secPerKm: Double): String {
            if (secPerKm <= 0) return "--"
            val m = (secPerKm / 60).toInt()
            val s = (secPerKm % 60).toInt()
            return "$m'${"%02d".format(s)}\""
        }

        fun fmtDuration(sec: Int): String {
            if (sec < 0) return "--:--"
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}
