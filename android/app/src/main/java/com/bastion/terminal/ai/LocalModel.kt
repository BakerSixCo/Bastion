package com.bastion.terminal.ai

import kotlinx.coroutines.flow.Flow

/** Measured numbers for one generation. Null fields were not reported by the runtime. */
data class GenerationStats(
    val loadSeconds: Double?,
    val timeToFirstChunkSeconds: Double,
    val totalSeconds: Double,
    val prefillTokens: Int?,
    val prefillTokensPerSecond: Double?,
    val decodeTokens: Int?,
    val decodeTokensPerSecond: Double?,
    val processRssMb: Long?,
)

/**
 * Small seam between the app and an on-device inference runtime, so the
 * runtime can be swapped (LiteRT-LM today; llama.cpp is the fallback) and a
 * test double can stand in where no model exists.
 */
interface LocalModel : AutoCloseable {
    /** Human-readable runtime + model label, e.g. "LiteRT-LM 0.19.1 · gemma-4-E2B-it". */
    val label: String

    /** Loads weights. Slow (seconds to tens of seconds); call off the main thread. */
    suspend fun load()

    /** Streams answer text chunks for a single grounded turn. */
    fun generate(systemInstruction: String, userPrompt: String, maxOutputTokens: Int): Flow<String>

    /** Stats for the most recent [generate] call, once it has finished. */
    fun lastStats(): GenerationStats?

    /** Stops an in-flight generation. */
    fun cancel()
}

/** Which compute unit runs the model. AUTO = GPU if it starts, otherwise CPU. */
enum class ModelBackend { AUTO, GPU, CPU }
