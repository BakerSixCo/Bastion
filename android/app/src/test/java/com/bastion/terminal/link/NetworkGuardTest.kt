package com.bastion.terminal.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BASTION declares INTERNET for exactly one thing: the loopback connection to
 * the Meshtastic app's local TAK server. Android cannot enforce "loopback
 * only", so this test does: it fails the build if network APIs appear
 * anywhere but link/TakClient.kt, or if that file could reach another host.
 */
class NetworkGuardTest {
    private val src = sequenceOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private val networkApis = listOf(
        "java.net.", "javax.net.", "HttpURLConnection", "HttpsURLConnection", "okhttp", "OkHttp",
        "Socket(", "ServerSocket", "DatagramSocket", "URL(", "openConnection", "WebView", "ktor", "retrofit",
    )

    @Test
    fun networkCodeLivesOnlyInTakClient() {
        val offenders = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("link/TakClient.kt") }
            .flatMap { f -> networkApis.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
            .toList()
        assertEquals("network API used outside link/TakClient.kt", emptyList<String>(), offenders)
    }

    @Test
    fun takClientCanOnlyReachThisPhone() {
        val text = src.walkTopDown().first { it.invariantSeparatorsPath.endsWith("link/TakClient.kt") }.readText()
        assertTrue("must connect to a literal 127.0.0.1", text.contains("byteArrayOf(127, 0, 0, 1)"))
        assertTrue(text.contains("TakConfig.requireLoopback(config.host)"))
        // No name lookups and no other address constructors.
        assertFalse(text.contains("getByName"))
        assertFalse(text.contains("getAllByName"))
        assertEquals(1, text.split("InetSocketAddress(").size - 1)
    }

    @Test
    fun configRejectsNonLoopbackHosts() {
        TakConfig.requireLoopback("127.0.0.1")
        TakConfig.requireLoopback("localhost")
        for (bad in listOf("192.168.1.5", "example.com", "10.0.2.2", "0.0.0.0", "")) {
            assertTrue(runCatching { TakConfig.requireLoopback(bad) }.isFailure)
        }
    }
}
