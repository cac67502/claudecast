package com.aide.claudecast

import android.content.Context
import com.aide.claudecast.data.FeedParser
import com.aide.claudecast.data.ItunesApi
import com.aide.claudecast.data.LocalStore
import com.aide.claudecast.data.MusicLibrary
import com.aide.claudecast.data.MusicStore
import com.aide.claudecast.data.RadioApi
import com.aide.claudecast.data.TvApi
import com.aide.claudecast.data.MovieApi
import com.aide.claudecast.data.Pc20Fetcher
import com.aide.claudecast.data.PodcastRepository
import com.aide.claudecast.data.SettingsStore
import com.aide.claudecast.playback.DlnaCaster
import com.aide.claudecast.playback.PlayerController
import com.aide.claudecast.playback.StreamRecorder
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
    lateinit var music: MusicStore
        private set
    lateinit var library: MusicLibrary
        private set
    lateinit var radio: RadioApi
        private set
    lateinit var tv: TvApi
        private set
    lateinit var movies: MovieApi
        private set
    lateinit var caster: DlnaCaster
        private set
    lateinit var recorder: StreamRecorder
        private set

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        settings = SettingsStore(app)
        store = LocalStore(app)

        music = MusicStore(app)
        runBlocking { store.load(); music.load() }
        library = MusicLibrary(app, music)
        radio = RadioApi()
        tv = TvApi(app)
        movies = MovieApi(app)
        caster = DlnaCaster(app)

        repo = PodcastRepository(ItunesApi(), FeedParser(), store, Pc20Fetcher())
        player = PlayerController(
            context = app,
            onPersistPosition = { id, ms -> store.savePosition(id, ms) },
            resumePositionProvider = { id -> store.getPosition(id) }
        )
        player.onMoviePosition = { id, pos, dur -> movies.saveResume(id, pos, dur) }
        recorder = StreamRecorder(library, music)
        recorder.bind(player)
        initialized = true
    }
}
