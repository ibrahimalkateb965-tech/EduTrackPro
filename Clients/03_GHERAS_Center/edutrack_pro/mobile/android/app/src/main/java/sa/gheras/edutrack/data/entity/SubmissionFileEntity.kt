package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "submission_files",
    foreignKeys = [
        ForeignKey(
            entity = SubmissionEntity::class,
            parentColumns = ["id"],
            childColumns = ["submission_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("submission_id")]
)
data class SubmissionFileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String?,
    @ColumnInfo(name = "submission_id") val submissionId: String,
    @ColumnInfo(name = "storage_key") val storageKey: String,
    val width: Int?,
    val height: Int?,
    val bytes: Long?,
    val sha256: String?,
    @ColumnInfo(name = "created_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val createdAt: Instant,
    @ColumnInfo(name = "updated_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null
)
