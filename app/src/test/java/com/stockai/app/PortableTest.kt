package com.stockai.app

import com.stockai.app.store.Portable
import com.stockai.app.store.Settings
import com.stockai.app.store.WatchItem
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableTest {
    private val items = listOf(WatchItem("2330.TW", "台積電"), WatchItem("AAPL", "Apple, Inc."), WatchItem("6488.TWO", "環球晶"))

    @Test fun jsonRoundTripKeepsOrderAndNames() {
        val text = Portable.exportJson(items, null, 0L)
        val p = Portable.parse(text)
        assertEquals(items, p.items)
        assertNull(p.settings)
    }

    @Test fun exportedSettingsNeverContainSecrets() {
        val s = Settings(finmindToken = "SECRET-TOKEN-1", alphaKey = "SECRET-KEY-2", redUp = false, capital = 500000.0, sort = "manual")
        val text = Portable.exportJson(items, s, 0L)
        assertFalse(text.contains("SECRET-TOKEN-1")); assertFalse(text.contains("SECRET-KEY-2"))
        assertFalse(JSONObject(text).getJSONObject("settings").has("finmindToken"))
        val back = Portable.parse(text).settings!!
        assertFalse(back.redUp); assertEquals(500000.0, back.capital, 0.0); assertEquals("manual", back.sort)
        assertEquals("", back.finmindToken)
    }

    @Test fun csvRoundTripHandlesCommasInNames() {
        val p = Portable.parse(Portable.exportCsv(items))
        assertEquals(items, p.items) // 標頭列被略過，"Apple, Inc." 的逗號被正確引號處理
    }

    @Test fun plainTextListVariants() {
        val p = Portable.parse("﻿# 我的清單\n2330 台積電\nAAPL\n  nvda  \n0050,元大台灣50\n\"BRK-B\",\"Berkshire\"\n00400A\n這不是代號!!\n")
        assertEquals(listOf("2330", "AAPL", "NVDA", "0050", "BRK-B", "00400A"), p.items.map { it.symbol })
        assertEquals("台積電", p.items[0].name); assertEquals("Berkshire", p.items[4].name)
        assertEquals(1, p.skipped.size)
    }

    @Test fun duplicatesInsideFileAreRemovedIncludingTwSuffixVariants() {
        val p = Portable.parse("2330\n2330.TW\nAAPL\naapl\n")
        assertEquals(listOf("2330", "AAPL"), p.items.map { it.symbol })
    }

    @Test fun badInputsGiveClearErrors() {
        assertThrows(IllegalArgumentException::class.java) { Portable.parse("") }
        assertThrows(IllegalArgumentException::class.java) { Portable.parse("{\"watchlist\": [") }
        assertThrows(IllegalArgumentException::class.java) { Portable.parse("{\"app\":\"Other\",\"watchlist\":[]}") }
        assertThrows(IllegalArgumentException::class.java) { Portable.parse("{\"foo\":1}") }
        assertThrows(IllegalArgumentException::class.java) { Portable.parse("x".repeat(Portable.MAX_CHARS + 1)) }
        assertTrue(Portable.parse("[\"AAPL\", {\"symbol\":\"nvda\"}, 5, {\"symbol\":\"bad symbol!\"}]").let { it.items.size == 2 && it.skipped.size == 2 })
    }

    @Test fun invalidSettingsValuesFallBackToDefaults() {
        val p = Portable.parse("""{"app":"StockAI","watchlist":["AAPL"],"settings":{"intervalMin":1,"capital":-5,"riskPct":99,"period":"9y","sort":"zzz"}}""")
        val s = p.settings!!
        assertEquals(15, s.intervalMin); assertEquals(1_000_000.0, s.capital, 0.0); assertEquals(20.0, s.riskPct, 0.0)
        assertEquals("2y", s.period); assertEquals("score", s.sort)
    }

    @Test fun mergeKeepsExistingAndAppendsNew() {
        val incoming = listOf(WatchItem("2330", ""), WatchItem("NVDA", "NVIDIA"), WatchItem("AAPL", ""))
        val r = Portable.merge(items, incoming, replace = false)
        assertEquals(listOf("2330.TW", "AAPL", "6488.TWO", "NVDA"), r.items.map { it.symbol })
        assertEquals(listOf("NVDA"), r.added.map { it.symbol }); assertEquals(2, r.duplicates)
        assertTrue(r.removed.isEmpty())
    }

    @Test fun replaceUsesFileOrderAndReportsRemoved() {
        val incoming = listOf(WatchItem("AAPL", ""), WatchItem("NVDA", "NVIDIA"), WatchItem("2330", ""))
        val r = Portable.merge(items, incoming, replace = true)
        assertEquals(listOf("AAPL", "NVDA", "2330.TW"), r.items.map { it.symbol }) // 順序依檔案；已存在的沿用標準化後項目
        assertEquals("Apple, Inc.", r.items[0].name)
        assertEquals(listOf("NVDA"), r.added.map { it.symbol })
        assertEquals(listOf("6488.TWO"), r.removed.map { it.symbol })
    }
}
