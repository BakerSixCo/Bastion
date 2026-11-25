package com.bastion.terminal.guide

import kotlin.math.roundToLong

/**
 * Metronome scheduling for a tool step (pure: time is passed in, so it is unit-tested). Beats at
 * [rateBpm]; counts 1..[compressions], then stops and prompts [breaths] breaths until
 * [breathsDone]; a cycle reminder every [cycleSeconds] since start (wall time, breaths included). compressions = 0 means
 * continuous beats with no breaths prompt. All numbers come from the approved flow, never the AI.
 *
 * The caller drives it: wait until [nextDueAt], then call [tick] with the current time and act on
 * the returned events (sound, pulse, prompt).
 */
class Metronome(
    val rateBpm: Double,
    val compressions: Int = 0,
    val breaths: Int = 0,
    val cycleSeconds: Int = 0,
) {
    sealed interface Event {
        /** A beat; [count] is 1..compressions (or the running beat number when continuous). */
        data class Beat(val count: Int) : Event
        /** The compressions are done: give [breaths] breaths, then call [breathsDone]. */
        data class Breaths(val breaths: Int) : Event
        /** [cycle] cycles of cycleSeconds have run. */
        data class Cycle(val cycle: Int) : Event
    }

    enum class Phase { STOPPED, BEATING, BREATHS }

    init { require(rateBpm > 0) { "rate_bpm must be > 0" } }

    val intervalMs: Double = 60_000.0 / rateBpm

    var phase = Phase.STOPPED; private set
    /** Beat count shown: 1..compressions in a round (0 before the first beat). */
    var count = 0; private set
    /** Rounds of compressions finished. */
    var rounds = 0; private set
    var cycles = 0; private set

    private var startedAt = 0L        // running time origin, for cycle reminders
    private var segmentAt = 0L        // when the current run of beats began
    private var beatsInSegment = 0    // beats already played in this run

    /** When beat [k] (0-based) of the current run falls. */
    fun beatTime(k: Int): Long = segmentAt + (k * intervalMs).roundToLong()

    fun start(now: Long) {
        phase = Phase.BEATING; count = 0; rounds = 0; cycles = 0
        startedAt = now; segmentAt = now; beatsInSegment = 0
    }

    fun stop() { phase = Phase.STOPPED }

    /** After the breaths prompt: the next round starts now, counting from 1. */
    fun breathsDone(now: Long) {
        if (phase != Phase.BREATHS) return
        phase = Phase.BEATING; count = 0; segmentAt = now; beatsInSegment = 0
    }

    /** Time since [start]. */
    fun elapsedMs(now: Long): Long = now - startedAt

    /** The next time [tick] has something to do; null when stopped (or waiting for [breathsDone] with no cycle reminder). */
    fun nextDueAt(): Long? {
        val cyc = if (cycleSeconds > 0) cycleDueAt() else null
        return when (phase) {
            Phase.BEATING -> cyc?.let { minOf(it, beatTime(beatsInSegment)) } ?: beatTime(beatsInSegment)
            Phase.BREATHS -> cyc
            Phase.STOPPED -> null
        }
    }

    private fun cycleDueAt(): Long = startedAt + (cycles + 1) * cycleSeconds * 1000L

    /** Plays everything due by [now], in order. Late ticks catch up (no beat is skipped from the count). */
    fun tick(now: Long): List<Event> {
        val out = mutableListOf<Event>()
        if (phase == Phase.STOPPED) return out
        while (true) {
            val beat = if (phase == Phase.BEATING) beatTime(beatsInSegment) else Long.MAX_VALUE
            val cyc = if (cycleSeconds > 0) cycleDueAt() else Long.MAX_VALUE
            if (cyc <= now && cyc <= beat) { cycles++; out += Event.Cycle(cycles); continue }
            if (beat > now || phase != Phase.BEATING) break
            beatsInSegment++
            count++
            out += Event.Beat(count)
            if (compressions > 0 && count >= compressions) {
                rounds++
                if (breaths > 0) { phase = Phase.BREATHS; out += Event.Breaths(breaths) }
                else { count = 0 }
            }
        }
        return out
    }
}

/** A countdown for a timer step (pure; time passed in). */
class Countdown(val seconds: Int) {
    var startedAt: Long? = null; private set

    fun start(now: Long) { startedAt = now }
    fun reset() { startedAt = null }
    val running: Boolean get() = startedAt != null

    /** Whole seconds left (rounded up), [seconds] before start, 0 when done. */
    fun remaining(now: Long): Int {
        val s = startedAt ?: return seconds
        val left = seconds * 1000L - (now - s)
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    fun done(now: Long): Boolean = startedAt != null && remaining(now) == 0
}
