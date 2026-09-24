package sa.gheras.edutrack.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sa.gheras.edutrack.data.entity.NotificationEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class NotificationGroupingTest {

    private val riyadh = ZoneId.of("Asia/Riyadh")
    private val today = LocalDate.of(2026, 9, 24)

    /** Instant for a Riyadh-local date at the given hour. */
    private fun at(date: LocalDate, hour: Int = 10): Instant = date.atTime(hour, 0).atZone(riyadh).toInstant()

    private fun notif(
        id: String,
        sentAt: Instant,
        title: String = "إشعار",
        body: String? = null,
        senderName: String? = null
    ) = NotificationEntity(
        id = id,
        branchId = null,
        userId = "u_1",
        kind = "announcement",
        title = title,
        body = body,
        senderName = senderName,
        readAt = null,
        sentAt = sentAt,
        createdAt = sentAt,
        updatedAt = sentAt
    )

    // ---- bucketFor ----

    @Test
    fun bucketFor_boundaries() {
        assertEquals(NotificationGrouping.BUCKET_TODAY, NotificationGrouping.bucketFor(at(today), riyadh, today))
        assertEquals(NotificationGrouping.BUCKET_YESTERDAY, NotificationGrouping.bucketFor(at(today.minusDays(1)), riyadh, today))
        assertEquals(NotificationGrouping.BUCKET_THIS_WEEK, NotificationGrouping.bucketFor(at(today.minusDays(2)), riyadh, today))
        assertEquals(NotificationGrouping.BUCKET_THIS_WEEK, NotificationGrouping.bucketFor(at(today.minusDays(7)), riyadh, today))
        assertEquals(NotificationGrouping.BUCKET_EARLIER, NotificationGrouping.bucketFor(at(today.minusDays(8)), riyadh, today))
    }

    @Test
    fun bucketFor_futureTimestamp_isToday() {
        // Clock skew: server sent_at slightly ahead of device.
        assertEquals(NotificationGrouping.BUCKET_TODAY, NotificationGrouping.bucketFor(at(today.plusDays(1)), riyadh, today))
    }

    @Test
    fun bucketFor_null_isEarlier() {
        assertEquals(NotificationGrouping.BUCKET_EARLIER, NotificationGrouping.bucketFor(null, riyadh, today))
    }

    @Test
    fun bucketFor_usesLocalZoneNotUtc() {
        // 2026-09-23T22:30Z == 2026-09-24T01:30 in Riyadh (UTC+3) → today, not yesterday.
        val instant = Instant.parse("2026-09-23T22:30:00Z")
        assertEquals(NotificationGrouping.BUCKET_TODAY, NotificationGrouping.bucketFor(instant, riyadh, today))
        assertEquals(NotificationGrouping.BUCKET_YESTERDAY, NotificationGrouping.bucketFor(instant, ZoneId.of("UTC"), today))
    }

    // ---- normalizeArabic ----

    @Test
    fun normalizeArabic_foldsAlefTaMarbutaYaAndDiacritics() {
        assertEquals("احمد", NotificationGrouping.normalizeArabic("أحمد"))
        assertEquals("اسلام", NotificationGrouping.normalizeArabic("إسلام"))
        assertEquals("امنه", NotificationGrouping.normalizeArabic("آمنة"))
        assertEquals("مصطفي", NotificationGrouping.normalizeArabic("مصطفى"))
        assertEquals("مدرسه", NotificationGrouping.normalizeArabic("مَدْرَسَةٌ"))
    }

    @Test
    fun normalizeArabic_lowercasesAndTrimsLatin() {
        assertEquals("quiz 1", NotificationGrouping.normalizeArabic("  QUIZ 1 "))
    }

    // ---- matchesQuery ----

    @Test
    fun matchesQuery_blankMatchesAll() {
        assertTrue(NotificationGrouping.matchesQuery(notif("1", at(today)), "  "))
    }

    @Test
    fun matchesQuery_searchesTitleBodyAndSender_normalized() {
        val n = notif("1", at(today), title = "واجب جديد", body = "موعد التسليم غداً", senderName = "أ. فاطمة")
        assertTrue(NotificationGrouping.matchesQuery(n, "واجب"))
        assertTrue(NotificationGrouping.matchesQuery(n, "التسليم"))
        assertTrue(NotificationGrouping.matchesQuery(n, "ا. فاطمه"))
        assertFalse(NotificationGrouping.matchesQuery(n, "غياب"))
    }

    @Test
    fun matchesQuery_nullBodyAndSender_safe() {
        val n = notif("1", at(today), title = "تسجيل غياب")
        assertFalse(NotificationGrouping.matchesQuery(n, "سارة"))
        assertTrue(NotificationGrouping.matchesQuery(n, "غياب"))
    }

    // ---- groupNotifications ----

    @Test
    fun groupNotifications_ordersBucketsAndDropsEmpty() {
        val items = listOf(
            notif("old", at(today.minusDays(30))),
            notif("t1", at(today, 9)),
            notif("wk", at(today.minusDays(3))),
            notif("t2", at(today, 8))
        )
        val groups = NotificationGrouping.groupNotifications(items, zone = riyadh, today = today)

        assertEquals(
            listOf(NotificationGrouping.BUCKET_TODAY, NotificationGrouping.BUCKET_THIS_WEEK, NotificationGrouping.BUCKET_EARLIER),
            groups.keys.toList()
        )
        // Within a bucket, input order is preserved.
        assertEquals(listOf("t1", "t2"), groups.getValue(NotificationGrouping.BUCKET_TODAY).map { it.id })
        assertFalse(groups.containsKey(NotificationGrouping.BUCKET_YESTERDAY))
    }

    @Test
    fun groupNotifications_appliesQueryFilter() {
        val items = listOf(
            notif("a", at(today), title = "إعلان رحلة"),
            notif("b", at(today.minusDays(1)), title = "واجب الرياضيات")
        )
        val groups = NotificationGrouping.groupNotifications(items, query = "اعلان", zone = riyadh, today = today)
        assertEquals(listOf(NotificationGrouping.BUCKET_TODAY), groups.keys.toList())
        assertEquals("a", groups.getValue(NotificationGrouping.BUCKET_TODAY).single().id)
    }

    @Test
    fun groupNotifications_emptyInput_emptyMap() {
        assertTrue(NotificationGrouping.groupNotifications(emptyList(), zone = riyadh, today = today).isEmpty())
    }
}
