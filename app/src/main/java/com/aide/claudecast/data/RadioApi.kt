package com.aide.claudecast.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Radio Browser (radio-browser.info): a free, community-run directory of ~50k stations.
 * No account and no API key. Tries several mirrors in case one is down.
 */
class RadioApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val mirrors = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )
    private val json = Json { ignoreUnknownKeys = true }

    /** English-language stations by name and/or genre, most listened to (last 24h) first. */
    suspend fun search(name: String = "", tag: String = "", limit: Int = 60): List<Station> =
        query(mapOf("name" to name, "tag" to tag, "language" to "english"), limit)

    /**
     * Stations in a US state, most listened to first. The directory lists some stations
     * under the full name ("Kansas") and some under the abbreviation ("KS"), so ask for both.
     * No language filter: many local stations leave that field blank.
     */
    suspend fun searchLocal(stateName: String, stateCode: String, limit: Int = 100): List<Station> {
        val full = runCatching {
            query(mapOf("countrycode" to "US", "state" to stateName, "stateExact" to "true"), limit)
        }
        val abbr = runCatching {
            query(mapOf("countrycode" to "US", "state" to stateCode, "stateExact" to "true"), limit)
        }
        if (full.isFailure && abbr.isFailure) throw full.exceptionOrNull()!!
        return (full.getOrDefault(emptyList()) + abbr.getOrDefault(emptyList()))
            .distinctBy { it.uuid }
            .sortedByDescending { it.plays }
    }

    private suspend fun query(params: Map<String, String>, limit: Int): List<Station> =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (base in mirrors) {
                try {
                    val url = "$base/json/stations/search".toHttpUrl().newBuilder()
                        .apply {
                            params.forEach { (k, v) -> if (v.isNotBlank()) addQueryParameter(k, v.trim()) }
                            addQueryParameter("hidebroken", "true")
                            addQueryParameter("order", "clickcount")
                            addQueryParameter("reverse", "true")
                            addQueryParameter("limit", limit.toString())
                        }.build()
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "ClaudeCast/1.2")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                        return@withContext parse(resp.body?.string().orEmpty())
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: RuntimeException("Radio directory unavailable")
        }

    private fun parse(body: String): List<Station> {
        val arr: JsonArray = json.parseToJsonElement(body).jsonArray
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = s("url_resolved").ifBlank { s("url") }
            if (url.isBlank()) return@mapNotNull null
            Station(
                uuid = s("stationuuid"),
                name = s("name").trim().ifBlank { "Unnamed station" },
                url = url,
                favicon = s("favicon").ifBlank { null },
                tags = s("tags"),
                country = s("countrycode"),
                codec = s("codec"),
                bitrate = o["bitrate"]?.jsonPrimitive?.intOrNull ?: 0,
                plays = o["clickcount"]?.jsonPrimitive?.intOrNull ?: 0,
                hls = (o["hls"]?.jsonPrimitive?.intOrNull ?: 0) == 1 ||
                    o["hls"]?.jsonPrimitive?.booleanOrNull == true
            )
        }.distinctBy { it.uuid }.sortedByDescending { it.plays }
    }
}
