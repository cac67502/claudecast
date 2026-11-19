package com.aide.claudecast.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ParsedFeed(
    val podcast: Podcast,
    val episodes: List<Episode>,
    val liveItems: List<LiveItem> = emptyList()
)

/**
 * Fetches a podcast RSS feed and parses it, including the Podcasting 2.0 `podcast:`
 * namespace (chapters, transcript, person, value/valueRecipient, soundbite, season,
 * episode) plus the itunes: namespace. Namespace processing stays OFF so tags arrive
 * as raw prefixed names like "podcast:transcript".
 */
class FeedParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun parse(feedUrl: String, maxItems: Int = 150): ParsedFeed = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(feedUrl).header("User-Agent", "ClaudeCast/1.0").build()
        val xml = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        parseXml(xml, feedUrl, maxItems)
    }

    private class EpBuilder {
        var title = ""
        var description = ""
        var pubDate = ""
        var guid: String? = null
        var enclosureUrl = ""
        var enclosureType = ""
        var altType = ""            // type of the podcast:alternateEnclosure being read
        var videoUrl: String? = null
        var durationRaw: String? = null
        var image: String? = null
        var chaptersUrl: String? = null
        val transcripts = mutableListOf<TranscriptRef>()
        val persons = mutableListOf<Person>()
        val soundbites = mutableListOf<Soundbite>()
        var value: ValueBlock? = null
        var season: Int? = null
        var episodeNum: Int? = null
    }

    private class LiveBuilder {
        var status = ""
        var start = 0L
        var end = 0L
        var title = ""
        var description = ""
        var guid: String? = null
        var url = ""
        var type = ""
        var image: String? = null
        var link: String? = null
    }

    /** ISO 8601 with an offset ("...Z", "...-06:00" or "...-0600") to epoch seconds, 0 if unreadable. */
    internal fun parseIsoDate(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        runCatching { return java.time.OffsetDateTime.parse(s).toEpochSecond() }
        runCatching {
            val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]xx")
            return java.time.OffsetDateTime.parse(s, f).toEpochSecond()
        }
        return 0L
    }

    internal fun parseXml(xml: String, feedUrl: String, maxItems: Int = 150): ParsedFeed {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var channelTitle = ""
        var channelAuthor = ""
        var channelDesc = ""
        var channelImage: String? = null
        var fundingUrl: String? = null
        var fundingMsg: String? = null

        val episodes = mutableListOf<Episode>()
        var inItem = false
        var ep: EpBuilder? = null

        // value block being assembled (channel-level or item-level)
        var vType = ""; var vMethod = ""; var vSuggested = ""
        var vRecipients: MutableList<ValueRecipient>? = null
        var channelValue: ValueBlock? = null

        // podcast:liveItem being read
        val liveItems = mutableListOf<LiveItem>()
        var inLive = false
        var live: LiveBuilder? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && episodes.size < maxItems) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name ?: ""
                    when {
                        name.equals("item", true) -> { inItem = true; ep = EpBuilder() }

                        name.equals("podcast:value", true) -> {
                            vType = parser.attr("type"); vMethod = parser.attr("method")
                            vSuggested = parser.attr("suggested"); vRecipients = mutableListOf()
                        }
                        name.equals("podcast:valueRecipient", true) -> {
                            vRecipients?.add(
                                ValueRecipient(
                                    name = parser.attr("name"),
                                    type = parser.attr("type"),
                                    address = parser.attr("address"),
                                    split = parser.attr("split").toIntOrNull() ?: 0
                                )
                            )
                        }

                        name.equals("podcast:liveItem", true) -> {
                            inLive = true
                            live = LiveBuilder().also {
                                it.status = parser.attr("status")
                                it.start = parseIsoDate(parser.attr("start"))
                                it.end = parseIsoDate(parser.attr("end"))
                            }
                        }
                        inLive -> live?.let { l ->
                            when {
                                name.equals("title", true) -> l.title = parser.textSafe()
                                name.equals("description", true) || name.equals("content:encoded", true) ->
                                    if (l.description.isEmpty()) l.description = parser.textSafe()
                                name.equals("guid", true) -> l.guid = parser.textSafe()
                                name.equals("enclosure", true) -> {
                                    l.url = parser.attr("url"); l.type = parser.attr("type")
                                }
                                name.equals("itunes:image", true) -> l.image = parser.attr("href").ifBlank { null }
                                name.equals("podcast:contentLink", true) -> l.link = parser.attr("href").ifBlank { null }
                            }
                        }

                        !inItem -> when {
                            name.equals("title", true) && channelTitle.isEmpty() ->
                                channelTitle = parser.textSafe()
                            name.equals("itunes:author", true) && channelAuthor.isEmpty() ->
                                channelAuthor = parser.textSafe()
                            name.equals("description", true) && channelDesc.isEmpty() ->
                                channelDesc = parser.textSafe()
                            name.equals("itunes:image", true) && channelImage == null ->
                                channelImage = parser.attr("href").ifBlank { null }
                            name.equals("url", true) && channelImage == null ->
                                channelImage = parser.textSafe().ifBlank { null }
                            name.equals("podcast:funding", true) -> {
                                fundingUrl = parser.attr("url").ifBlank { null }
                                fundingMsg = parser.textSafe().ifBlank { null }
                            }
                        }

                        else -> ep?.let { e ->
                            when {
                                name.equals("title", true) -> e.title = parser.textSafe()
                                name.equals("description", true) ->
                                    if (e.description.isEmpty()) e.description = parser.textSafe()
                                name.equals("content:encoded", true) ->
                                    if (e.description.isEmpty()) e.description = parser.textSafe()
                                name.equals("pubDate", true) -> e.pubDate = parser.textSafe()
                                name.equals("guid", true) -> e.guid = parser.textSafe()
                                name.equals("enclosure", true) -> {
                                    e.enclosureUrl = parser.attr("url"); e.enclosureType = parser.attr("type")
                                }
                                name.equals("podcast:alternateEnclosure", true) -> e.altType = parser.attr("type")
                                name.equals("podcast:source", true) -> {
                                    val u = parser.attr("uri")
                                    if (e.videoUrl == null && isVideoType(e.altType) && u.startsWith("http")) e.videoUrl = u
                                }
                                name.equals("itunes:duration", true) -> e.durationRaw = parser.textSafe()
                                name.equals("itunes:image", true) -> e.image = parser.attr("href").ifBlank { null }
                                name.equals("itunes:season", true) || name.equals("podcast:season", true) ->
                                    e.season = parser.textSafe().toIntOrNull()
                                name.equals("itunes:episode", true) || name.equals("podcast:episode", true) ->
                                    e.episodeNum = parser.textSafe().toIntOrNull()
                                name.equals("podcast:chapters", true) ->
                                    e.chaptersUrl = parser.attr("url").ifBlank { null }
                                name.equals("podcast:transcript", true) -> {
                                    val u = parser.attr("url")
                                    if (u.isNotBlank()) e.transcripts.add(TranscriptRef(u, parser.attr("type")))
                                }
                                name.equals("podcast:person", true) -> {
                                    val role = parser.attr("role"); val group = parser.attr("group")
                                    val img = parser.attr("img").ifBlank { null }
                                    val href = parser.attr("href").ifBlank { null }
                                    val pname = parser.textSafe()
                                    if (pname.isNotBlank()) e.persons.add(Person(pname, role, group, img, href))
                                }
                                name.equals("podcast:soundbite", true) -> {
                                    val st = parser.attr("startTime").toDoubleOrNull() ?: 0.0
                                    val du = parser.attr("duration").toDoubleOrNull() ?: 0.0
                                    val t = parser.textSafe()
                                    e.soundbites.add(Soundbite(st, du, t))
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val name = parser.name ?: ""
                    when {
                        name.equals("podcast:value", true) -> {
                            val block = ValueBlock(vType, vMethod, vSuggested, vRecipients?.toList() ?: emptyList())
                            if (inItem) ep?.value = block else if (!inLive) channelValue = block
                            vRecipients = null
                        }
                        name.equals("podcast:alternateEnclosure", true) -> ep?.altType = ""
                        name.equals("podcast:liveItem", true) -> {
                            live?.let { l ->
                                val url = cleanEnclosure(l.url)
                                if (url.isNotBlank()) {
                                    liveItems.add(
                                        LiveItem(
                                            id = "live:" + (l.guid?.ifBlank { null } ?: url),
                                            title = l.title.ifBlank { "Live show" },
                                            status = l.status,
                                            startSec = l.start,
                                            endSec = l.end,
                                            streamUrl = url,
                                            streamType = l.type,
                                            artworkUrl = l.image,
                                            description = l.description,
                                            linkUrl = l.link
                                        )
                                    )
                                }
                            }
                            inLive = false; live = null
                        }
                        name.equals("item", true) -> {
                            ep?.let { e ->
                                val id = e.guid?.ifBlank { null } ?: e.enclosureUrl.ifBlank { e.title }
                                episodes.add(
                                    Episode(
                                        id = id,
                                        feedUrl = feedUrl,
                                        title = e.title,
                                        description = e.description,
                                        datePublished = parseDate(e.pubDate),
                                        enclosureUrl = cleanEnclosure(e.enclosureUrl),
                                        durationSec = parseDuration(e.durationRaw),
                                        artworkUrl = e.image ?: channelImage,
                                        podcastTitle = channelTitle,
                                        chaptersUrl = e.chaptersUrl,
                                        transcripts = e.transcripts.toList(),
                                        persons = e.persons.toList(),
                                        soundbites = e.soundbites.toList(),
                                        value = e.value ?: channelValue,
                                        season = e.season,
                                        episodeNumber = e.episodeNum,
                                        videoUrl = e.videoUrl
                                            ?: if (isVideoType(e.enclosureType)) cleanEnclosure(e.enclosureUrl).ifBlank { null } else null
                                    )
                                )
                            }
                            inItem = false; ep = null
                        }
                    }
                }
            }
            event = parser.next()
        }

        val podcast = Podcast(
            feedUrl = feedUrl,
            title = channelTitle,
            author = channelAuthor,
            description = channelDesc,
            artworkUrl = channelImage,
            fundingUrl = fundingUrl,
            fundingMessage = fundingMsg
        )
        return ParsedFeed(podcast, episodes, liveItems.toList())
    }

    private fun XmlPullParser.attr(name: String): String =
        (0 until attributeCount).firstOrNull { getAttributeName(it).equals(name, true) }
            ?.let { getAttributeValue(it) } ?: ""

    /** Read text content of the current element, tolerant of empty/again-nested. */
    private fun XmlPullParser.textSafe(): String =
        runCatching { nextText().trim() }.getOrDefault("")

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z"
    )

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        for (f in dateFormats) {
            runCatching {
                val sdf = SimpleDateFormat(f, Locale.US)
                return sdf.parse(raw)!!.time / 1000L
            }
        }
        return 0L
    }

    /**
     * Strip known tracking / redirect prefixes from an enclosure URL (podtrac,
     * chartable, podsights, op3, etc.). This is a privacy measure — it removes the
     * analytics hops. NOTE: it does NOT remove host-side Dynamic Ad Insertion ads
     * (Megaphone/Art19/Omny/Acast), which are stitched into the audio at the origin
     * and cannot be stripped client-side without breaking playback.
     */
    private val trackingPrefixes = listOf(
        Regex("^https?://(www\\.)?dts\\.podtrac\\.com/(pts/)?redirect\\.[a-z0-9]+/", RegexOption.IGNORE_CASE),
        Regex("^https?://(www\\.)?podtrac\\.com/pts/redirect\\.[a-z0-9]+/", RegexOption.IGNORE_CASE),
        Regex("^https?://chrt\\.fm/track/[^/]+/", RegexOption.IGNORE_CASE),
        Regex("^https?://chtbl\\.com/track/[^/]+/", RegexOption.IGNORE_CASE),
        Regex("^https?://pdst\\.fm/e/", RegexOption.IGNORE_CASE),
        Regex("^https?://mgln\\.ai/e/[^/]+/", RegexOption.IGNORE_CASE),
        Regex("^https?://pscrb\\.fm/rss/p/", RegexOption.IGNORE_CASE),
        Regex("^https?://verifi\\.podscribe\\.com/rss/p/", RegexOption.IGNORE_CASE),
        Regex("^https?://op3\\.dev/e/", RegexOption.IGNORE_CASE),
        Regex("^https?://claritaspod\\.com/measure/", RegexOption.IGNORE_CASE),
        Regex("^https?://prefix\\.up\\.audio/s/", RegexOption.IGNORE_CASE),
        Regex("^https?://arttrk\\.com/p/[^/]+/", RegexOption.IGNORE_CASE),
        Regex("^https?://pdrl\\.fm/[^/]+/", RegexOption.IGNORE_CASE)
    )

    private fun isVideoType(type: String): Boolean =
        type.startsWith("video/", ignoreCase = true) || type.contains("mpegurl", ignoreCase = true)

    private fun cleanEnclosure(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return u
        var changed = true
        var guard = 0
        while (changed && guard++ < 8) {
            changed = false
            for (p in trackingPrefixes) {
                val m = p.find(u) ?: continue
                u = u.substring(m.range.last + 1)
                if (!u.startsWith("http", ignoreCase = true) && u.contains(".") && !u.startsWith("/")) {
                    u = "https://$u"
                }
                changed = true
            }
        }
        return u
    }

    private fun parseDuration(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return if (raw.contains(":")) {
            val parts = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
            when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> 0L
            }
        } else raw.trim().toDoubleOrNull()?.toLong() ?: 0L
    }
}
