package com.stockai.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class SourceError(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/** 全域資料設定（由 App 啟動時與設定頁寫入）。 */
object DataConfig {
    @Volatile var cacheDir: File? = null
    @Volatile var finmindToken: String = ""
    @Volatile var alphaVantageKey: String = ""
    @Volatile var disabled: Set<String> = emptySet()
}

object Http {
    // 誠實標示 App 身分；不偽裝成瀏覽器。被來源拒絕時由備援機制改用其他來源。
    const val UA = "StockAI/1.0 (Android; personal stock analysis app)"

    // 各網域最小請求間隔（毫秒）；證交所超過約每 5 秒 3 次會被封鎖 IP
    private val throttle = mapOf("www.twse.com.tw" to 3500L, "www.tpex.org.tw" to 1200L, "www.alphavantage.co" to 12500L)
    private val lastHit = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()

    private fun query(params: Map<String, String>) =
        params.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

    fun get(url: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): ByteArray {
        val full = if (params.isEmpty()) url else "$url?${query(params)}"
        val host = URL(full).host
        val lock = locks.getOrPut(host) { Any() }
        var err: Throwable? = null
        for (attempt in 0 until 3) {
            // 同網域序列化，並遵守最小間隔
            synchronized(lock) {
                val wait = (throttle[host] ?: 0L) - (System.currentTimeMillis() - (lastHit[host] ?: 0L))
                if (wait > 0) Thread.sleep(wait)
                try {
                    return fetchOnce(full, timeoutMs)
                } catch (e: RetryableHttp) {
                    err = e
                } catch (e: SourceError) {
                    throw e
                } catch (e: Exception) {
                    err = e
                } finally {
                    lastHit[host] = System.currentTimeMillis()
                }
            }
            if (attempt < 2) Thread.sleep(3000L * (attempt + 1)) // 307/429/5xx 多為暫時性限流
        }
        throw SourceError("$host 連線失敗：${err?.message}", err)
    }

    private class RetryableHttp(msg: String) : Exception(msg)

    private fun fetchOnce(url: String, timeoutMs: Int): ByteArray {
        var target = url
        for (hop in 0 until 4) {
            val c = URL(target).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            try {
                val code = c.responseCode
                when {
                    code in 200..299 -> return c.inputStream.use { it.readBytes() }
                    code in listOf(301, 302, 303, 308) && c.getHeaderField("Location") != null ->
                        target = URL(URL(target), c.getHeaderField("Location")).toString()
                    code == 307 || code == 429 || code in 500..504 -> throw RetryableHttp("HTTP $code")
                    else -> throw SourceError("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
        throw RetryableHttp("轉址次數過多")
    }

    fun getJson(url: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): Any {
        val raw = get(url, params, timeoutMs)
        val text = String(raw, Charsets.UTF_8).trim()
        return try {
            when {
                text.startsWith("[") -> JSONArray(text)
                else -> JSONObject(text)
            }
        } catch (e: Exception) {
            throw SourceError("回應不是 JSON（可能被擋）：${text.take(60)}", e)
        }
    }

    fun getObj(url: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): JSONObject =
        getJson(url, params, timeoutMs) as? JSONObject ?: throw SourceError("預期為 JSON 物件")

    fun getArr(url: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): JSONArray =
        getJson(url, params, timeoutMs) as? JSONArray ?: throw SourceError("預期為 JSON 陣列")
}
