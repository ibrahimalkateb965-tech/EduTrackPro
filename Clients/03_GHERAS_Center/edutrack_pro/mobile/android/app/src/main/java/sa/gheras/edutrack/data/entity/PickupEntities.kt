package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "pickup_delegates",
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
        Index("client_request_id")
    ]
)
data class PickupDelegateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    val name: String,
    val phone: String,
    val relation: String,
    @ColumnInfo(name = "is_approved") val isApproved: Boolean = true,
    @ColumnInfo(name = "client_request_id") val clientRequestId: String? = null,
    @ColumnInfo(name = "national_id_masked") val nationalIdMasked: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null
)
