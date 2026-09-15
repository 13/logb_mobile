package dev.logb.android.feature.settings.data

import android.content.Context
import android.net.Uri
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import javax.inject.Inject

/** Streams, both ways: an export or an import can be hundreds of megabytes; nothing is held whole in memory. */
object DataTransfer {
    private val ZIP: MediaType = "application/zip".toMediaType()

    suspend fun export(api: LogbApi, out: OutputStream): Long = api.exportAll().use { body -> body.byteStream().use { it.copyTo(out) } }

    fun zipBody(open: () -> InputStream, length: Long): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType = ZIP
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) { open().source().use { sink.writeAll(it) } }
    }

    fun exportFileName(today: LocalDate): String = "logb-export-$today.zip"
}

data class DataUiState(
    val busy: Busy? = null,
    val exported: Boolean = false,
    val imported: ImportCounts? = null,
    val pendingImport: Uri? = null,
    val error: DataError? = null,
)

enum class Busy { Exporting, Importing }
enum class DataError { Offline, TooLarge, Other }

@HiltViewModel
class DataViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: ActiveAccount,
    private val sessions: SessionRepository,
    private val syncManager: SyncManager,
) : ViewModel() {
    private val _state = MutableStateFlow(DataUiState())
    val state: StateFlow<DataUiState> = _state.asStateFlow()

    /** The server's message for the last [DataError.Other]: memory only, not part of [state]. */
    var lastErrorMessage: String? = null
        private set

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
        } catch (e: UnauthorizedException) {
            sessions.onUnauthorized()
            _state.update { it.copy(busy = null) }
        } catch (e: ApiException) {
            lastErrorMessage = e.message
            _state.update { it.copy(busy = null, error = DataError.Other) }
        } catch (e: IOException) {
            _state.update { it.copy(busy = null, error = DataError.Offline) }
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
                val length = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
                accounts.api.importZip(DataTransfer.zipBody({ context.contentResolver.openInputStream(uri)!! }, length))
            }
            accounts.db.syncStateDao().requestBootstrap()
            _state.update { it.copy(busy = null, imported = counts) }
            syncManager.syncNow()
        } catch (e: UnauthorizedException) {
            sessions.onUnauthorized()
            _state.update { it.copy(busy = null) }
        } catch (e: ApiException) {
            lastErrorMessage = e.message
            _state.update { it.copy(busy = null, error = if (e.status == 413) DataError.TooLarge else DataError.Other) }
        } catch (e: IOException) {
            _state.update { it.copy(busy = null, error = DataError.Offline) }
        }
    }
}
