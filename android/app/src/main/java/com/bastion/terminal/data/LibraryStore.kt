package com.bastion.terminal.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DocMeta(
    val id: Long,
    val title: String,
    val filename: String,
    val importedAt: Long,
    val sizeBytes: Long,
    val provenance: String,
    /** The pack this document belongs to; null = the general library. */
    val packId: Long? = null,
) {
    /** Written on this phone with BASTION's editor (so it can be edited again). */
    val isNote: Boolean get() = provenance.startsWith(LibraryStore.NOTE_PROVENANCE)
}

/**
 * A named set of documents — a trip, a hunting plan, a bug-out plan, a contact list.
 * [inAsk]: the Ask tab answers from it. [meshOk]: questions arriving over the mesh may be
 * answered from it (off unless the owner switches it on).
 */
data class Pack(val id: Long, val name: String, val kind: String, val inAsk: Boolean, val meshOk: Boolean, val docCount: Int)

/** Who is asking: decides which packs may be used. */
enum class Scope { ASK, MESH, ALL }

data class SearchHit(val docId: Long, val title: String, val snippet: String)

sealed class ImportResult {
    data class Ok(val doc: DocMeta) : ImportResult()
    data class Rejected(val reason: String) : ImportResult()
}

/**
 * Local document store: original bytes under files/library/, extracted text
 * and metadata in SQLite with an FTS4 index. Everything on-device; all public
 * functions are suspend and run on Dispatchers.IO.
 */
