package com.aide.claudecast.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Discovery via Apple's public iTunes Search API — no key, no signup, no auth.
 * Returns podcasts keyed by their RSS feedUrl; episodes + all PC2.0 metadata then
 * come from parsing that feed directly (see FeedParser).
 */
class ItunesApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "ClaudeCast/1.0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    /** Free-text search. country defaults to US; tweak if you like. */
    suspend fun search(term: String, limit: Int = 50): List<Podcast> {
        val url = "https://itunes.apple.com/search?media=podcast&limit=$limit&term=${enc(term)}"
        return parseResults(get(url))
    }

    /** Top charts -> collect ids -> single lookup to resolve feedUrls. */
    suspend fun popular(count: Int = 24): List<Podcast> {
        val chartUrl = "https://rss.applemarketingtools.com/api/v2/us/podcasts/top/$count/podcasts.json"
        val ids = runCatching {
            val root = json.parseToJsonElement(get(chartUrl)).jsonObject
            root["feed"]?.jsonObject?.get("results")?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                ?: emptyList()
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) return emptyList()
        val lookupUrl = "https://itunes.apple.com/lookup?id=${ids.joinToString(",")}"
        return parseResults(get(lookupUrl))
    }

    private fun parseResults(body: String): List<Podcast> {
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val feed = o["feedUrl"]?.jsonPrimitive?.contentOrNull
                if (feed.isNullOrBlank()) return@runCatching null
                Podcast(
                    feedUrl = feed,
                    title = o["collectionName"]?.jsonPrimitive?.contentOrNull
                        ?: o["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    author = o["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    artworkUrl = o["artworkUrl600"]?.jsonPrimitive?.contentOrNull
                        ?: o["artworkUrl100"]?.jsonPrimitive?.contentOrNull
                )
            }.getOrNull()
        }.distinctBy { it.feedUrl }
    }
}
