package com.bastion.terminal.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fake flows only: no real guidance in tests. */
object FakeFlows {
    const val JSON = """
    {
      "id": "fake", "title": "Fake test flow", "entry": true, "status": "approved", "approved_by": "test",
      "start": "q",
      "nodes": {
        "q":    { "type": "question", "text": "Test question?", "quote_refs": ["fq"],
                  "answers": [ { "label": "Yes", "next": "a" }, { "label": "No", "next": "t" } ] },
        "a":    { "type": "action", "text": "Test step A", "quote_refs": ["fq"], "log": "Test step A done", "next": "e" },
        "t":    { "type": "tool", "text": "Test tool", "quote_refs": ["fq"],
                  "tool": { "kind": "metronome", "rate_bpm": 110, "compressions": 30, "breaths": 2, "cycle_seconds": 120 },
                  "next": "s" },
        "s":    { "type": "tool", "text": "Test stamp", "quote_refs": ["fq"],
                  "tool": { "kind": "timestamp", "label": "Test thing" }, "next": "e" },
        "e":    { "type": "end", "text": "Test end" }
      },
      "quotes": [ { "id": "fq", "doc": "Fake-Doc.pdf", "page": 3, "text": "Fake quote from a fake doc." } ],
      "links": ["fake-card"]
    }"""

    fun flow(): Flow = FlowParser.parse(JSON)

    fun with(f: Flow = flow(), start: String = f.start, edit: (MutableMap<String, Node>) -> Unit = {}): Flow =
        f.copy(start = start, nodes = LinkedHashMap(f.nodes).also(edit))
}

class FlowParserTest {
    @Test fun parsesEveryField() {
        val f = FakeFlows.flow()
        assertEquals("fake", f.id); assertEquals("Fake test flow", f.title); assertTrue(f.entry)
        assertEquals("approved", f.status); assertEquals("q", f.start); assertEquals(listOf("fake-card"), f.links)
        assertEquals(5, f.nodes.size)
        val q = f.nodes.getValue("q")
        assertEquals(NodeType.QUESTION, q.type); assertEquals(listOf("Yes", "No"), q.answers.map { it.label })
        assertEquals(listOf("a", "t"), q.targets)
        val t = f.nodes.getValue("t").tool!!
        assertEquals(ToolKind.METRONOME, t.kind); assertEquals(110.0, t.rateBpm, 0.0)
        assertEquals(30, t.compressions); assertEquals(2, t.breaths); assertEquals(120, t.cycleSeconds)
        assertEquals("Test step A done", f.nodes.getValue("a").log)
        assertEquals(ToolKind.TIMESTAMP, f.nodes.getValue("s").tool!!.kind)
        val quote = f.quotes.getValue("fq")
        assertEquals(3, quote.page); assertEquals("Fake-Doc, p. 3", quote.source)
        assertFalse(f.test)
    }

    @Test fun parsesBundleAndMarksTest() {
        val fs = FlowParser.parseBundle("""{"version":1,"flows":[${FakeFlows.JSON}]}""", test = true)
        assertEquals(1, fs.size); assertTrue(fs[0].test)
        assertEquals(emptyList<Flow>(), FlowParser.parseBundle("""{"flows":[]}"""))
    }

    @Test fun unknownTypesAreKeptForTheValidator() {
        val f = FlowParser.parse(FakeFlows.JSON.replace("\"type\": \"action\"", "\"type\": \"maybe\"")
            .replace("\"kind\": \"timestamp\"", "\"kind\": \"laser\""))
        assertEquals(NodeType.UNKNOWN, f.nodes.getValue("a").type)
        assertEquals(ToolKind.UNKNOWN, f.nodes.getValue("s").tool!!.kind)
    }

    @Test(expected = Exception::class) fun malformedJsonThrows() { FlowParser.parse("{ not json") }

    @Test(expected = Exception::class) fun missingStartFieldThrows() { FlowParser.parse("""{"id":"x","title":"t","nodes":{}}""") }

    @Test fun orderedNodesFollowTheFlow() {
        assertEquals(listOf("q", "a", "t", "s", "e"), FakeFlows.flow().orderedNodes.map { it.id })
    }
}

class FlowValidatorTest {
    private fun problems(f: Flow) = FlowValidator.problems(f)
    private fun assertProblem(f: Flow, part: String) {
        val p = problems(f)
        assertTrue("expected '$part' in $p", p.any { part in it })
    }

    @Test fun fakeFlowIsValid() = assertEquals(emptyList<String>(), problems(FakeFlows.flow()))

    @Test fun missingStart() = assertProblem(FakeFlows.with(start = "nope"), "start 'nope' is not a node")

    @Test fun badTarget() = assertProblem(FakeFlows.with { it["a"] = it.getValue("a").copy(next = "ghost") }, "goes to 'ghost'")

