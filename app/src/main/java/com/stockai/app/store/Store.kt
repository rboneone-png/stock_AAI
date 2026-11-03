package com.stockai.app.store

import android.content.Context
import com.stockai.app.data.DataConfig
import org.json.JSONArray
import org.json.JSONObject

data class WatchItem(val symbol: String, val name: String = "")

data class Settings(
    val monitorEnabled: Boolean = true,
    val intervalMin: Int = 60,
    val alertRating: Boolean = true,
    val alertStopTarget: Boolean = true,
    val alertMa60: Boolean = true,
    val alertHighLow: Boolean = true,
    val alertBigMove: Boolean = true,
    val bigMovePct: Double = 5.0,
    val alertVolume: Boolean = true,
    val capital: Double = 1_000_000.0,
    val riskPct: Double = 1.0, // 百分比
    val redUp: Boolean = true,
    val period: String = "2y",
    val finmindToken: String = "",
    val alphaKey: String = "",
    val disabledSources: Set<String> = emptySet(),
    val sort: String = "score", // score / name / manual
)

/** 上一次掃描的狀態，用來偵測「評等變化、跌破停損」等事件。 */
data class Summary(
    val rating: String, val score: Double, val price: Double, val changePct: Double,
    val stop: Double, val target: Double, val time: Long, val name: String = "",
)

data class AlertEvent(val time: Long, val symbol: String, val name: String, val text: String)

class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("stockai", Context.MODE_PRIVATE)

    companion object {
        val DEFAULT_WATCHLIST = listOf(
            WatchItem("2330.TW", "台積電"), WatchItem("2317.TW", "鴻海"), WatchItem("2454.TW", "聯發科"),
            WatchItem("0050.TW", "元大台灣50"), WatchItem("AAPL", "Apple Inc"), WatchItem("NVDA", "NVIDIA Corporation"),
            WatchItem("MSFT", "Microsoft Corporation"),
        )
        const val MAX_EVENTS = 200
    }

    // ---------------------------------------------------------------- 自選股
    fun loadWatchlist(): List<WatchItem> {
        val raw = sp.getString("watch", null) ?: return DEFAULT_WATCHLIST.also { saveWatchlist(it) }
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> WatchItem(o.getString("s"), o.optString("n")) } }
        } catch (_: Exception) { DEFAULT_WATCHLIST }
    }

    fun saveWatchlist(list: List<WatchItem>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("s", it.symbol).put("n", it.name)) }
        sp.edit().putString("watch", a.toString()).apply()
    }

    // ---------------------------------------------------------------- 設定
    fun loadSettings(): Settings = try {
        val o = JSONObject(sp.getString("settings", "{}")!!)
        val d = Settings()
        Settings(
            o.optBoolean("monitorEnabled", d.monitorEnabled), o.optInt("intervalMin", d.intervalMin),
            o.optBoolean("alertRating", d.alertRating), o.optBoolean("alertStopTarget", d.alertStopTarget),
            o.optBoolean("alertMa60", d.alertMa60), o.optBoolean("alertHighLow", d.alertHighLow),
            o.optBoolean("alertBigMove", d.alertBigMove), o.optDouble("bigMovePct", d.bigMovePct),
            o.optBoolean("alertVolume", d.alertVolume), o.optDouble("capital", d.capital),
            o.optDouble("riskPct", d.riskPct), o.optBoolean("redUp", d.redUp), o.optString("period", d.period),
            o.optString("finmindToken", ""), o.optString("alphaKey", ""),
            o.optString("disabledSources", "").split(",").filter { it.isNotBlank() }.toSet(),
            o.optString("sort", d.sort),
        )
    } catch (_: Exception) { Settings() }

    fun saveSettings(s: Settings) {
        val o = JSONObject()
            .put("monitorEnabled", s.monitorEnabled).put("intervalMin", s.intervalMin)
            .put("alertRating", s.alertRating).put("alertStopTarget", s.alertStopTarget)
            .put("alertMa60", s.alertMa60).put("alertHighLow", s.alertHighLow)
            .put("alertBigMove", s.alertBigMove).put("bigMovePct", s.bigMovePct)
            .put("alertVolume", s.alertVolume).put("capital", s.capital).put("riskPct", s.riskPct)
            .put("redUp", s.redUp).put("period", s.period).put("finmindToken", s.finmindToken)
            .put("alphaKey", s.alphaKey).put("disabledSources", s.disabledSources.joinToString(","))
            .put("sort", s.sort)
        sp.edit().putString("settings", o.toString()).apply()
        applyToData(s)
    }

    /** 把設定同步到資料層（金鑰、停用來源）。 */
    fun applyToData(s: Settings) {
        DataConfig.finmindToken = s.finmindToken.trim()
        DataConfig.alphaVantageKey = s.alphaKey.trim()
        DataConfig.disabled = s.disabledSources
    }

    // ---------------------------------------------------------------- 掃描狀態
    fun loadState(): Map<String, Summary> = try {
        val o = JSONObject(sp.getString("state", "{}")!!)
        o.keys().asSequence().associateWith { k ->
            o.getJSONObject(k).let {
                Summary(it.getString("rating"), it.getDouble("score"), it.getDouble("price"), it.getDouble("chg"),
                    it.getDouble("stop"), it.getDouble("target"), it.getLong("time"), it.optString("name"))
            }
        }
    } catch (_: Exception) { emptyMap() }

    fun saveState(m: Map<String, Summary>) {
        val o = JSONObject()
        m.forEach { (k, v) ->
            o.put(k, JSONObject().put("rating", v.rating).put("score", v.score).put("price", v.price)
                .put("chg", v.changePct).put("stop", v.stop).put("target", v.target).put("time", v.time).put("name", v.name))
        }
        sp.edit().putString("state", o.toString()).apply()
    }

    // ---------------------------------------------------------------- 警示紀錄
    fun loadEvents(): List<AlertEvent> = try {
        val a = JSONArray(sp.getString("events", "[]")!!)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> AlertEvent(o.getLong("t"), o.getString("s"), o.optString("n"), o.getString("x")) } }
    } catch (_: Exception) { emptyList() }

    fun appendEvents(new: List<AlertEvent>): List<AlertEvent> {
        val all = (new + loadEvents()).sortedByDescending { it.time }.take(MAX_EVENTS)
        saveEvents(all); return all
    }

    fun saveEvents(list: List<AlertEvent>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("t", it.time).put("s", it.symbol).put("n", it.name).put("x", it.text)) }
        sp.edit().putString("events", a.toString()).apply()
    }
}
