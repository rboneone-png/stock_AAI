package com.stockai.app.engine

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 純 Kotlin 的技術指標實作（與 Python 版 indicators.py 逐項對應，NaN 代表缺值）。 */
internal object Ind {
    val NaN = Double.NaN

    fun sign(x: Double): Double = Math.signum(x) // NaN -> NaN
    fun clip(x: Double, lo: Double, hi: Double): Double = if (x < lo) lo else if (x > hi) hi else x
    fun nz(x: Double): Double = if (x.isNaN()) 0.0 else x

    fun sma(s: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(s.size) { NaN }
        for (i in n - 1 until s.size) {
            var sum = 0.0
            for (j in i - n + 1..i) sum += s[j]
            out[i] = sum / n
        }
        return out
    }

    fun rollingStdPop(s: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(s.size) { NaN }
        for (i in n - 1 until s.size) {
            var sum = 0.0
            for (j in i - n + 1..i) sum += s[j]
            val m = sum / n
            var sq = 0.0
            for (j in i - n + 1..i) sq += (s[j] - m) * (s[j] - m)
            out[i] = sqrt(sq / n)
        }
        return out
    }

    fun rollingMin(s: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(s.size) { NaN }
        for (i in n - 1 until s.size) {
            var m = Double.POSITIVE_INFINITY
            for (j in i - n + 1..i) m = min(m, s[j])
            out[i] = m
        }
        return out
    }

    fun rollingMax(s: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(s.size) { NaN }
        for (i in n - 1 until s.size) {
            var m = Double.NEGATIVE_INFINITY
            for (j in i - n + 1..i) m = max(m, s[j])
            out[i] = m
        }
        return out
    }

    /** pandas ewm(alpha, adjust=False, min_periods) */
    fun ewm(s: DoubleArray, alpha: Double, minPeriods: Int = 0): DoubleArray {
        val out = DoubleArray(s.size) { NaN }
        var prev = NaN
        var count = 0
        for (i in s.indices) {
            val x = s[i]
            if (!x.isNaN()) {
                prev = if (count == 0) x else alpha * x + (1 - alpha) * prev
                count++
            }
            out[i] = if (count >= minPeriods && count > 0) prev else NaN
        }
        return out
    }

    fun ema(s: DoubleArray, n: Int) = ewm(s, 2.0 / (n + 1))

    fun diff(s: DoubleArray, n: Int = 1) = DoubleArray(s.size) { if (it >= n) s[it] - s[it - n] else NaN }

    fun pctChange(s: DoubleArray, n: Int = 1) = DoubleArray(s.size) { if (it >= n) s[it] / s[it - n] - 1 else NaN }

    fun rsi(close: DoubleArray, n: Int = 14): DoubleArray {
        val d = diff(close)
        val gainIn = DoubleArray(d.size) { if (d[it].isNaN()) NaN else max(d[it], 0.0) }
        val lossIn = DoubleArray(d.size) { if (d[it].isNaN()) NaN else max(-d[it], 0.0) }
        val gain = ewm(gainIn, 1.0 / n, n)
        val loss = ewm(lossIn, 1.0 / n, n)
        return DoubleArray(close.size) {
            when {
                gain[it].isNaN() -> NaN
                loss[it] == 0.0 -> 100.0
                else -> 100 - 100 / (1 + gain[it] / loss[it])
            }
        }
    }

    fun atr(h: DoubleArray, l: DoubleArray, c: DoubleArray, n: Int = 14): DoubleArray {
        val tr = DoubleArray(c.size) {
            if (it == 0) h[it] - l[it]
            else maxOf(h[it] - l[it], abs(h[it] - c[it - 1]), abs(l[it] - c[it - 1]))
        }
        return ewm(tr, 1.0 / n, n)
    }
}

/** 一檔股票的完整時間序列（價格 + 指標 + 評分 + 回測淨值）。 */
class Frame(
    val dates: List<LocalDate>,
    val open: DoubleArray, val high: DoubleArray, val low: DoubleArray,
    val close: DoubleArray, val volume: DoubleArray,
) {
    val n = close.size
    val ma5 = Ind.sma(close, 5)
    val ma20 = Ind.sma(close, 20)
    val ma60 = Ind.sma(close, 60)
    val ma120 = Ind.sma(close, 120)
    val rsi = Ind.rsi(close)
    val dif: DoubleArray
    val dea: DoubleArray
    val macdH: DoubleArray
    val bbUp: DoubleArray
    val bbMid: DoubleArray
    val bbLow: DoubleArray
    val atr = Ind.atr(high, low, close)
    val k: DoubleArray
    val d: DoubleArray
    val obv: DoubleArray
    val volMa20 = Ind.sma(volume, 20)
    val ret = Ind.pctChange(close)

    /** 綜合評分、策略淨值、買進持有淨值（由 Engine 填入）。 */
    var score = DoubleArray(n)
    var equity = DoubleArray(n)
    var buyHold = DoubleArray(n)

    init {
        val e12 = Ind.ema(close, 12)
        val e26 = Ind.ema(close, 26)
        dif = DoubleArray(n) { e12[it] - e26[it] }
        dea = Ind.ema(dif, 9)
        macdH = DoubleArray(n) { dif[it] - dea[it] }

        bbMid = Ind.sma(close, 20)
        val std = Ind.rollingStdPop(close, 20)
        bbUp = DoubleArray(n) { bbMid[it] + 2.0 * std[it] }
        bbLow = DoubleArray(n) { bbMid[it] - 2.0 * std[it] }

        val lowN = Ind.rollingMin(low, 9)
        val highN = Ind.rollingMax(high, 9)
        val rsv = DoubleArray(n) {
            val rng = highN[it] - lowN[it]
            val v = if (rng == 0.0) Double.NaN else (close[it] - lowN[it]) / rng * 100
            if (v.isNaN()) 50.0 else v
        }
        val kk = Ind.ewm(rsv, 1.0 / 3)
        val dd = Ind.ewm(kk, 1.0 / 3)
        for (i in 0 until n) if (lowN[i].isNaN()) { kk[i] = Double.NaN; dd[i] = Double.NaN }
        k = kk; d = dd

        val o = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            val dir = if (i == 0) 0.0 else Math.signum(close[i] - close[i - 1])
            acc += dir * volume[i]
            o[i] = acc
        }
        obv = o
    }

    companion object {
        fun of(bars: List<Bar>) = Frame(
            bars.map { it.date },
            DoubleArray(bars.size) { bars[it].open }, DoubleArray(bars.size) { bars[it].high },
            DoubleArray(bars.size) { bars[it].low }, DoubleArray(bars.size) { bars[it].close },
            DoubleArray(bars.size) { bars[it].volume },
        )
    }
}
