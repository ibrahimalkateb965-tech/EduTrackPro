package sa.gheras.edutrack.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri

object Phone {

    fun dial(context: Context, phoneNumber: String) {
        val clean = phoneNumber.filter { it.isDigit() || it == '+' }
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun openWhatsApp(context: Context, phoneNumber: String, message: String = "") {
        var clean = phoneNumber.filter { it.isDigit() }
        if (clean.startsWith("05")) {
            clean = "966" + clean.drop(1)
        } else if (clean.startsWith("5")) {
            clean = "966$clean"
        }
        val encodedMsg = Uri.encode(message)
        val url = "https://wa.me/$clean?text=$encodedMsg"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
