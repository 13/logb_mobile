package dev.logb.android

import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

/** EN and DE ship the same keys, always; a string added to one language and not the other fails here, by name. */
class StringsParityTest {
    private fun keys(path: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val out = mutableSetOf<String>()
        for (tag in listOf("string", "plurals")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) out += "$tag/" + nodes.item(i).attributes.getNamedItem("name").nodeValue
        }
        return out
    }

    @Test
    fun `english and german declare the same keys`() {
        val en = keys("src/main/res/values/strings.xml")
        val de = keys("src/main/res/values-de/strings.xml")
        assertEquals(emptySet(), en - de, "missing in German")
        assertEquals(emptySet(), de - en, "missing in English")
    }
}
