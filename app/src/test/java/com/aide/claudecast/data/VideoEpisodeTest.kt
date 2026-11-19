package com.aide.claudecast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoEpisodeTest {
    private val parser = FeedParser()

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"
             xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>The Show</title>
            <item>
              <title>Audio only</title>
              <guid>a</guid>
              <enclosure url="https://example.com/a.mp3" type="audio/mpeg" length="1"/>
            </item>
            <item>
              <title>Video enclosure</title>
              <guid>b</guid>
              <enclosure url="https://example.com/b.mp4" type="video/mp4" length="1"/>
            </item>
            <item>
              <title>Audio with a video alternate</title>
              <guid>c</guid>
              <enclosure url="https://example.com/c.mp3" type="audio/mpeg" length="1"/>
              <podcast:alternateEnclosure type="audio/aac" length="1">
                <podcast:source uri="https://example.com/c.aac"/>
              </podcast:alternateEnclosure>
              <podcast:alternateEnclosure type="video/mp4" length="1">
                <podcast:source uri="https://example.com/c.mp4"/>
              </podcast:alternateEnclosure>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun findsVideoWhenTheFeedOffersIt() {
        val eps = parser.parseXml(feed, "https://example.com/feed.xml").episodes.associateBy { it.id }
        assertNull(eps.getValue("a").videoUrl)
        assertEquals("https://example.com/b.mp4", eps.getValue("b").videoUrl)
        assertEquals("https://example.com/c.mp4", eps.getValue("c").videoUrl)
        assertEquals("https://example.com/c.mp3", eps.getValue("c").enclosureUrl)
    }
}
