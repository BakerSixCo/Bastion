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
class LibraryStore private constructor(private val app: Context) {

    /** Bumped after every successful import so screens can reload. */
    val changeCounter = MutableStateFlow(0)

    private val helper = object : SQLiteOpenHelper(app, "bastion.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE docs(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL," +
                    "filename TEXT NOT NULL, imported_at INTEGER NOT NULL," +
                    "size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL UNIQUE," +
                    "provenance TEXT NOT NULL)"
            )
            db.execSQL("CREATE VIRTUAL TABLE doc_fts USING fts4(title, content)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) = Unit
    }

    private fun libDir(): File = File(app.filesDir, "library").apply { mkdirs() }

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
     * Retrieval for grounded answers: matches ANY key term of a natural-language
     * question, takes FTS4's best ~64-token fragment per document, and ranks by
     * how many distinct key terms each fragment contains. These exact passages
     * are shown to the user and given to the model.
     */
    suspend fun retrieve(question: String, limit: Int = 4): List<com.bastion.terminal.ai.Passage> =
        withContext(Dispatchers.IO) {
            val g = com.bastion.terminal.ai.Grounding
            val match = g.keywordQuery(question) ?: return@withContext emptyList()
            val terms = g.keyTerms(question)
            val sql = "SELECT f.docid, d.title, " +
                "snippet(doc_fts, '${TextUtil.MARK_START}', '${TextUtil.MARK_END}', ' … ', -1, 64) " +
                "FROM doc_fts f JOIN docs d ON d.id = f.docid WHERE doc_fts MATCH ? LIMIT 50"
            val rows = try {
                helper.readableDatabase.rawQuery(sql, arrayOf(match)).use { c ->
                    buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getString(2))) }
                }
            } catch (_: Exception) {
                emptyList()
            }
            rows.sortedByDescending { (_, _, snip) -> g.termHits(snip, terms) }
                .take(limit)
                .mapIndexed { i, (id, title, snip) -> com.bastion.terminal.ai.Passage(i + 1, id, title, snip) }
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

        @Volatile
        private var inst: LibraryStore? = null

        fun get(context: Context): LibraryStore =
            inst ?: synchronized(this) {
                inst ?: LibraryStore(context.applicationContext).also { inst = it }
            }
    }
}