    @Test fun badAnswerTarget() = assertProblem(FakeFlows.with {
        val q = it.getValue("q"); it["q"] = q.copy(answers = listOf(q.answers[0], Node.Answer("No", "ghost")))
    }, "q: goes to 'ghost'")

    @Test fun unreachableNode() = assertProblem(FakeFlows.with { it["orphan"] = Node("orphan", NodeType.END, "Orphan end") },
        "orphan: not reachable from start")

    @Test fun trapLoop() {
        // s goes back to t, and t to s: neither can reach an end.
        val f = FakeFlows.with { it["s"] = it.getValue("s").copy(next = "t") }
        val p = problems(f)
        assertTrue(p.toString(), p.any { "node s: no way from here to an end" in it })
        assertTrue(p.toString(), p.any { "node t: no way from here to an end" in it })
        assertFalse(p.toString(), p.any { "node q:" in it })   // q can still reach the end through a
    }

    @Test fun noEndAtAll() = assertProblem(FakeFlows.with {
        it.remove("e"); it["a"] = it.getValue("a").copy(next = "q"); it["s"] = it.getValue("s").copy(next = "q")
    }, "no end node")

    @Test fun questionWithOneAnswer() = assertProblem(FakeFlows.with {
        val q = it.getValue("q"); it["q"] = q.copy(answers = q.answers.take(1))
    }, "at least 2 answers")

    @Test fun missingQuoteRef() = assertProblem(FakeFlows.with { it["a"] = it.getValue("a").copy(quoteRefs = emptyList()) },
        "a: needs at least one quote_ref")

    @Test fun unknownQuoteRef() = assertProblem(FakeFlows.with { it["a"] = it.getValue("a").copy(quoteRefs = listOf("zz")) },
        "quote_ref 'zz' is not a quote")

    @Test fun endNeedsNoQuote() = assertEquals(emptyList<String>(), problems(FakeFlows.with { it["e"] = it.getValue("e").copy(quoteRefs = emptyList()) }))

    @Test fun actionWithoutNext() = assertProblem(FakeFlows.with { it["a"] = it.getValue("a").copy(next = null) }, "a: needs next")

    @Test fun unknownType() = assertProblem(FakeFlows.with { it["a"] = it.getValue("a").copy(type = NodeType.UNKNOWN) }, "type must be")

    @Test fun badTools() {
        assertProblem(FakeFlows.with { it["t"] = it.getValue("t").copy(tool = Tool(ToolKind.METRONOME, rateBpm = 0.0)) }, "rate_bpm")
        assertProblem(FakeFlows.with { it["t"] = it.getValue("t").copy(tool = Tool(ToolKind.TIMER, seconds = 0)) }, "timer needs seconds")
        assertProblem(FakeFlows.with { it["s"] = it.getValue("s").copy(tool = Tool(ToolKind.TIMESTAMP, label = "")) }, "timestamp needs a label")
        assertProblem(FakeFlows.with { it["s"] = it.getValue("s").copy(tool = null) }, "tool.kind")
        assertProblem(FakeFlows.with { it["a"] = it.getValue("a").copy(tool = Tool(ToolKind.TIMER, seconds = 5)) }, "only a tool node")
    }

    @Test fun badStatus() = assertProblem(FakeFlows.flow().copy(status = "final"), "status must be")

    /** The PC checker's fixture (also fake) passes the phone's checks too. */
    @Test fun pcFixturePassesPhoneValidator() {
        val f = listOf("../../tools", "../tools", "tools").map { File(it, "knowledge/flows-test-fixtures/good/fake-good.json") }.firstOrNull { it.isFile }
            ?: return   // not run from the repository
        assertEquals(emptyList<String>(), problems(FlowParser.parse(f.readText())))
    }
}

class FlowStoreTest {
    @Test fun acceptRefusesInvalidUnapprovedAndDuplicate() {
        val good = FakeFlows.flow()
        val broken = FakeFlows.with(start = "nope").copy(id = "broken")
        val draft = good.copy(id = "draft", status = "draft")
        val r = FlowStore.accept(listOf("a" to good, "b" to broken, "c" to draft, "d" to good), requireApproved = true)
        assertEquals(listOf("fake"), r.flows.map { it.id })
        assertEquals(3, r.rejected.size)
        assertTrue(r.rejected[0], r.rejected[0].startsWith("b: broken:"))
        assertTrue(r.rejected[1], "not approved" in r.rejected[1])
        assertTrue(r.rejected[2], "duplicate id" in r.rejected[2])
    }

    @Test fun testFolderMayHoldDrafts() {
        val draft = FakeFlows.flow().copy(status = "draft", test = true)
        assertEquals(1, FlowStore.accept(listOf("x" to draft), requireApproved = false).flows.size)
    }
}
