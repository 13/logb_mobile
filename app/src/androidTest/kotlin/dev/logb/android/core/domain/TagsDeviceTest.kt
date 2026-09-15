package dev.logb.android.core.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Runs on the real, on-device Android regex engine (ICU-backed), unlike [TagsTest] which runs
 * under Robolectric on the host JVM's `java.util.regex`. Guards against engine-only regressions
 * in [Tags.spaces] such as the `(?U)` inline flag Android's engine rejects at runtime with a
 * `PatternSyntaxException` -- invisible to every JVM-only unit test.
 */
@RunWith(AndroidJUnit4::class)
class TagsDeviceTest {
    @Test
    fun collapsesNbspIdeographicAndThinSpacesOnDevice() {
        assertEquals(AddResult.Added(listOf("A B")), Tags.addTag(emptyList(), "A  B"), "NBSP")
        assertEquals(AddResult.Added(listOf("A B")), Tags.addTag(listOf("A B"), "A　B"), "ideographic space")
        assertEquals(AddResult.Added(listOf("A B")), Tags.addTag(listOf("A B"), "A B"), "thin space")
        assertEquals(AddResult.Added(listOf("A B")), Tags.addTag(emptyList(), "A   B"), "runs of ASCII spaces")
    }

    @Test
    fun colorIndexMatchesTheKnownSlotOnDevice() {
        assertEquals(4, Tags.colorIndex("Winter"))
    }
}
