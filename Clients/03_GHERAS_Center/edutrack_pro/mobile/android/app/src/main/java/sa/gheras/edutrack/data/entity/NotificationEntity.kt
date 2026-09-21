package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Notifications are FK-free (UserEntity is not stored in local Room cache).
 */
@Entity(
    tableName = "notifications",
    indices = [Index("user_id")]
)
data class NotificationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String?,
    @ColumnInfo(name = "user_id") val userId: String,
    val kind: String,
    val title: String,
    val body: String?,
    @ColumnInfo(name = "read_at") val readAt: Instant?,
    @ColumnInfo(name = "sent_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val sentAt: Instant,
    @ColumnInfo(name = "created_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val createdAt: Instant,
    @ColumnInfo(name = "updated_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null
)
