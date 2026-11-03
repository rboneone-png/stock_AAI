package com.stockai.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stockai.app.store.Portable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** 匯入 / 匯出的操作入口（檔案選擇器 + 確認對話框 + 提示訊息）。 */
class IoController internal constructor(
    val snackbar: SnackbarHostState,
    private val onExportJson: (Boolean) -> Unit,
    private val onExportCsv: () -> Unit,
    private val onPick: () -> Unit,
    private val dialogs: @Composable () -> Unit,
) {
    fun exportJson(withSettings: Boolean) = onExportJson(withSettings)
    fun exportCsv() = onExportCsv()
    fun pickFile() = onPick()
    @Composable fun Dialogs() = dialogs()
}

private const val MAX_BYTES = 2_000_000

@Composable
fun rememberImportExport(vm: MainViewModel): IoController {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Portable.Parsed?>(null) }
    var replace by remember { mutableStateOf(false) }
    var withSettings by remember { mutableStateOf(false) }

    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    fun write(uri: Uri?) {
        val text = pending ?: return
        pending = null
        if (uri == null) return
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    null
                } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            }
            say(if (err == null) "已匯出" else "匯出失敗：$err")
        }
    }

    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { write(it) }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { write(it) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val buf = ByteArrayOutputStream()
                    ctx.contentResolver.openInputStream(uri)!!.use { ins ->
                        val b = ByteArray(8192)
                        while (true) {
                            val n = ins.read(b); if (n < 0) break
                            buf.write(b, 0, n)
                            require(buf.size() <= MAX_BYTES) { "檔案太大（超過 2 MB），應該不是自選股清單" }
                        }
                    }
                    Portable.parse(String(buf.toByteArray(), Charsets.UTF_8))
                }
            }
            r.onSuccess { replace = false; withSettings = false; preview = it }
                .onFailure { say("匯入失敗：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    val dialogs: @Composable () -> Unit = {
        preview?.let { p ->
            val existing by vm.items.collectAsStateWithLifecycle()
            val merged = Portable.merge(existing, p.items, replace)
            AlertDialog(
                onDismissRequest = { preview = null },
                title = { Text("匯入自選股") },
                text = {
                    Column {
                        Text("檔案中有 ${p.items.size} 檔股票" + if (p.skipped.isNotEmpty()) "，另有 ${p.skipped.size} 筆無法辨識已略過" else "")
                        ChoiceRow(!replace, "合併（保留現有清單）",
                            "加入 ${Portable.merge(existing, p.items, false).added.size} 檔新股票，已存在的會略過") { replace = false }
                        ChoiceRow(replace, "取代（以檔案為準）",
                            "清單與順序改成檔案內容" + Portable.merge(existing, p.items, true).removed.size.let { if (it > 0) "，會移除目前有、檔案沒有的 $it 檔" else "" }) { replace = true }
                        if (p.settings != null) {
                            Row(Modifier.fillMaxWidth().clickable { withSettings = !withSettings }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(withSettings, { withSettings = it })
                                Column {
                                    Text("同時套用檔案中的設定")
                                    Text("不含 API 金鑰；會覆蓋目前的警示、資金、排序等設定", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (merged.added.isNotEmpty()) Text("新加入的股票會立刻開始分析。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton({
                        val msg = vm.applyImport(p, replace, withSettings && p.settings != null)
                        preview = null; say(msg)
                    }) { Text("匯入") }
                },
                dismissButton = { TextButton({ preview = null }) { Text("取消") } },
            )
        }
    }

    return IoController(
        snackbar,
        onExportJson = { withSettings ->
            pending = vm.exportJson(withSettings)
            saveJson.launch("stockai-watchlist-${LocalDate.now()}.json")
        },
        onExportCsv = {
            pending = vm.exportCsv()
            saveCsv.launch("stockai-watchlist-${LocalDate.now()}.csv")
        },
        onPick = { open.launch(arrayOf("application/json", "text/*", "application/octet-stream", "*/*")) },
        dialogs = dialogs,
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
