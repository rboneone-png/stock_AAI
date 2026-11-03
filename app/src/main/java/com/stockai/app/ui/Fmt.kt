package com.stockai.app.ui

import java.util.Locale
import kotlin.math.abs

object Fmt {
    fun price(x: Double): String = if (x.isNaN()) "—" else String.format(Locale.US, "%,.2f", x)
    fun pct(x: Double): String = if (x.isNaN()) "—" else String.format(Locale.US, "%+.2f%%", x)
    fun num(x: Double?, nd: Int = 2): String = if (x == null || x.isNaN()) "—" else String.format(Locale.US, "%,.${nd}f", x)
    fun signed(x: Double, nd: Int = 0): String = String.format(Locale.US, "%+.${nd}f", x)
    fun int(x: Int): String = String.format(Locale.US, "%,d", x)

    /** 成交量：台股以「張」、美股以股數縮寫表示。 */
    fun volume(v: Double, tw: Boolean): String {
        if (tw) {
            val z = v / 1000
            return if (z >= 10_000) String.format(Locale.US, "%.1f萬張", z / 10_000) else String.format(Locale.US, "%,.0f張", z)
        }
        return when {
            abs(v) >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
            abs(v) >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
            abs(v) >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3)
            else -> String.format(Locale.US, "%.0f", v)
        }
    }
}
