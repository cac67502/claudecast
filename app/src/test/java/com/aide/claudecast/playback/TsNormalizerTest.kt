package com.aide.claudecast.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsNormalizerTest {
    private fun res(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("ts/$name")!!.readBytes()

    private fun pidOf(b: ByteArray, p: Int) = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)

    private fun pids(b: ByteArray): Set<Int> {
        assertEquals("whole packets", 0, b.size % 188)
        val s = HashSet<Int>()
        for (p in 0 until b.size step 188) {
            assertEquals("sync byte at $p", 0x47.toByte(), b[p])
            s.add(pidOf(b, p))
        }
        return s
    }

    /** The fixture with its audio packets removed, as an audio-less ad would arrive. */
    private fun withoutAudio(b: ByteArray, audioPid: Int = 257): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in 0 until b.size step 188) if (pidOf(b, p) != audioPid) out.write(b, p, 188)
        return out.toByteArray()
    }

    @Test
    fun rewritesALayoutedChunkToTheFixedLayout() {
        val out = TsNormalizer().process(res("show_0.ts"))
        val allowed = setOf(0, TsNormalizer.PMT_PID, TsNormalizer.VIDEO_PID, TsNormalizer.AUDIO_PID)
        assertTrue("only our PIDs: ${pids(out)}", allowed.containsAll(pids(out)))
        val info = TsInspector.describe(out)
        println("normalised: ${info.text}")
        assertTrue(info.text, info.text.contains("H264"))
        assertTrue(info.text, info.text.contains("AAC"))
    }

    @Test
    fun addsSilentAudioWhenAChunkHasNone() {
        val norm = TsNormalizer()
        val out = norm.process(withoutAudio(res("show_0.ts")))
        val info = TsInspector.describe(out)
        println("silent: ${info.text} / ${norm.note}")
        assertTrue(norm.note, norm.note.startsWith("no audio"))
        assertTrue(info.text, info.text.contains("AAC 48000Hz/2ch"))
        val audioPes = Regex("(\\d+) audio PES").find(info.text)!!.groupValues[1].toInt()
        assertTrue("audio PES: $audioPes", audioPes > 0)
        // audio must start close to the video (within a quarter second)
        val m = Regex("audio starts ([+-]\\d+)ms").find(info.text)
        assertTrue(info.text, m != null && Math.abs(m.groupValues[1].toInt()) < 250)
    }

    @Test
    fun generatedTablesHaveValidCrcs() {
        val norm = TsNormalizer()
        for (section in listOf(norm.patSection(), norm.pmtSection())) {
            assertEquals(0, TsNormalizer.crc32(section))
            val declared = ((section[1].toInt() and 0x0F) shl 8) or (section[2].toInt() and 0xFF)
            assertEquals("section_length", section.size - 3, declared)
        }
    }

    @Test
    fun silentFrameIsAWellFormedAdtsFrame() {
        val f = TsNormalizer.SILENT_FRAME
        assertEquals(0xFF.toByte(), f[0])
        assertEquals(0xF0, f[1].toInt() and 0xF0)
        val length = ((f[3].toInt() and 0x03) shl 11) or ((f[4].toInt() and 0xFF) shl 3) or ((f[5].toInt() and 0xFF) shr 5)
        assertEquals("ADTS frame_length", f.size, length)
    }

    @Test
    fun leavesUnreadableDataAlone() {
        val junk = ByteArray(100)
        val norm = TsNormalizer()
        assertArrayEquals(junk, norm.process(junk))
        assertTrue(norm.note, norm.note.startsWith("bypassed"))
    }
}
