package com.bastion.terminal.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
)

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
                    "provenance TEXT NOT NULL)"
            )
            db.execSQL("CREATE VIRTUAL TABLE doc_fts USING fts4(title, content)")
            createChunkTables(db)
        }

        /** v1 → v2: add passage chunks and backfill them from the preserved originals. */
        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            if (oldV < 2) {
                createChunkTables(db)
                val ids = db.rawQuery("SELECT id FROM docs", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0)) }
                }
                for (id in ids) {
                    val orig = File(libDir(), "$id.orig")
                    if (orig.isFile) insertChunks(db, id, String(orig.readBytes(), Charsets.UTF_8))
                }
            }
        }
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

    private fun libDir(): File = File(app.filesDir, libSubdir).apply { mkdirs() }

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
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
        indexBytes(bytes, name, "Imported via Android file picker")
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
    internal suspend fun importBytes(bytes: ByteArray, name: String, provenanceNote: String): ImportResult =
        withContext(Dispatchers.IO) { indexBytes(bytes, name, provenanceNote) }

    private fun indexBytes(bytes: ByteArray, name: String, provenanceNote: String): ImportResult {
        if (bytes.isEmpty()) return ImportResult.Rejected("The file is empty.")
        if (bytes.size > TextUtil.MAX_IMPORT_BYTES) return tooLarge(bytes.size.toLong())
        if (TextUtil.isLikelyBinary(bytes)) {
            return ImportResult.Rejected(
                "This looks like binary data. v0.1 indexes plain text and Markdown only; " +
                    "PDF, EPUB, and ZIM support are planned, separate capabilities."
            )
        }
        val text = String(bytes, Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val db = helper.writableDatabase
        db.rawQuery("SELECT id FROM docs WHERE sha256=?", arrayOf(sha)).use {
            if (it.moveToFirst()) return ImportResult.Rejected("Already in the library (identical content).")
        }
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))
        val title = name.removeSuffix(".md").removeSuffix(".txt")
        val provenance = "$provenanceNote · $date · SHA-256 ${sha.take(12)}…"
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
            })
            db.insertOrThrow("doc_fts", null, ContentValues().apply {
                put("docid", id)
                put("title", title)
                put("content", text)
            })
            insertChunks(db, id, text)
            File(libDir(), "$id.orig").writeBytes(bytes)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changeCounter.value = changeCounter.value + 1
        return ImportResult.Ok(DocMeta(id, title, name, now, bytes.size.toLong(), provenance))
    }

    suspend fun listDocs(): List<DocMeta> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT id,title,filename,imported_at,size_bytes,provenance FROM docs ORDER BY imported_at DESC",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(DocMeta(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5)))
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
    suspend fun retrieve(question: String): List<com.bastion.terminal.ai.Passage> =
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
            val chosen = Bm25.select(scored, { lengths[it] ?: 0 })
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
                        markedText = markTerms(c.getString(0), terms),
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

    /** Wraps occurrences of [terms] in highlight markers for display. */
    private fun markTerms(text: String, terms: List<String>): String {
        val ranges = TextUtil.highlightRanges(text, terms.joinToString(" "))
        val sb = StringBuilder(text.length + ranges.size * 2)
        var pos = 0
        for (r in ranges) {
            if (r.first < pos) continue
            sb.append(text, pos, r.first).append(TextUtil.MARK_START)
                .append(text, r.first, r.last + 1).append(TextUtil.MARK_END)
            pos = r.last + 1
        }
        return sb.append(text, pos, text.length).toString()
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
            "SELECT id,title,filename,imported_at,size_bytes,provenance FROM docs WHERE id=?",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                DocMeta(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5))
            } else null
        }
    }

    suspend fun stats(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(size_bytes),0) FROM docs", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else 0 to 0L }
    }

    companion object {
        private const val DB_VERSION = 2
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
