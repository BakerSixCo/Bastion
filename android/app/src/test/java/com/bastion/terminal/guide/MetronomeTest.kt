package com.bastion.terminal.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetronomeTest {
    private fun beats(ev: List<Metronome.Event>) = ev.filterIsInstance<Metronome.Event.Beat>().map { it.count }

    @Test fun beatTimesFollowTheRate() {
        val m = Metronome(120.0)
        m.start(1000)
        assertEquals(500.0, m.intervalMs, 0.0)
        assertEquals(listOf(1000L, 1500L, 2000L, 2500L), (0..3).map { m.beatTime(it) })
        val odd = Metronome(110.0); odd.start(0)
        assertEquals(545L, odd.beatTime(1)); assertEquals(60_000L, odd.beatTime(110))   // no drift over a minute
    }

    @Test fun ticksPlayEachBeatOnce() {
        val m = Metronome(60.0); m.start(0)
        assertEquals(0L, m.nextDueAt())
        assertEquals(listOf(1), beats(m.tick(0)))
        assertEquals(emptyList<Int>(), beats(m.tick(999)))
        assertEquals(1000L, m.nextDueAt())
        assertEquals(listOf(2), beats(m.tick(1000)))
        assertEquals(listOf(3, 4, 5), beats(m.tick(4500)))   // a late tick catches up, in order
    }

    @Test fun countsCompressionsThenPromptsBreaths() {
        val m = Metronome(120.0, compressions = 30, breaths = 2)
        m.start(0)
        val ev = m.tick(60_000)
        assertEquals((1..30).toList(), beats(ev))           // stops at 30, no 31st beat
        assertEquals(Metronome.Event.Breaths(2), ev.last())
        assertEquals(Metronome.Phase.BREATHS, m.phase)
        assertNull(m.nextDueAt())                            // waits for the rescuer
        assertEquals(emptyList<Metronome.Event>(), m.tick(70_000))
        m.breathsDone(70_000)
        assertEquals(Metronome.Phase.BEATING, m.phase); assertEquals(1, m.rounds)
        assertEquals(listOf(1, 2), beats(m.tick(70_500)))   // a new round counts from 1, starting now
    }

    @Test fun continuousWithoutBreaths() {
        val m = Metronome(100.0, compressions = 0)
        m.start(0)
        val ev = m.tick(59_999)
        assertEquals(100, beats(ev).size); assertTrue(ev.none { it is Metronome.Event.Breaths })
        val counted = Metronome(100.0, compressions = 15, breaths = 0); counted.start(0)
        val c = beats(counted.tick(17 * 600L))
        assertEquals((1..15).toList() + listOf(1, 2, 3), c)  // wraps with no prompt
    }

    @Test fun cycleReminderEveryCycleSeconds() {
        val m = Metronome(60.0, cycleSeconds = 120); m.start(0)
        val ev = m.tick(360_000)
        val cycles = ev.filterIsInstance<Metronome.Event.Cycle>().map { it.cycle }
        assertEquals(listOf(1, 2, 3), cycles)
        // The reminder comes before the beat due at the same moment.
        val i = ev.indexOf(Metronome.Event.Cycle(1))
        assertEquals(Metronome.Event.Beat(121), ev[i + 1])
    }

    @Test fun cycleReminderStillComesWhileWaitingForBreaths() {
        val m = Metronome(60.0, compressions = 5, breaths = 2, cycleSeconds = 10); m.start(0)
        m.tick(4_000)                                        // 5 beats, then breaths
        assertEquals(Metronome.Phase.BREATHS, m.phase)
        assertEquals(10_000L, m.nextDueAt())
        assertEquals(listOf(Metronome.Event.Cycle(1)), m.tick(10_000))
    }

    @Test fun stoppedDoesNothing() {
        val m = Metronome(100.0); m.start(0); m.stop()
        assertNull(m.nextDueAt()); assertEquals(emptyList<Metronome.Event>(), m.tick(10_000))
    }

    @Test fun countdown() {
        val c = Countdown(45)
        assertEquals(45, c.remaining(0)); assertEquals(false, c.done(0))
        c.start(1000)
        assertEquals(45, c.remaining(1000)); assertEquals(45, c.remaining(1001)); assertEquals(44, c.remaining(2000))
        assertEquals(1, c.remaining(45_999)); assertEquals(0, c.remaining(46_000)); assertTrue(c.done(46_000))
    }
}
