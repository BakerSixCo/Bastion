package com.bastion.terminal.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LiteRT-LM implementation. Backend per [ModelBackend]: AUTO tries the GPU
 * (OpenCL) first and falls back to the CPU if it can't start — the emulator
 * has no usable OpenCL, so it always lands on CPU there; phones with an
 * Adreno/Mali GPU are where GPU pays off. The label says which one ran.
 *
 * Uses LiteRT-LM's experimental benchmark API (prefill/decode token rates),
 * because measured numbers are the point of M2. If a future LiteRT-LM drops
 * it, stats fall back to our own wall-clock timings.
 */
@OptIn(ExperimentalApi::class)
class LiteRtLmModel(
    private val modelFile: File,
    private val cacheDir: File,
    private val preference: ModelBackend = ModelBackend.AUTO,
) : LocalModel {

    /** Which backend actually loaded (CPU until [load] succeeds). */
    var activeBackend: ModelBackend = ModelBackend.CPU
        private set

    /** Why the GPU was not used, when AUTO fell back (shown to the user). */
    var fallbackReason: String? = null
        private set

    override val label: String
        get() = "LiteRT-LM $RUNTIME_VERSION · ${modelFile.nameWithoutExtension} · ${activeBackend.name}"

    private var engine: Engine? = null
    private var loadSeconds: Double? = null
    @Volatile private var current: Conversation? = null
    @Volatile private var stats: GenerationStats? = null

    override suspend fun load() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        ExperimentalFlags.enableBenchmark = true
        val order = when (preference) {
            ModelBackend.AUTO -> listOf(ModelBackend.GPU, ModelBackend.CPU)
            else -> listOf(preference)
        }
        var lastError: Throwable? = null
        for (b in order) {
            val t0 = System.nanoTime()
            var e: Engine? = null
            try {
                // Android clears app caches when storage runs low: make sure the folder is there for every attempt.
                cacheDir.mkdirs()
                e = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = if (b == ModelBackend.GPU) Backend.GPU() else Backend.CPU(),
                        maxNumTokens = MAX_CONTEXT_TOKENS,
                        cacheDir = cacheDir.absolutePath,
                    )
                )
                e.initialize()
                // The GPU path can initialize fine and only fail at first use ("Can not find
                // OpenCL library" — seen on emulator), so prove it with a one-token run.
                if (b == ModelBackend.GPU) {
                    e.createConversation(ConversationConfig(maxOutputToken = 1)).use { it.sendMessage("ok") }
                }
            } catch (t: Throwable) {
                runCatching { e?.close() }
                lastError = t
                if (b == ModelBackend.GPU) fallbackReason = t.message?.lineSequence()?.firstOrNull()?.take(160) ?: t.javaClass.simpleName
                android.util.Log.w("BastionAI", "backend $b failed: ${t.message}")
                continue
            }
            engine = e
            activeBackend = b
            if (b == ModelBackend.GPU) fallbackReason = null
            loadSeconds = (System.nanoTime() - t0) / 1e9
            return@withContext
        }
        throw lastError ?: IllegalStateException("no backend")
    }

    override fun generate(systemInstruction: String, userPrompt: String, maxOutputTokens: Int): Flow<String> =
        run(systemInstruction, userPrompt, maxOutputTokens, null)

    // Constrained decoding: the runtime only lets the model produce JSON that fits the schema.
    // Greedy (topK 1), because a form decides what is shown and must not vary between runs.
    override suspend fun generateJson(systemInstruction: String, userPrompt: String, jsonSchema: String, maxOutputTokens: Int): String {
        val sb = StringBuilder()
        run(systemInstruction, userPrompt, maxOutputTokens, ResponseFormat.json(jsonSchema)).collect { sb.append(it) }
        return sb.toString()
    }

    private fun run(systemInstruction: String, userPrompt: String, maxOutputTokens: Int, format: ResponseFormat?): Flow<String> = flow {
        val e = checkNotNull(engine) { "Model not loaded" }
        stats = null
        val conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                // Low temperature: grounded answers should stick to the sources.
                samplerConfig = if (format == null) SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2)
                    else SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                maxOutputToken = maxOutputTokens,
                enableResponseFormat = format != null,
            )
        )
        current = conversation
        val t0 = System.nanoTime()
        var firstChunkAt = -1L
        try {
            (if (format == null) conversation.sendMessageAsync(userPrompt) else conversation.sendMessageAsync(userPrompt, responseFormat = format)).collect { msg ->
                val text = msg.toString()
                if (text.isNotEmpty()) {
                    if (firstChunkAt < 0) firstChunkAt = System.nanoTime()
                    emit(text)
                }
            }
            val end = System.nanoTime()
            val bench = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
            stats = GenerationStats(
                loadSeconds = loadSeconds,
                timeToFirstChunkSeconds = if (firstChunkAt > 0) (firstChunkAt - t0) / 1e9 else (end - t0) / 1e9,
                totalSeconds = (end - t0) / 1e9,
                prefillTokens = bench?.lastPrefillTokenCount?.takeIf { it > 0 },
                prefillTokensPerSecond = bench?.lastPrefillTokensPerSecond?.takeIf { it > 0 },
                decodeTokens = bench?.lastDecodeTokenCount?.takeIf { it > 0 },
                decodeTokensPerSecond = bench?.lastDecodeTokensPerSecond?.takeIf { it > 0 },
                processRssMb = readRssMb(),
            )
        } finally {
            current = null
            runCatching { conversation.close() }
        }
    }.flowOn(Dispatchers.IO)

    override fun lastStats(): GenerationStats? = stats

    fun lastLoadSeconds(): Double = loadSeconds ?: 0.0

    override fun cancel() {
        runCatching { current?.cancelProcess() }
    }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }

    companion object {
        const val RUNTIME_VERSION = "0.19.1"
        const val MAX_CONTEXT_TOKENS = 2048

        /** Resident set size of this process, from /proc/self/status (MB). */
        fun readRssMb(): Long? = runCatching {
            File("/proc/self/status").readLines()
                .first { it.startsWith("VmRSS:") }
                .split(Regex("\\s+"))[1].toLong() / 1024
        }.getOrNull()
    }
}
