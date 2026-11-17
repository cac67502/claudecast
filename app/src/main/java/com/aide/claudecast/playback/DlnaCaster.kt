package com.aide.claudecast.playback

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** A TV (or speaker) on the Wi-Fi that accepts DLNA "play this URL" commands. */
data class CastDevice(
    val name: String,
    val model: String,
    val controlUrl: String,
    val location: String
)

/** What to send to the TV. */
data class CastItem(
    val url: String,
    val title: String,
    val mime: String,
    val isVideo: Boolean,
    val isLive: Boolean,
    val startMs: Long
)

data class CastState(
    val scanning: Boolean = false,
    val devices: List<CastDevice> = emptyList(),
    val device: CastDevice? = null,
    val title: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isLive: Boolean = false,
    val message: String = ""
)

/**
 * DLNA / UPnP casting. Open standard, no Google: finds TVs with SSDP on the local
 * Wi-Fi and drives their AVTransport service with SOAP. The TV fetches the stream
 * itself, so only things on the internet can be cast (not files stored on the phone).
 */
class DlnaCaster(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private val relay = HlsRelay(context)

    val isCasting: Boolean get() = _state.value.device != null

    /* ---------------- Discovery ---------------- */

    fun scan() {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, message = "")
        scope.launch {
            val found = LinkedHashMap<String, CastDevice>()
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("claudecast-dlna").apply { setReferenceCounted(false) }
            try {
                lock.acquire()
                val locations = LinkedHashSet<String>()
                MulticastSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.bind(null)
                    sock.soTimeout = 1500
                    val group = InetAddress.getByName("239.255.255.250")
                    for (st in listOf(
                        "urn:schemas-upnp-org:service:AVTransport:1",
                        "urn:schemas-upnp-org:device:MediaRenderer:1"
                    )) {
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                        val bytes = msg.toByteArray()
                        repeat(2) { sock.send(DatagramPacket(bytes, bytes.size, group, 1900)) }
                    }
                    val deadline = System.currentTimeMillis() + 4500
                    val buf = ByteArray(4096)
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            sock.receive(p)
                            val text = String(p.data, 0, p.length)
                            text.lineSequence().firstOrNull { it.startsWith("LOCATION:", true) }
                                ?.substringAfter(':')?.trim()?.let { locations += it }
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
                for (loc in locations) {
                    runCatching { describe(loc) }.getOrNull()?.let { found[it.controlUrl] = it }
                    _state.value = _state.value.copy(devices = found.values.sortedBy { it.name.lowercase() })
                }
                _state.value = _state.value.copy(
                    scanning = false,
                    devices = found.values.sortedBy { it.name.lowercase() },
                    message = if (found.isEmpty())
                        "No TVs found. Make sure the TV is on and on the same Wi-Fi as your phone." else ""
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(scanning = false, message = "Couldn't search the Wi-Fi for TVs.")
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    /** Reads a device's description and finds its AVTransport control URL. */
    private fun describe(location: String): CastDevice? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() } ?: return null
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        fun text(e: Element, tag: String) = e.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
        val root = doc.documentElement
        val services = root.getElementsByTagName("service")
        var control: String? = null
        for (i in 0 until services.length) {
            val s = services.item(i) as Element
            if (text(s, "serviceType").contains("AVTransport")) { control = text(s, "controlURL"); break }
        }
        control ?: return null
        val base = text(root, "URLBase").ifBlank { location }
        val abs = URL(URL(base), control).toString()
        val device = root.getElementsByTagName("device").item(0) as? Element ?: return null
        return CastDevice(
            name = text(device, "friendlyName").ifBlank { "TV" },
            model = listOf(text(device, "manufacturer"), text(device, "modelName"))
                .filter { it.isNotBlank() }.joinToString(" "),
            controlUrl = abs,
            location = location
        )
    }

    /* ---------------- Control ---------------- */

    suspend fun start(device: CastDevice, original: CastItem): Boolean = withContext(Dispatchers.IO) {
        try {
            // Most TVs won't take HLS over DLNA, so the phone converts it to one continuous stream.
            relay.stop()
            val item = if (original.mime == "application/vnd.apple.mpegurl") {
                _state.value = _state.value.copy(message = "Converting the stream for the TV…")
                val (url, mime) = relay.start(original.url)
                original.copy(url = url, mime = mime, isVideo = mime.startsWith("video"))
            } else original
            val meta = didl(item)
            soap(device, "Stop", "", ignoreErrors = true)
            soap(device, "SetAVTransportURI",
                "<CurrentURI>${esc(item.url)}</CurrentURI><CurrentURIMetaData>${esc(meta)}</CurrentURIMetaData>")
            soap(device, "Play", "<Speed>1</Speed>")
            _state.value = _state.value.copy(
                device = device, title = item.title, playing = true, isLive = item.isLive,
                positionMs = 0, durationMs = 0, message = ""
            )
            if (!item.isLive && item.startMs > 5000) {
                // TVs often reject a seek until playback has actually started.
                scope.launch {
                    delay(3000)
                    runCatching { soap(device, "Seek", "<Unit>REL_TIME</Unit><Target>${hms(item.startMs)}</Target>") }
                }
            }
            startPolling()
            true
        } catch (e: HlsRelay.Unsupported) {
            relay.stop()
            _state.value = _state.value.copy(message = e.message ?: "This channel can't be cast.")
            false
        } catch (e: Exception) {
            relay.stop()
            _state.value = _state.value.copy(message = "${device.name} wouldn't play that. Some TVs only accept certain formats.")
            false
        }
    }

    fun togglePlay() {
        val d = _state.value.device ?: return
        val playing = _state.value.playing
        scope.launch {
            runCatching {
                if (playing) soap(d, "Pause", "") else soap(d, "Play", "<Speed>1</Speed>")
                _state.value = _state.value.copy(playing = !playing)
            }
        }
    }

    fun seekTo(ms: Long) {
        val d = _state.value.device ?: return
        scope.launch { runCatching { soap(d, "Seek", "<Unit>REL_TIME</Unit><Target>${hms(ms)}</Target>") } }
    }

    /** Stops the TV and returns where it was (for resuming on the phone). */
    suspend fun stop(): Long = withContext(Dispatchers.IO) {
        val d = _state.value.device
        val pos = _state.value.positionMs
        pollJob?.cancel()
        if (d != null) runCatching { soap(d, "Stop", "", ignoreErrors = true) }
        relay.stop()
        _state.value = _state.value.copy(device = null, playing = false, title = "", positionMs = 0, durationMs = 0)
        pos
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && _state.value.device != null) {
                delay(1000)
                val d = _state.value.device ?: break
                runCatching {
                    val r = soap(d, "GetPositionInfo", "")
                    val pos = parseHms(tag(r, "RelTime"))
                    val dur = parseHms(tag(r, "TrackDuration"))
                    _state.value = _state.value.copy(positionMs = pos, durationMs = dur)
                }
                runCatching {
                    val t = tag(soap(d, "GetTransportInfo", ""), "CurrentTransportState")
                    if (t.isNotBlank()) _state.value = _state.value.copy(playing = t == "PLAYING" || t == "TRANSITIONING")
                }
            }
        }
    }

    private fun soap(d: CastDevice, action: String, args: String, ignoreErrors: Boolean = false): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">""" +
            "<InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        val req = Request.Builder().url(d.controlUrl)
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && !ignoreErrors) throw RuntimeException("$action failed: HTTP ${resp.code}")
            return text
        }
    }

    private fun didl(item: CastItem): String {
        val cls = if (item.isVideo) "object.item.videoItem" else "object.item.audioItem.musicTrack"
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1"><dc:title>${esc(item.title)}</dc:title>""" +
            "<upnp:class>$cls</upnp:class>" +
            """<res protocolInfo="http-get:*:${item.mime}:DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000">${esc(item.url)}</res></item></DIDL-Lite>"""
    }

    private fun tag(xml: String, name: String) =
        Regex("<(?:\\w+:)?$name>([^<]*)</").find(xml)?.groupValues?.get(1)?.trim().orEmpty()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun hms(ms: Long): String {
        val t = ms / 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
    }

    private fun parseHms(s: String): Long {
        val p = s.substringBefore('.').split(':').mapNotNull { it.toLongOrNull() }
        if (p.size != 3) return 0
        return ((p[0] * 3600) + (p[1] * 60) + p[2]) * 1000
    }
}
