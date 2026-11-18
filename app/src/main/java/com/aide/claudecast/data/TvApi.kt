package com.aide.claudecast.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** A free live TV channel. */
@Serializable
data class Channel(
    val id: String,             // stable key (stream URL)
    val name: String,
    val logo: String? = null,
    val url: String,
    val categories: List<String> = emptyList(),
    val country: String = "",
    val part24: Boolean = false // "[Not 24/7]": only on the air part of the day
)

/**
 * Free live TV from the community-maintained iptv-org lists (no account, no key).
 *
 * Those lists mix official free channels with unauthorized rebroadcasts of paid cable
 * networks, so only streams served by known free-TV services and official broadcaster
 * hosts are kept. Bare-IP servers, header-spoofing entries and region-locked feeds
 * are dropped.
 */
class TvApi(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val base = "https://iptv-org.github.io/iptv"
    private val cacheMs = 12 * 60 * 60 * 1000L

    /** All English-language channels that pass the filter. */
    suspend fun allChannels(force: Boolean = false): List<Channel> =
        load("$base/languages/eng.m3u", "tv_eng.m3u", force)
            .filter { it.country in englishCountries || it.country.isBlank() }

    /** Channels the list ties to a US state, e.g. "KS". */
    suspend fun localChannels(stateCode: String, force: Boolean = false): List<Channel> {
        val code = stateCode.lowercase()
        return load("$base/subdivisions/us-$code.m3u", "tv_us_$code.m3u", force)
    }

    private suspend fun load(url: String, cacheName: String, force: Boolean): List<Channel> =
        withContext(Dispatchers.IO) {
            val cache = File(context.cacheDir, cacheName)
            val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < cacheMs
            val text = if (fresh && !force) cache.readText() else try {
                val req = Request.Builder().url(url).header("User-Agent", "ClaudeCast").build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> ""  // no list for that state
                        !resp.isSuccessful -> throw RuntimeException("HTTP ${resp.code}")
                        else -> resp.body?.string().orEmpty()
                    }
                }.also { if (it.isNotBlank()) runCatching { cache.writeText(it) } }
            } catch (e: Exception) {
                if (cache.exists()) cache.readText() else throw e
            }
            parse(text)
        }

    private fun parse(m3u: String): List<Channel> {
        val out = ArrayList<Channel>()
        val lines = m3u.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith("#EXTINF")) { i++; continue }
            // Collect option lines (#EXTVLCOPT etc.) until the URL.
            var j = i + 1
            var needsHeaders = false
            while (j < lines.size && lines[j].startsWith("#")) {
                if (lines[j].startsWith("#EXTVLCOPT") || lines[j].startsWith("#KODIPROP")) needsHeaders = true
                j++
            }
            val url = lines.getOrNull(j).orEmpty()
            i = j + 1

            val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
            if ("http-user-agent" in attrs || "http-referrer" in attrs) needsHeaders = true
            val rawName = line.substringAfterLast("\",", line.substringAfter(",")).trim()
            if (needsHeaders || !url.startsWith("http")) continue

            val tags = tagRegex.findAll(rawName).map { it.groupValues[1] }.joinToString(";")
            if ("Geo-blocked" in tags) continue
            val name = rawName.replace(tagRegex, "").replace(resRegex, "").trim()
            if (name.isBlank() || !allowed(name, url)) continue

            val tvgId = attrs["tvg-id"].orEmpty()
            out += Channel(
                id = url,
                name = name,
                logo = attrs["tvg-logo"]?.ifBlank { null },
                url = url,
                categories = attrs["group-title"].orEmpty().split(';')
                    .map { it.trim() }.filter { it.isNotBlank() && it != "Undefined" },
                country = tvgId.substringBefore('@').substringAfterLast('.', "").lowercase(),
                part24 = "Not 24/7" in tags
            )
        }
        return out.distinctBy { it.url }.sortedBy { it.name.lowercase() }
    }

    private fun allowed(name: String, url: String): Boolean {
        val host = hostRegex.find(url)?.groupValues?.get(1)?.lowercase() ?: return false
        if (host.first().isDigit()) return false
        val trusted = allowedHosts.any { host == it || host.endsWith(".$it") }
        if (!trusted) return false
        // Paid cable brands only count when they come from a free service that licenses them.
        if (paidBrands.containsMatchIn(name) && officialFreeServices.none { host == it || host.endsWith(".$it") }) {
            return false
        }
        return true
    }

    companion object {
        private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
        private val tagRegex = Regex("""\[([^\]]+)]""")
        private val resRegex = Regex("""\(\d{3,4}[pi]\)""")
        private val hostRegex = Regex("""^https?://([^/:?#]+)""")

        private val englishCountries = setOf("us", "ca", "uk", "gb", "au", "nz", "ie", "int")

        /** Free ad-supported TV services and official broadcaster CDNs. */
        private val officialFreeServices = listOf(
            "pluto.tv", "jmp2.uk", "tubi.video", "amagi.tv", "wurl.tv", "wurl.com",
            "samsung.com", "xumo.com", "plex.tv", "roku.com", "cbsnews.com", "cbsivideo.com"
        )

        private val allowedHosts = officialFreeServices + listOf(
            "klowdtv.com", "getpublica.com", "frequency.stream", "uplynk.com", "pbs.org",
            "pbskids.org", "bloomberg.com", "brightcove.com", "sinclairstoryline.com",
            "telvue.com", "cablecast.tv", "swagit.com", "champds.com", "streamguys1.com",
            "wowza.com", "bozztv.com", "streamhoster.com", "transmit.live", "ottera.tv",
            "otteravision.com", "30a-tv.com", "tripsmarter.com", "vos360.video", "g-mana.live",
            "fuelmedia.io", "akamaized.net", "cloudfront.net", "streamlock.net", "jstre.am",
            "tulix.tv", "abnvideos.com", "nasa.gov", "dc.gov", "ct-n.com", "redbox.com",
            "lightcast.com", "castr.io", "livepush.io"
        )

        private val paidBrands = Regex(
            """\b(ESPN|CNN|Fox (News|Sports|Business)|FS[12]\b|NFL Network|NBA TV|MLB Network|""" +
                """NHL Network|Big Ten Network|SEC Network|HBO|Showtime|Starz|Cinemax|AMC\b|""" +
                """Discovery|History|National Geographic|Nat Geo|Animal Planet|A&E|FX\b|TBS|TNT\b|""" +
                """Cartoon Network|Adult Swim|Disney|Nickelodeon|MTV|Bravo|USA Network|Syfy|CNBC|""" +
                """MSNBC|Comedy Central|Paramount Network|TLC\b|HGTV|Food Network|Travel Channel|""" +
                """Lifetime|beIN|AXS TV|Golf Channel|Tennis Channel|NBC Sports|Bally Sports)""",
            RegexOption.IGNORE_CASE
        )
    }
}
