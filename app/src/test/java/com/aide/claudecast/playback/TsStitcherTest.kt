package com.aide.claudecast.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Show -> ad -> show, with each piece on its own timeline. The expected output was
 * produced by a reference implementation and checked with ffmpeg: timestamps run
 * smoothly across both splices and it decodes with no errors.
 */
class TsStitcherTest {
    private fun res(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("ts/$name")!!.readBytes()

    @Test
    fun stitchesAdBreaksIntoOneTimeline() {
        val pieces = listOf(
            "show_0.ts" to false, "show_1.ts" to false,
            "ad_0.ts" to true, "ad_1.ts" to false,
            "show_0.ts" to true, "show_1.ts" to false
        )
        val st = TsStitcher()
        val out = java.io.ByteArrayOutputStream()
        for ((f, brk) in pieces) out.write(st.process(res(f), brk))
        assertArrayEquals(res("stitched.ts"), out.toByteArray())
    }
}
