package com.stockai.app

import com.stockai.app.data.DataConfig
import com.stockai.app.data.Info
import com.stockai.app.data.Sources
import com.stockai.app.engine.AnalysisOptions
import com.stockai.app.engine.Engine
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 連真實網路的整合測試：LIVE=1 gradle :app:testDebugUnitTest --tests '*LiveTest*' -i */
class LiveTest {
    @Before fun setup() {
        assumeTrue(System.getenv("LIVE") == "1")
        DataConfig.cacheDir = File(System.getProperty("java.io.tmpdir"), "stockai-live-cache").also { it.mkdirs() }
        DataConfig.disabled = emptySet()
    }

    private fun say(s: String) = println("LIVE> $s")

    @Test fun sourcesEach() {
        for ((sym, srcs) in listOf("2330" to listOf("yahoo", "twse", "finmind"), "6488" to listOf("yahoo", "tpex"),
            "AAPL" to listOf("yahoo", "nasdaq", "finmind"), "^TWII" to listOf("yahoo", "twse"), "^GSPC" to listOf("yahoo", "nasdaq"))) {
            for (s in srcs) {
                val t = System.currentTimeMillis()
                try {
                    val f = Sources.fetch(sym, "2y", s, cacheMs = 0)
                    say("$sym via $s -> ${f.bars.size} bars, last ${f.bars.last().date} close ${f.bars.last().close} sym=${f.symbol} name=${f.name} notes=${f.notes} (${System.currentTimeMillis() - t}ms)")
                } catch (e: Exception) { say("$sym via $s FAILED: ${e.message?.take(150)}") }
            }
        }
    }

    @Test fun fullAnalysisWithFallback() {
        DataConfig.disabled = setOf("yahoo") // 模擬 Yahoo 被擋
        for (sym in listOf("0050", "6488", "NVDA")) {
            val f = Sources.fetch(sym, "2y")
            val benchSym = if (Sources.marketOf(f.symbol) == "tw") "^TWII" else "^GSPC"
            val b = try { Sources.fetch(benchSym, "2y") } catch (e: Exception) { null }
            val sym2 = Info.resolveTwSuffix(f.symbol)
            val fund = Info.fundamentals(sym2, f.bars.last().close, f.name)
            val a = Engine.analyze(f.copy(symbol = sym2), b, fund, AnalysisOptions())
            say("$sym2 ${a.name} [${a.source}] ${a.date} px=${a.price} score=${"%.0f".format(a.score)} ${a.rating} bt=${"%.1f".format(a.backtest.strategyReturn)}% bh=${"%.1f".format(a.backtest.buyHoldReturn)}% beta=${a.risk.beta} bench=${b?.source} notes=${a.reasons.filter { it.startsWith("🔧") || it.startsWith("⚠️ 資料") }} fund=${fund.items}")
        }
    }

    @Test fun searchAndFundamentals() {
        for (q in listOf("2330", "台積", "元大", "apple", "NVDA", "00878", "環球")) {
            say("search '$q' -> " + Info.search(q).take(6).joinToString { "${it.symbol}:${it.name}(${it.market})" })
        }
        say("fund 2330 = ${Info.fundamentals("2330.TW", 2300.0)}")
        say("fund AAPL = ${Info.fundamentals("AAPL", 338.0)}")
        say("resolve 6488 = ${Info.resolve("6488")}")
        say("check = ${Sources.check("AAPL")}")
    }
}
