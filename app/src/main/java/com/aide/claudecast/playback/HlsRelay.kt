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
    @Volatile var restartCount = 0

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
        lastInfo = null
        resumeSeq = null
        totalChunks = 0
        restartCount = 0

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

    /** Called when the TV must be told to reconnect: at a stream change, or after a drop. */
    var onRestartNeeded: ((reason: String) -> Unit)? = null

    /** Where the next TV connection should pick up (so a restart doesn't skip anything). */
    @Volatile private var resumeSeq: Long? = null
    @Volatile private var totalChunks = 0L

    /** What the previous chunk looked like; survives TV reconnects. */
    @Volatile private var lastInfo: TsInspector.Info? = null

    // The last few chunks (as fetched, and as sent after stitching), kept for a drop dump.
    private val recentRaw = java.util.ArrayDeque<ByteArray>()
    private val recentOut = java.util.ArrayDeque<ByteArray>()

    private fun remember(raw: ByteArray, sent: ByteArray) {
        synchronized(recentRaw) {
            recentRaw.addLast(raw); recentOut.addLast(sent)
            while (recentRaw.size > 4) recentRaw.removeFirst()
            while (recentOut.size > 4) recentOut.removeFirst()
        }
    }

    /** Writes the recent chunks to the app's cache so they can be inspected after a drop. */
    private fun dumpRecent(seq: Long): String = try {
        val dir = java.io.File(context.cacheDir, "castdump").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        synchronized(recentRaw) {
            java.io.File(dir, "drop-$seq-raw.ts").outputStream().use { o -> recentRaw.forEach { o.write(it) } }
            java.io.File(dir, "drop-$seq-sent.ts").outputStream().use { o -> recentOut.forEach { o.write(it) } }
        }
        "cache/castdump/drop-$seq-*.ts"
    } catch (e: Exception) {
        "nowhere (${e.javaClass.simpleName})"
    }

    /**
     * Follows the live playlist and writes each chunk as it appears. When the channel
     * switches feeds (ads), many TVs can't follow the change inside one stream, so this
     * connection ends there and the TV is restarted at that exact chunk.
     */
    private suspend fun pump(out: OutputStream) {
        val keys = HashMap<String, ByteArray>()
        var lastSeq: Long? = null
        var connChunks = 0
        var lastReport = System.currentTimeMillis()
        val sc = scope ?: return
        val startAt = resumeSeq
        val stitcher = if (contentType == "video/mp2t") TsStitcher() else null
        val normalizer = if (contentType == "video/mp2t") TsNormalizer() else null
        var lastNote = ""
        if (startAt != null) onLog?.invoke("TV reconnected, continuing at chunk $startAt")
        while (sc.isActive) {
            val fetched = runCatching { getPlaylist(mediaPlaylistUrl) }.getOrNull()
            if (fetched == null) { delay(2000); continue }
            val (text, base) = fetched
            val segs = segments(base, text)
            val todo = when {
                lastSeq != null -> segs.filter { it.seq > lastSeq!! }
                startAt != null && segs.any { it.seq >= startAt } -> segs.filter { it.seq >= startAt }
                else -> segs.takeLast(3)    // start near the live edge, like a TV channel
            }
            for (seg in todo) {
                val raw = runCatching { fetchSegment(seg, keys) }.getOrNull() ?: continue
                // Ads switch to a new timeline; stitch them in so the TV sees one broadcast.
                val newTimeline = seg.afterBreak && connChunks > 0
                // Describe what this chunk is, so a drop can be matched to what changed.
                val info = if (stitcher != null) TsInspector.describe(raw) else null
                val before = lastInfo
                // One fixed stream layout for the TV (ads often differ from the show), then
                // stitch the timelines together.
                val prepared = normalizer?.process(raw) ?: raw
                val bytes = if (stitcher != null) stitcher.process(prepared, newTimeline) else prepared
                normalizer?.note?.let { n ->
                    if (n != lastNote) { lastNote = n; onLog?.invoke("Normalizer: $n (chunk ${seg.seq})") }
                }
                if (newTimeline) {
                    onLog?.invoke("Stitched stream change (ad break or program switch) at chunk ${seg.seq}")
                    onLog?.invoke("  was: ${before?.text ?: "?"}")
                    onLog?.invoke("  now: ${info?.text ?: "?"}")
                    if (stitcher != null) onLog?.invoke("  sent: ${TsInspector.describe(bytes).text}")
                } else if (info != null && before != null && info.signature != before.signature) {
                    onLog?.invoke("Format changed WITHOUT a break at chunk ${seg.seq}")
                    onLog?.invoke("  was: ${before.text}")
                    onLog?.invoke("  now: ${info.text}")
                } else if (info != null && before == null) {
                    onLog?.invoke("First chunk: ${info.text}")
                }
                if (info != null) lastInfo = info
                remember(raw, bytes)
                try {
                    out.write(bytes)
                    out.flush()
                } catch (e: Exception) {
                    if (connChunks >= 2) {
                        // A real drop, not the TV's quick format check. Pick up right after.
                        resumeSeq = seg.seq
                        onLog?.invoke("TV closed the connection at chunk ${seg.seq} (${e.javaClass.simpleName}: ${e.message}), reconnecting")
                        onLog?.invoke("  chunk being sent: ${info?.text ?: "?"}")
                        onLog?.invoke("  saved the last chunks to ${dumpRecent(seg.seq)}")
                        onRestartNeeded?.invoke("dropped")
                    }
                    return
                }
                if (connChunks == 0) onLog?.invoke("Sending video to the TV")
                connChunks++
                totalChunks++
                lastSeq = seg.seq
                resumeSeq = seg.seq + 1
                if (System.currentTimeMillis() - lastReport > 60_000) {
                    lastReport = System.currentTimeMillis()
                    onLog?.invoke("Still sending: $totalChunks chunks so far")
                }
            }
            if ("#EXT-X-ENDLIST" in text && todo.isEmpty()) return
            val target = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 6
            delay((target * 1000 / 2).coerceIn(1000, 5000))
        }
    }

    /**
     * Keeps this connection alive with MPEG-TS filler (null packets, which TVs ignore)
     * until the TV switches to the new connection, so it never sees the stream end
     * and doesn't pop its "unable to connect" screen.
     */
    private suspend fun holdOpen(out: OutputStream) {
        val filler = if (contentType == "video/mp2t") {
            val pkt = ByteArray(188) { 0xFF.toByte() }.also {
                it[0] = 0x47; it[1] = 0x1F; it[2] = 0xFF.toByte(); it[3] = 0x10
            }
            ByteArray(188 * 7).also { buf -> for (k in 0 until 7) pkt.copyInto(buf, k * 188) }
        } else null
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            try {
                if (filler != null) { out.write(filler); out.flush() }
            } catch (e: Exception) {
                return   // TV let go of the old connection: switch-over done
            }
            delay(400)
        }
    }

    /* ---------------- HLS parsing ---------------- */

    private data class Segment(
        val url: String, val seq: Long, val keyUrl: String?, val iv: ByteArray?, val afterBreak: Boolean = false
    )

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
        var brk = false
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-DISCONTINUITY") && !l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> brk = true
                l.startsWith("#EXT-X-KEY") -> {
                    val method = Regex("METHOD=([^,]+)").find(l)?.groupValues?.get(1)
                    if (method == "AES-128") {
                        keyUrl = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { URL(URL(base), it).toString() }
                        iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(l)?.groupValues?.get(1)?.let { hex(it) }
                    } else { keyUrl = null; iv = null }
                }
                l.isEmpty() || l.startsWith("#") -> Unit
                else -> { out += Segment(URL(URL(base), l).toString(), seq, keyUrl, iv, brk); brk = false; seq++ }
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
