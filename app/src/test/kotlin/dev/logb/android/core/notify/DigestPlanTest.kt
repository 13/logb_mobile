package dev.logb.android.core.notify

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DigestPlanTest {
    private fun plan(items: List<dev.logb.android.feature.reminders.DueItem>) =
        DigestPlan.plan(items, dueWord = "due", upcomingWord = { "in $it days" }, more = { "and $it more" })

    @Test fun `nothing due plans nothing`() = assertEquals(DigestNotifications(null, emptyList()), plan(emptyList()))

    @Test fun `one due service reminder is one notification with done and snooze`() {
        val p = plan(listOf(DueFixtures.service("r1", "Golf", "Oil change", due = true)))
        assertNull(p.summary)
        val child = p.children.single()
        assertEquals("Golf: Oil change", child.title)
        assertEquals("due", child.text)
        assertEquals(listOf(NotificationAction.Done, NotificationAction.Snooze), child.actions)
        assertEquals(DigestPlan.idFor("r1"), child.id)
    }

    @Test fun `a reading reminder only offers log reading`() =
        assertEquals(listOf(NotificationAction.LogReading), plan(listOf(DueFixtures.reading("r2", "Golf", "Mileage", due = true))).children.single().actions)

    @Test fun `several become a summary and at most five children in digest order`() {
        val items = (1..7).map { DueFixtures.service("r$it", "Object $it", "Task $it", due = it <= 3, soonestDays = it.toLong()) }
        val p = plan(items)
        assertEquals(5, p.children.size)
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), p.children.map { it.reminderUuid })
        // 7 items, 5 shown as children: 2 hidden.
        assertTrue(p.summary!!.title.startsWith("Object 1: Task 1"))
        assertTrue(p.summary!!.title.contains("and 2 more"))
    }

    @Test fun `a summary that shows every item names only the first, with no more and no body`() {
        val items = (1..3).map { DueFixtures.service("r$it", "Object $it", "Task $it", due = it <= 1, soonestDays = it.toLong()) }
        val p = plan(items)
        assertEquals(3, p.children.size)
        assertEquals("Object 1: Task 1 due", p.summary!!.title)
        assertNull(p.summary!!.body)
    }

    @Test fun `a summary with one hidden item says so`() {
        val items = (1..6).map { DueFixtures.service("r$it", "Object $it", "Task $it", due = it <= 1, soonestDays = it.toLong()) }
        val p = plan(items)
        assertEquals("Object 1: Task 1 due · and 1 more", p.summary!!.title)
    }

    @Test fun `ids are stable, positive and never the summary id`() {
        val ids = (1..500).map { DigestPlan.idFor("uuid-$it") }
        assertEquals(ids, (1..500).map { DigestPlan.idFor("uuid-$it") })
        assertTrue(ids.all { it > DigestPlan.SUMMARY_ID })
    }

    @Test fun `idFor stays clear of the summary id even when the masked hash is 0 or 1`() {
        // A length-1 string hashes to its own code point: pick the two whose masked hash is 0 or 1.
        val zero = 0.toChar().toString()
        val one = 1.toChar().toString()
        assertEquals(0, zero.hashCode())
        assertEquals(1, one.hashCode())
        assertEquals(2, DigestPlan.idFor(zero))
        assertEquals(3, DigestPlan.idFor(one))
    }
}
