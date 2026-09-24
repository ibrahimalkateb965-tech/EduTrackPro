package sa.gheras.edutrack.ui.teacher.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssignmentMediaParserTest {

    @Test
    fun roundTripWithInstructionsAndAttachments() {
        val originalText = "حفظ سورة النبأ من الآية 1 إلى 15"
        val attachments = listOf(
            MediaAttachment(
                id = "att_1",
                kind = "voice_note",
                title = "تسميع صوتي",
                uri = "/data/user/0/sa.gheras.edutrack/files/voice.m4a",
                durationSeconds = 45
            ),
            MediaAttachment(
                id = "att_2",
                kind = "google_form",
                title = "نموذج اختبار",
                uri = "https://forms.gle/xyz123"
            )
        )

        val serialized = AssignmentMediaParser.serialize(originalText, attachments)
        val (parsedText, parsedAttachments) = AssignmentMediaParser.parse(serialized)

        assertEquals(originalText, parsedText)
        assertEquals(2, parsedAttachments.size)
        assertEquals("att_1", parsedAttachments[0].id)
        assertEquals("voice_note", parsedAttachments[0].kind)
        assertEquals(45, parsedAttachments[0].durationSeconds)
        assertEquals("https://forms.gle/xyz123", parsedAttachments[1].uri)
    }

    @Test
    fun roundTripWithBlankInstructions() {
        val attachments = listOf(
            MediaAttachment(
                id = "att_blank",
                kind = "pdf_document",
                title = "ورقة عمل PDF",
                uri = "/data/files/sheet.pdf"
            )
        )

        val serialized = AssignmentMediaParser.serialize("", attachments)
        val (parsedText, parsedAttachments) = AssignmentMediaParser.parse(serialized)

        assertEquals("", parsedText)
        assertEquals(1, parsedAttachments.size)
        assertEquals("att_blank", parsedAttachments[0].id)
        assertEquals("pdf_document", parsedAttachments[0].kind)
    }

    @Test
    fun plainTextWithoutAttachments() {
        val text = "يرجى قراءة الدرس صفحة 22"
        val (parsedText, attachments) = AssignmentMediaParser.parse(text)

        assertEquals(text, parsedText)
        assertTrue(attachments.isEmpty())
    }

    @Test
    fun autoDetectGoogleFormsLinkWithPunctuation() {
        val text = "يرجى حل النموذج التالي (https://forms.gle/sampleForm123)."
        val (parsedText, attachments) = AssignmentMediaParser.parse(text)

        assertEquals(text, parsedText)
        assertEquals(1, attachments.size)
        assertEquals("google_form", attachments[0].kind)
        assertEquals("https://forms.gle/sampleForm123", attachments[0].uri)
    }

    @Test
    fun autoDetectYouTubeLink() {
        val text = "شاهد شرح التجويد: https://youtu.be/dQw4w9WgXcQ بالتوفيق"
        val (parsedText, attachments) = AssignmentMediaParser.parse(text)

        assertEquals(text, parsedText)
        assertEquals(1, attachments.size)
        assertEquals("video_link", attachments[0].kind)
        assertEquals("https://youtu.be/dQw4w9WgXcQ", attachments[0].uri)
    }

    @Test
    fun autoDetectLinkWithDotInPathOrQuery() {
        val text = "يرجى مراجعة الرابط https://forms.gle/test.exam.v1?user.id=123."
        val (parsedText, attachments) = AssignmentMediaParser.parse(text)

        assertEquals(text, parsedText)
        assertEquals(1, attachments.size)
        assertEquals("https://forms.gle/test.exam.v1?user.id=123", attachments[0].uri)
    }
}
