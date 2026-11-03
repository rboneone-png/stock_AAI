package com.stockai.app.engine

import java.time.LocalDate

data class Bar(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

/** 抓取結果：資料 + 實際代號 + 來源 + 備註（分割還原、歷史過短等）。 */
data class Fetched(
    val bars: List<Bar>,
    val symbol: String,
    val source: String,
    val notes: List<String> = emptyList(),
    val name: String? = null,
)

data class Plan(
    val status: String,
    val entry: Double,
    val stop: Double,
    val target1: Double,
    val target2: Double,
    val stopPct: Double,
    val shares: Int,
    val amount: Double,
    val maxLoss: Double,
)

data class Levels(
    val support20: Double, val resist20: Double,
    val support60: Double, val resist60: Double,
    val ma20: Double, val ma60: Double,
)

data class RiskStats(
    val oneYearReturn: Double,
    val volatility: Double,
    val sharpe: Double,
    val maxDrawdown: Double,
    val var95: Double,
    val beta: Double?,
    val correlation: Double?,
)

data class BacktestStats(
    val strategyReturn: Double,
    val buyHoldReturn: Double,
    val strategyAnnual: Double,
    val strategyMaxDD: Double,
    val buyHoldMaxDD: Double,
    val strategySharpe: Double,
    val trades: Int,
    val winRate: Double,
    val exposure: Double,
)

data class Fundamentals(
    val name: String? = null,
    val items: List<Pair<String, String>> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    companion object { val EMPTY = Fundamentals() }
}

data class Analysis(
    val symbol: String,
    val name: String,
    val source: String,
    val date: LocalDate,
    val price: Double,
    val changePct: Double,
    val score: Double,
    val rating: String,
    val icon: String,
    val factors: List<Pair<String, Double>>,
    val reasons: List<String>,
    val plan: Plan,
    val risk: RiskStats,
    val backtest: BacktestStats,
    val levels: Levels,
    val fundamentals: Fundamentals,
    val frame: Frame,
)

data class AnalysisOptions(
    val period: String = "2y",
    val capital: Double = 1_000_000.0,
    val riskPct: Double = 0.01,
)
