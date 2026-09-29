package sa.gheras.edutrack.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class NumTest {

    @Test
    fun `formatCurrency formats double with two decimals and sar suffix in 0-9 digits`() {
        val formatted = Num.formatCurrency(1250.5)
        assertEquals("1,250.50 ر.س", formatted)
    }

    @Test
    fun `formatCurrency handles zero properly`() {
        val formatted = Num.formatCurrency(0.0)
        assertEquals("0.00 ر.س", formatted)
    }

    @Test
    fun `formatInt adds thousands separator`() {
        val formatted = Num.formatInt(1000000)
        assertEquals("1,000,000", formatted)
    }

    @Test
    fun `enforceWesternNumerals replaces eastern arabic numerals with 0-9`() {
        val easternText = "الدرجة: ٩٥ من ١٠٠ والتاريخ ٢٠٢٦/٠٩/٢١"
        val normalized = Num.enforceWesternNumerals(easternText)
        assertEquals("الدرجة: 95 من 100 والتاريخ 2026/09/21", normalized)
    }

    @Test
    fun `formatDecimal formats whole numbers without trailing zeros and preserves exact decimals`() {
        assertEquals("10", Num.formatDecimal(10.0))
        assertEquals("0", Num.formatDecimal(0.0))
        assertEquals("9.5", Num.formatDecimal(9.5))
        assertEquals("9.25", Num.formatDecimal(9.25))
        assertEquals("9.75", Num.formatDecimal(9.75))
        assertEquals("9.96", Num.formatDecimal(9.96))
    }
}
