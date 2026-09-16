package dev.logb.android.core.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomTypesTest {
    private fun input(name: String, icon: String, categories: List<String>, unit: String? = null) = TypeInput(name, icon, categories, unit)
    private fun code(i: TypeInput) = (CustomTypes.normalize(i) as TypeCheck.Invalid).code

    @Test fun `the name is trimmed and limited in characters`() {
        assertEquals("E-scooter", (CustomTypes.normalize(input("  E-scooter ", "e-bike", listOf("repair"))) as TypeCheck.Valid).input.name)
        assertEquals(true, CustomTypes.normalize(input("é".repeat(40), "tool", listOf("repair"))) is TypeCheck.Valid)
        assertEquals("name_invalid", code(input("x".repeat(41), "tool", listOf("repair"))))
        assertEquals("name_invalid", code(input("   ", "tool", listOf("repair"))))
    }

    @Test fun `each refusal carries its stable code`() {
        assertEquals("icon_invalid", code(input("Boat", "rocket", listOf("repair"))))
        assertEquals("icon_invalid", code(input("Boat", "box", listOf("repair"))))
        assertEquals("categories_invalid", code(input("Boat", "tool", emptyList())))
        assertEquals("categories_invalid", code(input("Boat", "tool", listOf("sailing"))))
        assertEquals("unit_invalid", code(input("Boat", "tool", listOf("repair"), "nm")))
    }

    @Test fun `categories keep first-seen order, drop duplicates and gain other`() {
        assertEquals(listOf("repair", "fuel", "other"), CustomTypes.normalizeCategories(listOf("repair", "fuel", "repair")))
        assertEquals(listOf("other", "repair"), CustomTypes.normalizeCategories(listOf("other", "repair")))
        assertNull(CustomTypes.normalizeCategories(emptyList()))
    }

    @Test fun `keys`() {
        assertEquals("custom:3f25", CustomTypes.key("3f25"))
        assertEquals("3f25", CustomTypes.uuidOf("custom:3f25"))
        assertNull(CustomTypes.uuidOf("car"))
        assertNull(CustomTypes.uuidOf("custom:"))
    }

    @Test fun `icons are the server list in order`() =
        assertEquals(listOf("document", "camera", "car", "e-bike", "bike", "motorcycle", "home", "appliance", "tool", "body", "object"), CustomTypes.ICONS)

    @Test fun `categories round-trip through JSON`() {
        val categories = listOf("repair", "fuel", "other")
        assertEquals(categories, CustomTypes.categoriesFromJson(CustomTypes.categoriesToJson(categories)))
        assertEquals("""["repair","fuel","other"]""", CustomTypes.categoriesToJson(categories))
    }

    @Test fun `malformed category JSON reads back as empty rather than throwing`() {
        assertEquals(emptyList(), CustomTypes.categoriesFromJson("not json"))
        assertEquals(emptyList(), CustomTypes.categoriesFromJson(""))
    }
}
