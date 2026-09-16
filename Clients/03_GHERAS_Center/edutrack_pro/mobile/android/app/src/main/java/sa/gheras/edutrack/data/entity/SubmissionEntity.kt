package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "submissions",
    foreignKeys = [
        ForeignKey(
            entity = AssignmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["assignment_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["student_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("assignment_id"), Index("student_id")]
)
data class SubmissionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String?,
    @ColumnInfo(name = "assignment_id") val assignmentId: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "submitted_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val submittedAt: Instant,
    @ColumnInfo(name = "status", defaultValue = "'submitted'") val status: String = "submitted",
    val grade: Double?,
    val feedback: String?,
    @ColumnInfo(name = "created_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val createdAt: Instant,
    @ColumnInfo(name = "updated_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null
)
