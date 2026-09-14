package dev.logb.android.core.db

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * One write transaction on the writer connection. `room-ktx`'s `withTransaction` goes through
 * the SupportSQLite compatibility path, which a database opened with a driver does not have;
 * this is the driver-aware form, and DAO calls inside the block share the connection.
 */
suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
