package sa.gheras.edutrack.data.db

import android.content.Context
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
class Migration5To6Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var sqlDb: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("test-migration-5-6.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test-migration-5-6.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Create v5 assignments table (due_date NOT NULL, no is_draft)
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `assignments` (
                                `id` TEXT NOT NULL,
                                `branch_id` TEXT,
                                `title` TEXT NOT NULL,
                                `subject` TEXT,
                                `kind` TEXT,
                                `due_date` TEXT NOT NULL,
                                `teacher_user_id` TEXT,
                                `instructions` TEXT,
                                `page_ref` TEXT,
                                `created_at` TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
                                `updated_at` TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
                                `deleted_at` TEXT,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())

                        // Create v5 students table (needed for foreign key in submissions)
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

                        // Create v5 submissions table
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `submissions` (
                                `id` TEXT NOT NULL,
                                `branch_id` TEXT,
                                `assignment_id` TEXT NOT NULL,
                                `student_id` TEXT NOT NULL,
                                `submitted_at` TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
                                `status` TEXT NOT NULL DEFAULT 'submitted',
                                `grade` REAL,
                                `feedback` TEXT,
                                `created_at` TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
                                `updated_at` TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
                                `deleted_at` TEXT,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`assignment_id`) REFERENCES `assignments`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                                FOREIGN KEY(`student_id`) REFERENCES `students`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                            )
                        """.trimIndent())

                        // Insert sample v5 rows
                        db.execSQL("""
                            INSERT INTO `assignments` (`id`, `branch_id`, `title`, `subject`, `kind`, `due_date`, `teacher_user_id`, `instructions`, `page_ref`, `created_at`, `updated_at`)
                            VALUES ('assign_1', 'b1', 'Quran Surat Al-Fatiha', 'quran', 'reading', '2026-10-25', 't1', 'Read Ayah 1-7', 'p.1', '2026-10-21T10:00:00Z', '2026-10-21T10:00:00Z')
                        """.trimIndent())

                        db.execSQL("""
                            INSERT INTO `students` (`id`, `branch_id`, `name`, `gender`, `created_at`, `updated_at`)
                            VALUES ('stud_1', 'b1', 'Ahmad', 'MALE', '2026-10-21T10:00:00Z', '2026-10-21T10:00:00Z')
                        """.trimIndent())

                        db.execSQL("""
                            INSERT INTO `submissions` (`id`, `branch_id`, `assignment_id`, `student_id`, `submitted_at`, `status`, `grade`, `feedback`, `created_at`, `updated_at`)
                            VALUES ('sub_1', 'b1', 'assign_1', 'stud_1', '2026-10-21T11:00:00Z', 'graded', 95.0, 'ممتاز', '2026-10-21T11:00:00Z', '2026-10-21T11:00:00Z')
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
    fun testMigration5To6_rebuildsAssignmentsAndUpgradesSubmissions() {
        // Execute MIGRATION_5_6
        GherasDatabase.MIGRATION_5_6.migrate(sqlDb)

        // 1. Verify assignments columns and migrated row
        sqlDb.query("SELECT id, title, due_date, is_draft FROM assignments WHERE id='assign_1'").use { cursor ->
            assertTrue("assign_1 must exist after migration", cursor.moveToFirst())
            assertEquals("assign_1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("Quran Surat Al-Fatiha", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("2026-10-25", cursor.getString(cursor.getColumnIndexOrThrow("due_date")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("is_draft")))
        }

        // 2. Verify nullable due_date on assignments: insert assignment with NULL due_date
        sqlDb.execSQL("""
            INSERT INTO assignments (id, title, due_date, is_draft, created_at, updated_at)
            VALUES ('assign_no_due', 'Open Reading', NULL, 0, '2026-10-21T12:00:00Z', '2026-10-21T12:00:00Z')
        """.trimIndent())

        sqlDb.query("SELECT id, due_date FROM assignments WHERE id='assign_no_due'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("due_date")))
        }

        // 3. Verify submissions new columns and migrated row defaults
        sqlDb.query("SELECT id, attempt_number, parent_submission_id, grade_level, revision_note, teacher_feedback_key, is_late FROM submissions WHERE id='sub_1'").use { cursor ->
            assertTrue("sub_1 must exist after migration", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("attempt_number")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("parent_submission_id")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("grade_level")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("revision_note")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("teacher_feedback_key")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("is_late")))
        }

        // 4. Verify unique index on submissions (assignment_id, student_id, attempt_number)
        sqlDb.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_submissions_assignment_id_student_id_attempt_number'").use { cursor ->
            assertTrue("Unique index on submissions must exist", cursor.moveToFirst())
        }
    }
}
