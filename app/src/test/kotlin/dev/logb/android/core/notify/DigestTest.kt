package dev.logb.android.core.notify

import dev.logb.android.core.db.rem
import dev.logb.android.core.domain.ReminderPresenter
import dev.logb.android.feature.reminders.DueItem
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DigestTest {
    private val today = LocalDate.parse("2026-09-13")
    private fun item(obj: String, title: String, dueDate: String) =
        DueItem(ReminderPresenter.present(rem(title, "o", title = title, dueDate = dueDate), null, null, today), "o", obj, "car", "km")

    private fun text(vararg items: DueItem) = Digest.text(items.toList(), "due", { "in $it days" }, { "$it more" })

    @Test fun `one due item is the whole title`() {
        val t = text(item("Golf", "Oil change", "2026-09-01"))!!
        assertEquals("Golf: Oil change due", t.title)
        assertNull(t.body)
    }

    @Test fun `the rest are counted in the title and listed in the body`() {
        val t = text(item("Golf", "Oil change", "2026-09-01"), item("Golf", "Inspection", "2026-09-16"), item("House", "Boiler service", "2026-09-19"))!!
        assertEquals("Golf: Oil change due · 2 more", t.title)
        assertEquals("Golf: Inspection in 3 days\nHouse: Boiler service in 6 days", t.body)
    }

    @Test fun `nothing to say is null`() = assertNull(text())

    @Test fun `next run is today when the time is still ahead, else tomorrow`() {
        assertEquals(LocalDateTime.parse("2026-09-13T08:00"), Digest.nextRun(LocalDateTime.parse("2026-09-13T07:59"), 8, 0))
        assertEquals(LocalDateTime.parse("2026-09-14T08:00"), Digest.nextRun(LocalDateTime.parse("2026-09-13T08:00"), 8, 0))
        assertEquals(Duration.ofMinutes(1), Digest.delayUntil(LocalDateTime.parse("2026-09-13T07:59"), 8, 0))
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class LaunchTargetTest {
    @Test fun `a target is read from its intent and nothing else`() {
        val intent = android.content.Intent(dev.logb.android.feature.share.LaunchTarget.ACTION).putExtra(dev.logb.android.feature.share.LaunchTarget.EXTRA, "Due")
        assertEquals(dev.logb.android.feature.share.LaunchTarget.Due, dev.logb.android.feature.share.LaunchTarget.from(intent))
        assertNull(dev.logb.android.feature.share.LaunchTarget.from(android.content.Intent(android.content.Intent.ACTION_MAIN)))
        assertNull(dev.logb.android.feature.share.LaunchTarget.from(null))
    }
}
