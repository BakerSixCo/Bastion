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

/** What an opened ZIM is, for honest display and citations. [file] is its absolute path (the ZIM's key). */
data class ZimInfo(
    val title: String,
    val fileName: String,
    val date: String?,
    val articleCount: Int,
    val sizeBytes: Long,
    val hasFulltextIndex: Boolean,
    val file: String = "",
    val onSdCard: Boolean = false,
)

sealed class ZimState {
    data object NoZim : ZimState()
    data class Copying(val copiedBytes: Long, val totalBytes: Long?) : ZimState()
    data object Opening : ZimState()
    /** At least one ZIM is searchable. [problems]: files that could not be used, in plain words. */
    data class Ready(val zims: List<ZimInfo>, val problems: List<String> = emptyList()) : ZimState()
    data class Failed(val reason: String) : ZimState()
}

/** A search/title hit. [zim] is the ZIM's file path (which archive the [path] belongs to). */
data class ZimHit(val title: String, val path: String, val score: Int = 0, val zim: String = "")
data class ZimArticle(val title: String, val path: String, val text: String, val zim: String = "")

/** Where a new ZIM can be copied: internal shared storage or a removable SD card. */
data class ZimFolder(val dir: File, val removable: Boolean) {
    val label: String get() = if (removable) "SD card" else "phone storage"
}

/**
 * Offline reference ZIMs (e.g. Kiwix Wikipedia), kept in BASTION's own
 * reference folders — `Android/media/com.bastion.terminal/zim/` on phone
 * storage **and on each SD card** — and opened **by path**. Every ZIM found
 * there is opened and searched together.
 *
 * Why folders and not the picked file in place: libzim's full-text search
 * (Xapian) re-opens the file by path ("/dev/fd/N"), which Android's scoped
 * storage denies for file-picker descriptors (seen on emulator 2026-11-21).
 * App-specific folders need no permission (including on SD cards, via
 * `externalMediaDirs`). Same approach as Kiwix's Play build. Large files
 * (tens of GB) can be placed in a folder over USB instead of being copied by
 * the app. Read-only; no network.
 */
class ZimLibrary private constructor(private val app: Context) {

    private val _state = MutableStateFlow<ZimState>(ZimState.NoZim)
    val state: StateFlow<ZimState> = _state

    private class Open(val file: File, val archive: Archive, val searcher: Searcher?, val info: ZimInfo)

