package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "threads",
    foreignKeys = [
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["student_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("student_id"),
        Index("last_message_at")
    ]
)
data class ThreadEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String? = null,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "guardian_user_id") val guardianUserId: String,
    @ColumnInfo(name = "staff_user_id") val staffUserId: String,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "linked_assignment_id") val linkedAssignmentId: String? = null,
    @ColumnInfo(name = "linked_summary_id") val linkedSummaryId: String? = null,
    @ColumnInfo(name = "status", defaultValue = "open") val status: String = "open",
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Instant,
    @ColumnInfo(name = "last_message_preview") val lastMessagePreview: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant
)
