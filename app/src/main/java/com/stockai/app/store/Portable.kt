package com.stockai.app.store

import com.stockai.app.data.Sources
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** 自選股的匯入 / 匯出：JSON（含設定，不含金鑰）與純文字 / CSV 清單。 */
object Portable {
    const val FORMAT = "StockAI"
    const val MAX_ITEMS = 500
    const val MAX_CHARS = 1_000_000

    data class Parsed(val items: List<WatchItem>, val settings: Settings?, val skipped: List<String>)

    data class MergeResult(
        val items: List<WatchItem>,   // 套用後的完整清單
        val added: List<WatchItem>,   // 新加入、需要分析的股票
        val duplicates: Int,          // 已存在而略過的數量
        val removed: List<WatchItem>, // 取代模式下被移除的股票
    )

    private val SYMBOL = Regex("^[A-Z0-9]{1,10}([.\\-][A-Z0-9]{1,4})?$")
    private val HEADERS = setOf("SYMBOL", "CODE", "TICKER", "代號", "股票代號", "股票代碼", "代碼")

    /** 去重用的鍵：台股只看代號（2330 與 2330.TW 視為同一檔），美股看完整代號。 */
    fun key(symbol: String): String {
        val s = symbol.trim().uppercase()
        return if (Sources.marketOf(s) == "tw") "TW:" + Sources.twCode(s) else s
    }

    // ------------------------------------------------------------------ 匯出
    fun exportJson(items: List<WatchItem>, settings: Settings?, exportedAtMillis: Long): String {
        val o = JSONObject()
            .put("app", FORMAT).put("version", 1)
            .put("exportedAt", Instant.ofEpochMilli(exportedAtMillis).toString())
            .put("watchlist", JSONArray().also { a -> items.forEach { a.put(JSONObject().put("symbol", it.symbol).put("name", it.name)) } })
        if (settings != null) o.put("settings", settingsToJson(settings, includeSecrets = false))
        return o.toString(2)
    }

    fun exportCsv(items: List<WatchItem>): String =
        "symbol,name\n" + items.joinToString("\n") { "${it.symbol},${csv(it.name)}" } + "\n"

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ------------------------------------------------------------------ 匯入
    /** 自動判斷 JSON 或文字 / CSV。完全讀不到任何股票時丟出 IllegalArgumentException。 */
    fun parse(text: String): Parsed {
        require(text.length <= MAX_CHARS) { "檔案太大（超過 1 MB），應該不是自選股清單" }
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        val parsed = if (t.startsWith("{") || t.startsWith("[")) parseJson(t) else parseText(t)
        require(parsed.items.isNotEmpty()) { "檔案裡找不到任何股票代號" }
        return parsed
    }

    private fun parseJson(t: String): Parsed {
        val o = try { if (t.startsWith("[")) JSONObject().put("watchlist", JSONArray(t)) else JSONObject(t) }
        catch (e: Exception) { throw IllegalArgumentException("JSON 格式錯誤：${e.message?.take(60)}") }
        if (o.has("app") && o.optString("app") != FORMAT) throw IllegalArgumentException("這不是本程式匯出的檔案")
        val arr = o.optJSONArray("watchlist") ?: throw IllegalArgumentException("檔案裡沒有 watchlist 欄位")
        val items = ArrayList<WatchItem>(); val skipped = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val e = arr.opt(i)
            val (sym, name) = when (e) {
                is JSONObject -> e.optString("symbol") to e.optString("name")
                is String -> e to ""
                else -> "" to ""
            }
            val s = sym.trim().uppercase()
            if (SYMBOL.matches(s)) items += WatchItem(s, name.trim()) else skipped += (sym.ifBlank { "第 ${i + 1} 筆" })
        }
        val settings = o.optJSONObject("settings")?.let { settingsFromJson(it) }
        return Parsed(dedupe(items).take(MAX_ITEMS), settings, skipped)
    }

    private fun parseText(t: String): Parsed {
        val items = ArrayList<WatchItem>(); val skipped = ArrayList<String>()
        for (raw in t.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val parts = if (line.any { it == ',' || it == ';' || it == '\t' }) splitCsv(line) else line.split(Regex("\\s+"), limit = 2)
            val first = parts[0].trim().trim('"').uppercase()
            if (first in HEADERS) continue
            if (SYMBOL.matches(first)) items += WatchItem(first, parts.getOrElse(1) { "" }.trim().trim('"'))
            else skipped += line.take(30)
        }
        return Parsed(dedupe(items).take(MAX_ITEMS), null, skipped)
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                (c == ',' || c == ';' || c == '\t') && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun dedupe(items: List<WatchItem>): List<WatchItem> {
        val seen = HashSet<String>()
        return items.filter { seen.add(key(it.symbol)) }
    }

    // ------------------------------------------------------------------ 套用
    fun merge(existing: List<WatchItem>, incoming: List<WatchItem>, replace: Boolean): MergeResult {
        if (replace) {
            val old = existing.associateBy { key(it.symbol) }
            val inKeys = incoming.map { key(it.symbol) }.toSet()
            // 已存在的沿用原本（可能已標準化成 2330.TW 並有名稱）的項目，不必重新分析
            val items = incoming.map { old[key(it.symbol)] ?: it }
            val added = incoming.filter { key(it.symbol) !in old }
            return MergeResult(items, added, incoming.size - added.size, existing.filter { key(it.symbol) !in inKeys })
        }
        val have = existing.map { key(it.symbol) }.toMutableSet()
        val added = incoming.filter { have.add(key(it.symbol)) }
        return MergeResult(existing + added, added, incoming.size - added.size, emptyList())
    }
}
