package dev.logb.android.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

/**
 * An in-memory mirror for JVM tests, on Robolectric's framework SQLite. The bundled driver the
 * app uses ships natives for Android ABIs only, so tests run on the SQLite Robolectric provides;
 * every query here is plain SQL both accept (recursive CTEs included). A test class using this
 * runs with `@RunWith(RobolectricTestRunner::class)`.
 */
object TestDatabase {
    fun inMemory(): LogbDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LogbDatabase::class.java)
            .allowMainThreadQueries()
            .build()
}
