package sa.gheras.edutrack.data.repo.mappers

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sa.gheras.edutrack.data.remote.dto.NotificationDto
import java.time.Instant

class NotificationMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun toEntity_mapsAllV2Fields() {
        val dto = NotificationDto(
            id = "n_1",
            branchId = "b_server",
            kind = "assignment",
            title = "واجب جديد: الكسور",
            body = "موعد التسليم: 2026-09-30",
            targetType = "assignment",
            targetId = "a_1",
            priority = "urgent",
            actionUrl = "gheras://assignment?student_id=s_1",
            senderUserId = "u_teacher",
            senderName = "أ. سارة",
            broadcastId = null,
            readAt = "2026-09-24T09:00:00Z",
            sentAt = "2026-09-24T08:30:00Z",
            createdAt = "2026-09-24T08:00:00Z",
            updatedAt = "2026-09-24T09:00:00Z"
        )

        val e = NotificationMappers.toEntity(dto, currentUserId = "u_guardian", defaultBranchId = "b_default")

        assertEquals("n_1", e.id)
        assertEquals("b_server", e.branchId)
        assertEquals("u_guardian", e.userId)
        assertEquals("assignment", e.kind)
        assertEquals("واجب جديد: الكسور", e.title)
        assertEquals("موعد التسليم: 2026-09-30", e.body)
        assertEquals("assignment", e.targetType)
        assertEquals("a_1", e.targetId)
        assertEquals("urgent", e.priority)
        assertEquals("gheras://assignment?student_id=s_1", e.actionUrl)
        assertEquals("u_teacher", e.senderUserId)
        assertEquals("أ. سارة", e.senderName)
        assertNull(e.broadcastId)
        assertEquals(Instant.parse("2026-09-24T09:00:00Z"), e.readAt)
        assertEquals(Instant.parse("2026-09-24T08:30:00Z"), e.sentAt)
        assertEquals(Instant.parse("2026-09-24T08:00:00Z"), e.createdAt)
        assertEquals(Instant.parse("2026-09-24T09:00:00Z"), e.updatedAt)
        assertNull(e.deletedAt)
    }

    @Test
    fun toEntity_sentAtFallsBackToCreatedAt() {
        val dto = NotificationDto(id = "n_2", title = "t", createdAt = "2026-09-20T10:00:00Z", updatedAt = "2026-09-20T10:00:00Z")
        val e = NotificationMappers.toEntity(dto)
        assertEquals(Instant.parse("2026-09-20T10:00:00Z"), e.sentAt)
        assertEquals(e.createdAt, e.sentAt)
    }

    @Test
    fun toEntity_branchFallsBackToDefault() {
        val dto = NotificationDto(id = "n_3", title = "t", createdAt = "2026-09-20T10:00:00Z")
        assertEquals("b_default", NotificationMappers.toEntity(dto, defaultBranchId = "b_default").branchId)
        assertNull(NotificationMappers.toEntity(dto).branchId)
    }

    @Test
    fun toEntity_priorityNormalization() {
        fun p(raw: String) = NotificationMappers.toEntity(NotificationDto(id = "x", title = "t", priority = raw)).priority
        assertEquals("urgent", p("URGENT"))
        assertEquals("urgent", p("Urgent"))
        assertEquals("normal", p("normal"))
        assertEquals("normal", p("high"))
        assertEquals("normal", p(""))
    }

    @Test
    fun toEntity_unreadHasNullReadAt_andDefaultsUserId() {
        val e = NotificationMappers.toEntity(NotificationDto(id = "n_4", title = "t", createdAt = "2026-09-20T10:00:00Z"))
        assertNull(e.readAt)
        assertEquals("me", e.userId)
        assertEquals("announcement", e.kind)
    }

    @Test
    fun toEntity_postgresStyleTimestamp_parses() {
        // Server row_to_json may emit "YYYY-MM-DD HH:MM:SS" without zone.
        val dto = NotificationDto(id = "n_5", title = "t", createdAt = "2026-09-20 10:00:00")
        assertEquals(Instant.parse("2026-09-20T10:00:00Z"), NotificationMappers.toEntity(dto).createdAt)
    }

    @Test
    fun dto_deserializesServerPayload_withNullsAndUnknownKeys() {
        val payload = """
            {
              "id": "n_6",
              "branch_id": "b_1",
              "user_id": "u_1",
              "kind": "announcement",
              "title": "رحلة مدرسية",
              "body": null,
              "target_type": "announcement",
              "target_id": "bc_1",
              "priority": "normal",
              "action_url": "gheras://announcement?student_id=s_1",
              "sender_user_id": "u_t",
              "sender_name": "أ. خالد",
              "broadcast_id": "bc_1",
              "read_at": null,
              "sent_at": "2026-09-24T07:00:00+00:00",
              "created_at": "2026-09-24T07:00:00+00:00",
              "updated_at": "2026-09-24T07:00:00+00:00",
              "deleted_at": null
            }
        """.trimIndent()

        val dto = json.decodeFromString(NotificationDto.serializer(), payload)
        val e = NotificationMappers.toEntity(dto, currentUserId = "u_1")

        assertNull(e.body)
        assertEquals("bc_1", e.broadcastId)
        assertEquals("أ. خالد", e.senderName)
        assertEquals(Instant.parse("2026-09-24T07:00:00Z"), e.sentAt)
    }
}
