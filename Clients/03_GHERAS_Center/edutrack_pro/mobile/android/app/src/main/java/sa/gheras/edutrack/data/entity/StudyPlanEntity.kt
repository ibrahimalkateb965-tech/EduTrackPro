package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "study_plans",
    foreignKeys = [
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["student_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("student_id")]
)
data class StudyPlanEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String?,
    @ColumnInfo(name = "student_id") val studentId: String,
    val subject: String,
    val skill: String,
    val goal: String?,
    @ColumnInfo(name = "start_date") val startDate: LocalDate,
    @ColumnInfo(name = "end_date") val endDate: LocalDate?,
    val activities: String?,
    val notes: String?,
    @ColumnInfo(name = "created_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val createdAt: Instant,
    @ColumnInfo(name = "updated_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null
)
