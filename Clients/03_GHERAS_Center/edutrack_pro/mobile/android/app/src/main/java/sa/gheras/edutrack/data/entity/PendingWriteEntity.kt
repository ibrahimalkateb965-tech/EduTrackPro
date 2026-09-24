package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Durable write outbox row. No foreign keys on purpose: synced tables are replaced by pulls,
 * pending rows must survive that. `attempts == -1` marks a terminal (4xx) failure.
 */
@Entity(
    tableName = "pending_writes",
    indices = [
        Index(value = ["natural_key"], unique = true),
        Index("created_at")
    ]
)
data class PendingWriteEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(name = "natural_key") val naturalKey: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null
) {
    companion object {
        const val KIND_ATTENDANCE = "ATTENDANCE"
        const val KIND_DAILY_EVAL = "DAILY_EVAL"
        const val KIND_LESSON_LOG = "LESSON_LOG"
        const val KIND_NOTIFICATION_READ = "NOTIFICATION_READ"
        const val KIND_NOTIFICATION_BROADCAST = "NOTIFICATION_BROADCAST"
        const val KIND_NOTIFICATION_DELETE = "NOTIFICATION_DELETE"
        const val KIND_NOTIFICATION_CLEAR_READ = "NOTIFICATION_CLEAR_READ"
        const val KIND_ASSIGNMENT = "ASSIGNMENT"
        const val ATTEMPTS_FAILED = -1
    }
}
