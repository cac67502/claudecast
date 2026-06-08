package com.aide.claudecast.data

import kotlinx.serialization.Serializable

/* ---------- Domain models ---------- */

@Serializable
data class Podcast(
    val feedUrl: String,            // primary key (RSS feed URL)
    val title: String,
    val author: String = "",
    val description: String = "",
    val artworkUrl: String? = null,
    val fundingUrl: String? = null,
    val fundingMessage: String? = null
)

@Serializable
data class Episode(
    val id: String,                 // RSS <guid>, falls back to enclosure URL
    val feedUrl: String,
    val title: String,
    val description: String = "",
    val datePublished: Long = 0L,
    val enclosureUrl: String = "",
    val durationSec: Long = 0L,
    val artworkUrl: String? = null,
    val podcastTitle: String = "",
    val chaptersUrl: String? = null,
    val transcripts: List<TranscriptRef> = emptyList(),
    val persons: List<Person> = emptyList(),
    val soundbites: List<Soundbite> = emptyList(),
    val value: ValueBlock? = null,
    val season: Int? = null,
    val episodeNumber: Int? = null
)

@Serializable
data class TranscriptRef(val url: String, val type: String)

@Serializable
data class Person(
    val name: String,
    val role: String = "",
    val group: String = "",
    val img: String? = null,
    val href: String? = null
)

@Serializable
data class Soundbite(val startTime: Double, val duration: Double, val title: String = "")

@Serializable
data class ValueBlock(
    val type: String = "",
    val method: String = "",
    val suggested: String = "",
    val recipients: List<ValueRecipient> = emptyList()
)

@Serializable
data class ValueRecipient(
    val name: String,
    val type: String = "",
    val address: String = "",
    val split: Int = 0
)

/* ---------- PC2.0 chapters + transcript cues ---------- */

data class Chapter(
    val startSec: Double,
    val title: String,
    val imageUrl: String? = null,
    val linkUrl: String? = null
)

data class TranscriptCue(
    val startSec: Double,
    val endSec: Double,
    val speaker: String?,
    val text: String
)
