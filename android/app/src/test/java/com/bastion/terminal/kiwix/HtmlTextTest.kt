package com.bastion.terminal.kiwix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    private val html = """
        <html><head><title>PageTitleOnly</title><style>p{color:red}</style><script>var x=1;</script></head><body>
        <h1>Hypothermia</h1>
        <p><b>Hypothermia</b> is a body temperature below 35.0&nbsp;°C<sup class="reference"><a href="#c1">[1]</a></sup>.
        Symptoms include <i>shivering</i> &amp; confusion.[2][citation needed]</p>
        <details><summary><h2 id="Treatment">Treatment</h2></summary>
        <ul><li>Remove wet clothing</li><li>Insulate from the ground</li></ul>
        <p>Warm sweet drinks only if alert&#8212;not if confused &#x2014; ever.</p></details>
        <figure><img src="x.png"><figcaption>A caption</figcaption></figure>
        <h2>References</h2><ol class="references"><li>Some citation</li></ol>
        <h2>External links</h2><p>Link text</p>
        </body></html>
    """.trimIndent()

    @Test
    fun extract_keepsTextAndHeadings_dropsNoise() {
        val t = HtmlText.extract(html)
        assertTrue(t, t.contains("## Hypothermia"))
        assertTrue(t, t.contains("Hypothermia is a body temperature below 35.0 °C."))
        assertTrue(t, t.contains("Symptoms include shivering & confusion."))
        assertTrue(t, t.contains("## Treatment"))
        assertTrue(t, t.contains("- Remove wet clothing"))
        assertTrue(t, t.contains("Warm sweet drinks only if alert—not if confused — ever."))
        assertFalse(t, t.contains("[1]") || t.contains("[2]") || t.contains("citation needed"))
        assertFalse(t, t.contains("var x") || t.contains("color:red") || t.contains("caption"))
        assertFalse(t, t.contains("PageTitleOnly"))
        assertFalse(t, t.contains("References") || t.contains("Some citation") || t.contains("Link text"))
    }

    @Test
    fun decodeEntities_handlesNamedNumericAndUnknown() {
        assertEquals("a & b < c ° — x", HtmlText.decodeEntities("a &amp; b &lt; c &deg; &#x2014; x"))
        assertEquals("&bogus; stays", HtmlText.decodeEntities("&bogus; stays"))
    }
}
