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
    indices = [
        Index("assignment_id"),
        Index("student_id"),
        Index(value = ["assignment_id", "student_id", "attempt_number"], unique = true)
    ]
)
data class SubmissionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String?,
    @ColumnInfo(name = "assignment_id") val assignmentId: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "attempt_number", defaultValue = "1") val attemptNumber: Int = 1,
    @ColumnInfo(name = "parent_submission_id") val parentSubmissionId: String? = null,
    @ColumnInfo(name = "grade_level") val gradeLevel: String? = null,
    @ColumnInfo(name = "revision_note") val revisionNote: String? = null,
    @ColumnInfo(name = "teacher_feedback_key") val teacherFeedbackKey: String? = null,
    @ColumnInfo(name = "is_late", defaultValue = "0") val isLate: Boolean = false,
    @ColumnInfo(name = "submitted_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val submittedAt: Instant,
    @ColumnInfo(name = "status", defaultValue = "'submitted'") val status: String = "submitted",
    val grade: Double? = null,
    val feedback: String? = null,
    @ColumnInfo(name = "created_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val createdAt: Instant,
    @ColumnInfo(name = "updated_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null
)
