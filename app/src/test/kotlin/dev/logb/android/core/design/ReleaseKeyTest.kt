package dev.logb.android.core.design

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * [ReleaseKey] exists so [AppSignature.isReleaseSigned] -- a blocking PackageManager call -- is
 * read lazily and cached, instead of a ViewModel constructor computing it eagerly on the main
 * thread. The signing check itself stays covered by [AppSignatureTest].
 */
@RunWith(RobolectricTestRunner::class)
class ReleaseKeyTest {
    @Test fun `isRelease matches AppSignature and is stable across reads`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val key = ReleaseKey(context)

        val first = key.isRelease
        assertEquals(AppSignature.isReleaseSigned(context), first)
        assertEquals(first, key.isRelease, "a lazy value must not change on a second read")
    }
}
