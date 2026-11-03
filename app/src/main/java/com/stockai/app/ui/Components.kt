package com.stockai.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun scoreColor(score: Double): Color {
    val p = LocalPalette.current
    return if (score >= 20) p.up else if (score <= -20) p.down else p.flat
}

@Composable
fun changeColor(x: Double): Color {
    val p = LocalPalette.current
    return if (x > 0) p.up else if (x < 0) p.down else p.flat
}

@Composable
fun ScorePill(score: Double, big: Boolean = false) {
    val c = scoreColor(score)
    Text(
        Fmt.signed(score), color = c, fontWeight = FontWeight.Bold,
        fontSize = if (big) 20.sp else 14.sp,
        modifier = Modifier.background(c.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = if (big) 14.dp else 10.dp, vertical = if (big) 4.dp else 2.dp),
    )
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun KV(k: String, v: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(v, color = color, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 因子分數長條：以中線為 0，向右（看多）/向左（看空）。 */
@Composable
fun FactorBar(name: String, v: Double, weight: Int) {
    val pal = LocalPalette.current
    val track = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        Canvas(Modifier.weight(1f).height(10.dp)) {
            drawRoundRect(track, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f))
            val half = size.width / 2
            val len = (Math.abs(v) / weight).coerceAtMost(1.0).toFloat() * half
            val color = if (v >= 0) pal.up else pal.down
            drawRect(color, Offset(if (v >= 0) half else half - len, 0f), Size(len, size.height))
            drawRect(Color.Gray.copy(alpha = 0.6f), Offset(half - 0.5f, 0f), Size(1f, size.height))
        }
        Text(Fmt.signed(v, 1), Modifier.width(52.dp), color = changeColor(v), fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** 數字輸入框：輸入合法數字時才回呼。 */
@Composable
fun NumField(label: String, value: Double, suffix: String = "", modifier: Modifier = Modifier, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()) }
    OutlinedTextField(
        value = text, onValueChange = { t -> text = t; t.toDoubleOrNull()?.takeIf { it > 0 }?.let(onValue) },
        label = { Text(label) }, suffix = { Text(suffix) }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}
