package dev.logb.android.feature.settings

import dev.logb.android.core.auth.Session
import dev.logb.android.core.network.dto.User
import dev.logb.android.core.prefs.Appearance
import dev.logb.android.core.prefs.ThemeMode
import dev.logb.android.core.sync.SyncStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsRowsTest {
    private val signedIn = Session.SignedIn("https://x/", User(1, "ben"), "t", "EUR")

    @Test
    fun `rows carry their current value`() {
        val rows = settingsRows(signedIn, Appearance(ThemeMode.Dark, false), SyncStatus.Offline(pending = 3), "de", "0.1.0")
        assertEquals(listOf(SettingsPage.Appearance, SettingsPage.Account, SettingsPage.Sync, SettingsPage.About), rows.map { it.page })
        assertEquals("dark · DE", rows[0].value)
        assertEquals("ben", rows[1].value)
        assertEquals("pending:3", rows[2].value)
        assertEquals("0.1.0", rows[3].value)
    }

    @Test
    fun `a value not yet known renders as nothing, never a placeholder`() {
        val rows = settingsRows(Session.Loading, Appearance(), SyncStatus.None, "en", "0.1.0")
        assertNull(rows[1].value)
        assertNull(rows[2].value)
    }
}
