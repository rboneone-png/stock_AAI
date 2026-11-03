package com.stockai.app.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object Engine {
    const val MIN_BARS = 130

    /** 把大盤收盤價對齊到個股日期（等同 pandas reindex + ffill）。 */
    fun alignBench(dates: List<LocalDate>, bench: List<Bar>): DoubleArray {
        val m = bench.associate { it.date to it.close }
        val out = DoubleArray(dates.size) { Double.NaN }
        for (i in dates.indices) out[i] = m[dates[i]] ?: if (i > 0) out[i - 1] else Double.NaN
        return out
    }

    fun analyze(
        fetched: Fetched,
        bench: Fetched?,
        fund: Fundamentals,
        opts: AnalysisOptions = AnalysisOptions(),
        today: LocalDate = LocalDate.now(),
        checkFreshness: Boolean = true,
    ): Analysis {
        val bars = fetched.bars
        require(bars.size >= MIN_BARS) { "${fetched.symbol} 歷史資料只有 ${bars.size} 筆，至少需要 $MIN_BARS 筆" }
        val f = Frame.of(bars)

        val useBench = bench != null && bench.bars.size > 60
        val benchAligned = if (useBench) alignBench(f.dates, bench!!.bars) else null

        val factors = Signals.factorScores(f, benchAligned)
        f.score = Signals.composite(factors, f.n)
        val last = f.n - 1
        val score = f.score[last]
        val (rating, icon) = Signals.ratingOf(score)

        val sym = fetched.symbol
        val lot = if (sym.endsWith(".TW") || sym.endsWith(".TWO")) 1000 else 1
        val bt = Risk.backtest(f)
        val plan = Signals.tradePlan(f, opts.capital, opts.riskPct, lot, score)
        val risk = Risk.riskMetrics(f, if (useBench) bench!!.bars.map { it.date } else null,
            if (useBench) DoubleArray(bench!!.bars.size) { bench.bars[it].close } else null)

        val reasons = ArrayList<String>(Signals.explain(f))
        reasons += fund.notes
        fetched.notes.forEach { reasons += "🔧 $it" }
        val lag = ChronoUnit.DAYS.between(f.dates[last], today)
        if (checkFreshness && lag > 4)
            reasons += "⚠️ 資料最新只到 ${f.dates[last]}（落後 $lag 天），來源「${fetched.source}」可能延遲"

        return Analysis(
            symbol = sym, name = fund.name ?: "", source = fetched.source, date = f.dates[last],
            price = f.close[last], changePct = f.ret[last] * 100, score = score, rating = rating, icon = icon,
            factors = Signals.WEIGHTS.keys.mapIndexed { i, k -> k to factors[i][last] },
            reasons = reasons, plan = plan, risk = risk, backtest = bt,
            levels = Signals.keyLevels(f), fundamentals = fund, frame = f,
        )
    }
}
