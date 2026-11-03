package com.stockai.app.data

import com.stockai.app.engine.Fundamentals
import org.json.JSONArray
import java.io.File
import java.util.Locale

data class SearchHit(val symbol: String, val name: String, val market: String)

/** 台股代號/名稱目錄、估值資料與股票搜尋。 */
object Info {
    private const val DAY = 86_400_000L

    private fun cacheFile(name: String) = DataConfig.cacheDir?.let { File(it, name) }

    private fun readCache(name: String, ttl: Long, allowStale: Boolean = false): String? {
        val f = cacheFile(name) ?: return null
        if (!f.exists()) return null
        if (!allowStale && System.currentTimeMillis() - f.lastModified() > ttl) return null
        return try { f.readText() } catch (_: Exception) { null }
    }

    private fun writeCache(name: String, text: String) {
        val f = cacheFile(name) ?: return
        try { f.parentFile?.mkdirs(); f.writeText(text) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ 台股目錄
    data class TwEntry(val code: String, val name: String, val market: String) // market: 上市 / 上櫃

    @Volatile private var twDir: Map<String, TwEntry>? = null

    /** 上市（含 ETF）+ 上櫃（股票與 ETF），共約 3000 檔，快取 7 天。 */
    fun twDirectory(): Map<String, TwEntry> {
        twDir?.let { return it }
        synchronized(this) {
            twDir?.let { return it }
            val cached = readCache("tw_directory.json", 7 * DAY)
            val list: List<TwEntry> = if (cached != null) parseDir(JSONArray(cached)) else try {
                val out = LinkedHashMap<String, TwEntry>()
                val a = Http.getArr("https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL", timeoutMs = 30000)
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    out[it.getString("Code")] = TwEntry(it.getString("Code"), it.getString("Name").trim(), "上市")
                }
                val b = Http.getArr("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_peratio_analysis", timeoutMs = 30000)
                for (i in 0 until b.length()) b.getJSONObject(i).let {
                    out[it.getString("SecuritiesCompanyCode")] =
                        TwEntry(it.getString("SecuritiesCompanyCode"), it.getString("CompanyName").trim(), "上櫃")
                }
                try { // 上櫃 ETF 只出現在每日行情
                    val c = Http.getArr("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes", timeoutMs = 45000)
                    for (i in 0 until c.length()) c.getJSONObject(i).let {
                        val code = it.getString("SecuritiesCompanyCode")
                        if (code.startsWith("00") && code !in out) out[code] = TwEntry(code, it.getString("CompanyName").trim(), "上櫃")
                    }
                } catch (_: Exception) {}
                val res = out.values.toList()
                writeCache("tw_directory.json", JSONArray(res.map { JSONArray(listOf(it.code, it.name, it.market)) }).toString())
                res
            } catch (e: Exception) {
                readCache("tw_directory.json", 0, allowStale = true)?.let { parseDir(JSONArray(it)) } ?: throw e
            }
            return list.associateBy { it.code }.also { twDir = it }
        }
    }

    private fun parseDir(a: JSONArray) = (0 until a.length()).map {
        val r = a.getJSONArray(it); TwEntry(r.getString(0), r.getString(1), r.getString(2))
    }

    fun twEntry(symbol: String): TwEntry? = try { twDirectory()[Sources.twCode(symbol)] } catch (_: Exception) { null }

    /** 依目錄補上正確的 .TW / .TWO 後綴。 */
    fun resolveTwSuffix(symbol: String): String {
        val s = symbol.uppercase()
        if (!s.endsWith(".TW") && !s.endsWith(".TWO")) return s
        val e = twEntry(s) ?: return s
        return Sources.twCode(s) + if (e.market == "上櫃") ".TWO" else ".TW"
    }

    // ------------------------------------------------------------------ 估值 / 基本面
    private class Val(val pe: Double, val yld: Double, val pb: Double, val dps: Double, val date: String, val market: String)
    @Volatile private var twVal: Map<String, Val>? = null
    @Volatile private var twValAt = 0L

    private fun twValuation(): Map<String, Val> {
        val cur = twVal
        if (cur != null && System.currentTimeMillis() - twValAt < 6 * 3_600_000L) return cur
        synchronized(this) {
            val out = HashMap<String, Val>()
            val a = Http.getArr("https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL", timeoutMs = 30000)
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                out[it.getString("Code")] = Val(Sources.num(it.opt("PEratio")), Sources.num(it.opt("DividendYield")),
                    Sources.num(it.opt("PBratio")), Double.NaN, it.optString("Date"), "上市")
            }
            val b = Http.getArr("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_peratio_analysis", timeoutMs = 30000)
            for (i in 0 until b.length()) b.getJSONObject(i).let {
                out[it.getString("SecuritiesCompanyCode")] = Val(Sources.num(it.opt("PriceEarningRatio")),
                    Sources.num(it.opt("YieldRatio")), Sources.num(it.opt("PriceBookRatio")),
                    Sources.num(it.opt("DividendPerShare")), it.optString("Date"), "上櫃")
            }
            twVal = out; twValAt = System.currentTimeMillis()
            return out
        }
    }

