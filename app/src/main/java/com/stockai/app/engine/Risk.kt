package com.stockai.app.engine

import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** 風險指標與訊號回測，與 Python 版 risk.py 逐項對應。 */
object Risk {
    const val TRADING_DAYS = 252

    private fun mean(a: List<Double>) = a.sum() / a.size
    private fun std(a: List<Double>): Double {
        if (a.size < 2) return Double.NaN
        val m = mean(a)
        return sqrt(a.sumOf { (it - m) * (it - m) } / (a.size - 1))
    }

    fun maxDrawdown(eq: DoubleArray): Double {
        var peak = Double.NEGATIVE_INFINITY; var worst = 0.0
        for (v in eq) { if (v > peak) peak = v; worst = minOf(worst, v / peak - 1) }
        return worst
    }

    /** numpy.percentile 的線性插值。 */
    fun percentile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val pos = (s.size - 1) * q / 100.0
        val lo = floor(pos).toInt(); val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    fun riskMetrics(f: Frame, benchDates: List<LocalDate>?, benchClose: DoubleArray?, rf: Double = 0.015): RiskStats {
        val rets = (1 until f.n).map { f.close[it] / f.close[it - 1] - 1 }
        val dates = f.dates.drop(1)
        val r = rets.takeLast(TRADING_DAYS)
        val rd = dates.takeLast(TRADING_DAYS)
        val vol = std(r) * sqrt(TRADING_DAYS.toDouble())
        var prod = 1.0; r.forEach { prod *= 1 + it }
        val ann = prod.pow(TRADING_DAYS.toDouble() / maxOf(r.size, 1)) - 1
        val cum = DoubleArray(r.size); var acc = 1.0
        r.forEachIndexed { i, x -> acc *= 1 + x; cum[i] = acc }

        var beta: Double? = null; var corr: Double? = null
        if (benchDates != null && benchClose != null) {
            val br = HashMap<LocalDate, Double>()
            for (i in 1 until benchClose.size) br[benchDates[i]] = benchClose[i] / benchClose[i - 1] - 1
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            rd.forEachIndexed { i, d -> br[d]?.takeIf { !it.isNaN() }?.let { xs += r[i]; ys += it } }
            if (xs.size > 30) {
                val mx = mean(xs); val my = mean(ys)
                var cov = 0.0; var vx = 0.0; var vy = 0.0
                for (i in xs.indices) { cov += (xs[i] - mx) * (ys[i] - my); vx += (xs[i] - mx).pow(2); vy += (ys[i] - my).pow(2) }
                cov /= xs.size - 1; vx /= xs.size - 1; vy /= xs.size - 1
                if (vy > 0) { beta = cov / vy; corr = cov / sqrt(vx * vy) }
            }
        }
        return RiskStats(
            ann * 100, vol * 100, if (vol != 0.0) (ann - rf) / vol else Double.NaN,
            maxDrawdown(cum) * 100, percentile(r, 5.0) * 100, beta, corr,
        )
    }

    /**
     * 評分 ≥ entry 進場；≤ exit 或觸發 ATR 移動停損出場。t 日收盤判斷，t+1 日起承擔報酬（無未來函數）。
     * cost 為一次來回交易成本。
     */
    fun backtest(f: Frame, entry: Double = 20.0, exit: Double = -10.0, cost: Double = 0.004, atrStop: Double = 2.5): BacktestStats {
        val n = f.n
        val pos = DoubleArray(n)
        var holding = false; var trail = 0.0; var entryPx = 0.0
        val trades = ArrayList<Double>()
        for (i in 0 until n) {
            if (f.atr[i].isNaN()) continue
            if (holding) {
                trail = maxOf(trail, f.close[i] - atrStop * f.atr[i])
                if (f.score[i] <= exit || f.close[i] < trail) {
                    holding = false
                    trades += f.close[i] / entryPx - 1 - cost
                }
            } else if (f.score[i] >= entry) {
                holding = true; entryPx = f.close[i]; trail = f.close[i] - atrStop * f.atr[i]
            }
            pos[i] = if (holding) 1.0 else 0.0
        }
        if (holding) trades += f.close[n - 1] / entryPx - 1 - cost

        val held = DoubleArray(n) { if (it == 0) 0.0 else pos[it - 1] }
        val strat = DoubleArray(n)
        val eq = DoubleArray(n); val bh = DoubleArray(n)
        var e = 1.0; var b = 1.0
        for (i in 0 until n) {
            val ret = if (i == 0) 0.0 else f.close[i] / f.close[i - 1] - 1
            val prevHeld = if (i == 0) 0.0 else held[i - 1]
            val sw = Math.abs(held[i] - prevHeld)
            strat[i] = held[i] * ret - sw * cost / 2
            e *= 1 + strat[i]; b *= 1 + ret
            eq[i] = e; bh[i] = b
        }
        f.equity = eq; f.buyHold = bh
        val years = maxOf(n.toDouble() / TRADING_DAYS, 1e-9)
        val sl = strat.toList()
        val sd = std(sl)
        return BacktestStats(
            (eq[n - 1] - 1) * 100, (bh[n - 1] - 1) * 100,
            (eq[n - 1].pow(1 / years) - 1) * 100,
            maxDrawdown(eq) * 100, maxDrawdown(bh) * 100,
            if (sd != 0.0 && !sd.isNaN()) mean(sl) / sd * sqrt(TRADING_DAYS.toDouble()) else Double.NaN,
            trades.size,
            if (trades.isNotEmpty()) trades.count { it > 0 } * 100.0 / trades.size else Double.NaN,
            held.average() * 100,
        )
    }
}
