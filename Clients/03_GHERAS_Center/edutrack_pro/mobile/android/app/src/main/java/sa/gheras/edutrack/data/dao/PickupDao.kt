package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.PickupDelegateEntity

@Dao
interface PickupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PickupDelegateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PickupDelegateEntity)

    @Query("SELECT * FROM pickup_delegates WHERE student_id = :studentId AND deleted_at IS NULL ORDER BY created_at DESC")
    fun observeDelegatesByStudent(studentId: String): Flow<List<PickupDelegateEntity>>

    @Query("SELECT * FROM pickup_delegates WHERE student_id IN (:studentIds) AND deleted_at IS NULL ORDER BY created_at DESC")
    fun observeDelegatesByStudents(studentIds: List<String>): Flow<List<PickupDelegateEntity>>

    @Query("SELECT * FROM pickup_delegates WHERE id = :id AND deleted_at IS NULL")
    suspend fun getDelegateById(id: String): PickupDelegateEntity?

    @Query("SELECT * FROM pickup_delegates WHERE id = :id")
    suspend fun getDelegateByIdIncludingDeleted(id: String): PickupDelegateEntity?

    @Query("DELETE FROM pickup_delegates WHERE student_id = :studentId")
    suspend fun clearByStudent(studentId: String)

    @Query("DELETE FROM pickup_delegates")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceScope(studentId: String, items: List<PickupDelegateEntity>) {
        clearByStudent(studentId)
        upsertAll(items)
    }

    @Transaction
    suspend fun replaceAll(items: List<PickupDelegateEntity>) {
        clearAll()
        upsertAll(items)
    }
}
