package com.aide.claudecast.playback

/**
 * Makes a run of MPEG-TS chunks look like one unbroken broadcast.
 *
 * At an HLS discontinuity (an ad starting or ending) the timestamps jump to a new
 * timeline. Many TVs can't follow that inside a single stream and drop the connection.
 * This shifts every PTS, DTS and PCR after the jump so the timeline simply continues,
 * and renumbers continuity counters so the splice looks seamless. No re-encoding.
 */
class TsStitcher {
    private companion object {
        const val WRAP = 1L shl 33           // timestamps are 33-bit, 90 kHz
        const val HALF = 1L shl 32
        const val GAP = 3003L                // ~one video frame between pieces
    }

    private var offset = 0L                  // added to every timestamp
    private var lastTs = -1L                 // latest output PTS/DTS
    private val cc = IntArray(8192) { -1 }   // per-PID continuity counter we've written

    /** Rewrites [chunk] in place. [newTimeline] = this chunk follows a discontinuity. */
    fun process(chunk: ByteArray, newTimeline: Boolean): ByteArray {
        var start = 0
        while (start < chunk.size && chunk[start] != 0x47.toByte()) start++
        val usable = (chunk.size - start) / 188 * 188
        if (usable <= 0) return chunk

        if (newTimeline && lastTs >= 0) {
            val first = firstTimestamp(chunk, start, usable)
            if (first >= 0) offset = mod(lastTs + GAP - first)
        }

        var p = start
        while (p + 188 <= start + usable) {
            rewritePacket(chunk, p)
            p += 188
        }
        return if (start == 0 && usable == chunk.size) chunk else chunk.copyOfRange(start, start + usable)
    }

    private fun rewritePacket(b: ByteArray, p: Int) {
        if (b[p] != 0x47.toByte()) return
        val pusi = (b[p + 1].toInt() and 0x40) != 0
        val pid = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)
        val afc = (b[p + 3].toInt() shr 4) and 0x3
        val hasAf = afc == 2 || afc == 3
        val hasPayload = afc == 1 || afc == 3

        // Continuity counter: keep it running smoothly across the splice.
        if (pid != 0x1FFF) {
            val prev = cc[pid]
            val next = if (prev < 0) (b[p + 3].toInt() and 0x0F) else if (hasPayload) (prev + 1) and 0x0F else prev
            cc[pid] = next
            b[p + 3] = ((b[p + 3].toInt() and 0xF0) or next).toByte()
        }

        var payload = p + 4
        if (hasAf) {
            val afLen = b[p + 4].toInt() and 0xFF
            if (afLen > 0 && p + 5 < p + 188) {
                val flags = b[p + 5].toInt() and 0xFF
                // Clear the discontinuity indicator: from the TV's view there isn't one.
                b[p + 5] = (flags and 0x7F).toByte()
                if (flags and 0x10 != 0 && afLen >= 7) shiftPcr(b, p + 6)
            }
            payload = p + 5 + afLen
        }
        if (!pusi || !hasPayload || payload + 14 > p + 188) return
        // PES header?
        if (b[payload] != 0.toByte() || b[payload + 1] != 0.toByte() || b[payload + 2] != 1.toByte()) return
        val sid = b[payload + 3].toInt() and 0xFF
        if (sid == 0xBC || sid == 0xBE || sid == 0xBF || sid == 0xF0 || sid == 0xF1 || sid == 0xFF || sid == 0xF2 || sid == 0xF8) return
        val ptsDts = (b[payload + 7].toInt() shr 6) and 0x3
        if (ptsDts and 0x2 != 0) note(shiftTs(b, payload + 9))
        if (ptsDts == 0x3 && payload + 19 <= p + 188) note(shiftTs(b, payload + 14))
    }

    /** Earliest PTS/DTS among the first few PES headers of a chunk (input timeline). */
    private fun firstTimestamp(b: ByteArray, start: Int, len: Int): Long {
        var best = -1L
        var seen = 0
        var p = start
        while (p + 188 <= start + len && seen < 6) {
            val pusi = (b[p + 1].toInt() and 0x40) != 0
            val afc = (b[p + 3].toInt() shr 4) and 0x3
            var payload = p + 4
            if (afc == 2 || afc == 3) payload = p + 5 + (b[p + 4].toInt() and 0xFF)
            if (pusi && (afc == 1 || afc == 3) && payload + 14 <= p + 188 &&
                b[payload] == 0.toByte() && b[payload + 1] == 0.toByte() && b[payload + 2] == 1.toByte()
            ) {
                val flags = (b[payload + 7].toInt() shr 6) and 0x3
                if (flags and 0x2 != 0) {
                    var t = readTs(b, payload + 9)
                    if (flags == 0x3 && payload + 19 <= p + 188) {
                        val dts = readTs(b, payload + 14)
                        if (diff(dts, t) < 0) t = dts
                    }
                    best = if (best < 0 || diff(t, best) < 0) t else best
                    seen++
                }
            }
            p += 188
        }
        return best
    }

    private fun note(out: Long) {
        if (lastTs < 0 || diff(out, lastTs) > 0) lastTs = out
    }

    private fun shiftTs(b: ByteArray, i: Int): Long {
        val v = mod(readTs(b, i) + offset)
        val marker = b[i].toInt() and 0xF0   // keep the '0010/0011/0001' prefix bits
        b[i] = (marker or (((v shr 30) and 0x07).toInt() shl 1) or 1).toByte()
        b[i + 1] = ((v shr 22) and 0xFF).toByte()
        b[i + 2] = ((((v shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        b[i + 3] = ((v shr 7) and 0xFF).toByte()
        b[i + 4] = ((((v) and 0x7F).toInt() shl 1) or 1).toByte()
        return v
    }

    private fun readTs(b: ByteArray, i: Int): Long =
        (((b[i].toLong() shr 1) and 0x07) shl 30) or
            ((b[i + 1].toLong() and 0xFF) shl 22) or
            (((b[i + 2].toLong() shr 1) and 0x7F) shl 15) or
            ((b[i + 3].toLong() and 0xFF) shl 7) or
            ((b[i + 4].toLong() shr 1) and 0x7F)

    private fun shiftPcr(b: ByteArray, i: Int) {
        val base = ((b[i].toLong() and 0xFF) shl 25) or ((b[i + 1].toLong() and 0xFF) shl 17) or
            ((b[i + 2].toLong() and 0xFF) shl 9) or ((b[i + 3].toLong() and 0xFF) shl 1) or
            ((b[i + 4].toLong() shr 7) and 0x1)
        val v = mod(base + offset)
        b[i] = (v shr 25).toByte()
        b[i + 1] = (v shr 17).toByte()
        b[i + 2] = (v shr 9).toByte()
        b[i + 3] = (v shr 1).toByte()
        b[i + 4] = (((v and 1).toInt() shl 7) or (b[i + 4].toInt() and 0x7F)).toByte()
    }

    private fun mod(v: Long): Long = ((v % WRAP) + WRAP) % WRAP

    /** Signed difference a - b on the 33-bit wheel. */
    private fun diff(a: Long, b: Long): Long = mod(a - b + HALF) - HALF
}
