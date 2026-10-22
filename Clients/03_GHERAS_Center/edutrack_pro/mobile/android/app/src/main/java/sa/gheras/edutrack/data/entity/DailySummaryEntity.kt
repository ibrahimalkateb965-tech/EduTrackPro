package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "daily_summaries",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["room_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("room_id"),
        Index("summary_date")
    ]
)
data class DailySummaryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String? = null,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "teacher_user_id") val teacherUserId: String,
    @ColumnInfo(name = "summary_date") val summaryDate: LocalDate,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "class_summary") val classSummary: String,
    @ColumnInfo(name = "homework_summary") val homeworkSummary: String? = null,
    @ColumnInfo(name = "is_published", defaultValue = "0") val isPublished: Boolean = false,
    @ColumnInfo(name = "published_at") val publishedAt: Instant? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant
)
