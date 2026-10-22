package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "daily_summary_notes",
    foreignKeys = [
        ForeignKey(
            entity = DailySummaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["summary_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["student_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("summary_id"),
        Index("student_id")
    ]
)
data class DailySummaryNoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String? = null,
    @ColumnInfo(name = "summary_id") val summaryId: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "private_note") val privateNote: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant
)
