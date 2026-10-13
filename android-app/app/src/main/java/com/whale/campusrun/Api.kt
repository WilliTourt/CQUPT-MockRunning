/*
 * 重邮智慧体育 API 客户端（协议逆向自小程序 wx13b7520eb7351f4f）
 * 作者：蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import org.json.JSONObject
import java.io.BufferedWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 重邮智慧体育 API 客户端。
 *
 * 协议逆向自小程序 wx13b7520eb7351f4f，详见 refs/FINDINGS.md。
 * 无签名、无加密，全部明文 JSON，认证只靠 header: token。
 *
 * 刻意只用 HttpURLConnection + org.json —— 零第三方依赖，工程好维护。
 */
class Api {

    companion object {
        const val BASE = "https://sport.cqupt.edu.cn/new_wxapp"
        const val POINT_BATCH = 8      // 与小程序 globalData.pointCount 一致
    }

    var token: String = ""
    var lastError: String = ""

    /** 每次请求的原始记录，用于调试和事后复盘。 */
    val trace = ArrayList<String>()

    fun reset(openid: String) {
        token = ""
        trace.clear()
        trace.add("=== 新会话 openid=${mask(openid)} ===")
    }

    // ---------------------------------------------------------------- 基础

    fun get(path: String, params: Map<String, String> = emptyMap(), useToken: Boolean = true): JSONObject? {
        val qs = params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val sep = if (path.contains("?")) "&" else "?"
        val url = BASE + path + (if (qs.isEmpty()) "" else sep + qs)
        return call("GET", url, null, useToken)
    }

    fun post(path: String, body: String?, useToken: Boolean = true): JSONObject? =
        call("POST", BASE + path, body, useToken)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(method: String, urlStr: String, body: String?, useToken: Boolean): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "Mozilla/5.0 MicroMessenger")
                if (useToken && token.isNotEmpty()) setRequestProperty("token", token)
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            trace.add("$method $urlStr  body=${body?.let { truncate(it) } ?: "-"}  → $code ${truncate(text)}")

            if (code !in 200..299) {
                lastError = "HTTP $code: ${truncate(text)}"
                return null
            }
            JSONObject(text)
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            trace.add("$method $urlStr  → 异常 ${lastError}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------- 业务

    /** wxCode 留空即可，只要有 openid —— 见小程序 handleRefreshToken 分支。 */
    fun checkBinding(openid: String, phoneType: String = "PC_Windows"): JSONObject? =
        get(
            "/wxUnifyId/checkBinding",
            mapOf("wxCode" to "", "openid" to openid, "phoneType" to phoneType),
            useToken = false
        )

    fun getRule() = get("/setting/getRule")
    fun getPlayground() = get("/place/getPlayground")

    /**
     * 区域多边形来源。注意：小程序里那个 /areaModel/listAll/{x} 实测返回空对象，
     * 真正的多边形在 /area/getAllArea。返回的每一项带 placeCode + areaFuncCode +
     * areaPointList。
     */
    fun getAllArea() = get("/area/getAllArea")

    /** areaFuncCode 字典：dkq=打卡区 dzwl=电子围栏 jpq=禁跑区 */
    fun getAreaFunc() = get("/areaFunc/getFunc")

    /** 成绩汇总，字段：sportsResultNo / placeName / distance / duration / isValid / reason */
    fun getSportsResult() = get("/sportsResult/list")

    fun getAreaModelList(x: Int = 0) = get("/areaModel/listAll/$x")
    fun getPointsByNo(placeCode: String) = get("/sportPoint/listAll/$placeCode")
    fun getRecordInfo(no: String) = get("/sportRecord/info/$no")
    fun getRecordPage(page: Int = 1, size: Int = 8) = get("/sportRecord/mobile/list/$page/$size")

    fun startSport(placeName: String, placeCode: String): JSONObject? {
        // 逆向自 chunk_12.appservice.js：body 就这两个字段，data 直接是 sportRecordNo 字符串
        val body = JSONObject().apply {
            put("placeName", placeName)
            put("placeCode", placeCode)
        }
        return post("/sportRecord/sport/start2", body.toString())
    }

    fun uploadPoints(recordNo: String, points: List<JSONObject>): JSONObject? {
        val arr = org.json.JSONArray()
        points.forEach { arr.put(it) }
        val body = JSONObject().apply {
            put("sportRecordNo", recordNo)
            put("sportPointList", arr)
        }
        return post("/sportRecord/point/saveListByNo", body.toString())
    }

    fun endSport(recordNo: String) = post("/sportRecord/sport/end/$recordNo", null)

    // ---------------------------------------------------------------- 工具

    fun mask(s: String): String =
        if (s.length <= 8) "***" else s.take(4) + "****" + s.takeLast(4)

    fun truncate(s: String, n: Int = 300): String =
        if (s.length <= n) s else s.substring(0, n) + "...(${s.length}B)"
}

/** 业务响应包一层，方便判断 code == 10200。 */
class Resp(val ok: Boolean, val code: String, val msg: String, val data: Any?) {
    companion object {
        fun of(j: JSONObject?): Resp {
            if (j == null) return Resp(false, "-1", "网络异常", null)
            val code = j.optString("code", "?")
            // 去掉 JSONObject 返回的字符串引号
            val data = j.opt("data")
            return Resp(code == "10200", code, j.optString("msg", ""), data)
        }
    }
}
