package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "thread_messages",
    foreignKeys = [
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["thread_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("thread_id"),
        Index("sent_at")
    ]
)
data class ThreadMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String? = null,
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "sender_user_id") val senderUserId: String,
    @ColumnInfo(name = "sender_role") val senderRole: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "attachment_storage_key") val attachmentStorageKey: String? = null,
    @ColumnInfo(name = "attachment_type") val attachmentType: String? = null,
    @ColumnInfo(name = "attachment_name") val attachmentName: String? = null,
    @ColumnInfo(name = "attachment_size_bytes") val attachmentSizeBytes: Long? = null,
    @ColumnInfo(name = "sent_at") val sentAt: Instant,
    @ColumnInfo(name = "delivered_at") val deliveredAt: Instant? = null,
    @ColumnInfo(name = "read_at") val readAt: Instant? = null,
    @ColumnInfo(name = "client_message_id") val clientMessageId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant
)
