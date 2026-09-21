package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.ReceiptEntity

@Dao
interface ReceiptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ReceiptEntity>)

    @Query("SELECT * FROM receipts WHERE student_id = :studentId AND deleted_at IS NULL ORDER BY issued_on DESC")
    fun observeByStudent(studentId: String): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts WHERE student_id IN (:studentIds) AND deleted_at IS NULL ORDER BY issued_on DESC")
    fun observeByStudents(studentIds: List<String>): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): ReceiptEntity?

    @Query("DELETE FROM receipts WHERE student_id = :studentId")
    suspend fun clearByStudent(studentId: String)

    @Query("DELETE FROM receipts")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceScope(studentId: String, items: List<ReceiptEntity>) {
        clearByStudent(studentId)
        upsertAll(items)
    }

    @Transaction
    suspend fun replaceScopeAll(items: List<ReceiptEntity>) {
        clearAll()
        upsertAll(items)
    }
}
