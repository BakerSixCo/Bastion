package com.bastion.terminal.ai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bastion.terminal.data.DocMeta
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.kiwix.ZimLibrary
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Measures the actual Ask retrieval path against a kit already imported through the app. */
@RunWith(AndroidJUnit4::class)
class KitRetrievalEvalTest {
    private data class Case(
        val id: String,
        val topic: String,
        val question: String,
        val file: String?,
        val phrase: String?,
    )

    @Test
    fun measureKitRetrieval() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val media = context.externalMediaDirs.filterNotNull()
        val questions = media.map { File(it, "kit-questions.json") }.firstOrNull { it.isFile }
        checkNotNull(questions) { "Place kit-questions.json in BASTION's app media folder." }
        val cases = readCases(questions)
        val store = LibraryStore.get(context)
        val docs = store.listDocs().associateBy(DocMeta::id)
        logKitIndex(context, media, docs)
        val expected = cases.mapNotNull(Case::file).toSet()
        val missing = expected - docs.values.map(DocMeta::filename).toSet()
        missing.sorted().forEach { Log.w(TAG, "MISSING_EXPECTED_SOURCE $it") }
        check(missing.size < expected.size) { "No expected kit source is indexed. Launch BASTION and let its import finish." }

        val zim = ZimLibrary.get(context)
        zim.restore()
        val retriever = Retriever(store, zim)
        val totals = cases.filter { it.file != null }.groupingBy(Case::topic).eachCount()
        val hits = mutableMapOf<String, Int>()
        var negatives = 0
        var negativesWithResults = 0
        for (case in cases) {
            val passages = retriever.retrieve(case.question).take(5)
            val sources = passages.map { sourceFile(it, docs) }.distinct()
            if (case.file == null) {
                negatives++
                if (passages.isNotEmpty()) negativesWithResults++
                Log.i(TAG, "UNANSWERABLE ${case.id} | passages=${passages.size} | files=$sources")
            } else {
                val phrase = normalize(checkNotNull(case.phrase))
                val hit = passages.any {
                    !it.isZim && sourceFile(it, docs) == case.file &&
                        normalize(it.plainText).contains(phrase, ignoreCase = true)
                }
                if (hit) hits[case.topic] = hits.getOrDefault(case.topic, 0) + 1
                val state = if (hit) "HIT" else "MISS"
                Log.i(TAG, "$state ${case.id} | ${case.topic} | expected=${case.file} | top3=${sources.take(3)}")
            }
        }
        totals.forEach { (topic, count) -> Log.i(TAG, "TOPIC $topic ${hits.getOrDefault(topic, 0)}/$count") }
        Log.i(TAG, "SUMMARY hits=${hits.values.sum()}/${totals.values.sum()} " +
            "unanswerable=$negatives withResults=$negativesWithResults")
    }

    private fun readCases(file: File): List<Case> {
        val rows = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("cases")
        val cases = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("id")
            val topic = row.getString("topic")
            val question = row.getString("question")
            val source = if (row.isNull("expected_file")) null else row.getString("expected_file")
            val phrase = if (row.isNull("expected_phrase")) null else row.getString("expected_phrase")
            require(id.isNotBlank() && topic.isNotBlank() && question.isNotBlank())
            require(row.has("expected_file") && row.has("expected_phrase")) { "Missing expectation fields for $id" }
            if (topic == "unanswerable") {
                require(source == null && phrase == null) { "Unanswerable case $id has an expectation" }
            } else {
                require(!source.isNullOrBlank() && !phrase.isNullOrBlank()) { "Missing expectation for $id" }
                require(File(source).name == source && '\\' !in source) { "Expected a filename for $id" }
            }
            Case(id, topic, question, source, phrase)
        }
        require(cases.map(Case::id).distinct().size == cases.size) { "Duplicate question IDs" }
        val counts = cases.groupingBy(Case::topic).eachCount()
        require(counts.keys == TOPICS + "unanswerable") { "Unexpected or missing kit topics" }
        require(counts["unanswerable"] == 8) { "Expected eight unanswerable questions" }
        require(TOPICS.all { counts.getOrDefault(it, 0) >= 4 }) { "Expected at least four questions in every kit topic" }
        return cases
    }

    /** Read-only snapshot of imported file identity and indexed chunk counts. */
    private fun logKitIndex(context: Context, media: List<File>, docs: Map<Long, DocMeta>) {
        val prefs = context.getSharedPreferences("library_bulk_bastion.db", Context.MODE_PRIVATE)
        val index = SQLiteDatabase.openDatabase(context.getDatabasePath(DB_NAME).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT docs.sha256, docs.id, COUNT(chunks.id) FROM docs " +
                    "LEFT JOIN chunks ON chunks.doc_id = docs.id GROUP BY docs.id",
                null,
            ).use { rows ->
                buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getLong(1) to rows.getInt(2)) }
            }
        }
        for (root in media.map { File(it, "docs") }) {
            for (dir in listOf(root, File(root, "reference"))) {
                val expected = if (dir == root) SourceTier.OWN else SourceTier.REFERENCE
                val files = dir.listFiles { file -> file.isFile && file.extension.lowercase() in DOC_TYPES }.orEmpty()
                for (file in files.sortedBy { it.name }) {
                    val memo = prefs.getString("file|${file.name}|${file.length()}", null)
                    val identity = runCatching { sha256(file) }
                    val match = identity.getOrNull()?.let(index::get)
                    val doc = match?.let { docs[it.first] }
                    val tier = doc?.let { SourceTier.forProvenance(it.provenance) }
                    val state = when {
                        memo?.startsWith("Already") == true -> "SKIPPED_DUPLICATE"
                        memo != null && memo != "ok" -> "REJECTED"
                        match != null -> "INDEXED"
                        memo == "ok" -> "INDEX_ROW_MISSING"
                        identity.isFailure -> "UNREADABLE"
                        else -> "PENDING"
                    }
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    Log.i(TAG, "IMPORT $path | $state | chunks=${match?.second ?: "-"} | " +
                        "tier=${tier ?: "UNOBSERVED"} | expected=$expected | receipt=${memo ?: "-"}")
                    if (tier != null && tier != expected) Log.w(TAG, "TIER_MISMATCH $path | $tier | expected=$expected")
                }
            }
        }
    }

    private fun sourceFile(passage: Passage, docs: Map<Long, DocMeta>): String =
        passage.zimFile?.let { File(it).name } ?: docs[passage.docId]?.filename ?: "<unknown file>"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun normalize(text: String): String = text.replace(WHITESPACE, " ").trim()

    private companion object {
        const val TAG = "KitRetrievalEval"
        const val DB_NAME = "bastion.db"
        val WHITESPACE = Regex("[\\s\\u00a0]+")
        val DOC_TYPES = setOf("md", "markdown", "txt", "pdf", "epub")
        val TOPICS = setOf("radio-rules", "mayday", "weather-radio", "electronics", "land-navigation", "atak",
            "poultry-rabbits", "butchering", "food-temperatures", "food-power-outage", "herbs-chinese-medicine")
    }
}
