package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "users",
    foreignKeys = [
        ForeignKey(
            entity = GuardianEntity::class,
            parentColumns = ["id"],
            childColumns = ["guardian_id"],
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
        Index(value = ["username"], unique = true),
        Index("guardian_id"),
        Index("room_id")
    ]
)
data class UserEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String?,
    val username: String,
    @ColumnInfo(name = "password_hash") val passwordHash: String,
    val role: String,
    @ColumnInfo(name = "staff_id") val staffId: String?,
    @ColumnInfo(name = "guardian_id") val guardianId: String?,
    @ColumnInfo(name = "room_id") val roomId: String?,
    @ColumnInfo(name = "is_active", defaultValue = "1") val isActive: Boolean = true,
    @ColumnInfo(name = "created_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val createdAt: Instant,
    @ColumnInfo(name = "updated_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null
)
