package com.stockai.app.data

import com.stockai.app.engine.Bar
import com.stockai.app.engine.Fetched
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 多重資料來源與自動備援（與 Python 版 sources.py 對應）。
 *
 *  yahoo     Yahoo Finance chart API         台/美（已還原權息）
 *  twse      證交所官方 STOCK_DAY             台（上市）
 *  tpex      櫃買中心官方                      台（上櫃）
 *  finmind   FinMind 開放資料                 台/美
 *  nasdaq    Nasdaq.com                       美
 *  alphavantage  Alpha Vantage（需金鑰）       美
 */
object Sources {
    const val MIN_ROWS = 130
    val PERIOD_DAYS = mapOf("6mo" to 183, "1y" to 366, "2y" to 731, "3y" to 1096, "5y" to 1827)
    val PRICE_ORDER = mapOf(
        "tw" to listOf("yahoo", "twse", "tpex", "finmind"),
        "us" to listOf("yahoo", "nasdaq", "finmind", "alphavantage"),
    )
    private val SUPPORTS = mapOf(
        "yahoo" to setOf("tw", "us"), "twse" to setOf("tw"), "tpex" to setOf("tw"),
        "finmind" to setOf("tw", "us"), "nasdaq" to setOf("us"), "alphavantage" to setOf("us"),
    )
    val ALL_SOURCES = listOf("yahoo", "twse", "tpex", "finmind", "nasdaq", "alphavantage")
    private val OFFICIAL_UNADJUSTED = setOf("twse", "tpex", "finmind", "nasdaq", "alphavantage")

    private val TW_CODE = Regex("^\\d{4,6}[A-Z]?(\\.TWO?)?$")

    fun marketOf(symbol: String): String {
        val s = symbol.uppercase()
        return if (s == "^TWII" || TW_CODE.matches(s)) "tw" else "us"
    }

    fun twCode(symbol: String) = symbol.uppercase().substringBefore('.')

    // ------------------------------------------------------------------ 解析工具
    internal fun num(x: Any?): Double {
        if (x == null || x == JSONObject.NULL) return Double.NaN
        if (x is Number) return x.toDouble()
        val s = x.toString().replace(",", "").replace("$", "").replace("+", "").replace("%", "").trim()
        if (s in setOf("", "--", "---", "N/A", "X0.00")) return Double.NaN
        return s.trimStart('X').toDoubleOrNull() ?: Double.NaN
    }

    /** 民國日期 115/10/01 → 2026-10-01 */
    internal fun rocDate(s: String): LocalDate {
        val (y, m, d) = s.trim().replace("＊", "").split("/")
        return LocalDate.of(y.toInt() + 1911, m.toInt(), d.toInt())
    }

