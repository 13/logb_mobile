package dev.logb.android.feature.settings.data

import dev.logb.android.core.network.ApiException
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.assertEquals

class DataErrorsTest {
    @Test fun `a 413 on import means too large, with the server's own message`() {
        val (error, message) = classifyDataError(ApiException(413, "too_large", "archive too big"))
        assertEquals(DataError.TooLarge, error)
        assertEquals("archive too big", message)
    }

    @Test fun `any other server error keeps its own message, never offline`() {
        val (error, message) = classifyDataError(ApiException(400, "bad_zip", "not a zip archive"))
        assertEquals(DataError.Other, error)
        assertEquals("not a zip archive", message)
    }

    @Test fun `a revoked SAF grant reads as pick it again, not offline`() {
        val (error, message) = classifyDataError(SecurityException("Permission Denial"))
        assertEquals(DataError.FileGone, error)
        assertEquals(null, message)
    }

    @Test fun `a file that vanished is an IOException but still not offline`() {
        val (error, _) = classifyDataError(FileNotFoundException("gone"))
        assertEquals(DataError.FileGone, error)
    }

    @Test fun `a real connectivity failure is offline`() {
        val (error, message) = classifyDataError(IOException("Unable to resolve host"))
        assertEquals(DataError.Offline, error)
        assertEquals(null, message)
    }

    @Test fun `anything unrecognised keeps its message rather than hiding it`() {
        val (error, message) = classifyDataError(IllegalStateException("boom"))
        assertEquals(DataError.Other, error)
        assertEquals("boom", message)
    }
}
