package com.aide.claudecast.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Turns an HLS stream (a playlist of short chunks, which many TVs won't take over DLNA)
 * into one continuous MPEG-TS stream served from the phone on the home Wi-Fi.
 * No re-encoding: chunks are fetched, decrypted if needed, and passed straight through.
 */
class HlsRelay(private val context: Context) {

    class Unsupported(msg: String) : Exception(msg)

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    var onLog: ((String) -> Unit)? = null

    @Volatile private var mediaPlaylistUrl: String = ""
    @Volatile private var contentType: String = "video/mp2t"

    /**
     * Checks the stream can be relayed and starts serving it.
     * Returns the phone's URL for the TV, and the content type.
     */
    suspend fun start(hlsUrl: String): Pair<String, String> {
        stop()
        val media = resolveMediaPlaylist(hlsUrl)
        val text = getPlaylist(media).first
        onLog?.invoke("Playlist OK: ${media.substringBefore('?').takeLast(80)}")
        if ("#EXT-X-MAP" in text) throw Unsupported("This channel uses a newer stream format the converter can't handle.")
        if ("METHOD=SAMPLE-AES" in text) throw Unsupported("This channel is copy-protected and can't be cast.")
        val firstSeg = segments(media, text).lastOrNull() ?: throw Unsupported("Empty stream.")
        val sample = try {
            fetchSegment(firstSeg, keyCache = HashMap())
        } catch (e: Exception) {
            throw RuntimeException("first chunk failed: ${e.message}")
        }
        contentType = when {
            sample.isNotEmpty() && sample[0] == 0x47.toByte() -> "video/mp2t"
            sample.size > 1 && sample[0] == 0xFF.toByte() && (sample[1].toInt() and 0xF0) == 0xF0 -> "audio/aac"
            sample.size > 2 && String(sample, 0, 3) == "ID3" -> "audio/aac"
            else -> throw Unsupported("This channel's chunks aren't in a format the converter can pass through.")
        }
        mediaPlaylistUrl = media

        val ip = wifiAddress() ?: throw Unsupported("Your phone needs to be on Wi-Fi to cast this.")
        val ss = ServerSocket(0, 8, ip)
        server = ss
        val sc = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = sc
        acquireLocks()
        sc.launch {
            while (isActive) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                sc.launch { serve(client) }
            }
        }
        val ext = if (contentType == "video/mp2t") "ts" else "aac"
        return "http://${ip.hostAddress}:${ss.localPort}/live.$ext" to contentType
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        scope?.cancel()
        scope = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    /* ---------------- Serving ---------------- */

    private suspend fun serve(socket: Socket) {
        socket.use { s ->
            s.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            onLog?.invoke("TV requested: ${requestLine.take(60)} from ${s.inetAddress.hostAddress}")
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
            val out = s.getOutputStream()
            val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                "\r\n"
            out.write(headers.toByteArray())
            out.flush()
            if (requestLine.startsWith("HEAD")) return
            pump(out)
        }
    }

    /** Follows the live playlist and writes each new chunk as it appears. */
    private suspend fun pump(out: OutputStream) {
        val keys = HashMap<String, ByteArray>()
        val sent = LinkedHashSet<String>()
        var first = true
        val sc = scope ?: return
        while (sc.isActive) {
            val fetched = runCatching { getPlaylist(mediaPlaylistUrl) }.getOrNull()
            if (fetched == null) { delay(2000); continue }
            val (text, base) = fetched
            val segs = segments(base, text)
            // Start near the live edge, like a TV channel.
            val todo = (if (first) segs.takeLast(3) else segs).filter { it.url !in sent }
            first = false
            for (seg in todo) {
                val bytes = runCatching { fetchSegment(seg, keys) }.getOrNull() ?: continue
                try {
                    out.write(bytes)
                    out.flush()
                    if (sent.isEmpty()) onLog?.invoke("Sending video to the TV")
                } catch (e: Exception) {
                    onLog?.invoke("TV closed the connection")
                    return
                }
                sent += seg.url
                if (sent.size > 200) sent.remove(sent.first())
            }
            if ("#EXT-X-ENDLIST" in text && todo.isEmpty()) return
            val target = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 6
            delay((target * 1000 / 2).coerceIn(1000, 5000))
        }
    }

    /* ---------------- HLS parsing ---------------- */

    private data class Segment(val url: String, val seq: Long, val keyUrl: String?, val iv: ByteArray?)

    private fun resolveMediaPlaylist(start: String): String {
        val (text, url) = getPlaylist(start)
        if ("#EXT-X-STREAM-INF" !in text) return url
        // Master playlist: pick a variant around 3 Mbps that carries its own audio.
        val lines = text.lines()
        data class V(val bw: Long, val url: String, val separateAudio: Boolean)
        val variants = ArrayList<V>()
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            variants += V(bw, URL(URL(url), next.trim()).toString(), "AUDIO=" in l)
        }
        if (variants.isEmpty()) return url
        val pool = variants.filter { !it.separateAudio }.ifEmpty { variants }
        val pick = pool.filter { it.bw in 1..4_000_000 }.maxByOrNull { it.bw } ?: pool.minByOrNull { it.bw }!!
        return resolveMediaPlaylist(pick.url)
    }

    private fun segments(base: String, text: String): List<Segment> {
        val out = ArrayList<Segment>()
        var seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        var keyUrl: String? = null
        var iv: ByteArray? = null
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-KEY") -> {
                    val method = Regex("METHOD=([^,]+)").find(l)?.groupValues?.get(1)
                    if (method == "AES-128") {
                        keyUrl = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { URL(URL(base), it).toString() }
                        iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(l)?.groupValues?.get(1)?.let { hex(it) }
                    } else { keyUrl = null; iv = null }
                }
                l.isEmpty() || l.startsWith("#") -> Unit
                else -> { out += Segment(URL(URL(base), l).toString(), seq, keyUrl, iv); seq++ }
            }
        }
        return out
    }

    private fun fetchSegment(seg: Segment, keyCache: HashMap<String, ByteArray>): ByteArray {
        val data = getBytes(seg.url)
        val keyUrl = seg.keyUrl ?: return data
        val key = keyCache.getOrPut(keyUrl) { getBytes(keyUrl) }
        val iv = seg.iv ?: ByteArray(16).also { b ->
            var n = seg.seq
            for (i in 15 downTo 8) { b[i] = (n and 0xFF).toByte(); n = n shr 8 }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun get(url: String): String = String(getBytes(url))

    /** Fetches a playlist and returns it with its final address (after redirects). */
    private fun getPlaylist(url: String): Pair<String, String> {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("playlist HTTP ${r.code} from ${r.request.url.host}")
            return (r.body?.string().orEmpty()) to r.request.url.toString()
        }
    }

    private fun getBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} from ${r.request.url.host}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun hex(s: String): ByteArray {
        val clean = s.padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /* ---------------- Network ---------------- */

    private fun wifiAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        return cm.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudecast:relay").apply {
                setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
            }
        }
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "claudecast:relay").apply {
                setReferenceCounted(false); acquire()
            }
        }
    }
}