    private class Row(val date: LocalDate, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

    /** 排序、去重、移除無效收盤價，空的開高低以收盤補。 */
    private fun toBars(rows: List<Row>): List<Bar> {
        val m = java.util.TreeMap<LocalDate, Row>()
        rows.forEach { m[it.date] = it } // 後者覆蓋前者
        return m.values.filter { !it.c.isNaN() && it.c > 0 }.map {
            Bar(it.date, if (it.o.isNaN()) it.c else it.o, if (it.h.isNaN()) it.c else it.h,
                if (it.l.isNaN()) it.c else it.l, it.c, if (it.v.isNaN()) 0.0 else it.v)
        }
    }

    // ------------------------------------------------------------------ 各來源
    internal class Result(val bars: List<Bar>, val symbol: String, val name: String? = null)

    private fun srcYahoo(symbol: String, days: Int): Result {
        val s = symbol.uppercase()
        val cands = if ("." in s || marketOf(s) == "us" || s.startsWith("^")) listOf(s) else listOf("$s.TW", "$s.TWO")
        val range = when { days > 1827 -> "10y"; days > 1096 -> "5y"; days > 366 -> "2y"; else -> "1y" }
        var last: Exception? = null
        for (sym in cands) for (host in listOf("query1", "query2")) {
            try {
                val j = Http.getObj("https://$host.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode(sym, "UTF-8")}",
                    mapOf("range" to range, "interval" to "1d", "events" to "div,splits"))
                val res = j.optJSONObject("chart")?.optJSONArray("result")
                if (res == null || res.length() == 0) throw SourceError(j.optJSONObject("chart")?.optString("error") ?: "無結果")
                val r = res.getJSONObject(0)
                val meta = r.getJSONObject("meta")
                val ts = r.getJSONArray("timestamp")
                val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
                val adj = r.getJSONObject("indicators").optJSONArray("adjclose")?.optJSONObject(0)?.optJSONArray("adjclose")
                val off = meta.optLong("gmtoffset", 0)
                val rows = (0 until ts.length()).map { i ->
                    val d = Instant.ofEpochSecond(ts.getLong(i) + off).atOffset(ZoneOffset.UTC).toLocalDate()
                    var o = q.getJSONArray("open").optDouble(i, Double.NaN)
                    var h = q.getJSONArray("high").optDouble(i, Double.NaN)
                    var l = q.getJSONArray("low").optDouble(i, Double.NaN)
                    var c = q.getJSONArray("close").optDouble(i, Double.NaN)
                    val v = q.getJSONArray("volume").optDouble(i, Double.NaN)
                    val a = adj?.optDouble(i, Double.NaN) ?: Double.NaN
                    if (!a.isNaN() && !c.isNaN() && c != 0.0) { // 還原權息：依調整係數縮放 OHLC
                        val f = a / c; o *= f; h *= f; l *= f; c *= f
                    }
                    Row(d, o, h, l, c, v)
                }
                val name = meta.optString("longName").ifBlank { meta.optString("shortName") }.ifBlank { null }
                return Result(toBars(rows), sym, name)
            } catch (e: Exception) { last = e }
        }
        throw SourceError("Yahoo 無資料：${last?.message}")
    }

    private fun monthsBetween(start: LocalDate, end: LocalDate): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var y = start.year; var m = start.monthValue
        while (y < end.year || (y == end.year && m <= end.monthValue)) {
            out += y to m
            if (m == 12) { y++; m = 1 } else m++
        }
        return out
    }

    private fun rowsToJson(rows: List<Row>) = JSONArray().also { a ->
        rows.forEach { a.put(JSONArray(listOf(it.date.toString(), it.o, it.h, it.l, it.c, it.v))) }
    }

    private fun rowsFromJson(a: JSONArray) = (0 until a.length()).map {
        val r = a.getJSONArray(it)
        Row(LocalDate.parse(r.getString(0)), r.optDouble(1, Double.NaN), r.optDouble(2, Double.NaN),
            r.optDouble(3, Double.NaN), r.optDouble(4, Double.NaN), r.optDouble(5, Double.NaN))
    }

    /** 官方月資料：已結束的月份永久快取，當月只快取 10 分鐘；空結果不快取（可能是暫時被擋）。 */
    private fun monthlyCached(key: String, y: Int, m: Int, today: LocalDate, fetch: () -> List<Row>): List<Row> {
        val dir = DataConfig.cacheDir?.let { File(it, "monthly") }
        val f = dir?.let { File(it, "${key}_$y${"%02d".format(m)}.json") }
        val current = y == today.year && m == today.monthValue
        if (f != null && f.exists() && (!current || System.currentTimeMillis() - f.lastModified() < 600_000)) {
            try { return rowsFromJson(JSONArray(f.readText())) } catch (_: Exception) { f.delete() }
        }
        val rows = fetch()
        if (f != null && rows.isNotEmpty()) { dir.mkdirs(); f.writeText(rowsToJson(rows).toString()) }
        return rows
    }

    /** 由新到舊逐月抓取；最近兩個月都沒資料視為代號不存在，遇到空月（上市前）即停止。 */
    private fun officialMonths(key: String, days: Int, today: LocalDate, fetchMonth: (Int, Int) -> List<Row>): List<Bar> {
        val months = monthsBetween(today.minusDays(days.toLong()), today).reversed()
        val rows = ArrayList<Row>()
        for ((i, ym) in months.withIndex()) {
            val got = try {
                monthlyCached(key, ym.first, ym.second, today) { fetchMonth(ym.first, ym.second) }
            } catch (e: SourceError) {
                if (rows.size >= MIN_ROWS) break // 已有足夠連續的近期資料，較舊月份失敗就截斷
                throw e
            }
            if (got.isNotEmpty()) { rows += got; continue }
            if (rows.isNotEmpty() || i >= 1) {
                if (rows.isEmpty()) throw SourceError("查無此代號資料")
                break
            }
        }
        if (rows.isEmpty()) throw SourceError("查無此代號資料")
        return toBars(rows)
    }

