package com.stockai.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stockai.app.engine.Frame
import kotlin.math.max
import kotlin.math.min

/** K 線 + 均線 + 成交量 + 綜合評分。點擊或左右拖曳可查看某天數值。 */
@Composable
fun PriceChart(f: Frame, tw: Boolean, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    var range by remember { mutableIntStateOf(120) }
    var sel by remember { mutableStateOf<Int?>(null) }
    val count = min(range, f.n)
    val start = f.n - count
    val idx = (sel ?: (f.n - 1)).coerceIn(start, f.n - 1)
    val tm = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outline
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val rightPad = 78f

    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60 to "3個月", 120 to "6個月", 250 to "1年").forEach { (n, t) ->
                FilterChip(selected = range == n, onClick = { range = n; sel = null }, label = { Text(t) })
            }
        }
        val up = f.close[idx] >= f.open[idx]
        Text(
            "${f.dates[idx]}  開 ${Fmt.price(f.open[idx])}  高 ${Fmt.price(f.high[idx])}  低 ${Fmt.price(f.low[idx])}  " +
                "收 ${Fmt.price(f.close[idx])}  量 ${Fmt.volume(f.volume[idx], tw)}  評分 ${Fmt.signed(f.score[idx])}",
            style = MaterialTheme.typography.bodySmall, color = if (up) pal.up else pal.down,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Canvas(
            Modifier.fillMaxWidth().height(380.dp)
                .pointerInput(count) {
                    detectTapGestures { p ->
                        val step = (size.width - rightPad) / count
                        sel = start + (p.x / step).toInt().coerceIn(0, count - 1)
                    }
                }
                .pointerInput(count) {
                    detectHorizontalDragGestures { change, _ ->
                        val step = (size.width - rightPad) / count
                        sel = start + (change.position.x / step).toInt().coerceIn(0, count - 1)
                    }
                },
        ) {
            val w = size.width; val h = size.height
            val chartW = w - rightPad
            val step = chartW / count
            fun x(i: Int) = (i - start + 0.5f) * step

            // ---- 版面：價格 58%、成交量 16%、評分 20%、日期 6%
            val priceTop = 4f; val priceBot = h * 0.58f
            val volTop = h * 0.61f; val volBot = h * 0.76f
            val scTop = h * 0.79f; val scBot = h * 0.95f

            // ---- 價格區
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (i in start until f.n) { lo = min(lo, f.low[i].toFloat()); hi = max(hi, f.high[i].toFloat()) }
            val pad = (hi - lo) * 0.06f + 1e-6f
            lo -= pad; hi += pad
            fun py(v: Double) = priceBot - ((v.toFloat() - lo) / (hi - lo)) * (priceBot - priceTop)

            for (k in 0..3) {
                val v = lo + (hi - lo) * k / 3f
                val y = py(v.toDouble())
                drawLine(grid, Offset(0f, y), Offset(chartW, y), 1f)
                drawLabel(tm, Fmt.num(v.toDouble(), if (hi >= 1000f) 0 else 2), chartW + 6f, y - 14f, label)
            }
            val cw = max(1f, step * 0.68f)
            for (i in start until f.n) {
                val color = if (f.close[i] >= f.open[i]) pal.up else pal.down
                drawLine(color, Offset(x(i), py(f.high[i])), Offset(x(i), py(f.low[i])), 1.2f)
                val top = py(max(f.open[i], f.close[i])); val bot = py(min(f.open[i], f.close[i]))
                drawRect(color, Offset(x(i) - cw / 2, top), Size(cw, max(1.5f, bot - top)))
            }
            line(f.ma5, start, f.n, ::x, ::py, pal.ma5)
            line(f.ma20, start, f.n, ::x, ::py, pal.ma20)
            line(f.ma60, start, f.n, ::x, ::py, pal.ma60)
            var lx = 6f
            for ((t, c) in listOf("MA5" to pal.ma5, "MA20" to pal.ma20, "MA60" to pal.ma60)) {
                drawLabel(tm, t, lx, priceTop, c); lx += tm.measure(t, TextStyle(fontSize = 10.sp)).size.width + 18f
            }

            // ---- 成交量
            var vmax = 1.0
            for (i in start until f.n) vmax = max(vmax, f.volume[i])
            for (i in start until f.n) {
                val color = (if (f.close[i] >= f.open[i]) pal.up else pal.down).copy(alpha = 0.55f)
                val bh = (f.volume[i] / vmax).toFloat() * (volBot - volTop)
                drawRect(color, Offset(x(i) - cw / 2, volBot - bh), Size(cw, bh))
            }
            drawLabel(tm, "成交量", chartW + 6f, volTop, label)

            // ---- 評分（-100 ~ +100）
            fun sy(v: Double) = scBot - ((v.toFloat() + 100f) / 200f) * (scBot - scTop)
            drawLine(grid, Offset(0f, sy(0.0)), Offset(chartW, sy(0.0)), 1.2f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            drawLine(pal.up.copy(alpha = 0.6f), Offset(0f, sy(20.0)), Offset(chartW, sy(20.0)), 1f, pathEffect = dash)
            drawLine(pal.down.copy(alpha = 0.6f), Offset(0f, sy(-10.0)), Offset(chartW, sy(-10.0)), 1f, pathEffect = dash)
            line(f.score, start, f.n, ::x, ::sy, pal.score, 2.2f)
            drawLabel(tm, "+20", chartW + 6f, sy(20.0) - 14f, pal.up)
            drawLabel(tm, "-10", chartW + 6f, sy(-10.0) - 2f, pal.down)
            drawLabel(tm, "評分", chartW + 6f, scTop - 6f, label)

            // ---- 日期
            for (k in 0..3) {
                val i = start + ((count - 1) * k / 3)
                drawLabel(tm, f.dates[i].toString().substring(2, 7), (x(i) - 24f).coerceIn(0f, chartW - 40f), h * 0.955f, label)
            }

            // ---- 十字線
            val cx = x(idx)
            drawLine(label.copy(alpha = 0.7f), Offset(cx, 0f), Offset(cx, scBot), 1.2f)
        }
    }
}

private fun DrawScope.drawLabel(tm: TextMeasurer, text: String, x: Float, y: Float, color: Color) {
    drawText(tm, text, Offset(x, y), TextStyle(color = color, fontSize = 10.sp), softWrap = false, maxLines = 1)
}

private fun DrawScope.line(
    s: DoubleArray, from: Int, to: Int, x: (Int) -> Float, y: (Double) -> Float, color: Color, width: Float = 1.8f,
) {
    val p = Path(); var open = false
    for (i in from until to) {
        val v = s[i]
        if (v.isNaN()) { open = false; continue }
        if (!open) { p.moveTo(x(i), y(v)); open = true } else p.lineTo(x(i), y(v))
    }
    drawPath(p, color, style = Stroke(width))
}
