package com.aide.claudecast.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsInspectorTest {
    private fun res(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("ts/$name")!!.readBytes()

    @Test
    fun describesRealChunks() {
        val info = TsInspector.describe(res("show_0.ts"))
        println("show_0: ${info.text}")
        assertFalse(info.text.startsWith("unreadable"))
        assertTrue(info.text.startsWith("PMT["))
    }

    @Test
    fun chunksOfTheSameStreamShareASignature() {
        assertEquals(
            TsInspector.describe(res("show_0.ts")).signature,
            TsInspector.describe(res("show_1.ts")).signature
        )
    }

    @Test
    fun garbageDoesNotCrash() {
        assertFalse(TsInspector.describe(ByteArray(10)).text.isEmpty())
        assertFalse(TsInspector.describe(ByteArray(0)).text.isEmpty())
    }
}
