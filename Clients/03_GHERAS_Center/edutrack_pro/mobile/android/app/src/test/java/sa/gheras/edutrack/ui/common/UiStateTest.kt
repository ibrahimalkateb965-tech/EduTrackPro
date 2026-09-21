package sa.gheras.edutrack.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {

    @Test
    fun `UiState variants instantiate correctly`() {
        val loading = UiState.Loading
        assertTrue(loading is UiState.Loading)

        val success = UiState.Success("data")
        assertEquals("data", success.data)

        val empty = UiState.Empty("لا توجد بيانات")
        assertEquals("لا توجد بيانات", empty.message)

        val error = UiState.Error("خطأ في الشبكة")
        assertEquals("خطأ في الشبكة", error.message)
    }
}