class LibraryStore private constructor(
    private val app: Context,
    private val dbName: String = "bastion.db",
    private val libSubdir: String = "library",
) {

    /** Bumped after every successful import so screens can reload. */
    val changeCounter = MutableStateFlow(0)

    private val helper = object : SQLiteOpenHelper(app, dbName, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE docs(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL," +
                    "filename TEXT NOT NULL, imported_at INTEGER NOT NULL," +
                    "size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL UNIQUE," +
                    "provenance TEXT NOT NULL, pack_id INTEGER)"
            )
            db.execSQL("CREATE VIRTUAL TABLE doc_fts USING fts4(title, content)")
            createChunkTables(db)
            createPackTable(db)
        }

        /** v1 → v2: add passage chunks and backfill them from the preserved originals. */
        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            if (oldV < 2) {
                createChunkTables(db)
                val ids = db.rawQuery("SELECT id FROM docs", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0)) }
                }
                for (id in ids) {
                    val txt = File(libDir(), "$id.txt").takeIf { it.isFile } ?: File(libDir(), "$id.orig")
                    if (txt.isFile) insertChunks(db, id, String(txt.readBytes(), Charsets.UTF_8))
                }
            }
            // v2 → v3: packs. Existing documents stay in the general library (pack_id NULL).
            if (oldV < 3) {
                db.execSQL("ALTER TABLE docs ADD COLUMN pack_id INTEGER")
                createPackTable(db)
            }
        }
    }

    private fun createPackTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS packs(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL," +
                "kind TEXT NOT NULL, in_ask INTEGER NOT NULL DEFAULT 1, mesh_ok INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)"
        )
    }

    /**
     * Passage index (v2): `chunks` holds metadata, `chunk_fts` the passage
     * text (docid = chunks.id). Retrieval ranks these with BM25; the
     * whole-document `doc_fts` stays for the Library search and reader.
     */
    private fun createChunkTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS chunks(id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "doc_id INTEGER NOT NULL, ord INTEGER NOT NULL, heading TEXT, len INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS chunks_doc ON chunks(doc_id)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts4(content)")
    }

    private fun insertChunks(db: SQLiteDatabase, docId: Long, text: String) {
        for ((ord, ch) in Chunker.chunk(text).withIndex()) {
            val body = text.substring(ch.start, ch.end).trim()
            val chunkId = db.insertOrThrow("chunks", null, ContentValues().apply {
                put("doc_id", docId)
                put("ord", ord)
                put("heading", ch.heading)
                put("len", body.length)
            })
            db.insertOrThrow("chunk_fts", null, ContentValues().apply {
                put("docid", chunkId)
                put("content", body)
            })
        }
    }

    /** PDF text layer (PdfBox-Android). Pages and detected paragraphs become blank-line breaks for the chunker. */
    private fun pdfText(bytes: ByteArray): String {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
            val s = com.tom_roush.pdfbox.text.PDFTextStripper()
            s.sortByPosition = true
            s.paragraphEnd = "\n\n"
            s.pageEnd = "\n\n"
            return s.getText(doc)
        }
    }

    private fun libDir(): File = File(app.filesDir, libSubdir).apply { mkdirs() }

    suspend fun importFromUri(uri: Uri, packId: Long? = null): ImportResult = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        var name = "document.txt"
        var reportedSize: Long? = null
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (s >= 0 && !c.isNull(s)) reportedSize = c.getLong(s)
                }
            }
        }
        // Reject on the provider-reported size before reading anything.
        reportedSize?.let { if (it > TextUtil.MAX_IMPORT_BYTES) return@withContext tooLarge(it) }
        // The reported size can be missing or wrong, so the read itself is bounded too.
        val unreadable = ImportResult.Rejected("Could not read the selected file.")
        val stream = runCatching { resolver.openInputStream(uri) }.getOrNull()
            ?: return@withContext unreadable
        val bytes = runCatching { stream.use { TextUtil.readBounded(it, TextUtil.MAX_IMPORT_BYTES) } }
            .getOrElse { return@withContext unreadable }
            ?: return@withContext tooLarge(null)
        indexBytes(bytes, name, "Imported via Android file picker", packId)
    }

    private fun tooLarge(size: Long?): ImportResult.Rejected {
        val limit = TextUtil.formatSize(TextUtil.MAX_IMPORT_BYTES).replace(".0 ", " ")
        val shown = size?.let { TextUtil.formatSize(it) }
        return ImportResult.Rejected(
            when {
                shown == null -> "File is larger than the current $limit plain-text limit."
                // Rounding would print "20.0 MB" against a "20 MB" limit; say so plainly.
                shown == TextUtil.formatSize(TextUtil.MAX_IMPORT_BYTES) ->
                    "File is just over the current $limit plain-text limit."
                else -> "File is $shown; the current plain-text limit is $limit."
            }
        )
    }

    suspend fun importSample(): ImportResult = withContext(Dispatchers.IO) {
        val bytes = app.assets.open("sample_field_notes.md").use { it.readBytes() }
        indexBytes(bytes, "SAMPLE - Bastion field notes.md", "Bundled sample document (demo data, clearly labeled)")
    }

    /** Imports raw bytes through the same checks/indexing as the picker (tests and evals). */
    internal suspend fun importBytes(bytes: ByteArray, name: String, provenanceNote: String, packId: Long? = null): ImportResult =
        withContext(Dispatchers.IO) { indexBytes(bytes, name, provenanceNote, packId) }

    // ---- Kit documents: the docs folder and document bundles ----------------------------

    /** What the last kit import did, for the Home screen; blank when there is nothing to say. */
    val bulkStatus = MutableStateFlow("")
    private val bulkLock = Mutex()
    private val bulkPrefs by lazy { app.getSharedPreferences("library_bulk_$dbName", Context.MODE_PRIVATE) }

    /** BASTION's "docs" folders (phone storage and SD card): files copied here are added to the library. */
    fun docFolders(): List<File> =
        app.externalMediaDirs.filterNotNull().map { File(it, "docs").apply { mkdirs() } }.filter { it.isDirectory }

    /**
     * Adds what the kit brought that is not in the library yet: documents in the
     * docs folders, and PDFs inside references that are bundles of documents.
     * Each file is tried once; a file with no readable text is remembered and skipped.
     */
    suspend fun importKit(zims: com.bastion.terminal.kiwix.ZimLibrary) = withContext(Dispatchers.IO) {
        bulkLock.withLock {
            var added = 0
            val problems = ArrayList<String>()
            fun record(key: String, label: String, r: ImportResult) {
                bulkPrefs.edit().putString(key, if (r is ImportResult.Ok) "ok" else (r as ImportResult.Rejected).reason).apply()
                if (r is ImportResult.Ok) added++
                else if (!(r as ImportResult.Rejected).reason.startsWith("Already")) problems += "$label: ${r.reason}"
            }
            val files = docFolders().flatMap { d ->
                d.listFiles { f -> f.isFile && f.extension.lowercase() in DOC_TYPES }?.toList().orEmpty()
            }.sortedBy { it.length() }
            for (f in files) {
                val key = "file|${f.name}|${f.length()}"
                if (bulkPrefs.contains(key)) continue
                bulkStatus.value = "Adding ${f.name} to the library…"
                record(key, f.name, runCatching {
                    if (f.length() > BULK_MAX_BYTES) ImportResult.Rejected("larger than ${TextUtil.formatSize(BULK_MAX_BYTES)}")
                    else indexBytes(f.readBytes(), f.name, "From BASTION's docs folder", bulk = true)
                }.getOrElse { ImportResult.Rejected("could not be read (${it.javaClass.simpleName})") })
            }
            for (zi in zims.currentInfos()) {
                for ((path, title) in zims.pdfPaths(zi.file)) {
                    val key = "zim|${zi.fileName}|$path"
                    if (bulkPrefs.contains(key)) continue
                    val name = title.ifBlank { path.substringAfterLast('/') }.replace(Regex("(?i)\\.pdf$"), "") + ".pdf"
                    bulkStatus.value = "Adding $name from ${zi.title}…"
                    record(key, name, runCatching {
                        val bytes = zims.fileBytes(zi.file, path, BULK_MAX_BYTES)
                        if (bytes == null) ImportResult.Rejected("could not be read from ${zi.fileName}")
                        else indexBytes(bytes, name, "From “${zi.title}” (${zi.fileName})", bulk = true)
                    }.getOrElse { ImportResult.Rejected("could not be read (${it.javaClass.simpleName})") })
                }
            }
            bulkStatus.value = when {
                added == 0 && problems.isEmpty() -> ""
                else -> "Added $added document(s) from the docs folder and document collections." +
                    (if (problems.isEmpty()) "" else " Not added: " + problems.take(6).joinToString("; ") +
                        (if (problems.size > 6) "; and ${problems.size - 6} more" else "") + ".")
            }
        }
    }

    private fun indexBytes(bytes: ByteArray, name: String, provenanceNote: String, packId: Long? = null, bulk: Boolean = false): ImportResult {
        if (bytes.isEmpty()) return ImportResult.Rejected("The file is empty.")
        if (!bulk && bytes.size > TextUtil.MAX_IMPORT_BYTES) return tooLarge(bytes.size.toLong())
        val extracted = DocExtract.extract(bytes, name, ::pdfText)
        if (extracted is DocExtract.Result.Rejected) return ImportResult.Rejected(extracted.reason)
        extracted as DocExtract.Result.Ok
        val text = extracted.text
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val db = helper.writableDatabase
        db.rawQuery("SELECT id FROM docs WHERE sha256=?", arrayOf(sha)).use {
            if (it.moveToFirst()) return ImportResult.Rejected("Already in the library (identical content).")
        }
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))
        val base = name.replace(Regex("(?i)\\.(md|txt|markdown|pdf|epub)$"), "")
        // A bundled PDF is often just numbered ("files/Water (6)"); its own first line says what it is.
        val title = if (bulk && GENERIC_NAME.matches(base)) {
            text.lineSequence().map { it.trim().replace(Regex("\\s+"), " ") }
                .firstOrNull { l -> l.length in 8..90 && l.count { it.isLetter() } >= 6 }
                ?.let { "$it (${base.substringAfterLast('/')})" } ?: base.substringAfterLast('/')
        } else base
        val kindNote = when (extracted.kind) {
            DocExtract.Kind.PDF -> " · PDF text layer extracted"
            DocExtract.Kind.EPUB -> " · EPUB text extracted"
            DocExtract.Kind.TEXT -> ""
        }
        val provenance = "$provenanceNote$kindNote · $date · SHA-256 ${sha.take(12)}…"
        val id: Long
        db.beginTransaction()
        try {
            id = db.insertOrThrow("docs", null, ContentValues().apply {
                put("title", title)
                put("filename", name)
                put("imported_at", now)
                put("size_bytes", bytes.size.toLong())
                put("sha256", sha)
                put("provenance", provenance)
                packId?.let { put("pack_id", it) }
            })
            db.insertOrThrow("doc_fts", null, ContentValues().apply {
                put("docid", id)
                put("title", title)
                put("content", text)
            })
            insertChunks(db, id, text)
            // A kit PDF stays where it came from (the docs folder or its reference); only its text is kept here.
            if (!bulk || extracted.kind == DocExtract.Kind.TEXT) File(libDir(), "$id.orig").writeBytes(bytes)
            // Extracted text for the reader (PDF/EPUB originals aren't readable as text).
            if (extracted.kind != DocExtract.Kind.TEXT) File(libDir(), "$id.txt").writeText(text)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changeCounter.value = changeCounter.value + 1
        return ImportResult.Ok(DocMeta(id, title, name, now, bytes.size.toLong(), provenance, packId))
    }

    suspend fun listDocs(): List<DocMeta> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT id,title,filename,imported_at,size_bytes,provenance,pack_id FROM docs ORDER BY imported_at DESC",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(DocMeta(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5), if (c.isNull(6)) null else c.getLong(6)))
                }
            }
        }
    }

    /**
     * Retrieval for grounded answers (v2): matches ANY key term of a
     * natural-language question against paragraph-aligned passages, ranks
     * them with BM25 (rare terms weigh more), and keeps the best few — up to
     * 3 per document, within a prompt-size budget. These exact passages are
     * shown to the user and given to the model.
     */
    suspend fun retrieve(question: String, scope: Scope = Scope.ASK): List<com.bastion.terminal.ai.Passage> =
        withContext(Dispatchers.IO) {
            val g = com.bastion.terminal.ai.Grounding
            val match = g.keywordQuery(question) ?: return@withContext emptyList()
            val terms = g.keyTerms(question)
            val db = helper.readableDatabase
            val lengths = HashMap<Long, Int>()
            // Phase 1: score candidates from matchinfo only (no passage text loaded).
            val scored = try {
                db.rawQuery(
                    "SELECT c.id, c.doc_id, c.len, matchinfo(chunk_fts, 'pcnalx') FROM chunk_fts " +
                        "JOIN chunks c ON c.id = chunk_fts.docid WHERE chunk_fts MATCH ? LIMIT $MAX_CANDIDATES",
                    arrayOf(match),
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            lengths[c.getLong(0)] = c.getInt(2)
                            add(Bm25.scored(c.getLong(0), c.getLong(1), Bm25.decode(c.getBlob(3))))
                        }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
            // Only documents the asker may use (packs switched on for Ask / for the mesh).
            val allowed = allowedDocs(db, scope)
            val chosen = Bm25.select(if (allowed == null) scored else scored.filter { it.docId in allowed }, { lengths[it] ?: 0 })
            // Phase 2: load text for the chosen passages only.
            chosen.mapIndexedNotNull { i, sc ->
                db.rawQuery(
                    "SELECT f.content, c.heading, d.title FROM chunk_fts f JOIN chunks c ON c.id = f.docid " +
                        "JOIN docs d ON d.id = c.doc_id WHERE f.docid = ?",
                    arrayOf(sc.id.toString()),
                ).use { c ->
                    if (!c.moveToFirst()) return@use null
                    com.bastion.terminal.ai.Passage(
                        index = i + 1,
                        docId = sc.docId,
                        title = c.getString(2),
                        markedText = com.bastion.terminal.ai.Grounding.markTerms(c.getString(0), terms),
                        heading = c.getString(1),
                    )
                }
            }.mapIndexed { i, p -> p.copy(index = i + 1) }
        }

    /** Eval/debug: each key term's passage frequency, e.g. "rally 1/112, radio 30/112". */
    internal suspend fun termStats(question: String): String = withContext(Dispatchers.IO) {
        val g = com.bastion.terminal.ai.Grounding
        val terms = g.keyTerms(question)
        val match = g.keywordQuery(question) ?: return@withContext "(no terms)"
        helper.readableDatabase.rawQuery(
            "SELECT matchinfo(chunk_fts, 'pcnalx') FROM chunk_fts WHERE chunk_fts MATCH ? LIMIT 1",
            arrayOf(match),
        ).use { c ->
            if (!c.moveToFirst()) return@use "(no matches)"
            val s = Bm25.scored(0, 0, Bm25.decode(c.getBlob(0)))
            terms.indices.joinToString { "${terms[it]} ${s.termDf.getOrElse(it) { -1 }}/${s.rows}" }
        }
    }


    suspend fun search(raw: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val match = TextUtil.ftsQuery(raw) ?: return@withContext emptyList()
        val sql = "SELECT f.docid, d.title, " +
            "snippet(doc_fts, '${TextUtil.MARK_START}', '${TextUtil.MARK_END}', ' … ', -1, 40) " +
            "FROM doc_fts f JOIN docs d ON d.id = f.docid WHERE doc_fts MATCH ? LIMIT 50"
        try {
            helper.readableDatabase.rawQuery(sql, arrayOf(match)).use { c ->
                buildList {
                    while (c.moveToNext()) add(SearchHit(c.getLong(0), c.getString(1), c.getString(2)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Reads the preserved original (the same bytes that were indexed). Reading
     * the FTS row instead crashes for documents over ~2 MB: a single row must
     * fit Android's CursorWindow (SQLiteBlobTooBigException, found on emulator
     * 2026-11-21 with a 20 MB import). The DB path is only a fallback.
     */
    suspend fun docText(id: Long): String? = withContext(Dispatchers.IO) {
        val txt = File(libDir(), "$id.txt")
        if (txt.isFile) return@withContext txt.readText()
        val orig = File(libDir(), "$id.orig")
        if (orig.isFile) return@withContext String(orig.readBytes(), Charsets.UTF_8)
        runCatching {
            helper.readableDatabase.rawQuery(
                "SELECT content FROM doc_fts WHERE docid=?", arrayOf(id.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    suspend fun docMeta(id: Long): DocMeta? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT id,title,filename,imported_at,size_bytes,provenance,pack_id FROM docs WHERE id=?",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                DocMeta(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5), if (c.isNull(6)) null else c.getLong(6))
            } else null
        }
    }

    // ---- Packs -------------------------------------------------------------------------

    private val prefs by lazy { app.getSharedPreferences("library_$dbName", Context.MODE_PRIVATE) }

    /** The general library (documents in no pack): used by Ask unless switched off. */
    fun generalInAsk(): Boolean = prefs.getBoolean("general_in_ask", true)

    /** May mesh questions be answered from the general library? Off by default (privacy). */
    fun generalMeshOk(): Boolean = prefs.getBoolean("general_mesh_ok", false)

    fun setGeneral(inAsk: Boolean? = null, meshOk: Boolean? = null) {
        prefs.edit().apply {
            inAsk?.let { putBoolean("general_in_ask", it) }
            meshOk?.let { putBoolean("general_mesh_ok", it) }
        }.apply()
        changeCounter.value = changeCounter.value + 1
    }

    /** Doc ids usable in [scope], or null for "all". */
    private fun allowedDocs(db: SQLiteDatabase, scope: Scope): Set<Long>? {
        if (scope == Scope.ALL) return null
        val col = if (scope == Scope.MESH) "mesh_ok" else "in_ask"
        val general = if (scope == Scope.MESH) generalMeshOk() else generalInAsk()
        val sql = "SELECT d.id FROM docs d LEFT JOIN packs p ON p.id = d.pack_id WHERE " +
            "(d.pack_id IS NOT NULL AND p.$col = 1)" + if (general) " OR d.pack_id IS NULL OR p.id IS NULL" else ""
        return db.rawQuery(sql, null).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    suspend fun listPacks(): List<Pack> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT p.id, p.name, p.kind, p.in_ask, p.mesh_ok, (SELECT COUNT(*) FROM docs d WHERE d.pack_id = p.id) " +
                "FROM packs p ORDER BY p.created_at DESC", null,
        ).use { c ->
            buildList { while (c.moveToNext()) add(Pack(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getInt(4) == 1, c.getInt(5))) }
        }
    }

    suspend fun createPack(name: String, kind: String): Long = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertOrThrow("packs", null, ContentValues().apply {
            put("name", name.trim().take(60)); put("kind", kind); put("created_at", System.currentTimeMillis())
        }).also { changeCounter.value = changeCounter.value + 1 }
    }

    suspend fun setPack(id: Long, inAsk: Boolean? = null, meshOk: Boolean? = null) = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            inAsk?.let { put("in_ask", if (it) 1 else 0) }
            meshOk?.let { put("mesh_ok", if (it) 1 else 0) }
        }
        if (v.size() > 0) helper.writableDatabase.update("packs", v, "id=?", arrayOf(id.toString()))
        changeCounter.value = changeCounter.value + 1
    }

    /** Deletes the pack **and its documents** (a plan and its contents go together). */
    suspend fun deletePack(id: Long) = withContext(Dispatchers.IO) {
        val ids = helper.readableDatabase.rawQuery("SELECT id FROM docs WHERE pack_id=?", arrayOf(id.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        ids.forEach { deleteDocLocked(it) }
        helper.writableDatabase.delete("packs", "id=?", arrayOf(id.toString()))
        changeCounter.value = changeCounter.value + 1
    }

    suspend fun deleteDoc(id: Long) = withContext(Dispatchers.IO) {
        deleteDocLocked(id)
        changeCounter.value = changeCounter.value + 1
    }

    private fun deleteDocLocked(id: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM chunk_fts WHERE docid IN (SELECT id FROM chunks WHERE doc_id=?)", arrayOf(id))
            db.delete("chunks", "doc_id=?", arrayOf(id.toString()))
            db.delete("doc_fts", "docid=?", arrayOf(id.toString()))
            db.delete("docs", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        File(libDir(), "$id.orig").delete()
        File(libDir(), "$id.txt").delete()
    }

    /**
     * Saves text written on the phone as a Markdown document. [replaceDocId]: the note being
     * edited — removed only after the new version is safely stored.
     */
    suspend fun saveNote(title: String, text: String, packId: Long?, replaceDocId: Long? = null): ImportResult =
        withContext(Dispatchers.IO) {
            val name = title.trim().replace(Regex("[\\/:*?\"<>|]"), " ").take(80).ifBlank { "Note" } + ".md"
            val r = indexBytes(text.toByteArray(Charsets.UTF_8), name, NOTE_PROVENANCE, packId)
            if (r is ImportResult.Ok && replaceDocId != null && replaceDocId != r.doc.id) {
                deleteDocLocked(replaceDocId)
                changeCounter.value = changeCounter.value + 1
            }
            r
        }

    suspend fun stats(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(size_bytes),0) FROM docs", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else 0 to 0L }
    }

    companion object {
        private const val DB_VERSION = 3
        private val DOC_TYPES = setOf("pdf", "md", "markdown", "txt", "epub")
        private const val BULK_MAX_BYTES = 200L * 1024 * 1024
        private val GENERIC_NAME = Regex(".*\\(\\d+\\)$")
        const val NOTE_PROVENANCE = "Written in BASTION"
        private const val MAX_CANDIDATES = 5000

        @Volatile
        private var inst: LibraryStore? = null

        fun get(context: Context): LibraryStore =
            inst ?: synchronized(this) {
                inst ?: LibraryStore(context.applicationContext).also { inst = it }
            }

        /**
         * A separate store (own DB file and library dir) for instrumented
         * tests and retrieval evals, so they never touch the user's library.
         */
        internal fun isolated(context: Context, name: String): LibraryStore {
            val app = context.applicationContext
            app.deleteDatabase("$name.db")
            File(app.filesDir, "$name-library").deleteRecursively()
            return LibraryStore(app, "$name.db", "$name-library")
        }
    }
}
