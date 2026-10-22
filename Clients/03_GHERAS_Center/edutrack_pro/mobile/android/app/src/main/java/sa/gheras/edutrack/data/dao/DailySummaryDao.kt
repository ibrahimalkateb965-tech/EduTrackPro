package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.DailySummaryEntity
import sa.gheras.edutrack.data.entity.DailySummaryNoteEntity
import java.time.LocalDate

@Dao
interface DailySummaryDao {

    // --- Daily Summaries ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: DailySummaryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummaries(summaries: List<DailySummaryEntity>): List<Long>

    @Query("SELECT * FROM daily_summaries WHERE id = :id")
    suspend fun getSummaryById(id: String): DailySummaryEntity?

    @Query("SELECT * FROM daily_summaries WHERE room_id = :roomId ORDER BY summary_date DESC")
    fun observeSummariesByRoom(roomId: String): Flow<List<DailySummaryEntity>>

    @Query("SELECT * FROM daily_summaries WHERE room_id = :roomId AND summary_date = :date ORDER BY created_at DESC")
    fun observeSummariesByRoomAndDate(roomId: String, date: LocalDate): Flow<List<DailySummaryEntity>>

    @Query("DELETE FROM daily_summaries WHERE id = :id")
    suspend fun deleteSummaryById(id: String)

    @Query("DELETE FROM daily_summaries WHERE room_id = :roomId")
    suspend fun clearSummariesByRoom(roomId: String)

    @Query("DELETE FROM daily_summaries")
    suspend fun clearSummaries()

    // --- Private Student Notes ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: DailySummaryNoteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotes(notes: List<DailySummaryNoteEntity>): List<Long>

    @Query("SELECT * FROM daily_summary_notes WHERE summary_id = :summaryId")
    fun observeNotesBySummary(summaryId: String): Flow<List<DailySummaryNoteEntity>>

    @Query("SELECT * FROM daily_summary_notes WHERE student_id = :studentId AND summary_id = :summaryId LIMIT 1")
    fun observeNoteByStudentAndSummary(studentId: String, summaryId: String): Flow<DailySummaryNoteEntity?>

    @Query("DELETE FROM daily_summary_notes WHERE summary_id = :summaryId")
    suspend fun clearNotesBySummary(summaryId: String)

    @Query("DELETE FROM daily_summary_notes WHERE student_id = :studentId")
    suspend fun clearNotesByStudent(studentId: String)

    @Query("DELETE FROM daily_summary_notes")
    suspend fun clearNotes()

    @Transaction
    suspend fun replaceScope(roomId: String, summaries: List<DailySummaryEntity>, notes: List<DailySummaryNoteEntity>) {
        clearSummariesByRoom(roomId)
        upsertSummaries(summaries)
        upsertNotes(notes)
    }
}
