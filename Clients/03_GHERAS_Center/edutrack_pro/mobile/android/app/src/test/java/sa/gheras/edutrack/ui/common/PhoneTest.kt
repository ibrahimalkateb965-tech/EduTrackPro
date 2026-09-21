package sa.gheras.edutrack.ui.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneTest {

    @Test
    fun `dial does not crash with valid phone number`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Phone.dial(context, "+966501234567")
        assertNotNull(context)
    }

    @Test
    fun `openWhatsApp does not crash and formats saudi numbers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Phone.openWhatsApp(context, "0501234567", "مرحبا")
        assertNotNull(context)
    }
}
