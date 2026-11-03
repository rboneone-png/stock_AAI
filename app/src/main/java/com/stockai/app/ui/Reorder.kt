package com.stockai.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.stockai.app.engine.Analysis
import com.stockai.app.store.Summary
import com.stockai.app.store.WatchItem

/**
 * 手動排序清單：按住左側「≡」上下拖曳，或用右側 ▲▼ 逐格移動。
 * 清單順序就是 [list] 的順序，移動時透過 [onMove] 通知（索引為清單中的位置）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReorderList(
    list: List<WatchItem>,
    analyses: Map<String, Analysis>,
    summaries: Map<String, Summary>,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val state = rememberLazyListState()
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var fingerTop by remember { mutableFloatStateOf(0f) } // 被拖曳列「頂端」跟著手指的位置（清單座標）
    val move by rememberUpdatedState(onMove)

    fun info(key: String) = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(list, key = { _, it -> it.symbol }) { index, item ->
            val dragging = draggedKey == item.symbol
            val a = analyses[item.symbol]
            val price = a?.price ?: summaries[item.symbol]?.price
            val chg = a?.changePct ?: summaries[item.symbol]?.changePct
            Card(
                Modifier.fillMaxWidth()
                    .then(if (dragging) Modifier.zIndex(1f) else Modifier.animateItem())
                    .graphicsLayer {
                        // 被拖曳的列跟著手指；其餘列由 animateItem 負責換位動畫
                        translationY = if (dragging) fingerTop - (info(item.symbol)?.offset?.toFloat() ?: fingerTop) else 0f
                        shadowElevation = if (dragging) 16f else 0f
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(if (dragging) 2.dp else 1.dp, if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Menu, "拖曳排序",
                        Modifier.size(44.dp).padding(10.dp).testTag("handle_${item.symbol}").pointerInput(item.symbol) {
                            detectDragGestures(
                                onDragStart = { info(item.symbol)?.let { draggedKey = item.symbol; fingerTop = it.offset.toFloat() } },
                                onDragEnd = { draggedKey = null },
                                onDragCancel = { draggedKey = null },
                            ) { change, amount ->
                                change.consume()
                                fingerTop += amount.y
                                val me = info(item.symbol) ?: return@detectDragGestures
                                val center = fingerTop + me.size / 2f
                                val target = state.layoutInfo.visibleItemsInfo.firstOrNull {
                                    it.key != item.symbol && center >= it.offset && center < it.offset + it.size
                                }
                                if (target != null) move(me.index, target.index)
                                // 拖到上下邊緣時自動捲動
                                val vs = state.layoutInfo.viewportStartOffset; val ve = state.layoutInfo.viewportEndOffset
                                if (fingerTop < vs + 48f) state.dispatchRawDelta(-28f)
                                else if (fingerTop + me.size > ve - 48f) state.dispatchRawDelta(28f)
                            }
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text(item.name.ifBlank { item.symbol }, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            listOfNotNull(item.symbol, price?.let { Fmt.price(it) }, chg?.let { Fmt.pct(it) }).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton({ move(index, index - 1) }, enabled = index > 0, modifier = Modifier.testTag("up_${item.symbol}")) {
                        Icon(Icons.Default.KeyboardArrowUp, "上移")
                    }
                    IconButton({ move(index, index + 1) }, enabled = index < list.lastIndex, modifier = Modifier.testTag("down_${item.symbol}")) {
                        Icon(Icons.Default.KeyboardArrowDown, "下移")
                    }
                }
            }
        }
    }
}
