package com.stockai.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stockai.app.data.DataConfig
import com.stockai.app.data.Repo
import com.stockai.app.data.SearchHit
import com.stockai.app.data.Sources
import com.stockai.app.engine.Analysis
import com.stockai.app.store.AlertEvent
import com.stockai.app.store.Portable
import com.stockai.app.store.Settings
import com.stockai.app.store.Store
import com.stockai.app.store.Summary
import com.stockai.app.store.WatchItem
import com.stockai.app.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel @JvmOverloads constructor(app: Application, autoRefresh: Boolean = true) : AndroidViewModel(app) {
    private val store = Store(app)

    val settings = MutableStateFlow(store.loadSettings())
    val items = MutableStateFlow(store.loadWatchlist())
    val summaries = MutableStateFlow(store.loadState())
    val analyses = MutableStateFlow<Map<String, Analysis>>(emptyMap())
    val loading = MutableStateFlow<Set<String>>(emptySet())
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val events = MutableStateFlow(store.loadEvents())
    val selected = MutableStateFlow<String?>(null)
    val notificationsGranted = MutableStateFlow(true)

    val searchResults = MutableStateFlow<List<SearchHit>>(emptyList())
    val searching = MutableStateFlow(false)
    val sourceCheck = MutableStateFlow<List<Pair<String, String>>?>(null)
    val checkingSources = MutableStateFlow(false)

    private val lock = Any()

    init {
        DataConfig.cacheDir = app.cacheDir
        store.applyToData(settings.value)
        if (autoRefresh) refresh(items.value)
    }

    /** 重新分析指定股票，並把結果、事件、狀態合併進全域狀態。 */
    fun refresh(targets: List<WatchItem>) {
        if (targets.isEmpty()) return
        val keys = targets.map { it.symbol }.toSet()
        loading.update { it + keys }
        errors.update { it - keys }
        viewModelScope.launch {
            try {
                val st = settings.value
                val r = Repo.scan(targets, st, summaries.value) { sym, a, err ->
                    loading.update { it - sym }
                    if (a != null) analyses.update { m -> m + (sym to a) }
                    if (err != null) errors.update { m -> m + (sym to err) }
                }
                synchronized(lock) {
                    // 把標準化後的代號（2330 → 2330.TW）寫回清單與結果
                    val rename = targets.zip(r.items).filter { (o, n) -> o.symbol != n.symbol || o.name != n.name }
                    if (rename.isNotEmpty()) {
                        items.update { cur ->
                            cur.map { c -> rename.firstOrNull { it.first.symbol == c.symbol }?.second ?: c }
                                .distinctBy { it.symbol }
                        }
                        store.saveWatchlist(items.value)
                        analyses.update { m ->
                            val n = HashMap(m)
                            rename.forEach { (o, nn) -> n.remove(o.symbol)?.let { a -> n[nn.symbol] = a } }
                            n
                        }
                        errors.update { m -> m.filterKeys { k -> rename.none { it.first.symbol == k } } }
                        selected.update { s -> rename.firstOrNull { it.first.symbol == s }?.second?.symbol ?: s }
                    }
                    // 只合併這次掃描的標的，避免用舊快照覆蓋其他同時進行的掃描結果
                    val mine = r.items.map { it.symbol }.toSet()
                    val alive = items.value.map { it.symbol }.toSet()
                    summaries.value = ((summaries.value - targets.map { it.symbol }.toSet()) +
                        r.state.filterKeys { it in mine }).filterKeys { it in alive }
                    store.saveState(summaries.value)
                    if (r.events.isNotEmpty()) events.value = store.appendEvents(r.events)
                }
            } finally {
                loading.update { it - keys }
            }
        }
    }

    fun refreshAll() = refresh(items.value)

    private fun dedupeKey(sym: String) = Portable.key(sym)

    /** 新增自選股；回傳 false 代表已存在。 */
    fun addStock(symbol: String, name: String = ""): Boolean {
        val sym = symbol.trim().uppercase()
        if (sym.isEmpty()) return false
        val exists = items.value.firstOrNull { dedupeKey(it.symbol) == dedupeKey(sym) }
        if (exists != null) { selected.value = exists.symbol; return false }
        val item = WatchItem(sym, name)
        synchronized(lock) { items.update { it + item }; store.saveWatchlist(items.value) }
        refresh(listOf(item))
        return true
    }

    fun removeStock(symbol: String) {
        synchronized(lock) {
            items.update { l -> l.filter { it.symbol != symbol } }
            store.saveWatchlist(items.value)
            summaries.update { it - symbol }
            store.saveState(summaries.value)
        }
        analyses.update { it - symbol }
        errors.update { it - symbol }
        if (selected.value == symbol) selected.value = null
    }

    // ---------------------------------------------------------------- 手動排序
    /** 把清單中第 [from] 檔移到第 [to] 的位置（手動排序模式下的清單順序就是顯示順序）。 */
    fun moveStock(from: Int, to: Int) {
        synchronized(lock) {
            val cur = items.value
            if (from !in cur.indices || to !in cur.indices || from == to) return
            items.value = cur.toMutableList().also { it.add(to, it.removeAt(from)) }
            store.saveWatchlist(items.value)
        }
    }

    /** 進入手動排序：先把目前畫面上的順序（例如依評分）固定成清單順序，再切到手動模式。 */
    fun beginManualOrder(displayOrder: List<WatchItem>) {
        synchronized(lock) {
            val known = items.value.map { it.symbol }.toSet()
            val ordered = displayOrder.filter { it.symbol in known }
            val rest = items.value.filter { i -> ordered.none { it.symbol == i.symbol } }
            items.value = ordered + rest
            store.saveWatchlist(items.value)
        }
        if (settings.value.sort != "manual") updateSettings { it.copy(sort = "manual") }
    }

    // ---------------------------------------------------------------- 匯入 / 匯出
    fun exportJson(includeSettings: Boolean): String =
        Portable.exportJson(items.value, if (includeSettings) settings.value else null, System.currentTimeMillis())

    fun exportCsv(): String = Portable.exportCsv(items.value)

    /** 套用匯入結果，回傳給使用者看的摘要。新加入的股票會立刻開始分析。 */
    fun applyImport(parsed: Portable.Parsed, replace: Boolean, withSettings: Boolean): String {
        val r: Portable.MergeResult
        synchronized(lock) {
            r = Portable.merge(items.value, parsed.items, replace)
            items.value = r.items
            store.saveWatchlist(r.items)
            if (r.removed.isNotEmpty()) {
                val gone = r.removed.map { it.symbol }.toSet()
                summaries.update { it - gone }; store.saveState(summaries.value)
                analyses.update { it - gone }; errors.update { it - gone }
                if (selected.value in gone) selected.value = null
            }
        }
        var settingsNote = ""
        val imported = parsed.settings
        if (withSettings && imported != null) {
            // API 金鑰不會被匯出，匯入時保留本機原有的金鑰
            updateSettings { cur -> imported.copy(finmindToken = cur.finmindToken, alphaKey = cur.alphaKey) }
            settingsNote = "，並套用設定"
        }
        refresh(r.added)
        return buildString {
            append(if (replace) "已取代清單：共 ${r.items.size} 檔" else "已加入 ${r.added.size} 檔")
            if (!replace && r.duplicates > 0) append("（${r.duplicates} 檔已存在）")
            if (replace && r.removed.isNotEmpty()) append("（移除 ${r.removed.size} 檔）")
            append(settingsNote)
        }
    }

    fun updateSettings(f: (Settings) -> Settings) {
        val old = settings.value
        val new = f(old)
        settings.value = new
        store.saveSettings(new)
        Scheduler.apply(getApplication(), store)
        if (old.period != new.period || old.disabledSources != new.disabledSources ||
            old.finmindToken != new.finmindToken || old.alphaKey != new.alphaKey) Sources.clearCache()
        if (old.period != new.period || old.capital != new.capital || old.riskPct != new.riskPct) refreshAll()
    }

    fun clearEvents() { events.value = emptyList(); store.saveEvents(emptyList()) }

    private var searchJob: Job? = null
    fun search(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) { searchResults.value = emptyList(); searching.value = false; return }
        searchJob = viewModelScope.launch {
            searching.value = true
            try { searchResults.value = Repo.search(q) } finally { searching.value = false }
        }
    }

    fun checkSources() {
        if (checkingSources.value) return
        viewModelScope.launch {
            checkingSources.value = true
            sourceCheck.value = null
            try {
                sourceCheck.value = withContext(Dispatchers.IO) {
                    Sources.check("2330").map { "2330 ${it.first}" to it.second } +
                        Sources.check("AAPL").map { "AAPL ${it.first}" to it.second }
                }
            } finally { checkingSources.value = false }
        }
    }
}
