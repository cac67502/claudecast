package com.aide.claudecast

import android.content.Context
import com.aide.claudecast.data.FeedParser
import com.aide.claudecast.data.ItunesApi
import com.aide.claudecast.data.LocalStore
import com.aide.claudecast.data.Pc20Fetcher
import com.aide.claudecast.data.PodcastRepository
import com.aide.claudecast.data.SettingsStore
import com.aide.claudecast.playback.PlayerController
import kotlinx.coroutines.runBlocking

/** Manual, lightweight DI. Initialized once in Application.onCreate(). */
object ServiceLocator {
    lateinit var settings: SettingsStore
        private set
    lateinit var repo: PodcastRepository
        private set
    lateinit var player: PlayerController
        private set
    lateinit var store: LocalStore
        private set

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        settings = SettingsStore(app)
        store = LocalStore(app)

        runBlocking { store.load() }

        repo = PodcastRepository(ItunesApi(), FeedParser(), store, Pc20Fetcher())
        player = PlayerController(
            context = app,
            onPersistPosition = { id, ms -> store.savePosition(id, ms) },
            resumePositionProvider = { id -> store.getPosition(id) }
        )
        initialized = true
    }
}
