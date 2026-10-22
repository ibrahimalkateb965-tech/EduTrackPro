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
class Migration7To8Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var sqlDb: SupportSQLiteDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("test-migration-7-8.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test-migration-7-8.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Create v7 rooms table
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

                        // Create v7 students table
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
                        db.execSQL("INSERT INTO `students` (`id`, `room_id`, `name`, `gender`, `created_at`, `updated_at`) VALUES ('s1', 'r1', 'طالب 1', 'M', '2026-10-22T08:00:00Z', '2026-10-22T08:00:00Z')")
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
    fun testMigration7To8_createsMessagingAndDailySummaryTables() {
        // 1. Verify tables do not exist before migration
        for (tbl in listOf("threads", "thread_messages", "daily_summaries", "daily_summary_notes")) {
            sqlDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$tbl'").use { cursor ->
                assertEquals("$tbl must not exist before migration", 0, cursor.count)
            }
        }

        // 2. Execute MIGRATION_7_8
        GherasDatabase.MIGRATION_7_8.migrate(sqlDb)

        // 3. Verify all 4 tables exist after migration
        for (tbl in listOf("threads", "thread_messages", "daily_summaries", "daily_summary_notes")) {
            sqlDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$tbl'").use { cursor ->
                assertEquals("$tbl must exist after migration", 1, cursor.count)
            }
        }

        // 4. Verify columns in threads table
        val expectedThreadCols = setOf(
            "id", "branch_id", "student_id", "guardian_user_id", "staff_user_id",
            "subject", "linked_assignment_id", "linked_summary_id", "status",
            "last_message_at", "last_message_preview", "created_at", "updated_at"
        )
        val actualThreadCols = mutableSetOf<String>()
        sqlDb.query("PRAGMA table_info('threads')").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) actualThreadCols.add(cursor.getString(nameIdx))
        }
        assertEquals(expectedThreadCols, actualThreadCols)

        // 5. Verify columns in daily_summaries table
        val expectedSummaryCols = setOf(
            "id", "branch_id", "room_id", "teacher_user_id", "summary_date",
            "subject", "title", "class_summary", "homework_summary", "is_published",
            "published_at", "created_at", "updated_at"
        )
        val actualSummaryCols = mutableSetOf<String>()
        sqlDb.query("PRAGMA table_info('daily_summaries')").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) actualSummaryCols.add(cursor.getString(nameIdx))
        }
        assertEquals(expectedSummaryCols, actualSummaryCols)

        // 6. Test insert operations on threads and thread_messages
        sqlDb.execSQL("""
            INSERT INTO `threads` (
                `id`, `student_id`, `guardian_user_id`, `staff_user_id`,
                `subject`, `status`, `last_message_at`, `created_at`, `updated_at`
            ) VALUES (
                'th1', 's1', 'gu1', 'staff1',
                'استفسار عن الدرس', 'open', '2026-10-22T09:00:00Z', '2026-10-22T09:00:00Z', '2026-10-22T09:00:00Z'
            )
        """.trimIndent())

        sqlDb.execSQL("""
            INSERT INTO `thread_messages` (
                `id`, `thread_id`, `sender_user_id`, `sender_role`,
                `body`, `sent_at`, `created_at`, `updated_at`
            ) VALUES (
                'msg1', 'th1', 'gu1', 'guardian',
                'السلام عليكم', '2026-10-22T09:00:00Z', '2026-10-22T09:00:00Z', '2026-10-22T09:00:00Z'
            )
        """.trimIndent())

        sqlDb.query("SELECT id, body FROM thread_messages WHERE id='msg1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("msg1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("السلام عليكم", cursor.getString(cursor.getColumnIndexOrThrow("body")))
        }

        // 7. Test insert operations on daily_summaries and daily_summary_notes
        sqlDb.execSQL("""
            INSERT INTO `daily_summaries` (
                `id`, `room_id`, `teacher_user_id`, `summary_date`,
                `subject`, `title`, `class_summary`, `is_published`, `created_at`, `updated_at`
            ) VALUES (
                'ds1', 'r1', 'staff1', '2026-10-22',
                'القرآن', 'سورة الفجر', 'تلاوة وحفظ', 1, '2026-10-22T09:00:00Z', '2026-10-22T09:00:00Z'
            )
        """.trimIndent())

        sqlDb.execSQL("""
            INSERT INTO `daily_summary_notes` (
                `id`, `summary_id`, `student_id`, `private_note`, `created_at`, `updated_at`
            ) VALUES (
                'dsn1', 'ds1', 's1', 'ملاحظة ممتازة', '2026-10-22T09:00:00Z', '2026-10-22T09:00:00Z'
            )
        """.trimIndent())

        sqlDb.query("SELECT id, private_note FROM daily_summary_notes WHERE id='dsn1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("dsn1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("ملاحظة ممتازة", cursor.getString(cursor.getColumnIndexOrThrow("private_note")))
        }
    }
}
