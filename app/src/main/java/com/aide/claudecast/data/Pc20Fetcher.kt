package com.aide.claudecast.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fetches + parses PC2.0 chapters JSON and transcript files (SRT / VTT / JSON / plain). */
class Pc20Fetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "ClaudeCast/1.0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    suspend fun chapters(url: String): List<Chapter> = runCatching {
        val root = json.parseToJsonElement(fetch(url)).jsonObject
        root["chapters"]?.jsonArray?.mapNotNull { el ->
            val c = el.jsonObject
            val start = c["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            Chapter(
                startSec = start,
                title = c["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                imageUrl = c["img"]?.jsonPrimitive?.contentOrNull,
                linkUrl = c["url"]?.jsonPrimitive?.contentOrNull
            )
        }?.sortedBy { it.startSec } ?: emptyList()
    }.getOrDefault(emptyList())

    suspend fun transcript(ref: TranscriptRef): List<TranscriptCue> = runCatching {
        val raw = fetch(ref.url)
        val type = ref.type.lowercase()
        when {
            type.contains("json") || raw.trimStart().startsWith("{") -> parseJsonTranscript(raw)
            type.contains("vtt") || raw.trimStart().startsWith("WEBVTT") -> parseSrtVtt(raw, vtt = true)
            type.contains("srt") -> parseSrtVtt(raw, vtt = false)
            type.contains("html") -> listOf(TranscriptCue(0.0, 0.0, null, stripHtml(raw)))
            else -> parseSrtVtt(raw, vtt = false).ifEmpty {
                listOf(TranscriptCue(0.0, 0.0, null, raw.trim()))
            }
        }
    }.getOrDefault(emptyList())

    private fun parseJsonTranscript(raw: String): List<TranscriptCue> {
        val root = json.parseToJsonElement(raw).jsonObject
        val segs = root["segments"]?.jsonArray ?: return emptyList()
        return segs.mapNotNull { el ->
            val s = el.jsonObject
            val start = s["startTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val end = s["endTime"]?.jsonPrimitive?.doubleOrNull ?: start
            val body = s["body"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val speaker = s["speaker"]?.jsonPrimitive?.contentOrNull
            TranscriptCue(start, end, speaker, body)
        }
    }

    private fun parseSrtVtt(raw: String, vtt: Boolean): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        val blocks = raw.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        val tsRegex = Regex("(\\d{1,2}:\\d{2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}[.,]\\d{1,3})")
        for (block in blocks) {
            val lines = block.trim().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val tsLine = lines.firstOrNull { tsRegex.containsMatchIn(it) } ?: continue
            val m = tsRegex.find(tsLine) ?: continue
            val start = parseTimecode(m.groupValues[1])
            val end = parseTimecode(m.groupValues[2])
            val textLines = lines.dropWhile { !tsRegex.containsMatchIn(it) }.drop(1)
            val text = stripHtml(textLines.joinToString(" ")).trim()
            if (text.isNotBlank()) cues.add(TranscriptCue(start, end, null, text))
        }
        return cues
    }

    private fun parseTimecode(tc: String): Double {
        val norm = tc.replace(',', '.')
        val parts = norm.split(":")
        return when (parts.size) {
            3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
            2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
            else -> 0.0
        }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")
            .replace("&quot;", "\"").trim()
}
