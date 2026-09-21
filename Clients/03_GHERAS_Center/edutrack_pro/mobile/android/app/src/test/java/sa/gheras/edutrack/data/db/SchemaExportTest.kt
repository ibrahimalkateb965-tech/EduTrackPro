package sa.gheras.edutrack.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure JVM verification of the Room exported schema JSON (§5.1 / §7.4).
 * Parsed with kotlinx-serialization — `org.json` is an unmocked android.jar stub under plain JUnit.
 * Ensures schema version is 1, exactly the 16 required tables exist,
 * students table has nullable gender, and pending_writes has zero foreign keys.
 */
class SchemaExportTest {

    @Test
    fun testSchemaExport_exact16Tables_version1_noDanglingFKs() {
        val candidatePaths = listOf(
            File("schemas/sa.gheras.edutrack.data.db.GherasDatabase/1.json"),
            File("app/schemas/sa.gheras.edutrack.data.db.GherasDatabase/1.json"),
            File("../app/schemas/sa.gheras.edutrack.data.db.GherasDatabase/1.json"),
            File("mobile/android/app/schemas/sa.gheras.edutrack.data.db.GherasDatabase/1.json")
        )

        val schemaFile = candidatePaths.firstOrNull { it.exists() }
        assertNotNull(
            "Room schema file 1.json not found in candidate paths: ${candidatePaths.map { it.absolutePath }}",
            schemaFile
        )

        val jsonContent = schemaFile!!.readText(Charsets.UTF_8)
        val root = Json.parseToJsonElement(jsonContent).jsonObject
        val database = root.getValue("database").jsonObject

        // 1. Version must be 1
        assertEquals("Schema version must be 1", 1, database.getValue("version").jsonPrimitive.int)

        // 2. Exact set of 16 tables
        val expectedTables = setOf(
            "rooms",
            "students",
            "schedules",
            "student_attendance",
            "evaluations",
            "assignments",
            "assignment_students",
            "submissions",
            "submission_files",
            "lesson_logs",
            "skill_progress",
            "notifications",
            "installments",
            "receipts",
            "pending_writes",
            "sync_state"
        )

        val entities = database.getValue("entities").jsonArray
        val actualTables = mutableSetOf<String>()
        var studentsEntity: JsonObject? = null
        var pendingWritesEntity: JsonObject? = null

        for (element in entities) {
            val entity = element.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            actualTables.add(tableName)

            if (tableName == "students") {
                studentsEntity = entity
            }
            if (tableName == "pending_writes") {
                pendingWritesEntity = entity
            }
        }

        assertEquals("Table count must be exactly 16", 16, actualTables.size)
        assertEquals("Tables must match the exact design specification", expectedTables, actualTables)

        // 3. Ensure obsolete tables do NOT exist
        assertFalse("users table must NOT exist in local database", actualTables.contains("users"))
        assertFalse("guardians table must NOT exist in local database", actualTables.contains("guardians"))
        assertFalse("study_plans table must NOT exist in local database", actualTables.contains("study_plans"))

        // 4. Students table has nullable gender column
        assertNotNull("students table must exist", studentsEntity)
        val studentFields = studentsEntity!!.getValue("fields").jsonArray
        val genderField: JsonObject? = studentFields
            .map { it.jsonObject }
            .firstOrNull { it.getValue("columnName").jsonPrimitive.content == "gender" }
        assertNotNull("gender field must exist in students table", genderField)
        // Room omits `notNull` from the export when it is false, so absence == nullable.
        val genderNotNull = genderField!!["notNull"]?.jsonPrimitive?.boolean ?: false
        assertFalse("gender field in students table must be nullable", genderNotNull)

        // 5. pending_writes must have zero foreign keys
        assertNotNull("pending_writes table must exist", pendingWritesEntity)
        // Likewise `foreignKeys` is omitted entirely when the entity declares none.
        val foreignKeys = pendingWritesEntity!!["foreignKeys"]?.jsonArray ?: emptyList()
        assertEquals("pending_writes must have zero foreign keys", 0, foreignKeys.size)
    }
}
