package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.InstallmentEntity

@Dao
interface InstallmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<InstallmentEntity>)

    @Query("SELECT * FROM installments WHERE student_id = :studentId AND deleted_at IS NULL ORDER BY due_date ASC, seq_no ASC")
    fun observeByStudent(studentId: String): Flow<List<InstallmentEntity>>

    @Query("SELECT * FROM installments WHERE student_id IN (:studentIds) AND deleted_at IS NULL ORDER BY due_date ASC")
    fun observeByStudents(studentIds: List<String>): Flow<List<InstallmentEntity>>

    @Query("SELECT * FROM installments WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): InstallmentEntity?

    @Query("DELETE FROM installments WHERE student_id = :studentId")
    suspend fun clearByStudent(studentId: String)

    @Query("DELETE FROM installments")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceScope(studentId: String, items: List<InstallmentEntity>) {
        clearByStudent(studentId)
        upsertAll(items)
    }

    @Transaction
    suspend fun replaceScopeAll(items: List<InstallmentEntity>) {
        clearAll()
        upsertAll(items)
    }
}
