package com.bastion.terminal.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

class FlowSessionTest {
    private class MemStore(var saved: List<LogEntry> = emptyList()) : LogStore {
        override fun load() = saved
        override fun save(entries: List<LogEntry>) { saved = entries }
    }

    private var now = 1_700_000_000_000L   // 2023-11-14 22:13:20 UTC
    private val store = MemStore()
    private fun session() = FlowSession(store, clock = { now }, zone = TimeZone.getTimeZone("UTC"))

    @Test fun walksAnswersAndBack() {
        val s = session(); s.open(FakeFlows.flow())
        assertEquals("q", s.currentId); assertFalse(s.canBack)
        s.answer(1)                       // No -> tool t
        assertEquals("t", s.currentId); assertTrue(s.canBack)
        s.next()                          // t -> s
        assertEquals("s", s.currentId)
        assertTrue(s.back()); assertEquals("t", s.currentId)
        assertTrue(s.back()); assertEquals("q", s.currentId)
        assertFalse(s.back()); assertEquals("q", s.currentId)
        s.answer(0)                       // Yes -> a
        assertEquals("a", s.currentId)
        s.next(); assertEquals("e", s.currentId); assertEquals(NodeType.END, s.node!!.type)
        s.next(); assertEquals("e", s.currentId)   // an end goes nowhere
    }

    @Test fun wrongKindOfMoveIsIgnored() {
        val s = session(); s.open(FakeFlows.flow())
        s.next(); assertEquals("q", s.currentId)        // a question needs an answer
        s.answer(5); assertEquals("q", s.currentId)     // no such answer
        s.answer(0); s.answer(0); assertEquals("a", s.currentId)   // an action has no answers
    }

    @Test fun logsEveryConfirmedStepWithTime() {
        val s = session(); s.open(FakeFlows.flow())
        now += 1000; s.answer(0)
        now += 1000; s.next()
        val texts = s.log.map { it.text }
        assertEquals(listOf("Started guide: Fake test flow", "Test question? — Yes", "Test step A done"), texts)
        assertEquals(listOf("q", "q", "a"), s.log.map { it.nodeId })
        assertEquals(listOf(1_700_000_000_000L, 1_700_000_001_000L, 1_700_000_002_000L), s.log.map { it.timeMs })
        assertEquals(s.log.toList(), store.saved)       // persisted on every entry
        s.back(); assertEquals(3, s.log.size)           // Back does not unlog what happened
    }

    @Test fun timestampLogsLabelAtTime() {
        val s = session(); s.open(FakeFlows.flow()); s.answer(1); s.next()
        assertEquals("Test thing at 22:13", s.timestamp("Test thing"))
        assertEquals("Test thing at 22:13", s.log.last().text)
        assertEquals("s", s.log.last().nodeId)
    }

    @Test fun restartAndJump() {
        val s = session(); s.open(FakeFlows.flow()); s.answer(1); s.next()
        s.restart(); assertEquals("q", s.currentId); assertFalse(s.canBack)
        s.jumpTo("e"); assertEquals("e", s.currentId)
        s.back(); assertEquals("q", s.currentId)
        s.jumpTo("ghost"); assertEquals("q", s.currentId)
        assertTrue(s.log.any { it.text == "Restarted guide: Fake test flow" })
    }

    @Test fun reopeningTheSameFlowKeepsThePlace() {
        val s = session(); val f = FakeFlows.flow(); s.open(f); s.answer(1)
        s.open(f); assertEquals("t", s.currentId)
        s.close(); assertNull(s.node)
        s.open(f); assertEquals("q", s.currentId)
    }

    @Test fun logSurvivesANewSessionAndClears() {
        session().apply { open(FakeFlows.flow()); answer(0) }
        val again = session()
        assertEquals(2, again.log.size)
        again.clearLog(); assertEquals(0, again.log.size); assertEquals(emptyList<LogEntry>(), store.saved)
    }

    @Test fun testFlowEntriesAreMarked() {
        val s = session(); s.open(FakeFlows.flow().copy(test = true))
        assertTrue(s.log.single().test)
    }

    @Test fun casualtyLogFileRoundTrip() {
        val dir = Files.createTempDirectory("guide").toFile()
        try {
            val log = CasualtyLog(File(File(dir, CasualtyLog.DIR), "log.json"))
            assertEquals(emptyList<LogEntry>(), log.load())
            val e = listOf(LogEntry(5L, "fake", "q", "Test — Yes"), LogEntry(6L, "fake", "a", "Test step", test = true))
            log.save(e); assertEquals(e, log.load())
            log.save(emptyList()); assertEquals(emptyList<LogEntry>(), log.load())
        } finally { dir.deleteRecursively() }
    }

    /** The wipe PIN erases files/ except AppLock.KEEP_FILE_DIRS: the log's folder must not be kept. */
    @Test fun wipeDoesNotKeepTheLog() {
        assertFalse(CasualtyLog.DIR in com.bastion.terminal.security.AppLock.KEEP_FILE_DIRS)
    }
}
