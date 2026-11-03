package com.stockai.app

import com.stockai.app.data.DataConfig
import com.stockai.app.data.Sources
import com.stockai.app.data.SourceError
import com.stockai.app.engine.Bar
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SourcesTest {
    private fun series(n: Int, start: LocalDate = LocalDate.of(2025, 1, 1), px: (Int) -> Double): List<Bar> =
        (0 until n).map { val c = px(it); Bar(start.plusDays(it.toLong()), c, c, c, c, 10.0) }

    @Test fun parsers() {
        assertEquals(LocalDate.of(2026, 10, 1), Sources.rocDate("115/10/01"))
        assertEquals(1234.5, Sources.num("1,234.50"), 0.0)
        assertEquals(0.31, Sources.num("\$0.31%"), 0.0)
        assertTrue(Sources.num("--").isNaN())
        assertEquals("tw", Sources.marketOf("6488")); assertEquals("tw", Sources.marketOf("00400A"))
        assertEquals("tw", Sources.marketOf("2330.TW")); assertEquals("tw", Sources.marketOf("^TWII"))
        assertEquals("us", Sources.marketOf("AAPL")); assertEquals("us", Sources.marketOf("BRK-B"))
    }

    @Test fun splitAdjustTaiwan() {
        val bars = series(10) { if (it < 5) 100.0 + it else 26.0 + (it - 5) * 0.5 }
        val (adj, notes) = Sources.adjustSplits(bars, "tw")
        assertEquals(1, notes.size)
        assertEquals(25.0, adj[0].close, 1e-9)
        assertEquals(40.0, adj[0].volume, 1e-9)
        assertEquals(26.0, adj[5].close, 1e-9)
    }

    @Test fun normalMoveIsNotSplit() {
        val bars = series(10) { if (it < 5) 100.0 else 91.0 } // 台股跌停 -9% 不是分割
        assertEquals(0, Sources.adjustSplits(bars, "tw").second.size)
    }

    @Test fun usRealCrashIsNotSplit() {
        val bars = series(10) { if (it < 5) 100.0 else 45.0 } // 美股 -55%：不接近常見分割比例
        assertEquals(0, Sources.adjustSplits(bars, "us").second.size)
    }

    @Test fun fallbackOrderAndFilter() {
        DataConfig.disabled = emptySet()
        val calls = ArrayList<String>()
        val today = LocalDate.of(2026, 11, 3)
        val good = series(300, today.minusDays(299)) { 100.0 + it * 0.1 }
        val f = Sources.fetch("9999", "2y", "yahoo,twse,nasdaq", cacheMs = 0, today = today) { n, s, _, _ ->
            calls += n
            if (n == "yahoo") throw SourceError("被擋")
            Sources.Result(good, "$s.TW")
        }
        assertEquals("twse", f.source)
        assertEquals(listOf("yahoo", "twse"), calls) // 台股不會去試美股專用的 nasdaq
        assertThrows(SourceError::class.java) {
            Sources.fetch("ZZZ", "2y", "twse", cacheMs = 0, today = today) { _, _, _, _ -> throw SourceError("x") }
        }
    }

    @Test fun shortHistoryIsReported() {
        val today = LocalDate.of(2026, 11, 3)
        val short = series(200, today.minusDays(199)) { 100.0 + it }
        val f = Sources.fetch("AAPL", "2y", "yahoo", cacheMs = 0, today = today) { _, s, _, _ -> Sources.Result(short, s) }
        assertTrue(f.notes.any { "歷史只從" in it })
    }
}
