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
    val episodeNumber: Int? = null,
    /** A video version of the episode, when the feed offers one. */
    val videoUrl: String? = null
)

/** A Podcasting 2.0 `podcast:liveItem`: a live show (status pending / live / ended). */
data class LiveItem(
    val id: String,
    val title: String,
    val status: String,
    val startSec: Long = 0L,
    val endSec: Long = 0L,
    val streamUrl: String,
    val streamType: String = "",
    val artworkUrl: String? = null,
    val description: String = "",
    val linkUrl: String? = null
) {
    /** On the air now. A stale feed that still says "live" after its end time doesn't count. */
    fun isLive(nowSec: Long): Boolean =
        status.equals("live", true) && (endSec == 0L || endSec > nowSec)

    /** Scheduled for later. */
    fun isUpcoming(nowSec: Long): Boolean =
        status.equals("pending", true) && startSec > nowSec

    /** Playable like an episode; the "live:" id keeps it out of resume-position storage. */
    fun toEpisode(feedUrl: String, podcastTitle: String, fallbackArt: String?) = Episode(
        id = id,
        feedUrl = feedUrl,
        title = title,
        description = description,
        enclosureUrl = streamUrl,
        artworkUrl = artworkUrl ?: fallbackArt,
        podcastTitle = podcastTitle
    )
}

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
