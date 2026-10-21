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
class Migration4To5Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var sqlDb: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("test-migration-4-5.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test-migration-4-5.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Create version 4 schema for students table (with photo_url)
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `students` (
                                `id` TEXT NOT NULL,
                                `branch_id` TEXT,
                                `name` TEXT NOT NULL,
                                `national_id` TEXT,
                                `birth_date` TEXT,
                                `nationality` TEXT,
                                `gender` TEXT NOT NULL,
                                `difficulty_notes` TEXT,
                                `child_notes` TEXT,
                                `father_name` TEXT,
                                `father_phone` TEXT,
                                `mother_name` TEXT,
                                `mother_phone` TEXT,
                                `guardian_phone` TEXT,
                                `guardian_relation` TEXT,
                                `pickup_type` TEXT,
                                `pickup_name` TEXT,
                                `pickup_relation` TEXT,
                                `pickup_phone` TEXT,
                                `previous_school` TEXT,
                                `previous_level` TEXT,
                                `education_notes` TEXT,
                                `photo_url` TEXT,
                                `room_id` TEXT,
                                `group_name` TEXT,
                                `created_at` TEXT NOT NULL,
                                `updated_at` TEXT NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        // Insert sample v4 student record
                        db.execSQL("""
                            INSERT INTO `students` (`id`, `branch_id`, `name`, `gender`, `created_at`, `updated_at`)
                            VALUES ('s_mig_5', 'branch_1', 'Omar', 'MALE', '2026-10-18T00:00:00Z', '2026-10-18T00:00:00Z')
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
    fun testMigration4To5_createsPickupDelegatesTableWithColumnsAndIndices() {
        // 1. Verify before migration that pickup_delegates does NOT exist
        sqlDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='pickup_delegates'").use { cursor ->
            assertTrue("pickup_delegates must not exist before migration", cursor.count == 0)
        }

        // 2. Execute MIGRATION_4_5
        GherasDatabase.MIGRATION_4_5.migrate(sqlDb)

        // 3. Verify pickup_delegates table exists
        sqlDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='pickup_delegates'").use { cursor ->
            assertTrue("pickup_delegates table must exist after MIGRATION_4_5", cursor.moveToFirst())
        }

        // 4. Verify all expected columns exist
        val expectedColumns = setOf(
            "id", "student_id", "name", "phone", "relation", "is_approved",
            "client_request_id", "national_id_masked", "created_at", "updated_at", "deleted_at"
        )
        val actualColumns = mutableSetOf<String>()
        sqlDb.query("PRAGMA table_info('pickup_delegates')").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                actualColumns.add(cursor.getString(nameIdx))
            }
        }
        assertEquals("All expected columns must exist in pickup_delegates", expectedColumns, actualColumns)

        // 5. Verify indices exist
        sqlDb.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='pickup_delegates'").use { cursor ->
            val indexNames = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                indexNames.add(cursor.getString(0))
            }
            assertTrue("index_pickup_delegates_student_id must exist", indexNames.contains("index_pickup_delegates_student_id"))
            assertTrue("index_pickup_delegates_client_request_id must exist", indexNames.contains("index_pickup_delegates_client_request_id"))
        }
    }

    @Test
    fun testMigration4To5_insertDelegateLinkedToExistingStudentSucceeds() {
        // Execute MIGRATION_4_5 first
        GherasDatabase.MIGRATION_4_5.migrate(sqlDb)

        // Insert a linked delegate row referencing the pre-migration student
        sqlDb.execSQL("""
            INSERT INTO `pickup_delegates` (
                `id`, `student_id`, `name`, `phone`, `relation`, `is_approved`,
                `client_request_id`, `national_id_masked`, `created_at`, `updated_at`, `deleted_at`
            ) VALUES (
                'del_mig_1', 's_mig_5', 'Uncle Salem', '0555555555', 'Uncle', 1, 'cr_mig_1', '10******32',
                '2026-10-18T00:00:00Z', '2026-10-18T00:00:00Z', NULL
            )
        """.trimIndent())

        // Query it back to verify FK integrity and field persistence
        sqlDb.query("SELECT id, student_id, name, phone, relation, is_approved, national_id_masked FROM pickup_delegates WHERE id = 'del_mig_1'").use { cursor ->
            assertTrue("Inserted delegate row must be queryable", cursor.moveToFirst())
            assertEquals("del_mig_1", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("s_mig_5", cursor.getString(cursor.getColumnIndexOrThrow("student_id")))
            assertEquals("Uncle Salem", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertEquals("0555555555", cursor.getString(cursor.getColumnIndexOrThrow("phone")))
            assertEquals("Uncle", cursor.getString(cursor.getColumnIndexOrThrow("relation")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("is_approved")))
            assertEquals("10******32", cursor.getString(cursor.getColumnIndexOrThrow("national_id_masked")))
        }
    }
}
