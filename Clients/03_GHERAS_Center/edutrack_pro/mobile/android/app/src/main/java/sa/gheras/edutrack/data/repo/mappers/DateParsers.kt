package sa.gheras.edutrack.data.repo.mappers

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object DateParsers {

    fun parseInstant(str: String?): Instant {
        if (str.isNullOrBlank()) return Instant.now()
        return try {
            Instant.parse(str)
        } catch (e: DateTimeParseException) {
            try {
                // Try with offset/local fallback
                val ldt = java.time.LocalDateTime.parse(str.replace(" ", "T"))
                ldt.toInstant(java.time.ZoneOffset.UTC)
            } catch (e2: Exception) {
                Instant.now()
            }
        }
    }

    fun parseLocalDate(str: String?): LocalDate? {
        if (str.isNullOrBlank()) return null
        return try {
            LocalDate.parse(str.take(10))
        } catch (e: Exception) {
            null
        }
    }

    fun parseLocalTime(str: String?): LocalTime {
        if (str.isNullOrBlank()) return LocalTime.MIDNIGHT
        return try {
            val s = str.trim()
            if (s.length == 5) {
                LocalTime.parse("$s:00")
            } else {
                LocalTime.parse(s.take(8))
            }
        } catch (e: Exception) {
            LocalTime.MIDNIGHT
        }
    }
}
