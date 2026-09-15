package dev.logb.android.feature.settings.tokens

import dev.logb.android.core.network.dto.ApiToken
import org.junit.Test
import kotlin.test.assertEquals

class TokensViewModelTest {
    private fun token(id: Long) = ApiToken(id, "t$id", "logb_pat_$id", "2026-09-01T00:00:00Z", null)

    @Test fun `the phone's own token is marked and keeps the server's order`() {
        val rows = TokenRows.of(listOf(token(12), token(9), token(3)), phoneTokenId = 9)
        assertEquals(listOf(12L, 9L, 3L), rows.map { it.token.id })
        assertEquals(listOf(false, true, false), rows.map { it.isThisPhone })
    }

    @Test fun `no known phone token marks nothing`() =
        assertEquals(listOf(false), TokenRows.of(listOf(token(1)), phoneTokenId = null).map { it.isThisPhone })

    @Test fun `token names are trimmed and 1 to 64 characters`() {
        assertEquals(null, TokenRows.validName("   "))
        assertEquals("script", TokenRows.validName("  script "))
        assertEquals(null, TokenRows.validName("x".repeat(65)))
        assertEquals("é".repeat(64), TokenRows.validName("é".repeat(64)))
    }
}
