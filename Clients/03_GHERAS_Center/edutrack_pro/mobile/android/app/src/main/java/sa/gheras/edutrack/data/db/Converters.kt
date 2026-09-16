package sa.gheras.edutrack.data.db

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

object Converters {

    @TypeConverter
    fun instantToText(value: Instant?): String? = value?.toString()

    @TypeConverter
    fun textToInstant(value: String?): Instant? = value?.let(Instant::parse)

    @TypeConverter
    fun localDateToText(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun textToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun localTimeToText(value: LocalTime?): String? = value?.toString()

    @TypeConverter
    fun textToLocalTime(value: String?): LocalTime? = value?.let(LocalTime::parse)
}
