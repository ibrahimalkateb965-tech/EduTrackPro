package sa.gheras.edutrack.ui.common

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Num {

    private val symbols = DecimalFormatSymbols(Locale.US)
    private val currencyFormat = DecimalFormat("#,##0.00", symbols)
    private val integerFormat = DecimalFormat("#,##0", symbols)

    /**
     * Formats monetary amount in SAR using 0-9 Western Arabic numerals strictly (Rule 50).
     */
    fun formatCurrency(amount: Double): String {
        return "${currencyFormat.format(amount)} ر.س"
    }

    /**
     * Formats integer with thousands separator using 0-9 numerals.
     */
    fun formatInt(value: Int): String {
        return integerFormat.format(value)
    }

    /**
     * Enforces Western Arabic numerals (0-9) by replacing any Eastern Arabic numerals (٠-٩).
     */
    fun enforceWesternNumerals(text: String): String {
        val eastern = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        var result = text
        for (i in 0..9) {
            result = result.replace(eastern[i], ('0' + i))
        }
        return result
    }
}
