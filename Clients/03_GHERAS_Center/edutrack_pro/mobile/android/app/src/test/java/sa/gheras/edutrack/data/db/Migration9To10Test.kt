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
class Migration9To10Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var sqlDb: SupportSQLiteDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("test-migration-9-10.db")
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test-migration-9-10.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // v9 schema stub
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `rooms` (
                                `id` TEXT NOT NULL,
                                `name` TEXT NOT NULL,
                                PRIMARY KEY(`id`)
                            )
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
        if (::helper.isInitialized) {
            helper.close()
        }
        context.deleteDatabase("test-migration-9-10.db")
    }

    @Test
    fun migration9To10_createsStaffAttendanceAndShiftsTables() {
        // Execute migration 9 -> 10
        GherasDatabase.MIGRATION_9_10.migrate(sqlDb)

        // Verify staff_attendance table exists
        val cursorAtt = sqlDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='staff_attendance'")
        assertTrue("staff_attendance table should exist", cursorAtt.moveToFirst())
        cursorAtt.close()

        // Verify staff_shifts table exists
        val cursorShift = sqlDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='staff_shifts'")
        assertTrue("staff_shifts table should exist", cursorShift.moveToFirst())
        cursorShift.close()

        // Test insertion into staff_attendance
        sqlDb.execSQL("""
            INSERT INTO `staff_attendance` (
                `id`, `staff_id`, `date`, `status`, `check_in_time`,
                `check_in_lat`, `check_in_lng`, `check_in_distance`,
                `late_minutes`, `created_at`, `updated_at`
            ) VALUES (
                'att-1', 'staff-1', '2026-10-23', 'حاضر', '2026-10-23T07:55:00Z',
                24.7136, 46.6753, 5.2, 0, '2026-10-23T07:55:00Z', '2026-10-23T07:55:00Z'
            )
        """.trimIndent())

        val readCursor = sqlDb.query("SELECT id, status, check_in_distance FROM staff_attendance WHERE id='att-1'")
        assertTrue(readCursor.moveToFirst())
        assertEquals("att-1", readCursor.getString(0))
        assertEquals("حاضر", readCursor.getString(1))
        assertEquals(5.2, readCursor.getDouble(2), 0.01)
        readCursor.close()

        // Verify unique index on (staff_id, date)
        val indexCursor = sqlDb.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_staff_attendance_staff_id_date'")
        assertTrue("Unique index on staff_id and date should exist", indexCursor.moveToFirst())
        indexCursor.close()
    }
}
