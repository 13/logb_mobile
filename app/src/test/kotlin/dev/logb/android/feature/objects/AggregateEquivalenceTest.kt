package dev.logb.android.feature.objects

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.T0
import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.entity.ActivityEntity
import dev.logb.android.core.db.entity.AttachmentEntity
import dev.logb.android.core.db.entity.FileEntity
import dev.logb.android.core.db.entity.ObjectTypeEntity
import dev.logb.android.core.db.entity.ReminderEntity
import dev.logb.android.core.db.obj
import dev.logb.android.core.db.rem
import dev.logb.android.core.domain.CustomTypes
import dev.logb.android.core.domain.ReminderPresenter
import dev.logb.android.core.domain.ReminderRules
import dev.logb.android.core.domain.Tags
import dev.logb.android.feature.reminders.DueItem
import dev.logb.android.feature.reminders.DueListModel
import dev.logb.android.feature.stats.InsightsModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * The aggregate-query rewrite must not change what the objects list, due list and digest show.
 * `legacyCards`/`legacyDue` are the pre-change `ObjectsModel.cardsFor`/`DueListModel.items`
 * bodies, pasted here verbatim as the reference; they are deleted from production in Step 3.
 */
@RunWith(RobolectricTestRunner::class)
class AggregateEquivalenceTest {
    private val db = TestDatabase.inMemory()

    @After fun close() = db.close()

    @Test
    fun `cards from the aggregates equal cards from the per-object queries`() = runBlocking {
        seed(db, objects = 30)
        val today = LocalDate.parse("2026-09-15")
        val expected = legacyCards(db, today) // the pre-change ObjectsModel code, copied into this test verbatim
        val actual = ObjectsModel(db) { today }.allCards().first()
        assertEquals(expected.sortedBy { it.uuid }, actual.sortedBy { it.uuid })
    }

    @Test
    fun `due items from the aggregates equal the per-object due items`() = runBlocking {
        seed(db, objects = 30)
        val today = LocalDate.parse("2026-09-15")
        assertEquals(legacyDue(db, today, 30), DueListModel(db) { today }.items(30).first())
        assertEquals(legacyDue(db, today, 7), DueListModel(db) { today }.items(7).first())
    }

    @Test
    fun `query count does not grow with the number of objects`() = runBlocking {
        val small = countQueries { seed(it, objects = 5); ObjectsModel(it).allCards().first() }
        val large = countQueries { seed(it, objects = 200); ObjectsModel(it).allCards().first() }
        assertEquals(small, large)
        // Not literally <= 4: allCards() itself combines three source flows (live objects, open
        // reminders, the activity version tag), each issuing its own SELECT on subscription, on top
        // of the three aggregate queries -- 6, not 4. See the task report for the exact breakdown;
        // what this asserts is that the count is fixed, not that it is small.
        assertEquals(6, large, "allCards ran $large queries")
    }

    @Test
    fun `due list query count does not grow with the number of objects`() = runBlocking {
        val small = countQueries { seed(it, objects = 5); DueListModel(it).items().first() }
        val large = countQueries { seed(it, objects = 200); DueListModel(it).items().first() }
        assertEquals(small, large)
        // allOpen() (the source flow) + objectDao().all() + statsForAll() + readingRowsForAll() = 4.
        assertEquals(4, large, "items ran $large queries")
    }

    @Test
    fun `totalDue from the aggregate equals the sum of the per-object due counts`() = runBlocking {
        seed(db, objects = 30)
        val today = LocalDate.parse("2026-09-15")
        val expected = legacyTotalDue(db, today) // the pre-change ObjectsModel.totalDue body, copied into this test verbatim
        assertEquals(expected, ObjectsModel(db) { today }.totalDue().first())
    }
}

// --- Seed: a tree of objects with every edge the equivalence test must cover ---

/**
 * `objects` objects in a tree (every fifth a child of the one before), some archived, some of an
 * own (`custom:`) type, some tagged. Entries carry costs and counter readings, some deleted, some
 * dated in the future, two on the same date with different counters; one object (index 3, when
 * present) has no entries at all. Covers: one valid, one pointing at a deleted attachment, one
 * whose file has a blank sha. Reminders: open, snoozed, done and reading, per object.
 */
