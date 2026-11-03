package com.stockai.app.engine

import com.stockai.app.engine.Ind.clip
import com.stockai.app.engine.Ind.nz
import com.stockai.app.engine.Ind.sign
import java.util.Locale

/** 多因子評分（-100 ~ +100），與 Python 版 signals.py 逐項對應。 */
object Signals {
    val WEIGHTS = linkedMapOf("趨勢" to 30, "動能" to 25, "KD" to 15, "布林" to 10, "量能" to 10, "相對強弱" to 10)

    private val RATINGS = listOf(
        50.0 to ("強力買進" to "🟢🟢"), 20.0 to ("買進" to "🟢"), -20.0 to ("觀望" to "⚪"),
        -50.0 to ("賣出" to "🔴"), -101.0 to ("強力賣出" to "🔴🔴"),
    )

    fun ratingOf(score: Double): Pair<String, String> =
        RATINGS.firstOrNull { score >= it.first }?.second ?: RATINGS.last().second

    /** 回傳每個因子的逐日分數（順序同 WEIGHTS），bench 為已對齊個股日期的大盤收盤價（可為 null）。 */
    fun factorScores(f: Frame, bench: DoubleArray?): List<DoubleArray> {
        val n = f.n
        val c = f.close
        val trend = DoubleArray(n)
        val mom = DoubleArray(n)
        val kd = DoubleArray(n)
        val bb = DoubleArray(n)
        val vol = DoubleArray(n)
        val rel = DoubleArray(n)

        val ma20d5 = Ind.diff(f.ma20, 5)
        val hDiff = Ind.diff(f.macdH)
        val obvMean = Ind.sma(f.obv, 20)
        val c60 = Ind.pctChange(c, 60)
        val b60 = bench?.let { Ind.pctChange(it, 60) }

        for (i in 0 until n) {
            trend[i] = nz(
                sign(c[i] - f.ma20[i]) * 8 + sign(c[i] - f.ma60[i]) * 8 +
                    sign(f.ma20[i] - f.ma60[i]) * 8 + sign(ma20d5[i]) * 6
            )

            val m = sign(f.macdH[i]) * 7 + sign(hDiff[i]) * 5 + sign(f.dif[i]) * 3
            val r = f.rsi[i]
            var rp = 0.0
            if (r > 50 && r <= 70) rp = 6.0
            if (r < 50 && r >= 30) rp = -6.0
            if (r > 80) rp = -10.0
            if (r < 20) rp = 10.0
            mom[i] = nz(m + rp)

            val k = f.k[i]; val d = f.d[i]
            var crossUp = false; var crossDn = false
            if (i > 0) {
                crossUp = k > d && f.k[i - 1] <= f.d[i - 1]
                crossDn = k < d && f.k[i - 1] >= f.d[i - 1]
            }
            var kp = sign(k - d) * 5
            if (crossUp) kp += if (k < 30) 10 else 5
            if (crossDn) kp -= if (k > 70) 10 else 5
            kd[i] = nz(clip(kp, -15.0, 15.0))

            val width = f.bbUp[i] - f.bbLow[i]
            val pb = if (width == 0.0) Double.NaN else (c[i] - f.bbLow[i]) / width
            bb[i] = nz(clip(-(pb - 0.5) * 20, -10.0, 10.0))

            val vr = f.volume[i] / f.volMa20[i]
            val surge = if (vr > 1.5) sign(f.ret[i]) * 5 else 0.0
            vol[i] = nz(sign(f.obv[i] - obvMean[i]) * 5 + surge)

            rel[i] = nz(
                if (b60 != null) clip((c60[i] - b60[i]) * 100, -10.0, 10.0)
                else clip(c60[i] * 50, -10.0, 10.0)
            )
        }
        return listOf(trend, mom, kd, bb, vol, rel)
    }

    fun composite(factors: List<DoubleArray>, n: Int) =
        DoubleArray(n) { i -> clip(factors.sumOf { it[i] }, -100.0, 100.0) }

