package dev.logb.android.feature.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ApkInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val installer = ApkInstaller(context)

    @Test fun `a failed write abandons the session instead of leaking it`() {
        assertFailsWith<java.io.IOException> { installer.install(File(context.cacheDir, "missing.apk")) }

        assertTrue(context.packageManager.packageInstaller.allSessions.isEmpty())
    }
}
