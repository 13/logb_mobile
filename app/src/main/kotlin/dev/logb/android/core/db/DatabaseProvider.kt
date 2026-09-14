package dev.logb.android.core.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens the mirror for one account. A household phone that switches accounts keeps each
 * person's mirror in its own file, named by server and user, so neither sees the other's.
 */
@Singleton
class DatabaseProvider @Inject constructor(@ApplicationContext private val context: Context) {
    fun fileName(serverUrl: String, userId: Long): String = "logb-${sha256Hex(serverUrl).take(8)}-$userId.db"

    fun open(serverUrl: String, userId: Long): LogbDatabase =
        Room.databaseBuilder(context, LogbDatabase::class.java, fileName(serverUrl, userId))
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    fun delete(serverUrl: String, userId: Long): Boolean = context.deleteDatabase(fileName(serverUrl, userId))

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
