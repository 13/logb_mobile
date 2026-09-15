package dev.logb.android.feature.settings.data

import android.database.MatrixCursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** `OpenableColumns.SIZE` behaves like any other column: a real cursor, not a stub, is worth testing against. */
@RunWith(AndroidJUnit4::class)
class CursorSizeTest {
    @Test fun `a null size column means unknown, not zero`() {
        val cursor = MatrixCursor(arrayOf("size")).apply { addRow(arrayOf<Any?>(null)) }
        assertEquals(-1L, sizeOf(cursor))
    }

    @Test fun `a known size is read through`() {
        val cursor = MatrixCursor(arrayOf("size")).apply { addRow(arrayOf<Any?>(1234L)) }
        assertEquals(1234L, sizeOf(cursor))
    }

    @Test fun `an empty cursor means unknown too`() {
        val cursor = MatrixCursor(arrayOf("size"))
        assertEquals(-1L, sizeOf(cursor))
    }
}