    private fun srcTwse(symbol: String, days: Int, today: LocalDate): Result {
        val code = twCode(symbol)
        if (symbol.uppercase().endsWith(".TWO")) throw SourceError("上櫃股票請用 tpex")
        val isIndex = symbol.uppercase() == "^TWII"
        val fetchMonth = { y: Int, m: Int ->
            if (isIndex) {
                val j = Http.getObj("https://www.twse.com.tw/rwd/zh/TAIEX/MI_5MINS_HIST",
                    mapOf("date" to "$y${"%02d".format(m)}01", "response" to "json"))
                val data = j.optJSONArray("data") ?: JSONArray()
                (0 until data.length()).map {
                    val r = data.getJSONArray(it)
                    Row(rocDate(r.getString(0)), num(r.get(1)), num(r.get(2)), num(r.get(3)), num(r.get(4)), 0.0)
                }
            } else {
                val j = Http.getObj("https://www.twse.com.tw/rwd/zh/afterTrading/STOCK_DAY",
                    mapOf("date" to "$y${"%02d".format(m)}01", "stockNo" to code, "response" to "json"))
                if (j.optString("stat") != "OK") emptyList()
                else {
                    val data = j.getJSONArray("data")
                    (0 until data.length()).map {
                        val r = data.getJSONArray(it)
                        Row(rocDate(r.getString(0)), num(r.get(3)), num(r.get(4)), num(r.get(5)), num(r.get(6)), num(r.get(1)))
                    }
                }
            }
        }
        val bars = officialMonths("twse_${code.replace("^", "_")}", days, today, fetchMonth)
        return Result(bars, if (isIndex) "^TWII" else "$code.TW")
    }

    private fun srcTpex(symbol: String, days: Int, today: LocalDate): Result {
        val code = twCode(symbol)
        if (symbol.uppercase().endsWith(".TW") || symbol.uppercase() == "^TWII") throw SourceError("上市股票請用 twse")
        val bars = officialMonths("tpex_$code", days, today) { y, m ->
            val j = Http.getObj("https://www.tpex.org.tw/www/zh-tw/afterTrading/tradingStock",
                mapOf("code" to code, "date" to "$y/${"%02d".format(m)}/01", "response" to "json"))
            val data = j.optJSONArray("tables")?.optJSONObject(0)?.optJSONArray("data") ?: JSONArray()
            (0 until data.length()).map {
                val r = data.getJSONArray(it)
                // 成交張數 × 1000 = 股數
                Row(rocDate(r.getString(0)), num(r.get(3)), num(r.get(4)), num(r.get(5)), num(r.get(6)), num(r.get(1)) * 1000)
            }
        }
        return Result(bars, "$code.TWO")
    }

    private fun srcFinmind(symbol: String, days: Int, today: LocalDate): Result {
        val tw = marketOf(symbol) == "tw"
        val up = symbol.uppercase()
        val (dataId, dataset) = when {
            tw && up == "^TWII" -> "TAIEX" to "TaiwanStockPrice"
            tw -> twCode(up) to "TaiwanStockPrice"
            else -> up to "USStockPrice"
        }
        val params = mutableMapOf("dataset" to dataset, "data_id" to dataId, "start_date" to today.minusDays(days.toLong()).toString())
        if (DataConfig.finmindToken.isNotBlank()) params["token"] = DataConfig.finmindToken
        val j = Http.getObj("https://api.finmindtrade.com/api/v4/data", params, 30000)
        if (j.optInt("status") != 200) throw SourceError("FinMind：${j.optString("msg")}（可在設定填入 FinMind Token）")
        val data = j.getJSONArray("data")
        val rows = (0 until data.length()).map {
            val r = data.getJSONObject(it)
            val d = LocalDate.parse(r.getString("date"))
            if (tw) Row(d, num(r.opt("open")), num(r.opt("max")), num(r.opt("min")), num(r.opt("close")), num(r.opt("Trading_Volume")))
            else Row(d, num(r.opt("Open")), num(r.opt("High")), num(r.opt("Low")), num(r.opt("Close")), num(r.opt("Volume")))
        }
        val sym = if (!tw) up else if ("." in up || up.startsWith("^")) up else "$dataId.TW"
        return Result(toBars(rows), sym)
    }

