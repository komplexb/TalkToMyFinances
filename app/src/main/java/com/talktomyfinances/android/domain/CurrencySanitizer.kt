package com.talktomyfinances.android.domain

object CurrencySanitizer {
    /**
     * Preserves standard currency notation (e.g. $0.00, $15.50), eliminating concatenated
     * or corrupted decimal artifacts like $0.0.00, $0.0.0, or $15.5.50.
     */
    fun sanitize(text: String): String {
        return text
            // Handle zero artifacts: $0.0.00, $ 0.0.0, $0.0.0.00 -> $0.00
            .replace(Regex("""\$\s*0(?:\.0)+\b""")) { "$0.00" }
            // Handle multiple decimal point corruptions: e.g. $15.5.50 -> $15.50, $15.5.5 -> $15.50
            .replace(Regex("""\$(\d+)\.(?:\d+\.)+(\d{1,2})\b""")) { m ->
                val dollars = m.groupValues[1]
                val cents = m.groupValues[2].let { if (it.length == 1) "${it}0" else it }
                "$$dollars.$cents"
            }
    }
}