    private fun f2(x: Double) = String.format(Locale.US, "%,.2f", x)

    private fun notes(pe: Double, yld: Double, pb: Double, upside: Double): List<String> {
        val out = ArrayList<String>()
        if (!pe.isNaN() && pe != 0.0)
            out += if (pe < 12) "💡 本益比偏低（<12）" else if (pe > 35) "⚠️ 本益比偏高（>35）" else "➖ 本益比 ${String.format(Locale.US, "%.1f", pe)} 合理區間"
        if (!yld.isNaN() && yld >= 5) out += "💰 高殖利率 ${f2(yld)}%"
        if (!pb.isNaN() && pb != 0.0 && pb < 1) out += "💡 股價低於淨值（PB ${f2(pb)}）"
        if (!upside.isNaN())
            out += "${if (upside > 10) "✅" else if (upside < 0) "⚠️" else "➖"} 分析師目標價空間 ${String.format(Locale.US, "%+.1f", upside)}%"
        return out
    }

    private val usCache = HashMap<String, Pair<Long, Fundamentals>>()

    /** 取不到時回傳 EMPTY，不影響主分析。[hintName] 為資料來源順帶回傳的公司名稱。 */
    fun fundamentals(symbol: String, price: Double, hintName: String? = null): Fundamentals = try {
        if (Sources.marketOf(symbol) == "tw") {
            val code = Sources.twCode(symbol)
            val v = try { twValuation()[code] } catch (_: Exception) { null }
            val name = twEntry(symbol)?.name
            val items = ArrayList<Pair<String, String>>()
            if (v != null) {
                if (!v.pe.isNaN()) items += "本益比" to f2(v.pe)
                if (!v.yld.isNaN()) items += "殖利率%" to f2(v.yld)
                if (!v.pb.isNaN()) items += "股價淨值比" to f2(v.pb)
                if (!v.dps.isNaN()) items += "每股股利" to f2(v.dps)
                items += "資料日" to v.date; items += "市場" to v.market
            }
            Fundamentals(name, items, if (v != null) notes(v.pe, v.yld, v.pb, Double.NaN) else emptyList())
        } else {
            val key = symbol.uppercase()
            val hit = usCache[key]
            val base = if (hit != null && System.currentTimeMillis() - hit.first < 6 * 3_600_000L) hit.second else usSummary(key).also {
                usCache[key] = System.currentTimeMillis() to it
            }
            val target = base.items.firstOrNull { it.first == "一年目標價" }?.second?.replace(",", "")?.toDoubleOrNull()
            if (target != null && price > 0) {
                val up = (target / price - 1) * 100
                base.copy(name = base.name ?: hintName,
                    items = base.items + ("目標價空間%" to String.format(Locale.US, "%+.1f", up)),
                    notes = base.notes + notes(Double.NaN, Double.NaN, Double.NaN, up))
            } else base.copy(name = base.name ?: hintName)
        }.let { f -> if (f.name == null && Sources.marketOf(symbol) == "us") f.copy(name = usName(symbol)) else f }
    } catch (_: Exception) {
        Fundamentals(name = hintName ?: if (Sources.marketOf(symbol) == "tw") twEntry(symbol)?.name else null)
    }

