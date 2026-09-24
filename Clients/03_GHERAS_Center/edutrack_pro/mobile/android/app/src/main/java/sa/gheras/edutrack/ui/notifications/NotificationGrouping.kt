package sa.gheras.edutrack.ui.notifications

import sa.gheras.edutrack.data.entity.NotificationEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object NotificationGrouping {

    const val BUCKET_TODAY = "اليوم"
    const val BUCKET_YESTERDAY = "أمس"
    const val BUCKET_THIS_WEEK = "هذا الأسبوع"
    const val BUCKET_EARLIER = "سابقاً"

    val BUCKET_ORDER = listOf(
        BUCKET_TODAY,
        BUCKET_YESTERDAY,
        BUCKET_THIS_WEEK,
        BUCKET_EARLIER
    )

    fun bucketFor(timestamp: Instant?, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        if (timestamp == null) return BUCKET_EARLIER
        val date = timestamp.atZone(zone).toLocalDate()
        val daysDiff = ChronoUnit.DAYS.between(date, today)

        return when {
            daysDiff <= 0 -> BUCKET_TODAY
            daysDiff == 1L -> BUCKET_YESTERDAY
            daysDiff in 2L..7L -> BUCKET_THIS_WEEK
            else -> BUCKET_EARLIER
        }
    }

    private val DIACRITICS_REGEX = Regex("[\\u064B-\\u065F\\u0670]")

    fun normalizeArabic(text: String): String {
        return text
            .replace(DIACRITICS_REGEX, "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .lowercase()
            .trim()
    }

    fun matchesQuery(notification: NotificationEntity, query: String): Boolean {
        if (query.isBlank()) return true
        val normalizedQuery = normalizeArabic(query)
        val normalizedTitle = normalizeArabic(notification.title)
        val normalizedBody = notification.body?.let { normalizeArabic(it) } ?: ""
        val normalizedSender = notification.senderName?.let { normalizeArabic(it) } ?: ""

        return normalizedTitle.contains(normalizedQuery) ||
            normalizedBody.contains(normalizedQuery) ||
            normalizedSender.contains(normalizedQuery)
    }

    fun groupNotifications(
        notifications: List<NotificationEntity>,
        query: String = "",
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)
    ): Map<String, List<NotificationEntity>> {
        val filtered = if (query.isNotBlank()) {
            notifications.filter { matchesQuery(it, query) }
        } else {
            notifications
        }

        val groups = LinkedHashMap<String, MutableList<NotificationEntity>>()
        for (bucket in BUCKET_ORDER) {
            groups[bucket] = mutableListOf()
        }

        for (item in filtered) {
            val bucket = bucketFor(item.sentAt ?: item.createdAt, zone, today)
            groups.getOrPut(bucket) { mutableListOf() }.add(item)
        }

        // Return only buckets that have items, preserving BUCKET_ORDER
        return groups.filterValues { it.isNotEmpty() }
    }
}
