package com.aide.claudecast.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A free, on-demand feature film from the Internet Archive. */
@Serializable
data class Movie(
    val id: String,             // archive.org identifier
    val title: String,
    val year: String = "",
    val description: String = "",
    val runtime: String = "",
    val downloads: Long = 0
) {
    val poster: String get() = "https://archive.org/services/img/$id"
    val page: String get() = "https://archive.org/details/$id"
}

/**
 * Internet Archive "Feature Films" collection: public-domain and freely licensed
 * movies, no account and no key. Search uses advancedsearch; the playable file comes
 * from the item's metadata.
 */
class MovieApi(context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("claudecast_movies", Context.MODE_PRIVATE)

    /** Most-watched first. [genre] is a subject keyword ("western", "noir"...). */
    suspend fun search(text: String = "", genre: String = "", page: Int = 1, rows: Int = 50): List<Movie> =
        withContext(Dispatchers.IO) {
            val q = buildString {
                append("collection:(feature_films) AND mediatype:(movies)")
                if (genre.isNotBlank()) append(" AND (subject:(\"$genre\") OR title:(\"$genre\"))")
                if (text.isNotBlank()) {
                    val t = text.replace("\"", "").trim()
                    append(" AND (title:(\"$t\") OR subject:(\"$t\") OR creator:(\"$t\"))")
                }
            }
            val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .apply {
                    listOf("identifier", "title", "year", "description", "runtime", "downloads")
                        .forEach { addQueryParameter("fl[]", it) }
                }
                .addQueryParameter("sort[]", "downloads desc")
                .addQueryParameter("rows", rows.toString())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("output", "json")
                .build()
            val body = get(url.toString())
            val docs = json.parseToJsonElement(body).jsonObject["response"]
                ?.jsonObject?.get("docs")?.jsonArray ?: return@withContext emptyList()
            docs.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.str("identifier")
                if (id.isBlank()) return@mapNotNull null
                Movie(
                    id = id,
                    title = o.str("title").ifBlank { id },
                    year = o.str("year").take(4),
                    description = o.str("description").stripHtml().take(600),
                    runtime = o.str("runtime"),
                    downloads = o["downloads"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
                )
            }
        }

    /** Finds the best phone-friendly video file for a movie. */
    suspend fun streamUrl(movie: Movie): String = withContext(Dispatchers.IO) {
        val body = get("https://archive.org/metadata/${movie.id}")
        val root = json.parseToJsonElement(body).jsonObject
        val files = root["files"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        val server = root.str("d1").ifBlank { "" }
        val dir = root.str("dir")

        fun score(f: JsonObject): Int {
            val name = f.str("name").lowercase()
            val format = f.str("format").lowercase()
            if (name.endsWith(".mp4").not() && "mpeg4" !in format && "h.264" !in format) return -1
            if ("sample" in name || "trailer" in name) return -1
            return when {
                "h.264" in format && "ia" !in format -> 50     // usually the best quality mp4
                "512kb" in format || "512kb" in name -> 40     // small, reliable
                "mpeg4" in format -> 30
                "h.264 ia" in format -> 20
                else -> 10
            }
        }
        val best = files.filter { score(it) >= 0 }.maxByOrNull { f ->
            score(f) * 1_000_000_000L + (f.str("size").toLongOrNull() ?: 0L).coerceAtMost(999_999_999L)
        } ?: throw RuntimeException("No playable video file")

        val name = best.str("name")
        val encoded = name.split('/').joinToString("/") { android.net.Uri.encode(it) }
        if (server.isNotBlank() && dir.isNotBlank()) "https://$server$dir/$encoded"
        else "https://archive.org/download/${movie.id}/$encoded"
    }

    /* Resume points, kept on the phone only. */
    fun resumeMs(id: String): Long = prefs.getLong("pos_$id", 0L)
    fun saveResume(id: String, ms: Long, durationMs: Long) {
        val done = durationMs > 0 && ms > durationMs - 3 * 60_000   // within 3 min of the end
        prefs.edit().apply { if (done) remove("pos_$id") else putLong("pos_$id", ms) }.apply()
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "ClaudeCast").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun JsonObject.str(key: String): String = when (val v: JsonElement? = this[key]) {
        is JsonPrimitive -> v.contentOrNull.orEmpty()
        is JsonArray -> v.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty()
        else -> ""
    }

    private fun String.stripHtml(): String =
        replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()
}