private suspend fun seed(db: LogbDatabase, objects: Int) {
    db.objectTypeDao().upsert(ObjectTypeEntity("type-boat", null, "Boat", "car", """["repair","other"]""", "km", T0, T0, null))
    val ownType = CustomTypes.key("type-boat")

    val objs = (0 until objects).map { i ->
        val parent = if (i > 0 && i % 5 == 0) "obj-${i - 1}" else null
        val archived = if (i % 7 == 0) T0 else null
        val type = when {
            i % 11 == 0 -> ownType
            i % 3 == 0 -> "home"
            else -> "car"
        }
        val o = obj("obj-$i", "Object $i", parent = parent, archived = archived, type = type)
        if (i % 4 == 0) o.copy(tags = """["blue","fleet"]""") else o
    }
    db.objectDao().upsert(*objs.toTypedArray())

    if (objects >= 4) {
        db.fileDao().upsert(
            FileEntity("file-good", null, "sha-good", "photo.jpg", "image/jpeg", 100, null, null, null, T0, null),
            FileEntity("file-blank", null, "", "photo2.jpg", "image/jpeg", 100, null, null, null, T0, null),
            // whitespace-only, not the empty string: `isNotBlank()` (and now `TRIM(...) != ''`) treat it as blank too
            FileEntity("file-whitespace", null, "   ", "photo3.jpg", "image/jpeg", 100, null, null, null, T0, null),
        )
        db.attachmentDao().upsert(
            AttachmentEntity("att-good", null, "obj-0", null, "file-good", "photo", "", T0, null),
            // a cover pointing at a deleted attachment
            AttachmentEntity("att-deleted", null, "obj-1", null, "file-good", "photo", "", T0, T0),
            // a cover whose file has a blank sha
            AttachmentEntity("att-blank", null, "obj-2", null, "file-blank", "photo", "", T0, null),
            // a cover whose file has a whitespace-only sha
            AttachmentEntity("att-whitespace", null, "obj-3", null, "file-whitespace", "photo", "", T0, null),
        )
        db.objectDao().upsert(
            objs[0].copy(coverAttachmentUuid = "att-good"),
            objs[1].copy(coverAttachmentUuid = "att-deleted"),
            objs[2].copy(coverAttachmentUuid = "att-blank"),
            objs[3].copy(coverAttachmentUuid = "att-whitespace"),
        )
    }

    val entries = mutableListOf<ActivityEntity>()
    for (i in objs.indices) {
        if (i == 3) continue // an object with no entries at all
        val id = "obj-$i"
        entries += act("act-$i-1", id, "2026-0${(i % 8) + 1}-0${(i % 9) + 1}", cost = 1_000L + i, counter = 1_000L + i * 10L)
        entries += act("act-$i-2", id, "2026-06-15", cost = 500L, counter = 1_200L + i * 10L)
        // same date, different counter, to prove ordering of the batched query does not matter
        entries += act("act-$i-2b", id, "2026-06-15", cost = 50L, counter = 1_250L + i * 10L)
        entries += act("act-$i-del", id, "2026-07-01", cost = 999L, counter = 5_000L).copy(deletedAt = T0)
        entries += act("act-$i-future", id, "2099-01-01", cost = 10L, counter = 1L)
    }
    db.activityDao().upsert(*entries.toTypedArray())

    val reminders = mutableListOf<ReminderEntity>()
    for (i in objs.indices) {
        val id = "obj-$i"
        reminders += rem("rem-$i-open", id, dueDate = "2026-09-20")
        reminders += rem("rem-$i-snoozed", id, dueDate = "2026-09-16").copy(snoozedUntil = "2026-10-01")
        reminders += rem("rem-$i-done", id, dueDate = "2020-01-01", doneAt = T0)
        reminders += rem("rem-$i-reading", id, dueDate = "2026-01-01").copy(kind = "reading", everyN = 1, everyUnit = "month")
        // genuinely due (by date, already past), on every object -- including those with readings --
        // so a due count actually depends on the batched stats, not just structurally passes on zeros
        reminders += rem("rem-$i-due-now", id, dueDate = "2026-09-01")
    }
    db.reminderDao().upsert(*reminders.toTypedArray())
    // When called from countQueries, zero the counter now: only the queries the model itself
    // issues while computing its one emission should count, not the seed's own inserts/upserts.
    queryCounter.get()?.set(0)
}

// --- The reference: production code as it stood before this task, pasted verbatim ---

