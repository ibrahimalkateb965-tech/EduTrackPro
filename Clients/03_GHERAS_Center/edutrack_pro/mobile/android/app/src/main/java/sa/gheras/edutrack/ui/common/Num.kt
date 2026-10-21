package sa.gheras.edutrack.ui.common

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Num {

    private val symbols = DecimalFormatSymbols(Locale.US)
    private val currencyFormat = DecimalFormat("#,##0.00", symbols)
    private val integerFormat = DecimalFormat("#,##0", symbols)
    private val decimalFormat = DecimalFormat("0.##", symbols)

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
     * Formats decimal/grade values cleanly (e.g. 10, 9.5, 9.25) using 0-9 numerals without loss of precision.
     */
    fun formatDecimal(value: Double): String = decimalFormat.format(value)

    /**
     * Formats integers/counts with thousands separator using 0-9 numerals.
     */
    fun format(value: Int): String = integerFormat.format(value)
    fun format(value: Long): String = integerFormat.format(value)

    /**
     * Formats date cleanly using 0-9 Western Arabic numerals (Rule 50).
     */
    fun formatDate(date: java.time.LocalDate?): String {
        return date?.toString() ?: "غير محدد"
    }

    /**
     * Formats instant in Riyadh timezone (Asia/Riyadh) using 0-9 numerals.
     */
    fun formatInstant(instant: java.time.Instant?): String {
        if (instant == null) return "غير محدد"
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        return instant.atZone(java.time.ZoneId.of("Asia/Riyadh")).format(formatter)
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
