package com.stockai.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stockai.app.data.Sources
import com.stockai.app.engine.Analysis
import com.stockai.app.engine.Signals
import com.stockai.app.store.WatchItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: MainViewModel, symbol: String) {
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val a = analyses[symbol]
    val item = items.firstOrNull { it.symbol == symbol } ?: WatchItem(symbol)
    var confirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listOf(item.name, symbol).filter { it.isNotBlank() }.joinToString("  "), maxLines = 1) },
                navigationIcon = { IconButton({ vm.selected.value = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton({ vm.refresh(listOf(item)) }, enabled = symbol !in loading) { Icon(Icons.Default.Refresh, "重新分析") }
                    IconButton({ confirm = true }) { Icon(Icons.Default.Delete, "移除") }
                },
            )
        },
    ) { pad ->
        when {
            a != null -> DetailBody(a, Modifier.padding(pad), symbol in loading)
            symbol in loading -> Column(Modifier.fillMaxSize().padding(pad), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Text("分析中…", Modifier.padding(top = 12.dp))
            }
            else -> Column(Modifier.fillMaxSize().padding(pad).padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("無法取得資料", style = MaterialTheme.typography.titleMedium)
                Text(errors[symbol] ?: "請稍後再試", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ vm.refresh(listOf(item)) }) { Text("重試") }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("移除 ${item.name.ifBlank { symbol }}？") },
        text = { Text("會從自選股清單移除，不再主動分析與警示。") },
        confirmButton = { TextButton({ confirm = false; vm.removeStock(symbol) }) { Text("移除") } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } },
    )
}

@Composable
internal fun DetailBody(a: Analysis, modifier: Modifier, refreshing: Boolean) {
    val tw = Sources.marketOf(a.symbol) == "tw"
    LazyColumn(
        modifier.fillMaxSize().testTag("detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(Fmt.price(a.price), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = changeColor(a.changePct))
                    Text("${Fmt.pct(a.changePct)}  ·  ${a.date}  ·  來源 ${a.source}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    ScorePill(a.score, big = true)
                    Text("${a.icon} ${a.rating}", Modifier.padding(top = 4.dp), fontWeight = FontWeight.Medium)
                }
            }
            if (refreshing) Text("更新中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        item { Section("走勢圖") { PriceChart(a.frame, tw) } }
        item {
            Section("因子分數（綜合 ${Fmt.signed(a.score)} / ±100）") {
                a.factors.forEach { (k, v) -> FactorBar(k, v, Signals.WEIGHTS.getValue(k)) }
            }
        }
        item { Section("訊號解讀") { a.reasons.forEach { Text(it, Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodyMedium) } } }
        item {
            val p = a.plan
            Section("交易計畫（ATR 停損法）") {
                Text(p.status, fontWeight = FontWeight.Medium, color = if (p.shares > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                KV("進場參考", Fmt.price(p.entry))
                KV("停損價", "${Fmt.price(p.stop)}（${Fmt.num(p.stopPct, 1)}%）")
                KV("目標一 / 目標二", "${Fmt.price(p.target1)} / ${Fmt.price(p.target2)}")
                KV("建議股數", if (tw && p.shares > 0) "${Fmt.int(p.shares)} 股（${p.shares / 1000} 張）" else Fmt.int(p.shares))
                KV("部位金額", Fmt.num(p.amount, 0)); KV("最大虧損", Fmt.num(p.maxLoss, 0))
            }
        }
        item {
            val l = a.levels
            Section("關鍵價位") {
                KV("20日支撐 / 壓力", "${Fmt.price(l.support20)} / ${Fmt.price(l.resist20)}")
                KV("60日支撐 / 壓力", "${Fmt.price(l.support60)} / ${Fmt.price(l.resist60)}")
                KV("月線 / 季線", "${Fmt.price(l.ma20)} / ${Fmt.price(l.ma60)}")
            }
        }
        item {
            val r = a.risk
            Section("風險指標（近一年）") {
                KV("一年報酬", "${Fmt.num(r.oneYearReturn)}%"); KV("年化波動", "${Fmt.num(r.volatility)}%")
                KV("夏普值", Fmt.num(r.sharpe)); KV("最大回撤", "${Fmt.num(r.maxDrawdown)}%")
                KV("單日 VaR95", "${Fmt.num(r.var95)}%")
                KV("Beta（對大盤）", Fmt.num(r.beta)); KV("相關係數", Fmt.num(r.correlation))
            }
        }
        item {
            val b = a.backtest
            Section("策略回測（評分 ≥20 進場，≤-10 或移動停損出場，含成本）") {
                KV("策略總報酬", "${Fmt.num(b.strategyReturn, 1)}%", changeColor(b.strategyReturn))
                KV("買進持有", "${Fmt.num(b.buyHoldReturn, 1)}%", changeColor(b.buyHoldReturn))
                KV("策略年化", "${Fmt.num(b.strategyAnnual, 1)}%"); KV("策略最大回撤", "${Fmt.num(b.strategyMaxDD, 1)}%")
                KV("買進持有回撤", "${Fmt.num(b.buyHoldMaxDD, 1)}%"); KV("策略夏普", Fmt.num(b.strategySharpe))
                KV("交易次數", b.trades.toString()); KV("勝率", "${Fmt.num(b.winRate, 1)}%"); KV("持倉比例", "${Fmt.num(b.exposure, 1)}%")
                Text("回測是歷史模擬，過去表現不代表未來；多數個股買進持有可能優於此策略。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (a.fundamentals.items.isNotEmpty()) item {
            Section("估值 / 基本面（僅供參考，不計入評分）") { a.fundamentals.items.forEach { (k, v) -> KV(k, v) } }
        }
        item {
            Text("本程式僅供研究與教育用途，不構成任何投資建議。投資有風險，請自行判斷並承擔損益。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