private suspend fun legacyCards(db: LogbDatabase, today: LocalDate): List<ObjectCard> {
    val insights = InsightsModel(db) { today }
    val objects = db.objectDao().all().first()
    val open = db.reminderDao().allOpen().first()
    return objects.map { o ->
        val stats = db.objectDao().stats(o.uuid)
        val due = legacyDueCount(open.filter { it.objectUuid == o.uuid }, stats.currentCounter, stats.lastReadingDate, today)
        val cover = o.coverAttachmentUuid?.let { db.attachmentDao().get(it) }?.takeIf { it.deletedAt == null }?.let { db.fileDao().get(it.fileUuid) }?.sha256?.takeIf { it.isNotBlank() }
        val rate = if (o.counterUnit != null) insights.usage(o.uuid)?.rateMilli else null
        ObjectCard(
            o.uuid, o.name, o.type, stats.currentCounter, o.counterUnit, stats.totalCostCents, stats.lastActivityDate, due, cover,
            parentUuid = o.parentUuid, archived = o.archivedAt != null, description = o.description, updatedAt = o.updatedAt, counterPerDayMilli = rate,
            tags = Tags.fromJson(o.tags),
        )
    }
}

private fun legacyDueCount(open: List<ReminderEntity>, currentCounter: Long?, lastReadingDate: String?, today: LocalDate): Int {
    val lastReading = ReminderPresenter.clampLastReading(lastReadingDate, today)
    return open.count { ReminderPresenter.present(it, currentCounter, lastReading, today).due }
}

/** The pre-change `ObjectsModel.totalDue` body, verbatim. */
private suspend fun legacyTotalDue(db: LogbDatabase, today: LocalDate): Int {
    val open = db.reminderDao().allOpen().first()
    return open.groupBy { it.objectUuid }.entries.sumOf { (objectUuid, rs) ->
        val stats = db.objectDao().stats(objectUuid)
        legacyDueCount(rs, stats.currentCounter, stats.lastReadingDate, today)
    }
}

private suspend fun legacyDue(db: LogbDatabase, today: LocalDate, withinDays: Long): List<DueItem> {
    val insights = InsightsModel(db) { today }
    val open = db.reminderDao().allOpen().first()
    return open.groupBy { it.objectUuid }.flatMap { (objectUuid, rs) ->
        val obj = db.objectDao().get(objectUuid)?.takeIf { it.deletedAt == null } ?: return@flatMap emptyList()
        val stats = db.objectDao().stats(objectUuid)
        val lastReading = ReminderPresenter.clampLastReading(stats.lastReadingDate, today)
        val usage = insights.usage(objectUuid)
        rs.map { ReminderPresenter.present(it, stats.currentCounter, lastReading, today, usage) }
            .filter { ReminderRules.isUpcoming(today, it.due, it.soonestDays, withinDays, ReminderRules.parseDate(it.reminder.snoozedUntil)) }
            .map { DueItem(it, obj.uuid, obj.name, obj.type, obj.counterUnit) }
    }.sortedWith(compareByDescending<DueItem> { it.view.due }.thenBy { it.view.soonestDays ?: Long.MAX_VALUE }.thenBy { it.objectName })
}

// --- Query counting: a fresh, driver-less in-memory Room database whose query callback fires per SELECT ---

/** Set only while [countQueries] is measuring, so [seed] can zero it once seeding is done. */
private val queryCounter = ThreadLocal<AtomicInteger?>()

/**
 * Room's own bookkeeping is excluded: the invalidation tracker's own
 * `SELECT * FROM room_table_modification_log` (fired from a background check whose timing is not
 * deterministic -- it is not one of the DAO's queries) and the `SELECT changes()` an `@Upsert`
 * issues to tell an insert from an update (a seed-time artifact, in any case zeroed by `seed`).
 */
private fun isAppSelect(sql: String): Boolean {
    val trimmed = sql.trimStart()
    return trimmed.startsWith("SELECT", ignoreCase = true) &&
        !trimmed.contains("room_table_modification_log") &&
        !trimmed.startsWith("SELECT changes()", ignoreCase = true)
}

private suspend fun countQueries(block: suspend (LogbDatabase) -> Unit): Int {
    val count = AtomicInteger(0)
    // A same-thread executor: the callback fires synchronously as part of each statement's
    // execution, so `seed`'s reset (run on the calling coroutine right after its last upsert
    // returns) is guaranteed to see every increment that statement caused, with no race.
    val inline = Executor { it.run() }
    val counting = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LogbDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> if (isAppSelect(sql)) count.incrementAndGet() }, inline)
        .build()
    queryCounter.set(count)
    try {
        block(counting)
    } finally {
        queryCounter.remove()
        counting.close()
    }
    return count.get()
}
