@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.stockai.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stockai.app.data.Sources
import com.stockai.app.store.AlertEvent
import com.stockai.app.store.Settings
import com.stockai.app.store.WatchItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StockAiApp(vm: MainViewModel, requestNotifications: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    StockTheme(settings.redUp) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val sel = selected
            if (sel != null) {
                BackHandler { vm.selected.value = null }
                DetailScreen(vm, sel)
            } else Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("自選股") })
                        NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Notifications, null) }, label = { Text("警示") })
                        NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("設定") })
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    when (tab) {
                        0 -> WatchlistScreen(vm)
                        1 -> AlertsScreen(vm)
                        else -> SettingsScreen(vm, requestNotifications)
                    }
                }
            }
        }
    }
}

// ============================================================================ 自選股
@Composable
fun WatchlistScreen(vm: MainViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<WatchItem?>(null) }

    fun scoreOf(i: WatchItem): Double = analyses[i.symbol]?.score ?: summaries[i.symbol]?.score ?: Double.NEGATIVE_INFINITY
    val sorted = when (settings.sort) {
        "score" -> items.sortedByDescending { scoreOf(it) }
        "name" -> items.sortedBy { it.symbol }
        else -> items
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自選股") },
                actions = {
                    IconButton({ vm.refreshAll() }, enabled = loading.isEmpty()) { Icon(Icons.Default.Refresh, "重新整理") }
                    Box {
                        TextButton({ menu = true }) { Text(when (settings.sort) { "score" -> "依評分"; "name" -> "依代號"; else -> "依加入順序" }) }
                        DropdownMenu(menu, { menu = false }) {
                            listOf("score" to "依評分", "name" to "依代號", "manual" to "依加入順序").forEach { (k, t) ->
                                DropdownMenuItem({ Text(t) }, { menu = false; vm.updateSettings { it.copy(sort = k) } })
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(text = { Text("新增股票") }, icon = { Icon(Icons.Default.Add, null) }, onClick = { showAdd = true })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loading.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (items.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("還沒有自選股", style = MaterialTheme.typography.titleMedium)
                    Text("按右下角「新增股票」，輸入台股或美股的代號或名稱。", Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sorted, key = { it.symbol }) { it ->
                    StockRow(
                        it, analyses[it.symbol], summaries[it.symbol]?.let { s -> s.price to s.changePct },
                        isLoading = it.symbol in loading, error = errors[it.symbol],
                        onClick = { vm.selected.value = it.symbol }, onLong = { removing = it },
                    )
                }
                item {
                    Text("長按股票可移除。分數 ≥20 偏多、≤-20 偏空；僅供研究參考，不構成投資建議。",
                        Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showAdd) AddStockSheet(vm) { showAdd = false }
    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除 ${r.name.ifBlank { r.symbol }}？") },
            confirmButton = { TextButton({ vm.removeStock(r.symbol); removing = null }) { Text("移除") } },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun StockRow(
    item: WatchItem, a: com.stockai.app.engine.Analysis?, cached: Pair<Double, Double>?,
    isLoading: Boolean, error: String?, onClick: () -> Unit, onLong: () -> Unit,
) {
    val price = a?.price ?: cached?.first
    val chg = a?.changePct ?: cached?.second
    val sum = a
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(item.name.ifBlank { item.symbol }, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(item.symbol)
                        if (error != null && a == null) append("  ·  ⚠️ 取得失敗，點進去查看")
                        else if (a != null) append("  ·  ${a.source}")
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            if (isLoading && price == null) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp)
            else if (price != null && chg != null) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                    Text(Fmt.price(price), fontWeight = FontWeight.SemiBold, color = changeColor(chg))
                    Text(Fmt.pct(chg), style = MaterialTheme.typography.bodySmall, color = changeColor(chg))
                }
            }
            if (sum != null) Column(horizontalAlignment = Alignment.End) {
                ScorePill(sum.score)
                Text("${sum.icon} ${sum.rating}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private val QUICK_TW = listOf("2330" to "台積電", "2317" to "鴻海", "2454" to "聯發科", "2308" to "台達電", "2881" to "富邦金",
    "2882" to "國泰金", "0050" to "元大台灣50", "0056" to "元大高股息", "00878" to "國泰永續高股息", "00919" to "群益台灣精選高息")
private val QUICK_US = listOf("AAPL" to "Apple", "MSFT" to "Microsoft", "NVDA" to "NVIDIA", "GOOGL" to "Alphabet", "AMZN" to "Amazon",
    "META" to "Meta", "TSLA" to "Tesla", "AVGO" to "Broadcom", "SPY" to "S&P 500 ETF", "QQQ" to "Nasdaq 100 ETF", "VOO" to "Vanguard S&P 500")

@Composable
private fun AddStockSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun has(sym: String): Boolean = items.any {
        if (Sources.marketOf(sym) == "tw") Sources.marketOf(it.symbol) == "tw" && Sources.twCode(it.symbol) == Sources.twCode(sym)
        else it.symbol.equals(sym, true)
    }
    LaunchedEffect(text) { delay(350); vm.search(text) }

    ModalBottomSheet(onDismissRequest = { vm.search(""); onDismiss() }, sheetState = state) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("新增股票", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true,
                label = { Text("代號或名稱：2330、台積電、AAPL、nvidia") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { vm.addStock(text); text = "" } }),
            )
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            if (text.isNotBlank()) {
                results.forEach { h ->
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { vm.addStock(h.symbol, h.name) }).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(h.name.ifBlank { h.symbol }, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text("${h.symbol}  ·  ${h.market}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (has(h.symbol)) Icon(Icons.Default.Check, "已加入", tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Default.Add, "加入")
                    }
                    HorizontalDivider()
                }
                if (!searching && results.isEmpty()) Text("找不到符合的股票。", Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ vm.addStock(text); text = "" }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("直接新增「${text.trim().uppercase()}」")
                }
            } else {
                Text("熱門台股", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_TW.forEach { (c, n) -> QuickChip(c, n, has(c)) { vm.addStock(c, n) } }
                }
                Text("熱門美股", Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_US.forEach { (c, n) -> QuickChip(c, n, has(c)) { vm.addStock(c, n) } }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QuickChip(code: String, name: String, added: Boolean, onClick: () -> Unit) {
    FilterChip(added, onClick = { if (!added) onClick() }, label = { Text("$code $name") },
        leadingIcon = if (added) ({ Icon(Icons.Default.Check, null) }) else null)
}

// ============================================================================ 警示
@Composable
fun AlertsScreen(vm: MainViewModel) {
    val events by vm.events.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("警示紀錄") }, actions = {
            if (events.isNotEmpty()) TextButton({ vm.clearEvents() }) { Text("清除") }
        })
    }) { pad ->
        if (events.isEmpty()) Column(Modifier.padding(pad).fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("目前沒有警示", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
            Text("背景監控會定期重新分析自選股，偵測到評等變化、跌破停損、創新高新低等事件時，會通知你並記錄在這裡。",
                Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { e: AlertEvent ->
                Card(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.selected.value = e.symbol }),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(e.name.ifBlank { e.symbol } + "  " + e.symbol, fontWeight = FontWeight.Medium)
                            Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(e.text, Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

// ============================================================================ 設定
private val INTERVALS = listOf(15 to "15 分鐘", 30 to "30 分鐘", 60 to "1 小時", 120 to "2 小時", 240 to "4 小時", 720 to "12 小時", 1440 to "每天")

@Composable
fun SettingsScreen(vm: MainViewModel, requestNotifications: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val granted by vm.notificationsGranted.collectAsStateWithLifecycle()
    val check by vm.sourceCheck.collectAsStateWithLifecycle()
    val checking by vm.checkingSources.collectAsStateWithLifecycle()
    fun upd(f: (Settings) -> Settings) = vm.updateSettings(f)

    Scaffold(topBar = { TopAppBar(title = { Text("設定") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("背景監控") {
                SwitchRow("定期重新分析並通知", s.monitorEnabled) { v -> upd { it.copy(monitorEnabled = v) } }
                if (s.monitorEnabled) {
                    Text("檢查間隔", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        INTERVALS.forEach { (m, t) -> FilterChip(s.intervalMin == m, { upd { it.copy(intervalMin = m) } }, label = { Text(t) }) }
                    }
                    Text("Android 會依電量與網路狀況調整實際執行時間，間隔越短越耗電與流量。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!granted) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Text("尚未允許通知，收不到警示。", Modifier.weight(1f).padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
                        Button(requestNotifications) { Text("允許") }
                    }
                }
            }
            Section("警示條件") {
                SwitchRow("評等變化（例：觀望 → 買進）", s.alertRating) { v -> upd { it.copy(alertRating = v) } }
                SwitchRow("跌破停損價 / 突破目標價", s.alertStopTarget) { v -> upd { it.copy(alertStopTarget = v) } }
                SwitchRow("站上 / 跌破季線", s.alertMa60) { v -> upd { it.copy(alertMa60 = v) } }
                SwitchRow("創 60 日新高 / 新低", s.alertHighLow) { v -> upd { it.copy(alertHighLow = v) } }
                SwitchRow("爆量（2.5 倍均量以上）", s.alertVolume) { v -> upd { it.copy(alertVolume = v) } }
                SwitchRow("單日大幅波動", s.alertBigMove) { v -> upd { it.copy(alertBigMove = v) } }
                if (s.alertBigMove) NumField("波動門檻", s.bigMovePct, "%") { v -> upd { it.copy(bigMovePct = v) } }
            }
            Section("交易計畫") {
                NumField("可投入資金", s.capital) { v -> upd { it.copy(capital = v) } }
                NumField("單筆可承受虧損", s.riskPct, "% 資金", Modifier.padding(top = 8.dp)) { v -> upd { it.copy(riskPct = v.coerceAtMost(20.0)) } }
                Text("用來計算建議股數：資金 × 風險比例 ÷ 停損距離。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("顯示") {
                SwitchRow(if (s.redUp) "紅漲綠跌（台股慣例）" else "綠漲紅跌（美股慣例）", s.redUp) { v -> upd { it.copy(redUp = v) } }
            }
            Section("資料來源") {
                Text("歷史資料長度", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1y" to "1 年", "2y" to "2 年", "3y" to "3 年").forEach { (k, t) ->
                        FilterChip(s.period == k, { upd { it.copy(period = k) } }, label = { Text(t) })
                    }
                }
                Text("程式依序嘗試多個來源，被擋或沒資料時自動換下一個。可關閉不想用的來源：", Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf("yahoo" to "Yahoo Finance（台/美）", "twse" to "證交所（台股上市）", "tpex" to "櫃買中心（台股上櫃）",
                    "finmind" to "FinMind（台/美）", "nasdaq" to "Nasdaq（美股）", "alphavantage" to "Alpha Vantage（美股，需金鑰）").forEach { (k, t) ->
                    SwitchRow(t, k !in s.disabledSources) { on -> upd { it.copy(disabledSources = if (on) it.disabledSources - k else it.disabledSources + k) } }
                }
                var fm by remember(s.finmindToken) { mutableStateOf(s.finmindToken) }
                OutlinedTextField(fm, { fm = it; upd { s2 -> s2.copy(finmindToken = it.trim()) } }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("FinMind Token（選填，免費註冊可提高額度並加快台股載入）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                var av by remember(s.alphaKey) { mutableStateOf(s.alphaKey) }
                OutlinedTextField(av, { av = it; upd { s2 -> s2.copy(alphaKey = it.trim()) } }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Alpha Vantage API Key（選填）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ vm.checkSources() }, enabled = !checking) { Text(if (checking) "檢查中…" else "檢查資料來源") }
                    if (checking) CircularProgressIndicator(Modifier.padding(start = 12.dp).height(20.dp), strokeWidth = 2.dp)
                }
                check?.forEach { (k, v) ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(k, Modifier.padding(end = 8.dp), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                        Text(v, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Section("關於") {
                Text("股票主動分析 v1.0.0", fontWeight = FontWeight.Medium)
                Text("以技術面多因子評分（趨勢、動能、KD、布林、量能、相對強弱）整合成 -100 ~ +100 分，並提供 ATR 停損、風險指標與歷史回測。\n\n" +
                    "本程式僅供研究與教育用途，不構成任何投資建議。投資有風險，請自行判斷並承擔損益。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}
