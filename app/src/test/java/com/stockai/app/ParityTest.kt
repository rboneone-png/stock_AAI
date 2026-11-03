package com.stockai.app

import com.stockai.app.engine.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 以 Python 版（stockai/）產生的預期值，驗證 Kotlin 移植結果一致。 */
class ParityTest {
    private val expected = JSONObject(res("expected.json"))

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private fun bars(name: String): List<Bar> = res(name).lines().drop(1).filter { it.isNotBlank() }.map {
        val p = it.split(",")
        Bar(LocalDate.parse(p[0].take(10)), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
    }

    private fun benchBars(name: String): List<Bar>? = runCatching {
        res(name).lines().drop(1).filter { it.isNotBlank() }.map {
            val p = it.split(","); val c = p[1].toDouble()
            Bar(LocalDate.parse(p[0].take(10)), c, c, c, c, 0.0)
        }
    }.getOrNull()

    private fun near(exp: Any?, act: Double?, what: String, tol: Double = 1e-6) {
        if (exp == null || exp == JSONObject.NULL) { assertTrue("$what 預期 NaN/無，實際 $act", act == null || act.isNaN()); return }
        val e = (exp as Number).toDouble()
        assertTrue("$what 預期 NaN/無，實際 $act", act != null)
        assertEquals(what, e, act!!, tol * maxOf(1.0, Math.abs(e)))
    }

    private fun check(name: String, symbol: String, comparePlan: Boolean) {
        val ex = expected.getJSONObject(name)
        val b = benchBars("${name}_bench.csv")
        val a = Engine.analyze(
            Fetched(bars("$name.csv"), symbol, "test"),
            b?.let { Fetched(it, "BENCH", "test") }, Fundamentals.EMPTY,
            AnalysisOptions(), today = LocalDate.of(2026, 11, 3), checkFreshness = false,
        )
        val f = a.frame
        val last = ex.getJSONObject("last")
        val i = f.n - 1
        val actual = mapOf(
            "MA5" to f.ma5[i], "MA20" to f.ma20[i], "MA60" to f.ma60[i], "MA120" to f.ma120[i], "RSI" to f.rsi[i],
            "DIF" to f.dif[i], "DEA" to f.dea[i], "MACD_H" to f.macdH[i], "BB_UP" to f.bbUp[i], "BB_LOW" to f.bbLow[i],
            "ATR" to f.atr[i], "K" to f.k[i], "D" to f.d[i], "OBV" to f.obv[i], "VOL_MA20" to f.volMa20[i],
        )
        actual.forEach { (k, v) -> near(last.opt(k), v, "$name 指標 $k") }

        // 整條評分曲線都要一致（涵蓋所有因子、所有天數）
        val sc = ex.getJSONArray("score")
        assertEquals(sc.length(), f.n)
        for (j in 0 until f.n) near(sc.get(j), f.score[j], "$name 評分[$j]")

        val fl = ex.getJSONObject("factors_last")
        a.factors.forEach { (k, v) -> near(fl.opt(k), v, "$name 因子 $k") }

        val bt = ex.getJSONObject("backtest")
        near(bt.opt("策略總報酬%"), a.backtest.strategyReturn, "$name 策略總報酬")
        near(bt.opt("買進持有%"), a.backtest.buyHoldReturn, "$name 買進持有")
        near(bt.opt("策略年化%"), a.backtest.strategyAnnual, "$name 策略年化")
        near(bt.opt("策略最大回撤%"), a.backtest.strategyMaxDD, "$name 策略回撤")
        near(bt.opt("買進持有回撤%"), a.backtest.buyHoldMaxDD, "$name 持有回撤")
        near(bt.opt("策略夏普"), a.backtest.strategySharpe, "$name 夏普")
        near(bt.opt("交易次數"), a.backtest.trades.toDouble(), "$name 交易次數")
        near(bt.opt("勝率%"), a.backtest.winRate, "$name 勝率")
        near(bt.opt("持倉比例%"), a.backtest.exposure, "$name 持倉")
        near(ex.opt("equity_last"), f.equity[i], "$name 淨值")

        val rk = ex.getJSONObject("risk")
        near(rk.opt("一年報酬%"), a.risk.oneYearReturn, "$name 一年報酬")
        near(rk.opt("年化波動%"), a.risk.volatility, "$name 波動")
        near(rk.opt("夏普值"), a.risk.sharpe, "$name 夏普值")
        near(rk.opt("最大回撤%"), a.risk.maxDrawdown, "$name 最大回撤")
        near(rk.opt("單日VaR95%"), a.risk.var95, "$name VaR")
        near(rk.opt("Beta"), a.risk.beta, "$name Beta")
        near(rk.opt("相關係數"), a.risk.correlation, "$name 相關")

        val lv = ex.getJSONObject("levels")
        near(lv.opt("20日支撐"), a.levels.support20, "$name 20日支撐"); near(lv.opt("20日壓力"), a.levels.resist20, "$name 20日壓力")
        near(lv.opt("60日支撐"), a.levels.support60, "$name 60日支撐"); near(lv.opt("60日壓力"), a.levels.resist60, "$name 60日壓力")
        near(lv.opt("月線"), a.levels.ma20, "$name 月線"); near(lv.opt("季線"), a.levels.ma60, "$name 季線")

        if (comparePlan) {
            val pl = ex.getJSONObject("plan")
            assertEquals(pl.getString("狀態"), a.plan.status)
            near(pl.opt("停損"), a.plan.stop, "$name 停損"); near(pl.opt("目標一"), a.plan.target1, "$name 目標一")
            near(pl.opt("建議股數"), a.plan.shares.toDouble(), "$name 股數"); near(pl.opt("最大虧損"), a.plan.maxLoss, "$name 最大虧損")
        }

        val reasons = ex.getJSONArray("reasons")
        assertEquals("$name 訊號解讀", (0 until reasons.length()).map { reasons.getString(it) }, a.reasons)
    }

    @Test fun aapl() = check("AAPL", "AAPL", comparePlan = false)
    @Test fun tsmc() = check("2330", "2330.TW", comparePlan = true)
    @Test fun demoNoBench() = check("demo", "T.TW", comparePlan = true)
}
