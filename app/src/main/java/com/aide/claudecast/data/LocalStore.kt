package com.aide.claudecast.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed persistence for subscriptions and playback positions.
 * No Room / KSP — keeps the build dependency-light and bullet-proof to compile.
 */
class LocalStore(context: Context) {
    private val dir = context.filesDir
    private val subsFile = File(dir, "subscriptions.json")
    private val posFile = File(dir, "positions.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _subscriptions = MutableStateFlow<List<Podcast>>(emptyList())
    val subscriptions: StateFlow<List<Podcast>> = _subscriptions.asStateFlow()

    private var positions: MutableMap<String, Long> = mutableMapOf()

    suspend fun load() = withContext(Dispatchers.IO) {
        _subscriptions.value = runCatching {
            if (subsFile.exists()) json.decodeFromString<List<Podcast>>(subsFile.readText()) else emptyList()
        }.getOrDefault(emptyList())
        positions = runCatching {
            if (posFile.exists()) json.decodeFromString<Map<String, Long>>(posFile.readText()).toMutableMap()
            else mutableMapOf()
        }.getOrDefault(mutableMapOf())
    }

    fun isSubscribed(feedUrl: String): Boolean = _subscriptions.value.any { it.feedUrl == feedUrl }

    suspend fun subscribe(p: Podcast) = withContext(Dispatchers.IO) {
        if (_subscriptions.value.none { it.feedUrl == p.feedUrl }) {
            _subscriptions.value = (_subscriptions.value + p).sortedBy { it.title.lowercase() }
            persistSubs()
        }
    }

    suspend fun unsubscribe(feedUrl: String) = withContext(Dispatchers.IO) {
        _subscriptions.value = _subscriptions.value.filterNot { it.feedUrl == feedUrl }
        persistSubs()
    }

    private fun persistSubs() =
        runCatching { subsFile.writeText(json.encodeToString(_subscriptions.value)) }

    fun getPosition(episodeId: String): Long = positions[episodeId] ?: 0L

    suspend fun savePosition(episodeId: String, ms: Long) = withContext(Dispatchers.IO) {
        positions[episodeId] = ms
        runCatching { posFile.writeText(json.encodeToString(positions.toMap())) }
        Unit
    }
}
