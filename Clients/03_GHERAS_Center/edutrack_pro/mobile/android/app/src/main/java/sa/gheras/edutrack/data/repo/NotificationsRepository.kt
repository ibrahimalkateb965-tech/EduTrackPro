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
}
