package com.bastion.link

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.zip.ZipInputStream

/**
 * The connection details from the Meshtastic app's "Export TAK Data Package"
 * (.zip: a .pref with the address and certificate passwords, a client
 * certificate, a trust certificate). Kept in Link's private storage only.
 */
class TakConfig(val host: String, val port: Int, val clientP12: File, val trustP12: File,
                val clientPassword: String, val trustPassword: String) {

    companion object {
        private fun dir(ctx: Context) = File(ctx.filesDir, "tak").apply { mkdirs() }

        /** Loopback only. Link exists to talk to this phone's Meshtastic app and nothing else. */
        fun requireLoopback(host: String) {
            require(host == "127.0.0.1" || host == "localhost" || host == "::1") {
                "BASTION Link only connects to this phone (127.0.0.1); the package points at \"$host\"."
            }
        }

        fun load(ctx: Context): TakConfig? {
            val d = dir(ctx)
            val f = File(d, "config.properties")
            if (!f.isFile) return null
            val p = Properties().apply { f.inputStream().use(::load) }
            val host = p.getProperty("host") ?: return null
            return runCatching {
                requireLoopback(host)
                TakConfig(host, p.getProperty("port").toInt(), File(d, "client.p12"), File(d, "truststore.p12"),
                    p.getProperty("clientPassword"), p.getProperty("trustPassword"))
            }.getOrNull()?.takeIf { it.clientP12.isFile && it.trustP12.isFile }
        }

        /** Imports a data package. Returns a plain-words result; throws nothing. */
        fun import(ctx: Context, zip: InputStream): String = try {
            var pref: String? = null
            val files = HashMap<String, ByteArray>()
            ZipInputStream(zip).use { z ->
                var total = 0
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val bytes = z.readBytes()
                    total += bytes.size
                    require(total < 2_000_000) { "package is too large to be a TAK data package" }
                    val name = e.name.substringAfterLast('/')
                    if (name.endsWith(".pref")) pref = String(bytes, Charsets.UTF_8) else files[name] = bytes
                }
            }
            val xml = pref ?: error("no .pref file inside — is this a TAK data package?")
            fun entry(key: String) =
                Regex("key=\"$key\"[^>]*>([^<]*)<").find(xml)?.groupValues?.get(1)
            val connect = entry("connectString0") ?: error("no server address in the package")
            val parts = connect.split(':')
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: error("bad server address \"$connect\"")
            requireLoopback(host)
            val client = files[(entry("certificateLocation") ?: "client.p12").substringAfterLast('/')]
                ?: error("client certificate missing from the package")
            val trust = files[(entry("caLocation") ?: "truststore.p12").substringAfterLast('/')]
                ?: error("trust certificate missing from the package")
            val d = dir(ctx)
            File(d, "client.p12").writeBytes(client)
            File(d, "truststore.p12").writeBytes(trust)
            Properties().apply {
                setProperty("host", host); setProperty("port", port.toString())
                setProperty("clientPassword", entry("clientPassword").orEmpty())
                setProperty("trustPassword", entry("caPassword").orEmpty())
            }.let { p -> File(d, "config.properties").outputStream().use { p.store(it, null) } }
            "Imported. Link will connect to $host:$port when BASTION asks."
        } catch (t: Throwable) {
            "Could not import: ${t.message ?: t.javaClass.simpleName}"
        }

        fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }
    }
}