    private fun srcNasdaq(symbol: String, days: Int, today: LocalDate): Result {
        var sym = symbol.uppercase()
        if (sym == "^GSPC") sym = "SPY" // Nasdaq API 不提供 S&P 500 指數歷史，以 SPY ETF 代替
        var last: Exception? = null
        val fmt = DateTimeFormatter.ofPattern("MM/dd/yyyy")
        for (ac in listOf("stocks", "etf")) {
            try {
                val j = Http.getObj("https://api.nasdaq.com/api/quote/${URLEncoder.encode(sym, "UTF-8")}/historical",
                    mapOf("assetclass" to ac, "fromdate" to today.minusDays(days.toLong()).toString(),
                        "todate" to today.toString(), "limit" to "9999"), 20000)
                val t = j.optJSONObject("data")?.optJSONObject("tradesTable")?.optJSONArray("rows")
                if (t == null || t.length() == 0) throw SourceError("Nasdaq 無此代號")
                val rows = (0 until t.length()).map {
                    val r = t.getJSONObject(it)
                    Row(LocalDate.parse(r.getString("date"), fmt), num(r.opt("open")), num(r.opt("high")),
                        num(r.opt("low")), num(r.opt("close")), num(r.opt("volume")))
                }
                return Result(toBars(rows), symbol.uppercase())
            } catch (e: Exception) { last = e }
        }
        throw SourceError("Nasdaq 無資料：${last?.message}")
    }

    private fun srcAlphaVantage(symbol: String, days: Int): Result {
        val key = DataConfig.alphaVantageKey
        if (key.isBlank()) throw SourceError("未設定 Alpha Vantage API Key")
        fun call(size: String?): JSONObject = Http.getObj("https://www.alphavantage.co/query",
            buildMap { put("function", "TIME_SERIES_DAILY"); put("symbol", symbol.uppercase()); put("apikey", key); size?.let { put("outputsize", it) } }, 30000)
        var j = call(if (days > 140) "full" else "compact")
        var ts = j.optJSONObject("Time Series (Daily)")
        if (ts == null) { j = call(null); ts = j.optJSONObject("Time Series (Daily)") } // 免費方案不支援 full
        if (ts == null) throw SourceError("Alpha Vantage：${j.optString("Note").ifBlank { j.optString("Information") }}")
        val rows = ts.keys().asSequence().map {
            val v = ts.getJSONObject(it)
            Row(LocalDate.parse(it), num(v.opt("1. open")), num(v.opt("2. high")), num(v.opt("3. low")), num(v.opt("4. close")), num(v.opt("5. volume")))
        }.toList()
        return Result(toBars(rows), symbol.uppercase())
    }

    // ------------------------------------------------------------------ 分割偵測
    private val COMMON_RATIOS = listOf(2, 3, 4, 5, 6, 8, 10, 15, 20, 25, 30, 50)

    /**
     * 還原未調整來源中的股票分割/反分割。台股漲跌幅限制 10%，單日變動超過 15% 必為分割或減資；
     * 美股則要求比率接近常見分割比例。
     */
    internal fun adjustSplits(bars: List<Bar>, mkt: String): Pair<List<Bar>, List<String>> {
        val n = bars.size
        val o = DoubleArray(n) { bars[it].open }; val h = DoubleArray(n) { bars[it].high }
        val l = DoubleArray(n) { bars[it].low }; val c = DoubleArray(n) { bars[it].close }
        val v = DoubleArray(n) { bars[it].volume }
        val ratio = DoubleArray(n) { if (it == 0) Double.NaN else bars[it - 1].close / bars[it].close }
        val notes = ArrayList<String>()
        for (i in 1 until n) {
            val r = ratio[i]
            var k: Double? = null
            if (r > 1.6 || r < 0.625) {
                val inv = if (r > 1) r else 1 / r
                val near = COMMON_RATIOS.minBy { abs(it - inv) }
                if (abs(near - inv) / near < 0.06) k = if (r > 1) near.toDouble() else 1.0 / near
                else if (mkt == "tw" && (r > 1.15 || r < 0.85)) k = r // 減資等非整數比例
            }
            if (k != null) {
                for (j in 0 until i) { o[j] /= k; h[j] /= k; l[j] /= k; c[j] /= k; v[j] *= k }
                notes += "${bars[i].date} 偵測到${if (k > 1) "分割" else "反分割/減資"}（×${"%.4g".format(k)}），已還原"
            }
        }
        return List(n) { Bar(bars[it].date, o[it], h[it], l[it], c[it], v[it]) } to notes
    }

