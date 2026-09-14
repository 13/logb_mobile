package dev.logb.android.core.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class TokenStoreTest {
    @Test
    fun aTokenRoundTripsThroughTheKeystoreAndClears() = runBlocking {
        val store = KeystoreTokenStore(ApplicationProvider.getApplicationContext())
        store.write("logb_pat_secret")
        assertEquals("logb_pat_secret", store.read())
        store.clear()
        assertNull(store.read())
    }
}