    private fun f0(x: Double) = String.format(Locale.US, "%.0f", x)
    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)

    /** 把最新一天的指標狀態翻成文字。 */
    fun explain(f: Frame): List<String> {
        val i = f.n - 1; val p = i - 1
        val c = f.close[i]
        val out = mutableListOf<String>()
        if (c > f.ma20[i] && f.ma20[i] > f.ma60[i]) out += "✅ 多頭排列：股價 > 月線 > 季線"
        else if (c < f.ma20[i] && f.ma20[i] < f.ma60[i]) out += "❌ 空頭排列：股價 < 月線 < 季線"
        else out += "➖ 均線糾結，趨勢不明"
        if (f.ma5[p] <= f.ma20[p] && f.ma5[i] > f.ma20[i]) out += "✅ 5 日線黃金交叉月線"
        if (f.ma5[p] >= f.ma20[p] && f.ma5[i] < f.ma20[i]) out += "❌ 5 日線死亡交叉月線"
        if (f.dif[p] <= f.dea[p] && f.dif[i] > f.dea[i]) out += "✅ MACD 黃金交叉"
        else if (f.dif[p] >= f.dea[p] && f.dif[i] < f.dea[i]) out += "❌ MACD 死亡交叉"
        else {
            val pos = f.macdH[i] > 0
            val grow = Math.abs(f.macdH[i]) > Math.abs(f.macdH[p])
            out += "${if (pos) "✅" else "❌"} MACD 柱狀體${if (pos) "為正" else "為負"}且${if (grow) "擴大" else "收斂"}"
        }
        val r = f.rsi[i]
        out += when {
            r > 80 -> "⚠️ RSI ${f0(r)} 嚴重過熱，留意回檔"
            r > 70 -> "⚠️ RSI ${f0(r)} 偏熱"
            r < 20 -> "💡 RSI ${f0(r)} 嚴重超賣，可能醞釀反彈"
            r < 30 -> "💡 RSI ${f0(r)} 超賣區"
            else -> "➖ RSI ${f0(r)} 中性"
        }
        if (f.k[p] <= f.d[p] && f.k[i] > f.d[i])
            out += "✅ KD 黃金交叉（K=${f0(f.k[i])}${if (f.k[i] < 30) "，低檔" else ""}）"
        else if (f.k[p] >= f.d[p] && f.k[i] < f.d[i])
            out += "❌ KD 死亡交叉（K=${f0(f.k[i])}${if (f.k[i] > 70) "，高檔" else ""}）"
        if (c > f.bbUp[i]) out += "⚠️ 突破布林上軌（強勢但易過熱）"
        else if (c < f.bbLow[i]) out += "💡 跌破布林下軌（超跌）"
        val vr = if (f.volMa20[i] != 0.0 && !f.volMa20[i].isNaN()) f.volume[i] / f.volMa20[i] else 1.0
        if (vr > 1.5) out += "${if (f.ret[i] > 0) "✅ 帶量上漲" else "❌ 帶量下跌"}（量能 ${f1(vr)} 倍均量）"
        else if (vr < 0.5) out += "➖ 量縮（${f1(vr)} 倍均量）"
        return out
    }

    /** 以 ATR 設定停損/目標價，並依可承受風險計算部位大小。 */
    fun tradePlan(f: Frame, capital: Double, riskPct: Double, lot: Int, score: Double?): Plan {
        val i = f.n - 1
        val c = f.close[i]; val a = f.atr[i]
        val stop = c - 2 * a
        val riskPerShare = c - stop
        var shares = if (riskPerShare > 0) (capital * riskPct / riskPerShare).toInt() else 0
        shares = maxOf(0, minOf(shares, (capital / c).toInt()))
        shares -= shares % lot
        var status = "可依計畫進場"
        if (score != null && score < 20) { shares = 0; status = "評分未達進場門檻（≥20），暫不建倉" }
        return Plan(
            status, c, stop, c + 2 * a, c + 4 * a, -riskPerShare / c * 100, shares,
            shares * c, shares * riskPerShare,
        )
    }

    fun keyLevels(f: Frame): Levels {
        val n = f.n
        fun lo(k: Int) = (n - k until n).minOf { f.low[it] }
        fun hi(k: Int) = (n - k until n).maxOf { f.high[it] }
        return Levels(lo(20), hi(20), lo(60), hi(60), f.ma20[n - 1], f.ma60[n - 1])
    }
}
