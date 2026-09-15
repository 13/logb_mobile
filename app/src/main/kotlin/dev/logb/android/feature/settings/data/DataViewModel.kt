package dev.logb.android.feature.settings.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.network.ApiException
import dev.logb.android.core.network.LogbApi
import dev.logb.android.core.network.UnauthorizedException
import dev.logb.android.core.network.dto.ImportCounts
import dev.logb.android.core.sync.SyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import javax.inject.Inject

/** Streams, both ways: an export or an import can be hundreds of megabytes; nothing is held whole in memory. */
object DataTransfer {
    private val ZIP: MediaType = "application/zip".toMediaType()

    suspend fun export(api: LogbApi, out: OutputStream): Long = api.exportAll().use { body -> body.byteStream().use { it.copyTo(out) } }

    fun zipBody(open: () -> InputStream?, length: Long): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType = ZIP
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) { openOrThrow(open).source().use { sink.writeAll(it) } }
    }

    /**
     * `ContentResolver.openInputStream` returns null, rather than throwing, when the provider has
     * nothing to hand back; a revoked `content://` grant surfaces as a [SecurityException] instead.
     * Both mean the same thing to the caller -- the picked document is gone -- so both become the
     * [FileNotFoundException] [classifyDataError] already maps to `data_file_gone`, instead of an
     * unclassified [NullPointerException] or a raw [SecurityException] escaping [writeTo].
     */
    internal fun openOrThrow(open: () -> InputStream?): InputStream =
        try {
            open() ?: throw FileNotFoundException("grant revoked")
        } catch (e: SecurityException) {
            throw FileNotFoundException(e.message)
        }

    fun exportFileName(today: LocalDate): String = "logb-export-$today.zip"
}

data class DataUiState(
    val busy: Busy? = null,
    val exported: Boolean = false,
    val imported: ImportCounts? = null,
    val pendingImport: Uri? = null,
    val error: DataError? = null,
    /** The server's message for [DataError.Other]; null for the other, already-localized kinds. */
    val errorMessage: String? = null,
)

enum class Busy { Exporting, Importing }
enum class DataError { Offline, TooLarge, FileGone, Other }

/**
 * `OpenableColumns.SIZE` is null, not absent, when the provider doesn't know the size: a bare
 * `getLong(0)` on a null column silently reads as 0, which [DataTransfer.zipBody] would then take
 * as "an empty file" rather than "unknown -- stream it chunked". An empty cursor (`moveToFirst()`
 * false) means the same: unknown.
 */
internal fun sizeOf(cursor: Cursor): Long = if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L

/**
 * What a failed export or import should show, and how. Handles everything except
 * [UnauthorizedException] (its own outcome: sign out) and [CancellationException] (never
 * classified -- always rethrown so the coroutine cancels cleanly). A 413 on import means the
 * archive is too large; a revoked `content://` grant ([SecurityException]) or a file that has
 * since vanished ([FileNotFoundException], an [IOException] but not a connectivity failure) both
 * read as "pick it again", never as "offline"; every other [IOException] really is offline; and
 * anything else keeps the server's own message.
 */
internal fun classifyDataError(e: Exception): Pair<DataError, String?> = when {
    e is ApiException -> (if (e.status == 413) DataError.TooLarge else DataError.Other) to e.message
    e is SecurityException || e is FileNotFoundException -> DataError.FileGone to null
    e is IOException -> DataError.Offline to null
    else -> DataError.Other to e.message
}

@HiltViewModel
class DataViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: ActiveAccount,
    private val sessions: SessionRepository,
    private val syncManager: SyncManager,
) : ViewModel() {
    private val _state = MutableStateFlow(DataUiState())
    val state: StateFlow<DataUiState> = _state.asStateFlow()

    /** A fast double tap must not run the export twice: `busy` is read and set in the same atomic update. */
    fun export(to: Uri) = viewModelScope.launch {
        var proceed = false
        _state.update { s -> if (s.busy != null) return@update s; proceed = true; DataUiState(busy = Busy.Exporting) }
        if (!proceed) return@launch
        try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(to)!!.use { out -> DataTransfer.export(accounts.api, out) }
            }
            _state.update { it.copy(busy = null, exported = true) }
        } catch (e: CancellationException) {
            // Leaving the screen cancels an in-flight export: the partial document it leaves
            // behind is cleaned up before the cancellation propagates.
            deleteQuietly(to)
            throw e
        } catch (e: Exception) {
            deleteQuietly(to)
            handleFailure(e)
        }
    }

    /** Asks first: import adds everything, and a second import duplicates. */
    fun askImport(from: Uri) = _state.update { s -> if (s.busy != null) s else DataUiState(pendingImport = from) }

    fun cancelImport() = _state.update { it.copy(pendingImport = null) }

    /** Same atomic busy check as [export]: a fast double tap on Import must not run the upload twice. */
    fun confirmImport() = viewModelScope.launch {
        var from: Uri? = null
        _state.update { s ->
            val pending = s.pendingImport
            if (s.busy != null || pending == null) return@update s
            from = pending
            DataUiState(busy = Busy.Importing)
        }
        val uri = from ?: return@launch
        try {
            val counts = withContext(Dispatchers.IO) {
                val length = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> sizeOf(c) } ?: -1L
                accounts.api.importZip(DataTransfer.zipBody({ context.contentResolver.openInputStream(uri) }, length))
            }
            accounts.db.syncStateDao().requestBootstrap()
            _state.update { it.copy(busy = null, imported = counts) }
            syncManager.syncNow()
        } catch (e: CancellationException) {
            // Leaving the screen cancels an in-flight import too; nothing local was written, so
            // there is nothing to clean up here.
            throw e
        } catch (e: Exception) {
            handleFailure(e)
        }
    }

    private suspend fun handleFailure(e: Exception) {
        if (e is UnauthorizedException) {
            sessions.onUnauthorized()
            _state.update { it.copy(busy = null) }
            return
        }
        val (error, message) = classifyDataError(e)
        _state.update { it.copy(busy = null, error = error, errorMessage = message) }
    }

    /** Best-effort: the document may already be gone, or the grant revoked -- either way, nothing more to do. */
    private fun deleteQuietly(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }
}
