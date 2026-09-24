package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import sa.gheras.edutrack.data.dao.NotificationDao
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.sync.Outbox

class NotificationsRepository(
    private val notificationDao: NotificationDao,
    private val outbox: Outbox
) {
    fun observeAll(userId: String): Flow<List<NotificationEntity>> =
        notificationDao.observeByUser(userId)

    fun observeUnread(userId: String): Flow<List<NotificationEntity>> =
        notificationDao.observeUnreadByUser(userId)

    fun countUnread(userId: String): Flow<Int> =
        notificationDao.countUnreadByUser(userId)

    suspend fun markRead(id: String) {
        val now = java.time.Instant.now()
        notificationDao.markRead(id, now, now)

        val payload = JSONObject().apply {
            put("id", id)
        }.toString()

        outbox.enqueue(
            kind = PendingWriteEntity.KIND_NOTIFICATION_READ,
            naturalKey = "notif_read:$id",
            payloadJson = payload
        )
    }

    suspend fun markAllRead(userId: String) {
        val now = java.time.Instant.now()
        val unreadList = notificationDao.getUnreadByUser(userId)
        notificationDao.markAllReadByUser(userId, now, now)

        for (notif in unreadList) {
            val payload = JSONObject().apply {
                put("id", notif.id)
            }.toString()

            outbox.enqueue(
                kind = PendingWriteEntity.KIND_NOTIFICATION_READ,
                naturalKey = "notif_read:${notif.id}",
                payloadJson = payload
            )
        }
    }

    suspend fun softDeleteLocal(id: String) {
        val now = java.time.Instant.now()
        notificationDao.softDelete(listOf(id), now)
    }

    suspend fun restoreLocal(id: String) {
        notificationDao.restore(listOf(id))
    }

    suspend fun commitDelete(id: String) {
        val payload = JSONObject().apply {
            put("id", id)
        }.toString()

        outbox.enqueue(
            kind = PendingWriteEntity.KIND_NOTIFICATION_DELETE,
            naturalKey = "notif_delete:$id",
            payloadJson = payload
        )
    }

    suspend fun deleteNotification(id: String) {
        softDeleteLocal(id)
        commitDelete(id)
    }

    suspend fun clearRead(userId: String): Int {
        val readIds = notificationDao.getReadIds(userId)
        if (readIds.isEmpty()) return 0
        val now = java.time.Instant.now()
        notificationDao.softDelete(readIds, now)

        val payload = JSONObject().apply {
            val arr = org.json.JSONArray()
            for (id in readIds) arr.put(id)
            put("ids", arr)
        }.toString()

        val batchKey = java.util.UUID.randomUUID().toString()
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_NOTIFICATION_CLEAR_READ,
            naturalKey = "notif_clear_read:$batchKey",
            payloadJson = payload
        )
        return readIds.size
    }

    suspend fun broadcastNotification(
        title: String,
        body: String?,
        priority: String = "normal",
        roomId: String? = null,
        studentIds: List<String> = emptyList(),
        includeGuardians: Boolean = true,
        includeStudents: Boolean = true
    ): String {
        val broadcastId = java.util.UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("id", broadcastId)
            put("title", title)
            if (!body.isNullOrBlank()) put("body", body)
            put("priority", priority)
            if (!roomId.isNullOrBlank()) put("room_id", roomId)
            val arr = org.json.JSONArray()
            for (sid in studentIds) arr.put(sid)
            put("student_ids", arr)
            put("include_guardians", includeGuardians)
            put("include_students", includeStudents)
        }.toString()

        outbox.enqueue(
            kind = PendingWriteEntity.KIND_NOTIFICATION_BROADCAST,
            naturalKey = "notif_broadcast:$broadcastId",
            payloadJson = payload
        )
        return broadcastId
    }
}
