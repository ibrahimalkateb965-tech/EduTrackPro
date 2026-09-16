package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "students",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["room_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("room_id"), Index("status")]
)
data class StudentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String?,
    val name: String,
    @ColumnInfo(name = "national_id") val nationalId: String?,
    @ColumnInfo(name = "birth_date") val birthDate: LocalDate?,
    val nationality: String?,
    @ColumnInfo(name = "has_difficulties", defaultValue = "0") val hasDifficulties: Boolean = false,
    @ColumnInfo(name = "difficulty_notes") val difficultyNotes: String?,
    @ColumnInfo(name = "child_notes") val childNotes: String?,
    @ColumnInfo(name = "father_name") val fatherName: String?,
    @ColumnInfo(name = "father_phone") val fatherPhone: String?,
    @ColumnInfo(name = "mother_name") val motherName: String?,
    @ColumnInfo(name = "mother_phone") val motherPhone: String?,
    @ColumnInfo(name = "guardian_phone") val guardianPhone: String?,
    @ColumnInfo(name = "guardian_relation") val guardianRelation: String?,
    @ColumnInfo(name = "pickup_type") val pickupType: String?,
    @ColumnInfo(name = "pickup_name") val pickupName: String?,
    @ColumnInfo(name = "pickup_relation") val pickupRelation: String?,
    @ColumnInfo(name = "pickup_phone") val pickupPhone: String?,
    @ColumnInfo(name = "previous_study", defaultValue = "0") val previousStudy: Boolean = false,
    @ColumnInfo(name = "previous_school") val previousSchool: String?,
    @ColumnInfo(name = "previous_level") val previousLevel: String?,
    @ColumnInfo(name = "education_notes") val educationNotes: String?,
    @ColumnInfo(name = "room_id") val roomId: String?,
    @ColumnInfo(name = "group_name") val groupName: String?,
    @ColumnInfo(name = "status", defaultValue = "'active'") val status: String = "active",
    @ColumnInfo(name = "created_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val createdAt: Instant,
    @ColumnInfo(name = "updated_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null
)
