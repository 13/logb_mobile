package dev.logb.android.feature.objects

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every test in `frontend/tests/object-list.test.ts`. */
class ObjectListingTest {
    private var next = 1
    private fun obj(
        name: String? = null, type: String = "other", description: String = "", parent: String? = null, archived: Boolean = false,
        updatedAt: String = "2026-01-01T00:00:00Z", lastActivity: String? = null, cost: Long = 0, counter: Long? = null, uuid: String? = null,
        tags: List<String> = emptyList(),
    ): ObjectCard {
        val id = uuid ?: "u${next++}"
        return ObjectCard(id, name ?: "Object $id", type, counter, null, cost, lastActivity, 0, null, parentUuid = parent, archived = archived, description = description, updatedAt = updatedAt, tags = tags)
    }

    private val label: (String) -> String = { mapOf("e_bike" to "E-Bike", "home" to "Zuhause")[it] ?: it }
    private fun names(list: List<ObjectCard>) = list.map { it.name }
    private val en = Locale.ENGLISH

    @Test fun `parse accepts only known values`() {
        assertEquals(SortKey.LastActivity, SortKey.parse("last-activity"))
        assertNull(SortKey.parse("nonsense")); assertNull(SortKey.parse(null))
    }

    @Test fun `matches name, type label and description, ignoring case and accents`() {
        val bike = obj(name = "Cube Kathmandu", type = "e_bike", description = "Pendeln zur Arbeit")
        assertTrue(ObjectListing.matchesQuery(bike, "kathm", label))
        assertTrue(ObjectListing.matchesQuery(bike, "e-bike", label))
        assertTrue(ObjectListing.matchesQuery(bike, "ARBEIT", label))
        assertTrue(ObjectListing.matchesQuery(obj(name = "Fahrräder"), "fahrrader", label))
        assertFalse(ObjectListing.matchesQuery(bike, "boiler", label))
        assertTrue(ObjectListing.matchesQuery(bike, "   ", label), "blank matches everything")
    }

    private val a = obj(name = "alpha", updatedAt = "2026-03-01T00:00:00Z", lastActivity = "2026-02-01", cost = 500, counter = 10)
    private val b = obj(name = "Bravo", updatedAt = "2026-05-01T00:00:00Z", lastActivity = "2026-06-01", cost = 0, counter = null)
    private val c = obj(name = "charlie", updatedAt = "2026-04-01T00:00:00Z", lastActivity = null, cost = 900, counter = 20)
    private val d = obj(name = "delta", updatedAt = "2026-04-01T00:00:00Z", lastActivity = null, cost = 900, counter = 20)

    @Test fun `sorts names case-insensitively`() = assertEquals(listOf("alpha", "Bravo", "charlie"), names(ObjectListing.sortObjects(listOf(c, b, a), SortKey.Name, en)))
    @Test fun `puts the newest activity first and objects without any last, by name`() = assertEquals(listOf("Bravo", "alpha", "charlie", "delta"), names(ObjectListing.sortObjects(listOf(d, c, a, b), SortKey.LastActivity, en)))
    @Test fun `puts the most recently changed first, ties by name`() = assertEquals(listOf("Bravo", "charlie", "delta", "alpha"), names(ObjectListing.sortObjects(listOf(a, d, b, c), SortKey.Changed, en)))
    @Test fun `puts the highest cost first and zero cost last`() = assertEquals(listOf("charlie", "delta", "alpha", "Bravo"), names(ObjectListing.sortObjects(listOf(b, a, d, c), SortKey.Cost, en)))
    @Test fun `puts the highest counter first and no counter last`() = assertEquals(listOf("charlie", "delta", "alpha", "Bravo"), names(ObjectListing.sortObjects(listOf(b, a, d, c), SortKey.Counter, en)))
    @Test fun `does not mutate its input`() {
        val input = listOf(c, b, a); ObjectListing.sortObjects(input, SortKey.Name, en); assertEquals(listOf("charlie", "Bravo", "alpha"), names(input))
    }

    private val house = obj(name = "House", type = "home", uuid = "100")
    private val boiler = obj(name = "Boiler", parent = "100", uuid = "101")
    private val shed = obj(name = "Shed", parent = "100", archived = true, uuid = "102")
    private val mower = obj(name = "Mower", parent = "102", uuid = "103") // live child of an archived parent
    private val car = obj(name = "Car", uuid = "104")
    private val active = listOf(house, boiler, mower, car)
    private val archived = listOf(shed)
    private fun rows(showArchived: Boolean, query: String) = ObjectListing.visibleRows(active, archived, showArchived, query, SortKey.Name, label, en).map { it.card.name to it.parentName }

    @Test fun `shows top-level objects on the active tab, counting a child of an archived parent as top-level`() =
        assertEquals(listOf("Car" to null, "House" to null, "Mower" to null), rows(false, ""))
    @Test fun `searches every depth and names the parent of a nested match`() = assertEquals(listOf("Boiler" to "House"), rows(false, "boil"))
    @Test fun `names an archived parent too`() = assertEquals(listOf("Mower" to "Shed"), rows(false, "mow"))
    @Test fun `lists archived objects flat, with their parent`() = assertEquals(listOf("Shed" to "House"), rows(true, ""))

    private val tagHouse = obj(name = "Tag House", uuid = "200", tags = listOf("Lease"))
    private val tagBoiler = obj(name = "Tag Boiler", parent = "200", uuid = "201", tags = listOf("winter"))
    private val tagCar = obj(name = "Tag Car", uuid = "202", tags = listOf("Winter", "Lease"))

    @Test fun `search matches tags`() =
        assertEquals(listOf("Tag Car", "Tag House"), ObjectListing.visibleRows(listOf(tagHouse, tagBoiler, tagCar), emptyList(), false, "lease", SortKey.Name, { it }, en).map { it.card.name })

    @Test fun `a tag filter keeps carriers at every depth, ignoring case`() =
        assertEquals(
            listOf("Tag Boiler" to "Tag House", "Tag Car" to null),
            ObjectListing.visibleRows(listOf(tagHouse, tagBoiler, tagCar), emptyList(), false, "", SortKey.Name, { it }, en, "WINTER").map { it.card.name to it.parentName },
        )
}
