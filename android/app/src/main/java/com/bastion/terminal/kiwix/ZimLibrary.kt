package com.bastion.terminal.kiwix

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kiwix.libkiwix.JNIKiwix
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import java.io.File

/** What the opened ZIM is, for honest display and citations. */
data class ZimInfo(
    val title: String,
    val fileName: String,
    val date: String?,
    val articleCount: Int,
    val sizeBytes: Long,
    val hasFulltextIndex: Boolean,
)

sealed class ZimState {
    data object NoZim : ZimState()
    data class Copying(val copiedBytes: Long, val totalBytes: Long?) : ZimState()
    data object Opening : ZimState()
    data class Ready(val info: ZimInfo) : ZimState()
    data class Failed(val reason: String) : ZimState()
}

data class ZimHit(val title: String, val path: String, val score: Int = 0)
data class ZimArticle(val title: String, val path: String, val text: String)

/**
 * One offline reference ZIM (e.g. Kiwix Wikipedia), kept in BASTION's own
 * reference folder — `Android/media/com.bastion.terminal/zim/` — and opened
 * **by path**.
 *
 * Why a folder and not the picked file in place: libzim's full-text search
 * (Xapian) re-opens the file by path ("/dev/fd/N"), which Android's scoped
 * storage denies for file-picker descriptors (seen on emulator 2026-11-21).
 * App-specific storage needs no permission. Same approach as Kiwix's Play
 * build. Large files (tens of GB) can be placed in the folder over USB
 * instead of being copied by the app. Read-only; no network.
 */
class ZimLibrary private constructor(private val app: Context) {

    private val _state = MutableStateFlow<ZimState>(ZimState.NoZim)
    val state: StateFlow<ZimState> = _state

