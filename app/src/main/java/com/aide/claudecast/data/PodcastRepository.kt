package com.aide.claudecast.data

import kotlinx.coroutines.flow.StateFlow

class PodcastRepository(
    private val itunes: ItunesApi,
    private val feeds: FeedParser,
    private val store: LocalStore,
    private val pc20: Pc20Fetcher
) {
    val subscriptions: StateFlow<List<Podcast>> get() = store.subscriptions

    private val podcastCache = mutableMapOf<String, Podcast>()
    private val feedCache = mutableMapOf<String, ParsedFeed>()

    suspend fun loadLocal() = store.load()

    suspend fun search(term: String): List<Podcast> =
        itunes.search(term).also { list -> list.forEach { podcastCache[it.feedUrl] = it } }

    suspend fun popular(): List<Podcast> =
        itunes.popular().also { list -> list.forEach { podcastCache[it.feedUrl] = it } }

    private suspend fun loadFeed(feedUrl: String, forceRefresh: Boolean = false): ParsedFeed {
        if (!forceRefresh) feedCache[feedUrl]?.let { return it }
        return feeds.parse(feedUrl).also { feedCache[feedUrl] = it }
    }

    /** Prefer the richer iTunes record for header art/title; fall back to the feed channel. */
    suspend fun podcast(feedUrl: String): Podcast {
        val fromFeed = loadFeed(feedUrl).podcast
        val fromItunes = podcastCache[feedUrl]
        return when {
            fromItunes == null -> fromFeed
            else -> fromItunes.copy(
                description = fromItunes.description.ifBlank { fromFeed.description },
                fundingUrl = fromItunes.fundingUrl ?: fromFeed.fundingUrl,
                fundingMessage = fromItunes.fundingMessage ?: fromFeed.fundingMessage,
                artworkUrl = fromItunes.artworkUrl ?: fromFeed.artworkUrl
            )
        }
    }

    suspend fun episodes(feedUrl: String, forceRefresh: Boolean = false): List<Episode> =
        loadFeed(feedUrl, forceRefresh).episodes

    /** Live shows from the (already loaded) feed; call after [episodes] with forceRefresh. */
    suspend fun liveItems(feedUrl: String): List<LiveItem> = loadFeed(feedUrl).liveItems

    fun isSubscribed(feedUrl: String): Boolean = store.isSubscribed(feedUrl)
    suspend fun subscribe(p: Podcast) = store.subscribe(p)
    suspend fun unsubscribe(feedUrl: String) = store.unsubscribe(feedUrl)

    fun getPosition(episodeId: String): Long = store.getPosition(episodeId)
    suspend fun savePosition(episodeId: String, ms: Long) = store.savePosition(episodeId, ms)

    suspend fun chapters(url: String): List<Chapter> = pc20.chapters(url)
    suspend fun transcript(ref: TranscriptRef): List<TranscriptCue> = pc20.transcript(ref)
}
