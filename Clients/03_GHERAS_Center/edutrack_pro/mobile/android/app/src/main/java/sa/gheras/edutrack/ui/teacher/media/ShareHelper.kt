package sa.gheras.edutrack.ui.teacher.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import sa.gheras.edutrack.data.entity.AssignmentEntity

object ShareHelper {

    fun shareAssignmentViaWhatsApp(
        context: Context,
        assignment: AssignmentEntity,
        roomName: String,
        attachments: List<MediaAttachment> = emptyList()
    ) {
        val (cleanInstructions, parsedAttachments) = AssignmentMediaParser.parse(assignment.instructions)
        val allAttachments = if (attachments.isNotEmpty()) attachments else parsedAttachments

        val sb = StringBuilder()
        sb.append("السلام عليكم ورحمة الله وبركاته 🌿\n\n")
        sb.append("📢 *تكليف مدرسي جديد - مركز غراس التعليمي*\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("🏛️ *القاعة:* $roomName\n")
        if (!assignment.subject.isNullOrBlank()) {
            sb.append("📖 *المادة:* ${assignment.subject}\n")
        }
        sb.append("📝 *عنوان الواجب:* ${assignment.title}\n")
        if (!assignment.pageRef.isNullOrBlank()) {
            sb.append("📑 *الصفحة / الآيات:* ${assignment.pageRef}\n")
        }
        sb.append("📅 *موعد التسليم:* ${assignment.dueDate}\n\n")

        if (cleanInstructions.isNotBlank()) {
            sb.append("📋 *التعليمات والتوجيهات:*\n")
            sb.append(cleanInstructions)
            sb.append("\n\n")
        }

        val links = allAttachments.filter { it.kind in listOf("google_form", "video_link", "web_link") }
        if (links.isNotEmpty()) {
            sb.append("🔗 *الروابط ونماذج الاختبار:*\n")
            links.forEach { link ->
                sb.append("• ${link.title}: ${link.uri}\n")
            }
            sb.append("\n")
        }

        sb.append("مع تمنياتنا لأبنائنا بالتميز والنجاح ✨")

        val message = sb.toString()

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Fallback to general share sheet if WhatsApp not installed
            val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(fallbackIntent, "مشاركة التكليف عبر...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun openLink(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun openAttachment(context: Context, pathOrUrl: String) {
        if (pathOrUrl.isBlank()) return
        try {
            if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
                openLink(context, pathOrUrl)
                return
            }

            val file = java.io.File(pathOrUrl)
            if (file.exists()) {
                val authority = "${context.packageName}.fileprovider"
                val contentUri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                val ext = file.extension.lowercase()
                val mimeType = when (ext) {
                    "pdf" -> "application/pdf"
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "m4a", "aac", "mp3" -> "audio/*"
                    "mp4" -> "video/mp4"
                    else -> "*/*"
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "فتح المرفق").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } else {
                openLink(context, pathOrUrl)
            }
        } catch (_: Exception) {}
    }
}
