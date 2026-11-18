package com.aide.claudecast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveItemTest {
    private val parser = FeedParser()

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"
             xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>The Show</title>
            <itunes:author>Host</itunes:author>
            <podcast:liveItem status="live" start="2021-01-01T00:00:00Z" end="2021-01-01T02:00:00Z">
              <title>Live from the studio</title>
              <guid>live-guid-1</guid>
              <enclosure url="https://stream.example.com/live.mp3" type="audio/mpeg" length="0"/>
              <itunes:image href="https://example.com/live.jpg"/>
              <podcast:contentLink href="https://example.com/watch">Watch</podcast:contentLink>
              <podcast:value type="lightning" method="keysend" suggested="0.00000015000">
                <podcast:valueRecipient name="Host" type="node" address="abc" split="100"/>
              </podcast:value>
            </podcast:liveItem>
            <item>
              <title>Episode 1</title>
              <guid>ep-1</guid>
              <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg" length="1"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun parsesLiveItem() {
        val parsed = parser.parseXml(feed, "https://example.com/feed.xml")
        assertEquals(1, parsed.liveItems.size)
        val live = parsed.liveItems[0]
        assertEquals("live:live-guid-1", live.id)
        assertEquals("Live from the studio", live.title)
        assertEquals("live", live.status)
        assertEquals("https://stream.example.com/live.mp3", live.streamUrl)
        assertEquals("audio/mpeg", live.streamType)
        assertEquals("https://example.com/live.jpg", live.artworkUrl)
        assertEquals("https://example.com/watch", live.linkUrl)
        assertEquals(1609459200L, live.startSec)
        assertEquals(1609466400L, live.endSec)
    }

    @Test
    fun liveItemDoesNotLeakIntoChannelOrEpisodes() {
        val parsed = parser.parseXml(feed, "https://example.com/feed.xml")
        assertEquals("The Show", parsed.podcast.title)
        assertEquals(1, parsed.episodes.size)
        assertEquals("Episode 1", parsed.episodes[0].title)
        // the live item's value block must not become the channel value
        assertNull(parsed.episodes[0].value)
    }

    @Test
    fun feedWithoutLiveItemHasNone() {
        val plain = feed.substringBefore("<podcast:liveItem") + "<item><title>E</title>" +
            "<enclosure url=\"https://example.com/e.mp3\"/></item></channel></rss>"
        assertTrue(parser.parseXml(plain, "https://example.com/feed.xml").liveItems.isEmpty())
    }

    @Test
    fun parsesIsoOffsets() {
        assertEquals(1609459200L, parser.parseIsoDate("2021-01-01T00:00:00Z"))
        assertEquals(1609480800L, parser.parseIsoDate("2021-01-01T00:00:00-0600"))
        assertEquals(1609480800L, parser.parseIsoDate("2021-01-01T00:00:00-06:00"))
        assertEquals(1609455600L, parser.parseIsoDate("2021-01-01T00:00:00.000+01:00"))
        assertEquals(0L, parser.parseIsoDate(""))
        assertEquals(0L, parser.parseIsoDate("not a date"))
    }

    @Test
    fun liveAndUpcomingWindows() {
        val live = LiveItem("live:a", "t", "live", startSec = 100, endSec = 200, streamUrl = "u")
        assertTrue(live.isLive(150))
        assertFalse("stale feed past its end time", live.isLive(250))
        assertTrue(LiveItem("live:b", "t", "live", streamUrl = "u").isLive(999)) // no end time

        val pending = LiveItem("live:c", "t", "pending", startSec = 500, streamUrl = "u")
        assertTrue(pending.isUpcoming(100))
        assertFalse(pending.isUpcoming(600))
        assertFalse(pending.isLive(600))

        assertFalse(LiveItem("live:d", "t", "ended", streamUrl = "u").isLive(0))
    }
}
