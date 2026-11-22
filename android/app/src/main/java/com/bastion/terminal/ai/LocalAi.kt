package com.bastion.terminal.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Honest, user-visible state of the local AI. */
sealed class AiState {
    data object NoModel : AiState()
    data class Importing(val copiedBytes: Long, val totalBytes: Long?) : AiState()
    /** Model file present; weights load on first use. */
    data class Ready(val file: File) : AiState()
    data class Loading(val file: File) : AiState()
    /** [note]: e.g. why AUTO fell back from GPU to CPU (plain words), else null. */
    data class Loaded(val file: File, val label: String, val loadSeconds: Double, val note: String? = null) : AiState()
    data class Failed(val file: File?, val reason: String) : AiState()
}

/**
 * App-wide owner of the model file and the loaded runtime. Model files are
 * managed assets: imported by the user (Android file picker) into app-private
 * storage, never bundled in the APK, never committed to git, never downloaded
 * by the app (BASTION never connects to the internet).
 */
class LocalAi private constructor(private val app: Context) {

    private val _state = MutableStateFlow<AiState>(AiState.NoModel)
    val state: StateFlow<AiState> = _state

    private val lock = Mutex()
    private var model: LocalModel? = null

    private val prefs = app.getSharedPreferences("local_ai", Context.MODE_PRIVATE)

    /** Saved compute-unit preference (default AUTO: GPU if it starts, else CPU). */
    fun backend(): ModelBackend =
        runCatching { ModelBackend.valueOf(prefs.getString("backend", null) ?: "AUTO") }.getOrDefault(ModelBackend.AUTO)

    /** Saves the preference; a loaded model is unloaded so the next answer uses it. */
    suspend fun setBackend(b: ModelBackend) = lock.withLock {
        prefs.edit().putString("backend", b.name).apply()
        unloadLocked()
    }

    private fun modelDir(): File = File(app.filesDir, "models").apply { mkdirs() }

    fun currentModelFile(): File? =
        modelDir().listFiles { f -> f.isFile && f.name.endsWith(MODEL_EXT) }?.maxByOrNull { it.lastModified() }

    /** Re-derives state from disk (call at startup). Does not load weights. */
    fun refresh() {
        if (model != null) return
        val s = _state.value
        if (s is AiState.Importing || s is AiState.Loading) return
        _state.value = currentModelFile()?.let { AiState.Ready(it) } ?: AiState.NoModel
    }

    /**
     * Copies a picked .litertlm file into app storage, streaming (the file is
     * GBs; it is never held in memory). Replaces any previous model.
     */
    suspend fun importModel(uri: Uri): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val resolver = app.contentResolver
            var name: String? = null
            var size: Long? = null
            runCatching {
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            }
            val fileName = name ?: return@withLock "Could not read the selected file's name."
            if (!fileName.endsWith(MODEL_EXT)) {
                return@withLock "Not a LiteRT-LM model: expected a $MODEL_EXT file, got \"$fileName\"."
            }
            val free = modelDir().usableSpace
            size?.let { if (it > free - SPACE_MARGIN) return@withLock "Not enough storage: model needs ${it / 1_000_000} MB, ${free / 1_000_000} MB free." }

            unloadLocked()
            val tmp = File(modelDir(), "$fileName.part")
            val target = File(modelDir(), fileName)
            try {
                _state.value = AiState.Importing(0, size)
                val input = resolver.openInputStream(uri) ?: return@withLock "Could not open the selected file."
                input.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - lastReport >= 32L shl 20) {
                                lastReport = copied
                                _state.value = AiState.Importing(copied, size)
                            }
                        }
                    }
                }
                // One model at a time: remove older ones to save space.
                modelDir().listFiles { f -> f.name.endsWith(MODEL_EXT) && f.name != fileName }?.forEach { it.delete() }
                if (!tmp.renameTo(target)) {
                    target.delete()
                    check(tmp.renameTo(target)) { "rename failed" }
                }
                _state.value = AiState.Ready(target)
                "Imported model “$fileName” (${target.length() / 1_000_000} MB). It loads on first question."
            } catch (t: Throwable) {
                tmp.delete()
                _state.value = currentModelFile()?.let { AiState.Ready(it) } ?: AiState.NoModel
                "Model import failed: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    /** Returns a loaded model, loading it first if needed. Null + Failed state on error. */
    suspend fun obtain(): LocalModel? = lock.withLock {
        model?.let { return@withLock it }
        val file = currentModelFile() ?: run {
            _state.value = AiState.NoModel
            return@withLock null
        }
        _state.value = AiState.Loading(file)
        val m = LiteRtLmModel(file, File(app.cacheDir, "litertlm").apply { mkdirs() }, backend())
        try {
            m.load()
            model = m
            _state.value = AiState.Loaded(
                file, m.label, m.lastLoadSeconds(),
                m.fallbackReason?.let { "GPU unavailable, using CPU ($it)" },
            )
            m
        } catch (t: Throwable) {
            runCatching { m.close() }
            _state.value = AiState.Failed(file, "Model failed to load: ${t.message ?: t.javaClass.simpleName}")
            null
        }
    }

    suspend fun unload() = lock.withLock { unloadLocked() }

    private fun unloadLocked() {
        model?.close()
        model = null
        _state.value = currentModelFile()?.let { AiState.Ready(it) } ?: AiState.NoModel
    }

    companion object {
        const val MODEL_EXT = ".litertlm"
        private const val SPACE_MARGIN = 200L * 1_000_000

        @Volatile
        private var inst: LocalAi? = null

        fun get(context: Context): LocalAi =
            inst ?: synchronized(this) {
                inst ?: LocalAi(context.applicationContext).also { it.refresh(); inst = it }
            }
    }
}
