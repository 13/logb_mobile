package dev.logb.android.feature.stats

import dev.logb.android.core.domain.SpendStats.ObjectNode
import org.junit.Test
import kotlin.test.assertEquals

class TreeTest {
    private fun node(id: String, cents: Long, vararg kids: ObjectNode) = ObjectNode(id, id, "other", false, cents, kids.toList())

    @Test fun `flattening shows children only under expanded ancestors`() {
        val tree = listOf(node("house", 100, node("garage", 40, node("bulb", 5))), node("car", 50))
        assertEquals(listOf("house" to 0, "car" to 0), flattenTree(tree, emptySet()).map { it.node.id to it.depth })
        assertEquals(listOf("house", "garage", "car"), flattenTree(tree, setOf("house")).map { it.node.id })
        assertEquals(listOf("house", "garage", "bulb", "car"), flattenTree(tree, setOf("house", "garage")).map { it.node.id })
        assertEquals(listOf("house", "car"), flattenTree(tree, setOf("garage")).map { it.node.id }, "a collapsed parent hides its open child")
        val garage = flattenTree(tree, setOf("house")).first { it.node.id == "garage" }
        assertEquals(true to false, garage.hasChildren to garage.expanded)
    }

    @Test fun `share rounds to whole percent and is zero without a total`() {
        assertEquals(33, sharePct(1, 3))
        assertEquals(67, sharePct(2, 3))
        assertEquals(0, sharePct(5, 0))
    }
}
