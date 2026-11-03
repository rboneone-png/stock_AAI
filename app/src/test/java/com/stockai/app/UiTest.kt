package com.stockai.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stockai.app.engine.*
import com.stockai.app.store.AlertEvent
import com.stockai.app.store.WatchItem
import com.stockai.app.ui.MainViewModel
import com.stockai.app.ui.StockAiApp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val out = File(System.getProperty("java.io.tmpdir"), "stockai-shots").also { it.mkdirs() }

    private fun analysis(csv: String, bench: String?, sym: String, name: String): Analysis {
        fun load(n: String, ohlc: Boolean) = javaClass.classLoader!!.getResource(n)!!.readText().lines().drop(1).filter { it.isNotBlank() }.map {
            val p = it.split(","); val d = LocalDate.parse(p[0].take(10))
            if (ohlc) Bar(d, p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
            else p[1].toDouble().let { c -> Bar(d, c, c, c, c, 0.0) }
        }
        return Engine.analyze(
            Fetched(load(csv, true), sym, "yahoo", listOf("測試備註：歷史資料來源示範")),
            bench?.let { Fetched(load(it, false), "BENCH", "yahoo") },
            Fundamentals(name, listOf("本益比" to "27.01", "殖利率%" to "0.94", "市場" to "上市"), listOf("➖ 本益比 27.0 合理區間")),
            AnalysisOptions(), today = LocalDate.of(2026, 11, 3), checkFreshness = false,
        )
    }

    // 測試資料不放「載入中」狀態：無限循環的進度條動畫會讓 waitForIdle / captureToImage 逾時
    private fun settle() = rule.waitForIdle()

    /** Robolectric 的 captureToImage 會等不到重繪；直接把視窗 draw 到 Bitmap。 */
    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(maxOf(view.width, 1), maxOf(view.height, 1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun newVm(): MainViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app, autoRefresh = false)
        val a1 = analysis("AAPL.csv", "AAPL_bench.csv", "AAPL", "Apple Inc")
        val a2 = analysis("2330.csv", "2330_bench.csv", "2330.TW", "台積電")
        vm.items.value = listOf(WatchItem("AAPL", "Apple Inc"), WatchItem("2330.TW", "台積電"), WatchItem("BAD", "壞資料"), WatchItem("NVDA", "NVIDIA"))
        vm.analyses.value = mapOf("AAPL" to a1, "2330.TW" to a2)
        vm.errors.value = mapOf("BAD" to "BAD 所有來源都失敗")
        vm.events.value = listOf(
            AlertEvent(System.currentTimeMillis(), "2330.TW", "台積電", "🕳️ 創 60 日新低"),
            AlertEvent(System.currentTimeMillis() - 3_600_000, "AAPL", "Apple Inc", "評等變化：觀望 → 🔴 賣出（-1 → -35）"),
        )
        return vm
    }

    @Test fun mainScreensRender() {
        val vm = newVm()
        rule.setContent { StockAiApp(vm) {} }
        settle(); shot("1_watchlist")
        assertTrue(rule.onAllNodesWithText("Apple Inc").fetchSemanticsNodes().isNotEmpty())

        rule.onNodeWithText("警示", useUnmergedTree = true).performClick(); settle(); shot("8_alerts")
        rule.onNodeWithText("設定", useUnmergedTree = true).performClick(); settle(); shot("9_settings")
        rule.onNodeWithText("自選股", useUnmergedTree = true).performClick(); settle()
    }

    @Test fun detailRenders() {
        val vm = newVm()
        vm.selected.value = "2330.TW"
        rule.setContent { StockAiApp(vm) {} }
        settle(); shot("3_detail_top")
        for ((i, n) in listOf(1 to "4_chart", 3 to "5_reasons", 4 to "6_plan", 6 to "7_risk", 7 to "7b_backtest", 8 to "7c_fund")) {
            rule.onNodeWithTag("detail").performScrollToIndex(i); settle(); shot("detail_$n")
        }
    }

    @Test fun errorAndLoadingDetail() {
        val vm = newVm()
        vm.selected.value = "BAD"
        rule.setContent { StockAiApp(vm) {} }
        settle(); shot("10_detail_error")
        assertTrue(rule.onAllNodesWithText("無法取得資料").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun addSheetRenders() {
        val vm = newVm()
        rule.setContent { StockAiApp(vm) {} }
        rule.onNodeWithText("新增股票", useUnmergedTree = true).performClick(); settle(); shot("2_add")
        assertTrue(rule.onAllNodesWithText("熱門台股").fetchSemanticsNodes().isNotEmpty())
    }
}
