package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/** One row per pulled resource (`students`, `schedule`, ...): last successful pull and the server total seen. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val resource: String,
    @ColumnInfo(name = "last_pull_at") val lastPullAt: Instant,
    @ColumnInfo(name = "last_total") val lastTotal: Int
)
