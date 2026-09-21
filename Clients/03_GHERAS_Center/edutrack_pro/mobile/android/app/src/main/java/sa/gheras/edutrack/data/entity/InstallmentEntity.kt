package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "installments",
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
        Index("fee_plan_id"),
        Index("due_date"),
        Index("status")
    ]
)
data class InstallmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String? = null,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "fee_plan_id") val feePlanId: String,
    @ColumnInfo(name = "seq_no") val seqNo: Int,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate,
    val amount: Double,
    @ColumnInfo(name = "paid_amount", defaultValue = "0.0") val paidAmount: Double = 0.0,
    val status: String,
    @ColumnInfo(name = "created_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val createdAt: Instant,
    @ColumnInfo(name = "updated_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null
)
