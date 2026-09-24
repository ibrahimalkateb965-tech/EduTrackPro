package sa.gheras.edutrack.ui.teacher.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class MediaKind(val title: String) {
    VOICE_NOTE("تسجيل صوتي"),
    GOOGLE_FORM("نموذج Google Form"),
    VIDEO_LINK("فيديو شرح"),
    IMAGE("صورة"),
    PDF_DOCUMENT("مستند PDF"),
    WEB_LINK("رابط خارجي")
}

@Serializable
data class MediaAttachment(
    val id: String,
    val kind: String, // voice_note, google_form, video_link, image, pdf_document, web_link
    val title: String,
    val uri: String,
    val durationSeconds: Int? = null,
    val sizeBytes: Long? = null
)

object AssignmentMediaParser {
    private const val HEADER_DELIMITER = "---مرفقات---\n"
    private const val ATTACHMENTS_DELIMITER = "\n\n$HEADER_DELIMITER"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Parses instructions text and extracts structured attachments,
     * including auto-detecting Google Forms and YouTube/Drive links in the text.
     */
    fun parse(instructions: String?): Pair<String, List<MediaAttachment>> {
        if (instructions.isNullOrBlank()) return Pair("", emptyList())

        val raw = instructions.trim()
        val (cleanText, jsonChunk) = when {
            raw.startsWith(HEADER_DELIMITER) -> {
                Pair("", raw.removePrefix(HEADER_DELIMITER).trim())
            }
            raw.contains(ATTACHMENTS_DELIMITER) -> {
                val parts = raw.split(ATTACHMENTS_DELIMITER, limit = 2)
                Pair(parts[0].trim(), parts[1].trim())
            }
            raw.contains(HEADER_DELIMITER) -> {
                val parts = raw.split(HEADER_DELIMITER, limit = 2)
                Pair(parts[0].trim(), parts[1].trim())
            }
            else -> {
                Pair(raw, null)
            }
        }

        val explicitAttachments = if (!jsonChunk.isNullOrBlank()) {
            try {
                json.decodeFromString<List<MediaAttachment>>(jsonChunk)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val autoDetected = mutableListOf<MediaAttachment>()

        // 1. Auto-detect Google Forms links in text (excluding punctuation at end)
        val googleFormRegex = Regex("""https?://(forms\.gle|docs\.google\.com/forms)[^\s،\(\)\[\]\<\>]+""")
        googleFormRegex.findAll(cleanText).forEach { match ->
            val url = match.value.trim().trimEnd('.', '،', ',', ')', ']', '>')
            if (explicitAttachments.none { it.uri == url } && autoDetected.none { it.uri == url }) {
                autoDetected.add(
                    MediaAttachment(
                        id = "auto_form_${url.hashCode()}",
                        kind = MediaKind.GOOGLE_FORM.name.lowercase(),
                        title = "نموذج أسئلة (Google Form)",
                        uri = url
                    )
                )
            }
        }

        // 2. Auto-detect YouTube and Drive video links in text (excluding punctuation at end)
        val videoRegex = Regex("""https?://(www\.youtube\.com|youtu\.be|drive\.google\.com/file)[^\s،\(\)\[\]\<\>]+""")
        videoRegex.findAll(cleanText).forEach { match ->
            val url = match.value.trim().trimEnd('.', '،', ',', ')', ']', '>')
            if (explicitAttachments.none { it.uri == url } && autoDetected.none { it.uri == url }) {
                autoDetected.add(
                    MediaAttachment(
                        id = "auto_video_${url.hashCode()}",
                        kind = MediaKind.VIDEO_LINK.name.lowercase(),
                        title = "مقطع فيديو للشرح",
                        uri = url
                    )
                )
            }
        }

        return Pair(cleanText, explicitAttachments + autoDetected)
    }

    /**
     * Serializes clean text and structured attachments into single instructions payload.
     */
    fun serialize(cleanText: String, attachments: List<MediaAttachment>): String {
        val trimmed = cleanText.trim()
        if (attachments.isEmpty()) return trimmed
        val jsonStr = json.encodeToString(attachments)
        return if (trimmed.isBlank()) {
            "$HEADER_DELIMITER$jsonStr"
        } else {
            "$trimmed$ATTACHMENTS_DELIMITER$jsonStr"
        }
    }
}
