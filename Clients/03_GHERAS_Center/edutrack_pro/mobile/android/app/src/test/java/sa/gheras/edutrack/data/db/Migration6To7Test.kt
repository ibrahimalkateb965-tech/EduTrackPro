package sa.gheras.edutrack.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration6To7Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var sqlDb: SupportSQLiteDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("test-migration-6-7.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test-migration-6-7.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Create v6 students table
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `students` (
                                `id` TEXT NOT NULL,
                                `branch_id` TEXT,
                                `name` TEXT NOT NULL,
                                `gender` TEXT NOT NULL,
                                `created_at` TEXT NOT NULL,
                                `updated_at` TEXT NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())

                        // Create v6 student_attendance table (no is_draft, no excuse_id)
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `student_attendance` (
                                `id` TEXT NOT NULL,
                                `branch_id` TEXT,
                                `student_id` TEXT NOT NULL,
                                `date` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                `note` TEXT,
                                `recorded_by_user_id` TEXT,
                                `created_at` TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
                                `updated_at` TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
                                `deleted_at` TEXT,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`student_id`) REFERENCES `students`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                            )
                        """.trimIndent())

                        // Insert sample v6 data
                        db.execSQL("""
                            INSERT INTO `students` (`id`, `branch_id`, `name`, `gender`, `created_at`, `updated_at`)
                            VALUES ('stud_m3', 'b1', 'Kareem', 'MALE', '2026-10-22T08:00:00Z', '2026-10-22T08:00:00Z')
                        """.trimIndent())

                        db.execSQL("""
                            INSERT INTO `student_attendance` (`id`, `branch_id`, `student_id`, `date`, `status`, `note`, `recorded_by_user_id`, `created_at`, `updated_at`)
                            VALUES ('att_m3_1', 'b1', 'stud_m3', '2026-10-22', 'حاضر', 'حضور منتظم', 't_1', '2026-10-22T08:30:00Z', '2026-10-22T08:30:00Z')
                        """.trimIndent())
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        sqlDb = helper.writableDatabase
    }

    @After
    fun tearDown() {
        sqlDb.close()
        helper.close()
    }

    @Test
    fun testMigration6To7_altersStudentAttendanceAndCreatesAttendanceExcuses() {
        // 1. Verify attendance_excuses does NOT exist before migration
        sqlDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='attendance_excuses'").use { cursor ->
            assertEquals("attendance_excuses must not exist before migration", 0, cursor.count)
        }

        // 2. Execute MIGRATION_6_7
        GherasDatabase.MIGRATION_6_7.migrate(sqlDb)

        // 3. Verify student_attendance columns and pre-existing row values
        sqlDb.query("SELECT id, status, is_draft, excuse_id FROM student_attendance WHERE id='att_m3_1'").use { cursor ->
            assertTrue("att_m3_1 must exist after migration", cursor.moveToFirst())
            assertEquals("att_m3_1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("حاضر", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("is_draft")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("excuse_id")))
        }

        // 4. Verify inserting attendance with is_draft=1 and excuse_id succeeds
        sqlDb.execSQL("""
            INSERT INTO `student_attendance` (`id`, `branch_id`, `student_id`, `date`, `status`, `note`, `is_draft`, `excuse_id`, `created_at`, `updated_at`)
            VALUES ('att_m3_2', 'b1', 'stud_m3', '2026-10-23', 'غائب', 'مسودة غياب', 1, 'exc_1', '2026-10-22T09:00:00Z', '2026-10-22T09:00:00Z')
        """.trimIndent())

        sqlDb.query("SELECT id, is_draft, excuse_id FROM student_attendance WHERE id='att_m3_2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("is_draft")))
            assertEquals("exc_1", cursor.getString(cursor.getColumnIndexOrThrow("excuse_id")))
        }

        // 5. Verify attendance_excuses table columns
        val expectedColumns = setOf(
            "id", "branch_id", "student_id", "excuse_date", "reason_text",
            "attachment_storage_key", "status", "supervisor_notes", "reviewed_at",
            "client_request_id", "created_at", "updated_at"
        )
        val actualColumns = mutableSetOf<String>()
        sqlDb.query("PRAGMA table_info('attendance_excuses')").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                actualColumns.add(cursor.getString(nameIdx))
            }
        }
        assertEquals("All expected columns must exist in attendance_excuses", expectedColumns, actualColumns)

        // 6. Verify indices on attendance_excuses
        sqlDb.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='attendance_excuses'").use { cursor ->
            val indexNames = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                indexNames.add(cursor.getString(0))
            }
            assertTrue("index_attendance_excuses_student_id must exist", indexNames.contains("index_attendance_excuses_student_id"))
            assertTrue("index_attendance_excuses_excuse_date must exist", indexNames.contains("index_attendance_excuses_excuse_date"))
        }

        // 7. Verify insert and query on attendance_excuses
        sqlDb.execSQL("""
            INSERT INTO `attendance_excuses` (
                `id`, `branch_id`, `student_id`, `excuse_date`, `reason_text`,
                `attachment_storage_key`, `status`, `supervisor_notes`, `reviewed_at`,
                `client_request_id`, `created_at`, `updated_at`
            ) VALUES (
                'exc_1', 'b1', 'stud_m3', '2026-10-23', 'مرض شديد',
                'media/excuses/proof.jpg', 'pending', NULL, NULL,
                'req_client_1', '2026-10-22T08:00:00Z', '2026-10-22T08:00:00Z'
            )
        """.trimIndent())

        sqlDb.query("SELECT id, student_id, excuse_date, reason_text, status, client_request_id FROM attendance_excuses WHERE id='exc_1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("exc_1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("stud_m3", cursor.getString(cursor.getColumnIndexOrThrow("student_id")))
            assertEquals("2026-10-23", cursor.getString(cursor.getColumnIndexOrThrow("excuse_date")))
            assertEquals("مرض شديد", cursor.getString(cursor.getColumnIndexOrThrow("reason_text")))
            assertEquals("pending", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals("req_client_1", cursor.getString(cursor.getColumnIndexOrThrow("client_request_id")))
        }
    }

    @Test
    fun testRoomDatabaseSchemaValidation_version7() {
        // Verify that Room builds and validates schema at v7 without throwing IllegalStateException
        val db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .addMigrations(
                GherasDatabase.MIGRATION_1_2,
                GherasDatabase.MIGRATION_2_3,
                GherasDatabase.MIGRATION_3_4,
                GherasDatabase.MIGRATION_4_5,
                GherasDatabase.MIGRATION_5_6,
                GherasDatabase.MIGRATION_6_7
            )
            .build()
        try {
            // Trigger database opening which validates schemas
            val openDb = db.openHelper.writableDatabase
            assertTrue("Room database must open and validate schema at v7", openDb.isOpen)
        } finally {
            db.close()
        }
    }
}
