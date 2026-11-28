package com.bastion.terminal.link

import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Streams CoT XML events from the local TAK server (mutual TLS on loopback)
 * and writes events to it. One background thread; reconnects until [stop].
 */
class TakClient(
    private val config: TakConfig,
    private val onState: (state: String, detail: String) -> Unit,
    private val onEvent: (xml: String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var out: OutputStream? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "bastion-tak").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.interrupt()
    }

    /** Sends one CoT event. False if not connected. */
    fun send(xml: String): Boolean = synchronized(this) {
        val o = out ?: return false
        runCatching { o.write(xml.toByteArray(Charsets.UTF_8)); o.flush() }.isSuccess
    }

    private fun context(): SSLContext {
        val keys = KeyStore.getInstance("PKCS12").apply {
            config.clientP12.inputStream().use { load(it, config.clientPassword.toCharArray()) }
        }
        val trust = KeyStore.getInstance("PKCS12").apply {
            config.trustP12.inputStream().use { load(it, config.trustPassword.toCharArray()) }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keys, config.clientPassword.toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
    }

    private fun loop() {
        var failures = 0
        while (running) {
            try {
                TakConfig.requireLoopback(config.host)
                onState("connecting", "")
                val s = context().socketFactory.createSocket() as SSLSocket
                socket = s
                // A literal IPv4 loopback address, never a name lookup: this cannot leave the phone.
                // (getLoopbackAddress() may be ::1, and Meshtastic's server listens on IPv4 only.)
                val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                s.connect(InetSocketAddress(loopback, config.port), 5_000)
                s.soTimeout = 10_000
                s.startHandshake()
                s.soTimeout = 0
                android.util.Log.i("BastionLink", "connected to local TAK server")
                out = s.outputStream
                failures = 0
                onState("connected", "")
                read(s)
                onState("disconnected", "The TAK server closed the connection.")
            } catch (t: Throwable) {
                android.util.Log.w("BastionLink", "connect failed: ${t.javaClass.simpleName}: ${t.message}")
                if (running) {
                    failures++
                    onState(
                        "disconnected",
                        when {
                            t is java.net.ConnectException -> "Meshtastic's local TAK server isn't running. Open Meshtastic → Settings → Advanced → TAK Server and switch it on."
                            t is javax.net.ssl.SSLException -> "The certificates were refused. Export the TAK data package from Meshtastic again and re-import it in BASTION (Mesh tab)."
                            else -> t.message ?: t.javaClass.simpleName
                        },
                    )
                }
            } finally {
                out = null
                runCatching { socket?.close() }
                socket = null
            }
            if (running) runCatching { Thread.sleep(minOf(30_000L, 3_000L * maxOf(1, failures))) }
        }
        onState("stopped", "")
    }

    private fun read(s: SSLSocket) {
        val input = s.inputStream
        val buf = ByteArray(8192)
        val sb = StringBuilder()
        while (running) {
            val n = input.read(buf)
            if (n < 0) return
            sb.append(String(buf, 0, n, Charsets.UTF_8))
            while (true) {
                val end = sb.indexOf(END)
                if (end < 0) break
                val start = sb.indexOf("<event")
                if (start in 0 until end) onEvent(sb.substring(start, end + END.length))
                sb.delete(0, end + END.length)
            }
            if (sb.length > 1_000_000) sb.setLength(0) // malformed stream: drop, don't grow forever
        }
    }

    private companion object { const val END = "</event>" }
}
