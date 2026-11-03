package com.stockai.app.data

import com.stockai.app.engine.Analysis
import com.stockai.app.engine.AnalysisOptions
import com.stockai.app.engine.Engine
import com.stockai.app.store.AlertEvent
import com.stockai.app.store.Settings
import com.stockai.app.store.Summary
import com.stockai.app.store.WatchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale

/** 分析流程與事件偵測（對應 Python 版 engine.py + monitor.py）。 */
object Repo {
    fun benchmarkFor(symbol: String) = if (Sources.marketOf(symbol) == "tw") "^TWII" else "^GSPC"

    /** 抓資料 → 指標/評分/回測 → 估值。回傳分析結果與（可能被標準化的）自選股項目。 */
    fun analyzeOne(item: WatchItem, st: Settings): Pair<Analysis, WatchItem> {
        val opts = AnalysisOptions(st.period, st.capital, st.riskPct / 100.0)
        val f = Sources.fetch(item.symbol, st.period)
        val sym = Info.resolveTwSuffix(f.symbol)
        val bench = try { Sources.fetch(benchmarkFor(sym), st.period) } catch (_: Exception) { null }
        val fund = Info.fundamentals(sym, f.bars.last().close, f.name)
        val a = Engine.analyze(f.copy(symbol = sym), bench, fund, opts)
        val name = a.name.ifBlank { item.name }
        return a to WatchItem(sym, name)
    }

    data class ScanResult(
        val analyses: Map<String, Analysis>,        // key = 原始輸入的 symbol
        val items: List<WatchItem>,                 // 標準化後的清單（順序不變）
        val errors: Map<String, String>,
        val events: List<AlertEvent>,
        val state: Map<String, Summary>,
    )

    suspend fun scan(
        items: List<WatchItem>, st: Settings, prevState: Map<String, Summary>,
        onProgress: (String, Analysis?, String?) -> Unit = { _, _, _ -> },
    ): ScanResult = coroutineScope {
        val gate = Semaphore(3)
        val now = System.currentTimeMillis()
        val outs = items.map { it0 ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    try {
                        val (a, norm) = analyzeOne(it0, st)
                        onProgress(it0.symbol, a, null)
                        Triple(it0, norm, a)
                    } catch (e: Exception) {
                        val msg = e.message ?: e.javaClass.simpleName
                        onProgress(it0.symbol, null, msg)
                        Triple(it0, it0, msg)
                    }
                }
            }
        }.awaitAll()

        val analyses = LinkedHashMap<String, Analysis>()
        val errors = LinkedHashMap<String, String>()
        val events = ArrayList<AlertEvent>()
        val state = HashMap(prevState)
        val newItems = ArrayList<WatchItem>()
        for ((orig, norm, r) in outs) {
            newItems += norm
            if (r is Analysis) {
                analyses[orig.symbol] = r
                val prev = prevState[norm.symbol] ?: prevState[orig.symbol]
                detectEvents(r, prev, st).forEach { events += AlertEvent(now, norm.symbol, norm.name, it) }
                state.remove(orig.symbol)
                state[norm.symbol] = Summary(r.rating, r.score, r.price, r.changePct, r.plan.stop, r.plan.target1, now, norm.name)
            } else errors[orig.symbol] = r as String
        }
        ScanResult(analyses, newItems, errors, events, state)
    }

    private fun f2(x: Double) = String.format(Locale.US, "%,.2f", x)

    fun detectEvents(a: Analysis, prev: Summary?, st: Settings): List<String> {
        val ev = ArrayList<String>()
        val f = a.frame; val i = f.n - 1; val p = i - 1
        if (prev != null) {
            if (st.alertRating && prev.rating != a.rating)
                ev += "評等變化：${prev.rating} → ${a.icon} ${a.rating}（${"%+.0f".format(prev.score)} → ${"%+.0f".format(a.score)}）"
            if (st.alertStopTarget) {
                if (a.price < prev.stop) ev += "⛔ 跌破前次停損價 ${f2(prev.stop)}"
                if (a.price > prev.target) ev += "🎯 突破前次目標價 ${f2(prev.target)}"
            }
        }
        if (st.alertMa60) {
            if (f.close[p] <= f.ma60[p] && f.close[i] > f.ma60[i]) ev += "📈 站上季線"
            if (f.close[p] >= f.ma60[p] && f.close[i] < f.ma60[i]) ev += "📉 跌破季線"
        }
        if (st.alertHighLow && f.n > 61) {
            val hi = (i - 60 until i).maxOf { f.high[it] }
            val lo = (i - 60 until i).minOf { f.low[it] }
            if (f.close[i] >= hi) ev += "🚀 創 60 日新高"
            if (f.close[i] <= lo) ev += "🕳️ 創 60 日新低"
        }
        if (st.alertBigMove && Math.abs(a.changePct) >= st.bigMovePct) ev += "⚡ 單日大幅波動 ${"%+.2f".format(a.changePct)}%"
        if (st.alertVolume && f.volMa20[i] > 0 && f.volume[i] > 2.5 * f.volMa20[i])
            ev += "🔊 爆量 ${"%.1f".format(f.volume[i] / f.volMa20[i])} 倍"
        return ev
    }

    suspend fun resolve(input: String): Pair<String, String> = withContext(Dispatchers.IO) { Info.resolve(input) }
    suspend fun search(q: String): List<SearchHit> = withContext(Dispatchers.IO) { Info.search(q) }
}