    private val lock = Mutex()
    private var archive: Archive? = null
    private var searcher: Searcher? = null
    private var info: ZimInfo? = null
    private var openFile: File? = null
    private val articleCache = object : LinkedHashMap<String, ZimArticle>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ZimArticle>?) = size > 24
    }

    /** BASTION's reference folder (app-specific; visible over USB as Android/media/…/zim). */
    fun folder(): File {
        val base = app.externalMediaDirs.firstOrNull { it != null } ?: app.getExternalFilesDir(null) ?: app.filesDir
        return File(base, "zim").apply { mkdirs() }
    }

    private fun zimsInFolder(): List<File> =
        folder().listFiles { f -> f.isFile && f.name.endsWith(".zim", ignoreCase = true) }?.toList().orEmpty()

    /** Opens the ZIM in the reference folder, if any (call at startup, off the main thread). */
    suspend fun restore() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (archive != null) return@withLock
            if (_state.value is ZimState.Copying) return@withLock
            val f = zimsInFolder().maxByOrNull { it.length() }
            if (f == null) _state.value = ZimState.NoZim else openLocked(f)
        }
    }

    /**
     * Copies a picked .zim into the reference folder (streamed, with progress
     * and a free-space check), then opens it. Replaces any previous copy.
     */
    suspend fun add(uri: Uri): String = withContext(Dispatchers.IO) {
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
            if (!fileName.endsWith(".zim", ignoreCase = true)) {
                return@withLock "Not a ZIM file: expected .zim, got \"$fileName\"."
            }
            val dir = folder()
            size?.let { if (it > dir.usableSpace - SPACE_MARGIN) {
                return@withLock "Not enough storage to copy it: needs ${it / 1_000_000} MB, " +
                    "${dir.usableSpace / 1_000_000} MB free. Large ZIMs can be placed in ${dir.absolutePath} over USB instead."
            } }
            closeLocked()
            val tmp = File(dir, "$fileName.part")
            val target = File(dir, fileName)
            try {
                _state.value = ZimState.Copying(0, size)
                val input = resolver.openInputStream(uri) ?: return@withLock "Could not open the selected file."
                input.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L
                        var last = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - last >= 32L shl 20) { last = copied; _state.value = ZimState.Copying(copied, size) }
                        }
                    }
                }
                // One reference ZIM at a time (v1): remove other copies to save space.
                zimsInFolder().filter { it.name != fileName }.forEach { it.delete() }
                target.delete()
                check(tmp.renameTo(target)) { "rename failed" }
            } catch (t: Throwable) {
                tmp.delete()
                _state.value = ZimState.Failed("Copy failed: ${t.message ?: t.javaClass.simpleName}")
                return@withLock "Copy failed: ${t.message ?: t.javaClass.simpleName}"
            }
            openLocked(target)
            when (val s = _state.value) {
                is ZimState.Ready -> "Added “${s.info.title}” (${"%,d".format(s.info.articleCount)} articles)."
                is ZimState.Failed -> s.reason
                else -> "Could not open the ZIM file."
            }
        }
    }

    /** Closes and deletes BASTION's copy (the original picked file is untouched). */
    suspend fun remove(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val f = openFile
            closeLocked()
            val deleted = zimsInFolder().onEach { it.delete() }
            _state.value = ZimState.NoZim
            if (deleted.isEmpty() && f == null) "Nothing to remove."
            else "Removed BASTION's copy (${deleted.joinToString { it.name }}). Any original file elsewhere is untouched."
        }
    }

    fun currentInfo(): ZimInfo? = info

    /**
     * Full-text search (the ZIM's own Xapian index). All key terms together
     * first (trusted). If that finds too little, any term — but those looser
     * hits are kept only when the article *title* contains a key term, so an
     * antenna question doesn't pull in "Photosynthesis" (seen on emulator).
     */
    suspend fun search(terms: List<String>, max: Int = 5): List<ZimHit> = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = searcher ?: return@withLock emptyList()
            if (terms.isEmpty()) return@withLock emptyList()
            val strict = runSearch(s, terms.joinToString(" "), max)
            if (strict.size >= max) return@withLock strict
            val loose = runSearch(s, terms.joinToString(" OR "), max * 2)
                .filter { hit -> titleMatches(hit.title, terms) }
            (strict + loose).distinctBy { it.path }.take(max)
        }
    }

    /**
     * Exact article-title lookup ("burn" → *Burn*, "CPR" → redirect →
     * *Cardiopulmonary resuscitation*). Full-text ranking alone can bury the
     * main article under longer ones that repeat the words ("First aid kit"
     * for "first aid for a burn" — seen on emulator). Redirects are followed;
     * only HTML articles count.
     */
    suspend fun titleLookup(phrases: List<String>, max: Int = 2): List<ZimHit> = withContext(Dispatchers.IO) {
        lock.withLock {
            val a = archive ?: return@withLock emptyList()
            val out = ArrayList<ZimHit>()
            for (p in phrases) {
                if (out.size >= max) break
                val variants = linkedSetOf(
                    p.replaceFirstChar { it.uppercase() },
                    p.lowercase().replaceFirstChar { it.uppercase() },
                    p.uppercase(),
                )
                if (p.length > 4 && p.endsWith("s", ignoreCase = true)) variants += p.dropLast(1).replaceFirstChar { it.uppercase() }
                for (v in variants) {
                    val hit = runCatching {
                        if (!a.hasEntryByTitle(v)) return@runCatching null
                        var e = a.getEntryByTitle(v)
                        var hops = 0
                        while (e.isRedirect && hops++ < 5) e = e.redirectEntry
                        if (e.isRedirect || !e.getItem(true).mimetype.startsWith("text/html")) null
                        else ZimHit(e.title, e.path, 100)
                    }.getOrNull()
                    if (hit != null) {
                        if (out.none { it.path == hit.path }) out += hit
                        break
                    }
                }
            }
            android.util.Log.i("BastionZim", "titleLookup $phrases → ${out.map { it.title }}")
            out
        }
    }

    private fun titleMatches(title: String, terms: List<String>): Boolean {
        val words = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        return terms.any { t -> words.any { it.startsWith(t) } }
    }

    private fun runSearch(s: Searcher, q: String, max: Int): List<ZimHit> = runCatching {
        val it = s.search(Query(q)).getResults(0, max)
        val out = ArrayList<ZimHit>()
        var aligned = 0
        while (it.hasNext()) {
            val prePath = runCatching { it.path }.getOrNull()
            val preScore = runCatching { it.score }.getOrDefault(0)
            val e = it.next()
            if (prePath == e.path) aligned++
            out.add(ZimHit(e.title, e.path, preScore))
        }
        android.util.Log.i("BastionZim", "query=[$q] results=${out.size} iteratorAligned=$aligned")
        out
    }.getOrDefault(emptyList())

    /** Article as plain text with Markdown headings (cached). */
    suspend fun article(path: String): ZimArticle? = withContext(Dispatchers.IO) {
        lock.withLock {
            articleCache[path]?.let { return@withLock it }
            val a = archive ?: return@withLock null
            runCatching {
                val entry = a.getEntryByPath(path)
                val item = entry.getItem(true)
                if (!item.mimetype.startsWith("text/html")) return@runCatching null
                ZimArticle(entry.title, path, HtmlText.extract(String(item.data.data, Charsets.UTF_8)))
            }.getOrNull()?.also { articleCache[path] = it }
        }
    }

    private fun openLocked(file: File) {
        _state.value = ZimState.Opening
        try {
            ensureNative(app)
            val a = Archive(file.absolutePath)
            val zi = ZimInfo(
                title = meta(a, "Title") ?: file.name.removeSuffix(".zim"),
                fileName = file.name,
                date = meta(a, "Date"),
                articleCount = a.articleCount,
                sizeBytes = a.filesize,
                hasFulltextIndex = a.hasFulltextIndex(),
            )
            archive = a
            info = zi
            openFile = file
            if (!zi.hasFulltextIndex) {
                _state.value = ZimState.Failed("“${zi.title}” has no full-text index, so it cannot be searched.")
                return
            }
            searcher = Searcher(a)
            _state.value = ZimState.Ready(zi)
        } catch (t: Throwable) {
            closeLocked()
            _state.value = ZimState.Failed("Could not open ${file.name}: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun closeLocked() {
        runCatching { searcher?.dispose() }
        searcher = null
        archive = null
        info = null
        openFile = null
        articleCache.clear()
    }

    private fun meta(a: Archive, key: String): String? =
        runCatching { a.getMetadata(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private const val SPACE_MARGIN = 200L * 1_000_000

        @Volatile private var inst: ZimLibrary? = null
        @Volatile private var nativeLoaded = false

        fun get(context: Context): ZimLibrary =
            inst ?: synchronized(this) { inst ?: ZimLibrary(context.applicationContext).also { inst = it } }

        private fun ensureNative(context: Context) {
            if (nativeLoaded) return
            synchronized(this) {
                if (!nativeLoaded) {
                    JNIKiwix(context.applicationContext)
                    nativeLoaded = true
                }
            }
        }
    }
}