    // ------------------------------------------------------------------ 對外介面
    private val priceCache = ConcurrentHashMap<String, Pair<Long, Fetched>>()

    fun clearCache() = priceCache.clear()

    private fun run(name: String, symbol: String, days: Int, today: LocalDate): Result = when (name) {
        "yahoo" -> srcYahoo(symbol, days)
        "twse" -> srcTwse(symbol, days, today)
        "tpex" -> srcTpex(symbol, days, today)
        "finmind" -> srcFinmind(symbol, days, today)
        "nasdaq" -> srcNasdaq(symbol, days, today)
        "alphavantage" -> srcAlphaVantage(symbol, days)
        else -> throw SourceError("未知來源")
    }

    /**
     * 取得日線資料。source 為 "auto"、單一來源或以逗號分隔的順序；任何錯誤都會換下一個來源。
     * [runner] 可在測試中替換各來源實作。
     */
    fun fetch(
        symbol: String, period: String = "2y", source: String = "auto", cacheMs: Long = 600_000,
        today: LocalDate = LocalDate.now(),
        runner: (String, String, Int, LocalDate) -> Any? = { n, s, d, t -> run(n, s, d, t) },
    ): Fetched {
        val days = PERIOD_DAYS[period] ?: 731
        val sym0 = symbol.trim().uppercase()
        val mkt = marketOf(sym0)
        var order = if (source == "auto") PRICE_ORDER.getValue(mkt).let { base ->
            // 有 FinMind Token 時一次請求就能取得整段歷史，比逐月抓官方資料快很多
            if (DataConfig.finmindToken.isNotBlank()) listOf(base.first(), "finmind") + base.drop(1).filter { it != "finmind" } else base
        } else source.split(",").map { it.trim() }
        order = order.filter { mkt in (SUPPORTS[it] ?: setOf(mkt)) && it !in DataConfig.disabled }
        if (sym0.endsWith(".TWO")) order = order.filter { it != "twse" }
        if (sym0.endsWith(".TW")) order = order.filter { it != "tpex" }

        val errors = ArrayList<String>()
        for (name in order) {
            val ck = "$name|$sym0|$period"
            try {
                val cached = priceCache[ck]
                if (cacheMs > 0 && cached != null && System.currentTimeMillis() - cached.first < cacheMs) return cached.second
                @Suppress("UNCHECKED_CAST")
                val r = runner(name, sym0, days, today) as Result
                var bars = r.bars.filter { !it.date.isBefore(today.minusDays(days.toLong())) }
                if (bars.size < MIN_ROWS && !sym0.startsWith("^")) throw SourceError("資料只有 ${bars.size} 筆")
                val notes = ArrayList<String>()
                if (name in OFFICIAL_UNADJUSTED && !sym0.startsWith("^")) {
                    val (adj, n) = adjustSplits(bars, mkt); bars = adj; notes += n
                }
                if (bars.isNotEmpty() && !sym0.startsWith("^") &&
                    ChronoUnit.DAYS.between(today.minusDays(days.toLong()), bars.first().date) > 45)
                    notes += "歷史只從 ${bars.first().date} 起（少於要求的 $period），回測與風險指標的期間較短，不宜直接與其他來源比較"
                val out = Fetched(bars, r.symbol, name, notes, r.name)
                priceCache[ck] = System.currentTimeMillis() to out
                return out
            } catch (e: Exception) {
                errors += "$name: ${e.message}"
            }
        }
        throw SourceError("$sym0 所有來源都失敗 → ${errors.joinToString(" | ")}")
    }

    /** 測試所有來源（設定頁「檢查資料來源」使用）。回傳 (來源, 結果文字)。 */
    fun check(symbol: String): List<Pair<String, String>> {
        val mkt = marketOf(symbol)
        return PRICE_ORDER.getValue(mkt).filter { it !in DataConfig.disabled }.map { name ->
            val t0 = System.currentTimeMillis()
            try {
                val f = fetch(symbol, "1y", name, cacheMs = 0)
                name to "✅ ${f.bars.size} 筆，最新 ${f.bars.last().date} 收 ${"%.2f".format(f.bars.last().close)}（${(System.currentTimeMillis() - t0) / 1000.0}s）"
            } catch (e: Exception) {
                name to "❌ ${e.message?.substringAfter("→")?.trim()?.take(80)}"
            }
        }
    }
}