    private fun usSummary(symbol: String): Fundamentals {
        for (ac in listOf("stocks", "etf")) {
            val j = Http.getObj("https://api.nasdaq.com/api/quote/$symbol/summary", mapOf("assetclass" to ac))
            val sd = j.optJSONObject("data")?.optJSONObject("summaryData") ?: continue
            fun g(k: String) = sd.optJSONObject(k)?.optString("value")?.takeIf { it.isNotBlank() && it != "N/A" }
            val items = ArrayList<Pair<String, String>>()
            (g("Sector") ?: g("Category"))?.let { items += "產業" to it }
            g("Industry")?.let { items += "細產業" to it }
            g("MarketCap")?.let { Sources.num(it).takeIf { v -> !v.isNaN() }?.let { v -> items += "市值(億美元)" to f2(v / 1e8) } }
            g("Yield")?.let { Sources.num(it).takeIf { v -> !v.isNaN() }?.let { v -> items += "殖利率%" to f2(v) } }
            g("PERatio")?.let { Sources.num(it).takeIf { v -> !v.isNaN() }?.let { v -> items += "本益比" to f2(v) } }
            g("OneYrTarget")?.let { Sources.num(it).takeIf { v -> !v.isNaN() }?.let { v -> items += "一年目標價" to f2(v) } }
            g("FiftTwoWeekHighLow")?.split("/")?.let { hl ->
                Sources.num(hl.first()).takeIf { !it.isNaN() }?.let { items += "52週高" to f2(it) }
                Sources.num(hl.last()).takeIf { !it.isNaN() }?.let { items += "52週低" to f2(it) }
            }
            val pe = items.firstOrNull { it.first == "本益比" }?.second?.replace(",", "")?.toDoubleOrNull() ?: Double.NaN
            val yl = items.firstOrNull { it.first == "殖利率%" }?.second?.replace(",", "")?.toDoubleOrNull() ?: Double.NaN
            return Fundamentals(null, items, notes(pe, yl, Double.NaN, Double.NaN))
        }
        return Fundamentals.EMPTY
    }

    // ------------------------------------------------------------------ 搜尋
    /** 搜尋台股（代號或中文名稱，本機目錄）與美股（Yahoo 搜尋）。 */
    fun search(query: String): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = LinkedHashMap<String, SearchHit>()
        val up = q.uppercase()

        try {
            val dir = twDirectory().values
            val exact = dir.filter { it.code.equals(up, true) }
            val prefix = dir.filter { it.code.startsWith(up, true) && it !in exact }
            val byName = dir.filter { it.name.contains(q, true) && it !in exact && it !in prefix }
            for (e in (exact + prefix + byName).take(15)) {
                val sym = e.code + if (e.market == "上櫃") ".TWO" else ".TW"
                hits[sym] = SearchHit(sym, e.name, "台股${e.market}")
            }
        } catch (_: Exception) {}

        if (!q.all { it.code > 127 || it.isDigit() }) { // 純中文或純數字不必查美股
            try {
                for (h in nasdaqLookup(q).take(8)) hits.putIfAbsent(h.symbol, h)
            } catch (_: Exception) {}
        }
        return hits.values.toList()
    }

    /** Nasdaq 官方搜尋（只保留股票與 ETF，並以代號相符者優先）。 */
    private fun nasdaqLookup(q: String): List<SearchHit> {
        val arr = Http.getObj("https://api.nasdaq.com/api/autocomplete/slookup/20", mapOf("search" to q))
            .optJSONArray("data") ?: return emptyList()
        val out = ArrayList<SearchHit>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val asset = o.optString("asset")
            val sym = o.optString("symbol")
            if (asset !in setOf("STOCKS", "ETF") || sym.isBlank() || !sym.all { it.isLetterOrDigit() || it == '-' }) continue
            val exch = o.optString("exchange").takeIf { it.isNotBlank() && it != "null" } ?: "US"
            out += SearchHit(sym, cleanUsName(o.optString("name")), "美股 $exch")
        }
        return out.sortedBy { if (it.symbol.equals(q, true)) 0 else if (it.symbol.startsWith(q, true)) 1 else 2 }
    }

    private fun cleanUsName(n: String) = n.trim().removeSuffix("Common Stock").removeSuffix("Ordinary Shares").trim()

    private val usNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 美股公司名稱（Nasdaq 官方搜尋，失敗回傳 null）。 */
    fun usName(symbol: String): String? {
        val s = symbol.uppercase()
        usNames[s]?.let { return it }
        return try { nasdaqLookup(s).firstOrNull { it.symbol == s }?.name?.also { usNames[s] = it } } catch (_: Exception) { null }
    }

    /** 驗證代號能取得資料，回傳 (標準化代號, 名稱)。 */
    fun resolve(input: String): Pair<String, String> {
        val f = Sources.fetch(input.trim().uppercase(), "1y")
        val sym = resolveTwSuffix(f.symbol)
        val name = f.name ?: if (Sources.marketOf(sym) == "tw") twEntry(sym)?.name ?: "" else usName(sym) ?: ""
        return sym to name
    }

}
