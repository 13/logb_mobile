package dev.logb.android.core.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagsTest {
    @Test fun `colour index is stable and ignores case and accents`() {
        assertEquals(Tags.colorIndex("Winter"), Tags.colorIndex("winter"))
        assertEquals(Tags.colorIndex("Fahrräder"), Tags.colorIndex("FAHRRADER"))
    }

    @Test fun `colour index stays inside the palette and spreads common tags`() {
        val tags = listOf("winter", "summer", "lease", "tax 2026", "garage", "warranty", "work", "family", "loan", "garden", "kids", "travel")
        val used = tags.map(Tags::colorIndex).toSet()
        assertTrue(used.all { it in 0 until Tags.PALETTE_SIZE })
        assertTrue(used.size >= 5)
    }

    /** The slots the web's `tagColorIndex` (tags.ts) gives these tags, computed with its own code on 2026-09-15. */
    @Test fun `colour index matches the web for known tags`() {
        assertEquals(4, Tags.colorIndex("Winter"))
        assertEquals(1, Tags.colorIndex("Lease"))
        assertEquals(4, Tags.colorIndex("tax 2026"))
    }

    @Test fun `add normalises and dedupes ignoring case and accents`() {
        assertEquals(AddResult.Added(listOf("Winter")), Tags.addTag(listOf("Winter"), "  winter "))
        assertEquals(AddResult.Added(listOf("Winter", "Garage 2")), Tags.addTag(listOf("Winter"), " Garage   2 "))
    }

    /** `\s` on the JVM is ASCII-only unless told otherwise; the web's `/\s+/g` collapses NBSP and other Unicode spaces too. */
    @Test fun `add collapses Unicode whitespace like the web`() {
        assertEquals(AddResult.Added(listOf("A B")), Tags.addTag(emptyList(), "A  B"))
        assertEquals(AddResult.Added(listOf("A B")), Tags.addTag(listOf("A B"), "a b"))
    }

    /** The ideographic space (U+3000) and the thin space (U+2009) collapse too -- not just NBSP. */
    @Test fun `add collapses the ideographic and thin spaces`() {
        assertEquals(AddResult.Added(listOf("A B")), Tags.addTag(emptyList(), "A　B"))
        assertEquals(AddResult.Added(listOf("A B")), Tags.addTag(listOf("A B"), "a b"))
    }

    /**
     * NEL (U+0085) and BOM (U+FEFF) are not whitespace by Java's `Character.isWhitespace`, so a
     * trim-then-collapse order leaves a fresh leading/trailing space uncaught; collapse-then-trim
     * (the fixed order) removes it. Written as backslash-u escapes, not literal characters.
     */
    @Test fun `add trims whitespace the collapse itself introduces at the edges`() {
        assertEquals(AddResult.Added(listOf("Winter")), Tags.addTag(emptyList(), "Winter\u0085"))
        assertEquals(AddResult.Added(listOf("Winter")), Tags.addTag(emptyList(), "\uFEFFWinter"))
    }

    @Test fun `add refuses empty, too long and too many`() {
        assertEquals(AddResult.Refused(TagError.EMPTY), Tags.addTag(emptyList(), "   "))
        assertEquals(AddResult.Refused(TagError.TOO_LONG), Tags.addTag(emptyList(), "x".repeat(33)))
        assertEquals(AddResult.Refused(TagError.TOO_MANY), Tags.addTag((0 until 10).map { "t$it" }, "eleventh"))
        assertEquals(AddResult.Added(listOf("é".repeat(32))), Tags.addTag(emptyList(), "é".repeat(32)), "the limit counts characters, not bytes")
    }

    @Test fun `remove by exact tag`() = assertEquals(listOf("b"), Tags.removeTag(listOf("a", "b"), "a"))

    @Test fun `split commits completed segments and keeps the rest`() {
        assertEquals(SplitResult(listOf("A", "b"), "", null), Tags.splitTyped(listOf("A"), "b,"))
        assertEquals(SplitResult(listOf("a", "b"), "c", null), Tags.splitTyped(emptyList(), "a, b ,c"))
        assertEquals(SplitResult(listOf("Winter"), "x", null), Tags.splitTyped(listOf("Winter"), "winter,x"))
        assertEquals(SplitResult(listOf("ok"), "x".repeat(33), TagError.TOO_LONG), Tags.splitTyped(emptyList(), "${"x".repeat(33)},ok,"))
        val ten = (0 until 10).map { "t$it" }
        assertEquals(SplitResult(ten, "eleven", TagError.TOO_MANY), Tags.splitTyped(ten, "eleven,"))
        assertEquals(SplitResult(listOf("A"), "partial", null), Tags.splitTyped(listOf("A"), "partial"))
    }

    private val all = listOf(TagCount("Winter", 5), TagCount("Wartung", 2), TagCount("Lease", 9), TagCount("Twin", 1))

    @Test fun `suggest prefix before contains, not what is there`() {
        assertEquals(listOf("Winter", "Wartung", "Twin"), Tags.suggestTags(all, emptyList(), "w"))
        assertEquals(listOf("Twin"), Tags.suggestTags(all, listOf("Winter"), "win"))
    }

    @Test fun `suggest most used when nothing typed`() = assertEquals(listOf("Lease", "Winter"), Tags.suggestTags(all, emptyList(), "", 2))

    /** The same table is asserted in logb's src/domain/tags.rs and frontend/tests/tags.test.ts — copy its string literals byte for byte. */
    @Test fun `folds like the server and the web`() {
        listOf("ß" to "ß", "ẞ" to "ß", "İ" to "i", "ΟΔΟΣ" to "οδος", "Fahrräder" to "fahrrader", "Élan Vital" to "elan vital", "INFO" to "info")
            .forEach { (input, folded) -> assertEquals(folded, Tags.fold(input), "fold($input)") }
    }

    @Test fun `carries compares folded`() {
        assertTrue(Tags.carries(listOf("Winter"), "WINTER"))
        assertTrue(!Tags.carries(listOf("Winter"), "Summer"))
    }

    @Test fun `count by folded tag, most used spelling wins, ties alphabetical`() {
        val columns = listOf("""["Winter","Lease"]""", """["winter"]""", """["Winter"]""", "not json", "[]")
        assertEquals(listOf(TagCount("Winter", 3), TagCount("Lease", 1)), Tags.count(columns))
    }

    /** Parity with `api::tags::count` (src/api/tags.rs): sorted by count descending, ties alphabetical. */
    @Test fun `count comes back sorted by count then tag, like the server`() {
        val columns = listOf("""["b"]""", """["a"]""", """["c","c2"]""", """["c"]""")
        assertEquals(listOf(TagCount("c", 2), TagCount("a", 1), TagCount("b", 1), TagCount("c2", 1)), Tags.count(columns))
    }

    @Test fun `json round trips and bad text reads empty`() {
        val tags = listOf("a \"quoted\" tag", "Zweite")
        assertEquals(tags, Tags.fromJson(Tags.toJson(tags)))
        assertEquals(emptyList(), Tags.fromJson("not json"))
        assertEquals("[]", Tags.toJson(emptyList()))
    }

    @Test fun `wcag contrast`() {
        assertEquals(21.0, Tags.contrastRatio(0xFF000000, 0xFFFFFFFF), 0.1)
        assertEquals(4.48, Tags.contrastRatio(0xFF777777, 0xFFFFFFFF), 0.05)
    }
}
