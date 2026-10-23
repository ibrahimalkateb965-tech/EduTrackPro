package sa.gheras.edutrack.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration8To9Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var sqlDb: SupportSQLiteDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("test-migration-8-9.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test-migration-8-9.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Create v8 rooms table
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `rooms` (
                                `id` TEXT NOT NULL,
                                `branch_id` TEXT,
                                `name` TEXT NOT NULL,
                                `group_name` TEXT,
                                `created_at` TEXT NOT NULL,
                                `updated_at` TEXT NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())

                        // Create v8 students table
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `students` (
                                `id` TEXT NOT NULL,
                                `branch_id` TEXT,
                                `room_id` TEXT,
                                `name` TEXT NOT NULL,
                                `gender` TEXT NOT NULL,
                                `created_at` TEXT NOT NULL,
                                `updated_at` TEXT NOT NULL,
                                PRIMARY KEY(`id`),
                                FOREIGN KEY(`room_id`) REFERENCES `rooms`(`id`) ON DELETE RESTRICT
                            )
                        """.trimIndent())

                        db.execSQL("INSERT INTO `rooms` (`id`, `name`, `created_at`, `updated_at`) VALUES ('r1', 'قاعة 1', '2026-10-22T08:00:00Z', '2026-10-22T08:00:00Z')")
                        db.execSQL("INSERT INTO `students` (`id`, `room_id`, `name`, `gender`, `created_at`, `updated_at`) VALUES ('s1', 'r1', 'محمد أحمد', 'male', '2026-10-22T08:00:00Z', '2026-10-22T08:00:00Z')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        sqlDb = helper.writableDatabase
    }

    @After
    fun tearDown() {
        if (::helper.isInitialized) {
            helper.close()
        }
        context.deleteDatabase("test-migration-8-9.db")
    }

    @Test
    fun migration8To9_createsMonthlyStudentReportsTableAndIndices() {
        // Execute migration
        GherasDatabase.MIGRATION_8_9.migrate(sqlDb)

        // Verify table exists
        val cursor = sqlDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='monthly_student_reports'")
        assertTrue("monthly_student_reports table should exist", cursor.moveToFirst())
        cursor.close()

        // Verify insertion into table
        sqlDb.execSQL("""
            INSERT INTO `monthly_student_reports` (
                `id`, `student_id`, `room_id`, `month`, `teacher_user_id`, `status`,
                `attendance_stats_json`, `subject_ratings_json`, `created_at`, `updated_at`
            ) VALUES (
                'rep-1', 's1', 'r1', '2026-10', 't-1', 'draft',
                '{"total_days":20,"present_days":18}', '[]', '2026-10-22T10:00:00Z', '2026-10-22T10:00:00Z'
            )
        """.trimIndent())

        val readCursor = sqlDb.query("SELECT id, month, status FROM monthly_student_reports WHERE id='rep-1'")
        assertTrue(readCursor.moveToFirst())
        assertEquals("rep-1", readCursor.getString(0))
        assertEquals("2026-10", readCursor.getString(1))
        assertEquals("draft", readCursor.getString(2))
        readCursor.close()

        // Verify unique index on student_id + month
        val indexCursor = sqlDb.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_monthly_student_reports_student_id_month'")
        assertTrue("Index on student_id and month should exist", indexCursor.moveToFirst())
        indexCursor.close()
    }
}
