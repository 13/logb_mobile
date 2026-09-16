package dev.logb.android.core.auth

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class DataStoreServerStoreTest {
    private val store = DataStoreServerStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `setVersion writes the version for the matching url and leaves the rest of the record intact`() = runTest {
        store.write(ServerRecord("https://logb.example/", userId = 1, username = "ben", tokenId = 9, currency = "CHF", serverVersion = "0.7.1"))

        store.setVersion("https://logb.example/", "0.8.0")

        val record = store.read()!!
        assertEquals("0.8.0", record.serverVersion)
        assertEquals(1, record.userId)
        assertEquals("ben", record.username)
        assertEquals(9, record.tokenId)
        assertEquals("CHF", record.currency)
    }

    @Test
    fun `setVersion for a url that is no longer the stored server is ignored`() = runTest {
        store.write(ServerRecord("https://logb.example/", serverVersion = "0.7.1"))

        store.setVersion("https://other.example/", "0.8.0")

        assertEquals("0.7.1", store.read()!!.serverVersion)
    }

    @Test
    fun `setVersion with a null version clears it for the matching url`() = runTest {
        store.write(ServerRecord("https://logb.example/", serverVersion = "0.7.1"))

        store.setVersion("https://logb.example/", null)

        assertNull(store.read()!!.serverVersion)
    }
}
