package sa.gheras.edutrack.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration3To4Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var sqlDb: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("test-migration-3-4.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test-migration-3-4.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Create version 3 schema for students table (without photo_url)
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `students` (
                                `id` TEXT NOT NULL,
                                `branch_id` TEXT NOT NULL,
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
                                `room_id` TEXT,
                                `group_name` TEXT,
                                `created_at` TEXT NOT NULL,
                                `updated_at` TEXT NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                        """.trimIndent())
                        // Insert sample v3 student record
                        db.execSQL("""
                            INSERT INTO `students` (`id`, `branch_id`, `name`, `gender`, `created_at`, `updated_at`)
                            VALUES ('s_mig_1', 'branch_1', 'Zaid', 'MALE', '2026-10-15T00:00:00Z', '2026-10-15T00:00:00Z')
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
    fun testMigration3To4_addsPhotoUrlColumnAndPreservesExistingData() {
        // 1. Verify before migration that photo_url does NOT exist
        val beforeCursor = sqlDb.query("PRAGMA table_info('students')")
        var hasPhotoBefore = false
        while (beforeCursor.moveToNext()) {
            val colName = beforeCursor.getString(beforeCursor.getColumnIndexOrThrow("name"))
            if (colName == "photo_url") {
                hasPhotoBefore = true
                break
            }
        }
        beforeCursor.close()
        assertFalse("photo_url must not exist before migration", hasPhotoBefore)

        // 2. Execute MIGRATION_3_4
        GherasDatabase.MIGRATION_3_4.migrate(sqlDb)

        // 3. Verify photo_url column now exists
        val afterCursor = sqlDb.query("PRAGMA table_info('students')")
        var hasPhotoAfter = false
        while (afterCursor.moveToNext()) {
            val colName = afterCursor.getString(afterCursor.getColumnIndexOrThrow("name"))
            if (colName == "photo_url") {
                hasPhotoAfter = true
                break
            }
        }
        afterCursor.close()
        assertTrue("photo_url column must exist after MIGRATION_3_4", hasPhotoAfter)

        // 4. Verify existing record survived with photo_url as null
        val rowCursor = sqlDb.query("SELECT id, name, photo_url FROM students WHERE id = 's_mig_1'")
        assertTrue(rowCursor.moveToFirst())
        assertEquals("Zaid", rowCursor.getString(rowCursor.getColumnIndexOrThrow("name")))
        assertNull(rowCursor.getString(rowCursor.getColumnIndexOrThrow("photo_url")))
        rowCursor.close()

        // 5. Verify writing a new photo_url works cleanly
        sqlDb.execSQL("UPDATE students SET photo_url = '/api/v1/students/s_mig_1/photo' WHERE id = 's_mig_1'")
        val updatedCursor = sqlDb.query("SELECT photo_url FROM students WHERE id = 's_mig_1'")
        assertTrue(updatedCursor.moveToFirst())
        assertEquals("/api/v1/students/s_mig_1/photo", updatedCursor.getString(0))
        updatedCursor.close()
    }
}
