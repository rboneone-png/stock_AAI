package com.stockai.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 漲跌顏色：台股慣例紅漲綠跌，美股慣例相反（可在設定切換）。 */
data class Palette(val up: Color, val down: Color, val flat: Color, val ma5: Color, val ma20: Color, val ma60: Color, val score: Color)

val LocalPalette = staticCompositionLocalOf {
    Palette(Color(0xFFE5484D), Color(0xFF30A46C), Color(0xFF8B8D98), Color(0xFFF5A524), Color(0xFF3E63DD), Color(0xFF8E4EC6), Color(0xFF12A594))
}

private val Light = lightColorScheme(
    primary = Color(0xFF3E63DD), onPrimary = Color.White,
    background = Color(0xFFFBFBFC), onBackground = Color(0xFF1C2024),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1C2024),
    surfaceVariant = Color(0xFFF0F0F3), onSurfaceVariant = Color(0xFF60646C),
    outline = Color(0xFFE0E1E6), outlineVariant = Color(0xFFE8E8EC),
    error = Color(0xFFD13438),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8DA4EF), onPrimary = Color(0xFF111113),
    background = Color(0xFF111113), onBackground = Color(0xFFEDEEF0),
    surface = Color(0xFF18191B), onSurface = Color(0xFFEDEEF0),
    surfaceVariant = Color(0xFF212225), onSurfaceVariant = Color(0xFFB0B4BA),
    outline = Color(0xFF2E3135), outlineVariant = Color(0xFF2A2C2F),
    error = Color(0xFFFF6369),
)

@Composable
fun StockTheme(redUp: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val red = if (dark) Color(0xFFFF6369) else Color(0xFFE5484D)
    val green = if (dark) Color(0xFF3DD68C) else Color(0xFF30A46C)
    val pal = Palette(
        up = if (redUp) red else green, down = if (redUp) green else red,
        flat = Color(0xFF8B8D98), ma5 = Color(0xFFF5A524),
        ma20 = if (dark) Color(0xFF8DA4EF) else Color(0xFF3E63DD),
        ma60 = if (dark) Color(0xFFC29CF1) else Color(0xFF8E4EC6), score = Color(0xFF12A594),
    )
    CompositionLocalProvider(LocalPalette provides pal) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
