package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "attendance_excuses",
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
        Index("excuse_date")
    ]
)
data class AttendanceExcuseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String? = null,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "excuse_date") val excuseDate: LocalDate,
    @ColumnInfo(name = "reason_text") val reasonText: String,
    @ColumnInfo(name = "attachment_storage_key") val attachmentStorageKey: String? = null,
    @ColumnInfo(name = "status", defaultValue = "pending") val status: String = "pending",
    @ColumnInfo(name = "supervisor_notes") val supervisorNotes: String? = null,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Instant? = null,
    @ColumnInfo(name = "client_request_id") val clientRequestId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant
)
