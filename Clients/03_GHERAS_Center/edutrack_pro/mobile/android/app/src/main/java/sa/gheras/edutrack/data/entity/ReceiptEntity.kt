package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "receipts",
    foreignKeys = [
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["student_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = InstallmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["installment_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("student_id"),
        Index("installment_id"),
        Index(value = ["receipt_no"], unique = true),
        Index("issued_on")
    ]
)
data class ReceiptEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "branch_id") val branchId: String? = null,
    @ColumnInfo(name = "payment_id") val paymentId: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "installment_id") val installmentId: String? = null,
    @ColumnInfo(name = "receipt_no") val receiptNo: Int,
    val amount: Double,
    val method: String,
    @ColumnInfo(name = "paid_on") val paidOn: LocalDate,
    @ColumnInfo(name = "issued_on") val issuedOn: Instant,
    @ColumnInfo(name = "created_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val createdAt: Instant,
    @ColumnInfo(name = "updated_at", defaultValue = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant? = null
)