    private val lock = Mutex()
    private val open = LinkedHashMap<String, Open>()
    private val problems = LinkedHashMap<String, String>()
    private val articleCache = object : LinkedHashMap<String, ZimArticle>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ZimArticle>?) = size > 24
    }

    /** All of BASTION's reference folders: phone storage first, then any SD cards. */
    fun folders(): List<ZimFolder> {
        val dirs = app.externalMediaDirs.filterNotNull()
        val out = dirs.mapIndexed { i, d ->
            val removable = runCatching { android.os.Environment.isExternalStorageRemovable(d) }.getOrDefault(i > 0)
            ZimFolder(File(d, "zim").apply { mkdirs() }, removable)
        }.filter { it.dir.isDirectory }
        return out.ifEmpty { listOf(ZimFolder(File(app.getExternalFilesDir(null) ?: app.filesDir, "zim").apply { mkdirs() }, false)) }
    }

    /** The phone-storage reference folder (shown in messages). */
    fun folder(): File = folders().first().dir

    fun sdFolder(): ZimFolder? = folders().firstOrNull { it.removable }

    private fun zimsInFolders(): List<File> = folders().flatMap { f ->
        f.dir.listFiles { x -> x.isFile && x.name.endsWith(".zim", ignoreCase = true) }?.toList().orEmpty()
    }

    /** Opens every ZIM in the reference folders not yet open (startup, after USB copies). */
    suspend fun restore() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (_state.value is ZimState.Copying) return@withLock
            val present = zimsInFolders().map { it.absolutePath }.toSet()
            // Files deleted over USB: close them.
            (open.keys - present).forEach { closeLocked(it) }
            problems.clear() // Rescan retries files that failed before.
            val toOpen = zimsInFolders().filter { it.absolutePath !in open && it.absolutePath !in problems }
                .sortedByDescending { it.length() }
            if (toOpen.isNotEmpty()) _state.value = ZimState.Opening
            toOpen.forEach { openLocked(it) }
            publishLocked()
        }
    }

    /**
     * Copies a picked .zim into a reference folder (streamed, with progress
     * and a free-space check), then opens it alongside the others. A file of
     * the same name is replaced. [toSdCard] uses the SD card's folder if one
     * exists.
     */
    suspend fun add(uri: Uri, toSdCard: Boolean = false): String = withContext(Dispatchers.IO) {
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
            val dest = (if (toSdCard) sdFolder() else null) ?: folders().first()
            val dir = dest.dir
            size?.let {
                if (it > dir.usableSpace - SPACE_MARGIN) {
                    val hint = if (dest.removable) "Use a larger SD card, or Add ZIM to phone storage."
                    else "Free up space" + (sdFolder()?.let { ", or Add to SD card" } ?: "") + "."
                    return@withLock "Not enough space on ${dest.label} to copy it: needs ${it / 1_000_000} MB, " +
                        "${dir.usableSpace / 1_000_000} MB free. $hint"
                }
            }
            val tmp = File(dir, "$fileName.part")
            val target = File(dir, fileName)
            // Replacing a file of the same name: close it first.
            closeLocked(target.absolutePath)
            problems.remove(target.absolutePath)
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
                target.delete()
                check(tmp.renameTo(target)) { "rename failed" }
            } catch (t: Throwable) {
                tmp.delete()
                publishLocked()
                return@withLock "Copy failed: ${t.message ?: t.javaClass.simpleName}"
            }
            _state.value = ZimState.Opening
            openLocked(target)
            publishLocked()
            open[target.absolutePath]?.info?.let {
                if (it.hasFulltextIndex) "Added “${it.title}” (${"%,d".format(it.articleCount)} articles) on ${dest.label}."
                else "Added “${it.title}”, but it has no full-text index, so Ask cannot search it."
            } ?: problems[target.absolutePath] ?: "Could not open the ZIM file."
        }
    }

    /** Closes and deletes BASTION's copy of one ZIM (any original elsewhere is untouched). */
    suspend fun remove(file: String): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val f = File(file)
            if (folders().none { f.parentFile?.absolutePath == it.dir.absolutePath }) return@withLock "Not one of BASTION's ZIMs."
            val title = open[file]?.info?.title ?: f.name
            closeLocked(file)
            problems.remove(file)
            val ok = f.delete()
            publishLocked()
            if (ok) "Removed “$title” (BASTION's copy). Any original file elsewhere is untouched." else "Could not delete ${f.name}."
        }
    }

    fun currentInfos(): List<ZimInfo> = (state.value as? ZimState.Ready)?.zims.orEmpty()

    fun infoFor(file: String): ZimInfo? = currentInfos().firstOrNull { it.file == file }

    /**
     * Full-text search (each ZIM's own Xapian index), up to [max] hits per ZIM.
     * All key terms together first (trusted). If that finds too little, the
     * question is loosened: each pair of neighbouring terms is searched on its
     * own ("head gasket", "jeep wrangler") and articles are ranked by how many
     * pairs they match. A long question rarely has every word in one article:
     * "repair blown head gasket 2009 jeep wrangler" found nothing at all
     * (2026-11-22), and libzim does not treat "OR" as an operator, so the old
     * any-term fallback never loosened anything. Looser hits are kept only when
     * the article *title* contains a key term, so an antenna question doesn't
     * pull in "Photosynthesis" (seen on emulator).
     */
    suspend fun search(terms: List<String>, max: Int = 5): List<ZimHit> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (terms.isEmpty()) return@withLock emptyList()
            open.values.flatMap { o ->
                val s = o.searcher ?: return@flatMap emptyList()
                // Of the articles holding every term, prefer those that say so in the title:
                // on a Q&A reference "How do I jump start a car" beats a long thread that
                // merely mentions jump leads (seen on emulator 2026-11-22).
                val strict = runSearch(s, terms.joinToString(" "), max * 4, o.info.file)
                    .sortedByDescending { titleCoverage(it.title, terms) }
                    .take(max)
                if (strict.size >= max || terms.size < 2) return@flatMap strict
                val queries = if (terms.size == 2) terms.map { listOf(it) } else terms.zipWithNext { x, y -> listOf(x, y) }
                val votes = LinkedHashMap<String, Pair<ZimHit, Int>>()
                for (q in queries) {
                    for (hit in runSearch(s, q.joinToString(" "), max, o.info.file)) {
                        if (!titleMatches(hit.title, terms)) continue
                        val prev = votes[hit.path]
                        votes[hit.path] = (prev?.first ?: hit) to (prev?.second ?: 0) + 1
                    }
                }
                val loose = votes.values
                    .sortedWith(compareByDescending<Pair<ZimHit, Int>> { it.second }.thenByDescending { it.first.score })
                    .map { it.first }
                (strict + loose).distinctBy { it.path }.take(max)
            }
        }
    }

    /**
     * Exact article-title lookup in every ZIM ("burn" → *Burn*, "CPR" →
     * redirect → *Cardiopulmonary resuscitation*). Full-text ranking alone can
     * bury the main article under longer ones that repeat the words ("First
     * aid kit" for "first aid for a burn" — seen on emulator). Redirects are
     * followed; only HTML articles count. Up to [max] per ZIM.
     */
    suspend fun titleLookup(phrases: List<String>, max: Int = 2): List<ZimHit> = withContext(Dispatchers.IO) {
        lock.withLock {
            val all = ArrayList<ZimHit>()
            for (o in open.values) {
                val a = o.archive
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
                            else followHtmlRedirect(a, e).let { t -> ZimHit(t.title, t.path, 100, o.info.file) }
                        }.getOrNull()
                        if (hit != null) {
                            if (out.none { it.path == hit.path }) out += hit
                            break
                        }
                    }
                }
                all += out
            }
            android.util.Log.i("BastionZim", "titleLookup $phrases → ${all.map { it.title }}")
            all
        }
    }

    /**
     * Some ZIMs store a redirect as a tiny HTML page (meta refresh) instead of
     * a real redirect entry: in WikiMed "Sucking chest wound" is a 19-character
     * stub pointing at "Pneumothorax". Reading the stub gave the AI nothing
     * (found 2026-11-22). Follows up to 3 such hops; returns [e] if it is a
     * normal article.
     */
    private fun followHtmlRedirect(a: Archive, e: org.kiwix.libzim.Entry): org.kiwix.libzim.Entry {
        var cur = e
        repeat(3) {
            val item = runCatching { cur.getItem(true) }.getOrNull() ?: return cur
            if (!item.mimetype.startsWith("text/html") || item.size > 4096) return cur
            val target = HtmlText.redirectTarget(String(item.data.data, Charsets.UTF_8)) ?: return cur
            val base = cur.path.substringBeforeLast('/', "")
            val candidates = listOf(
                target, if (base.isEmpty()) target else "$base/$target", target.replace('_', ' '),
            )
            cur = candidates.firstNotNullOfOrNull { p ->
                runCatching { a.getEntryByPath(p) }.getOrNull() ?: runCatching { a.getEntryByTitle(p) }.getOrNull()
            } ?: return cur
            var hops = 0
            while (cur.isRedirect && hops++ < 5) cur = cur.redirectEntry
        }
        return cur
    }

    private val LISTING = Regex("^(questions/tagged|tags|users|search)([/_?]|$)")
    /** Translated copies of an article ("Rocket stove/th"): the AI answers in English. */
    private val TRANSLATION = Regex("/[a-z]{2}(-[a-z]{2,4})?$")

    private fun titleMatches(title: String, terms: List<String>): Boolean = titleCoverage(title, terms) > 0

    /** How many of [terms] the title contains (as word prefixes). */
    private fun titleCoverage(title: String, terms: List<String>): Int {
        val words = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        return terms.count { t -> words.any { it.startsWith(t) } }
    }

    private fun runSearch(s: Searcher, q: String, max: Int, zim: String): List<ZimHit> = runCatching {
        val it = s.search(Query(q)).getResults(0, max)
        val out = ArrayList<ZimHit>()
        while (it.hasNext()) {
            val score = runCatching { it.score }.getOrDefault(0)
            val e = it.next()
            // Index pages (a Q&A site's tag and user listings) are lists of links, not answers.
            if (LISTING.containsMatchIn(e.path) || TRANSLATION.containsMatchIn(e.path)) continue
            out.add(ZimHit(e.title, e.path, score, zim))
        }
        android.util.Log.i("BastionZim", "query=[$q] zim=${File(zim).name} results=${out.size}")
        out
    }.getOrDefault(emptyList())

    /** Article as plain text with Markdown headings (cached). [zim] is the ZIM's file path. */
    suspend fun article(zim: String, path: String): ZimArticle? = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = "$zim|$path"
            articleCache[key]?.let { return@withLock it }
            val a = open[zim]?.archive ?: return@withLock null
            runCatching {
                val entry = followHtmlRedirect(a, a.getEntryByPath(path))
                val item = entry.getItem(true)
                if (!item.mimetype.startsWith("text/html")) return@runCatching null
                ZimArticle(entry.title, path, HtmlText.extract(String(item.data.data, Charsets.UTF_8)), zim)
            }.getOrNull()?.also { articleCache[key] = it }
        }
    }

    private fun openLocked(file: File) {
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
                file = file.absolutePath,
                onSdCard = folders().any { it.removable && file.parentFile?.absolutePath == it.dir.absolutePath },
            )
            if (!zi.hasFulltextIndex) {
                problems[file.absolutePath] = "“${zi.title}” (${file.name}) has no full-text index, so it cannot be searched."
                return
            }
            open[file.absolutePath] = Open(file, a, Searcher(a), zi)
        } catch (t: Throwable) {
            problems[file.absolutePath] = "Could not open ${file.name}: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun closeLocked(key: String) {
        open.remove(key)?.let { runCatching { it.searcher?.dispose() } }
        articleCache.keys.removeAll { it.startsWith("$key|") }
    }

    private fun publishLocked() {
        _state.value = when {
            open.isNotEmpty() -> ZimState.Ready(open.values.map { it.info }, problems.values.toList())
            problems.isNotEmpty() -> ZimState.Failed(problems.values.joinToString("\n"))
            else -> ZimState.NoZim
        }
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
