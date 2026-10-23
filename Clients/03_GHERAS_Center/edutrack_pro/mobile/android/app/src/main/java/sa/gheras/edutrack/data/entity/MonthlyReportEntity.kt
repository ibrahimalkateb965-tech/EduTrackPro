package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "monthly_student_reports",
    foreignKeys = [
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["student_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["room_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["student_id", "month"], unique = true),
        Index("room_id", "month"),
        Index("status")
    ]
)
data class MonthlyStudentReportEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String? = null,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "month") val month: String, // YYYY-MM
    @ColumnInfo(name = "teacher_user_id") val teacherUserId: String,
    @ColumnInfo(name = "status", defaultValue = "'draft'") val status: String,
    @ColumnInfo(name = "attendance_stats_json") val attendanceStatsJson: String,
    @ColumnInfo(name = "subject_ratings_json") val subjectRatingsJson: String,
    @ColumnInfo(name = "strengths") val strengths: String? = null,
    @ColumnInfo(name = "improvements") val improvements: String? = null,
    @ColumnInfo(name = "recommendations") val recommendations: String? = null,
    @ColumnInfo(name = "revision_notes") val revisionNotes: String? = null,
    @ColumnInfo(name = "submitted_at") val submittedAt: Instant? = null,
    @ColumnInfo(name = "reviewed_by_user_id") val reviewedByUserId: String? = null,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Instant? = null,
    @ColumnInfo(name = "published_at") val publishedAt: Instant? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant
)
