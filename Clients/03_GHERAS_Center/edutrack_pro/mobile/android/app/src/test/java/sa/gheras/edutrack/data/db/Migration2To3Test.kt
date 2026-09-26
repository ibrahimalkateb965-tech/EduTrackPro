package sa.gheras.edutrack.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class Migration2To3Test {

    private lateinit var db: GherasDatabase
    private lateinit var sqlDb: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sqlDb = db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testMigration2To3_deduplicatesEvaluationsKeepingLatest() = runBlocking {
        // Drop unique index temporarily to simulate pre-migration state with duplicate rows
        sqlDb.execSQL("DROP INDEX IF EXISTS index_evaluations_student_id_date_subject_eval_type")

        val now = Instant.parse("2026-09-26T00:00:00Z")
        val today = LocalDate.parse("2026-09-26")

        // Seed room and student using Room entities to ensure all schema constraints are met
        val room = RoomEntity(
            id = "room_1",
            branchId = "branch_1",
            name = "Room 1",
            groupName = "Group 1",
            createdAt = now,
            updatedAt = now
        )
        db.roomDao().upsert(room)

        val student = StudentEntity(
            id = "student_1",
            branchId = "branch_1",
            name = "Student 1",
            nationalId = "1234567890",
            birthDate = today.minusYears(10),
            nationality = "SA",
            gender = "MALE",
            difficultyNotes = null,
            childNotes = null,
            fatherName = "Father",
            fatherPhone = "0500000001",
            motherName = "Mother",
            motherPhone = "0500000002",
            guardianPhone = null,
            guardianRelation = null,
            pickupType = null,
            pickupName = null,
            pickupRelation = null,
            pickupPhone = null,
            previousSchool = null,
            previousLevel = null,
            educationNotes = null,
            roomId = "room_1",
            groupName = "Group 1",
            createdAt = now,
            updatedAt = now
        )
        db.studentDao().upsert(student)

        // Insert duplicate rows for same student, date, subject, eval_type
        sqlDb.execSQL("""
            INSERT INTO evaluations (id, student_id, subject, eval_type, date, value, created_at, updated_at)
            VALUES ('eval_server_uuid', 'student_1', 'القرآن', 'daily', '2026-09-26', 10.0, '2026-09-26T08:00:00Z', '2026-09-26T08:00:00Z')
        """.trimIndent())
        sqlDb.execSQL("""
            INSERT INTO evaluations (id, student_id, subject, eval_type, date, value, created_at, updated_at)
            VALUES ('local:student_1:2026-09-26:القرآن', 'student_1', 'القرآن', 'daily', '2026-09-26', 7.5, '2026-09-26T08:00:00Z', '2026-09-26T10:00:00Z')
        """.trimIndent())

        // Verify that before migration, 2 rows exist
        val beforeCount = sqlDb.query("SELECT COUNT(*) FROM evaluations").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(2, beforeCount)

        // Execute MIGRATION_2_3
        GherasDatabase.MIGRATION_2_3.migrate(sqlDb)

        // Verify only 1 row remains after deduplication
        val afterCount = sqlDb.query("SELECT COUNT(*) FROM evaluations").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(1, afterCount)

        // Verify the remaining row has the updated value 7.5
        val remainingValue = sqlDb.query("SELECT value FROM evaluations WHERE student_id = 'student_1'").use {
            it.moveToFirst()
            it.getDouble(0)
        }
        assertEquals(7.5, remainingValue, 0.001)

        // Verify unique index was created
        val indexCursor = sqlDb.query("PRAGMA index_list('evaluations')")
        var foundUniqueIndex = false
        while (indexCursor.moveToNext()) {
            val name = indexCursor.getString(indexCursor.getColumnIndexOrThrow("name"))
            val unique = indexCursor.getInt(indexCursor.getColumnIndexOrThrow("unique"))
            if (name == "index_evaluations_student_id_date_subject_eval_type" && unique == 1) {
                foundUniqueIndex = true
                break
            }
        }
        indexCursor.close()
        assertTrue("Unique index must exist on evaluations table", foundUniqueIndex)
    }
}
