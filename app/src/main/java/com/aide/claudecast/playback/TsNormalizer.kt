package com.aide.claudecast.playback

import java.io.ByteArrayOutputStream

/**
 * Makes every chunk of a relayed channel look like the same kind of stream to the TV.
 *
 * Ads spliced into a live channel often arrive with a different stream layout than the
 * show: other PIDs, an extra timed-metadata stream, or no audio at all. A Samsung's DLNA
 * player can play the show but errors out on such an ad. This rewrites each chunk to one
 * fixed layout (PAT/PMT we generate, video on [VIDEO_PID], AAC audio on [AUDIO_PID]), drops
 * every other stream, and fills a chunk that has no audio with silent AAC. No re-encoding.
 *
 * Anything unexpected (HEVC, non-AAC audio, a clock that isn't on the video PID, bad data)
 * returns the chunk untouched and says why in [note].
 */
class TsNormalizer {
    companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101

        private const val FRAME_TICKS = 1920L          // 1024 samples at 48 kHz, in 90 kHz ticks
        private const val LOOKAHEAD = 9000L            // keep silence ~0.1 s ahead of the video
        private const val WRAP = 1L shl 33
        private const val HALF = 1L shl 32

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        /**
         * One silent AAC-LC stereo 48 kHz frame in ADTS: a 7-byte header, then a raw block
         * with an empty channel pair (no bands coded, so it decodes to silence).
         */
        internal val SILENT_FRAME = bytes(0xFF, 0xF1, 0x4C, 0x80, 0x01, 0xDF, 0xFC, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0E)

        /** MPEG-2 CRC-32 (poly 0x04C11DB7, init all ones, no reflection, no final xor). */
        internal fun crc32(data: ByteArray, len: Int = data.size): Int {
            var crc = -1
            for (i in 0 until len) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }

    /** What happened to the most recent chunk, for the log. */
    var note: String = ""
        private set

    private class Layout {
        var pmtPid = -1
        var video = -1
        var videoType = 0
        var audio = -1
        var pcr = -1
        var audioOther = false
    }

    private var layout = Layout()
    private var nextSilent = -1L          // next silent-audio PTS, in the chunks' own timeline

    fun process(chunk: ByteArray): ByteArray =
        try {
            run(chunk)
        } catch (e: Exception) {
            note = "bypassed (${e.javaClass.simpleName})"
            chunk
        }

    private fun run(b: ByteArray): ByteArray {
        var start = 0
        while (start < b.size && b[start] != 0x47.toByte()) start++
        val end = start + (b.size - start) / 188 * 188
        if (end <= start) { note = "bypassed (no packets)"; return b }

        scanLayout(b, start, end)
        val l = layout
        when {
            l.video < 0 || l.videoType != 0x1B -> { note = "bypassed (not H.264 video)"; return b }
            l.pcr != l.video -> { note = "bypassed (clock not on the video PID)"; return b }
            l.audioOther -> { note = "bypassed (audio isn't AAC)"; return b }
        }

        var audioSeen = false
        var p = start
        while (p + 188 <= end) {
            if (l.audio >= 0 && pidOf(b, p) == l.audio) { audioSeen = true; break }
            p += 188
        }
        if (audioSeen) nextSilent = -1L
        val fillSilence = !audioSeen

        val out = ByteArrayOutputStream(end - start + 4096)
        writeTables(out)
        var firstVideo = true
        var silentFrames = 0
        p = start
        while (p + 188 <= end) {
            val pid = pidOf(b, p)
            val pusi = (b[p + 1].toInt() and 0x40) != 0
            when {
                pid == 0 -> if (pusi) writeTables(out)            // replace the source PAT/PMT with ours
                pid == l.pmtPid -> {}
                pid == l.video -> {
                    if (fillSilence) {
                        val pts = videoPts(b, p, pusi)
                        if (pts >= 0) {
                            if (firstVideo) {
                                firstVideo = false
                                if (nextSilent < 0 || Math.abs(diff(nextSilent, pts)) > 45000L) nextSilent = pts
                            }
                            silentFrames += writeSilence(out, pts + LOOKAHEAD)
                        }
                    }
                    out.write(remap(b, p, VIDEO_PID))
                }
                pid == l.audio -> out.write(remap(b, p, AUDIO_PID))
                else -> {}                                        // metadata, SDT, nulls: not sent
            }
            p += 188
        }
        note = if (fillSilence) "no audio in this piece, added silent audio ($silentFrames frames)" else "layout normalised"
        return out.toByteArray()
    }

    /* ---------------- Reading the source layout ---------------- */

    private fun pidOf(b: ByteArray, p: Int) = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)

    private fun payloadStart(b: ByteArray, p: Int): Int {
        val afc = (b[p + 3].toInt() shr 4) and 0x3
        if (afc == 0 || afc == 2) return -1
        return if (afc == 3) p + 5 + (b[p + 4].toInt() and 0xFF) else p + 4
    }

    /** Finds the PAT and PMT in this chunk; keeps the previous layout if the chunk has none. */
    private fun scanLayout(b: ByteArray, start: Int, end: Int) {
        var pmtPid = -1
        var p = start
        while (p + 188 <= end) {
            val pid = pidOf(b, p)
            val pusi = (b[p + 1].toInt() and 0x40) != 0
            val pl = payloadStart(b, p)
            val limit = p + 188
            if (pusi && pl in 0 until limit) {
                if (pid == 0) {
                    val t = pl + 1 + (b[pl].toInt() and 0xFF)
                    if (t + 12 <= limit) {
                        val secLen = ((b[t + 1].toInt() and 0x0F) shl 8) or (b[t + 2].toInt() and 0xFF)
                        var x = t + 8
                        val e = minOf(t + 3 + secLen - 4, limit)
                        while (x + 4 <= e) {
                            val program = ((b[x].toInt() and 0xFF) shl 8) or (b[x + 1].toInt() and 0xFF)
                            if (program != 0) {
                                pmtPid = ((b[x + 2].toInt() and 0x1F) shl 8) or (b[x + 3].toInt() and 0xFF)
                                break
                            }
                            x += 4
                        }
                    }
                } else if (pid == pmtPid) {
                    parsePmt(b, pl, limit, pmtPid)
                    return
                }
            }
            p += 188
        }
    }

    private fun parsePmt(b: ByteArray, pl: Int, limit: Int, pmtPid: Int) {
        val t = pl + 1 + (b[pl].toInt() and 0xFF)
        if (t + 12 > limit) return
        val secLen = ((b[t + 1].toInt() and 0x0F) shl 8) or (b[t + 2].toInt() and 0xFF)
        val next = Layout()
        next.pmtPid = pmtPid
        next.pcr = ((b[t + 8].toInt() and 0x1F) shl 8) or (b[t + 9].toInt() and 0xFF)
        val programInfo = ((b[t + 10].toInt() and 0x0F) shl 8) or (b[t + 11].toInt() and 0xFF)
        var x = t + 12 + programInfo
        val e = minOf(t + 3 + secLen - 4, limit)
        while (x + 5 <= e) {
            val type = b[x].toInt() and 0xFF
            val pid = ((b[x + 1].toInt() and 0x1F) shl 8) or (b[x + 2].toInt() and 0xFF)
            val esInfo = ((b[x + 3].toInt() and 0x0F) shl 8) or (b[x + 4].toInt() and 0xFF)
            when (type) {
                0x1B, 0x24 -> if (next.video < 0) { next.video = pid; next.videoType = type }
                0x0F -> if (next.audio < 0) next.audio = pid
                0x03, 0x04, 0x11, 0x81, 0x87 -> next.audioOther = true   // audio, but not plain AAC
            }
            x += 5 + esInfo
        }
        layout = next
    }

    private fun videoPts(b: ByteArray, p: Int, pusi: Boolean): Long {
        if (!pusi) return -1
        val pl = payloadStart(b, p)
        if (pl < 0 || pl + 14 > p + 188) return -1
        if (b[pl] != 0.toByte() || b[pl + 1] != 0.toByte() || b[pl + 2] != 1.toByte()) return -1
        if (((b[pl + 7].toInt() shr 6) and 0x2) == 0) return -1
        return readTs(b, pl + 9)
    }

    /* ---------------- Writing the normalised stream ---------------- */

    private fun remap(b: ByteArray, p: Int, pid: Int): ByteArray {
        val pkt = b.copyOfRange(p, p + 188)
        pkt[1] = ((pkt[1].toInt() and 0xE0) or (pid shr 8)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        return pkt
    }

    internal fun patSection(): ByteArray {
        val s = ByteArray(16)
        val body = bytes(
            0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00,
            0x00, 0x01, 0xE0 or (PMT_PID shr 8), PMT_PID and 0xFF
        )
        body.copyInto(s)
        return withCrc(s, 12)
    }

    internal fun pmtSection(): ByteArray {
        val s = ByteArray(26)
        val body = bytes(
            0x02, 0xB0, 0x17, 0x00, 0x01, 0xC1, 0x00, 0x00,
            0xE0 or (VIDEO_PID shr 8), VIDEO_PID and 0xFF, 0xF0, 0x00,
            0x1B, 0xE0 or (VIDEO_PID shr 8), VIDEO_PID and 0xFF, 0xF0, 0x00,
            0x0F, 0xE0 or (AUDIO_PID shr 8), AUDIO_PID and 0xFF, 0xF0, 0x00
        )
        body.copyInto(s)
        return withCrc(s, 22)
    }

    private fun withCrc(s: ByteArray, bodyLen: Int): ByteArray {
        val crc = crc32(s, bodyLen)
        s[bodyLen] = (crc ushr 24).toByte(); s[bodyLen + 1] = (crc ushr 16).toByte()
        s[bodyLen + 2] = (crc ushr 8).toByte(); s[bodyLen + 3] = crc.toByte()
        return s
    }

    private fun writeTables(out: ByteArrayOutputStream) {
        out.write(psiPacket(0, patSection()))
        out.write(psiPacket(PMT_PID, pmtSection()))
    }

    private fun psiPacket(pid: Int, section: ByteArray): ByteArray {
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47; pkt[1] = (0x40 or (pid shr 8)).toByte(); pkt[2] = (pid and 0xFF).toByte(); pkt[3] = 0x10
        pkt[4] = 0   // pointer field
        section.copyInto(pkt, 5)
        return pkt
    }

    /** Silent AAC up to [upTo] (90 kHz ticks), as PES packets. Returns the frames written. */
    private fun writeSilence(out: ByteArrayOutputStream, upTo: Long): Int {
        var written = 0
        while (diff(upTo, nextSilent) > 0) {
            val frames = minOf(5L, (diff(upTo, nextSilent) + FRAME_TICKS - 1) / FRAME_TICKS).toInt()
            writePes(out, AUDIO_PID, silentPes(nextSilent, frames))
            nextSilent = mod(nextSilent + frames * FRAME_TICKS)
            written += frames
        }
        return written
    }

    internal fun silentPes(pts: Long, frames: Int): ByteArray {
        val payload = frames * SILENT_FRAME.size
        val pes = ByteArray(14 + payload)
        val lenField = 3 + 5 + payload
        pes[0] = 0; pes[1] = 0; pes[2] = 1; pes[3] = 0xC0.toByte()
        pes[4] = (lenField shr 8).toByte(); pes[5] = lenField.toByte()
        pes[6] = 0x80.toByte(); pes[7] = 0x80.toByte(); pes[8] = 5
        pes[9] = (0x21 or (((pts shr 30) and 0x07).toInt() shl 1)).toByte()
        pes[10] = ((pts shr 22) and 0xFF).toByte()
        pes[11] = ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        pes[12] = ((pts shr 7) and 0xFF).toByte()
        pes[13] = ((((pts) and 0x7F).toInt() shl 1) or 1).toByte()
        for (k in 0 until frames) SILENT_FRAME.copyInto(pes, 14 + k * SILENT_FRAME.size)
        return pes
    }

    /** Splits a PES into 188-byte packets; the last one is padded with adaptation-field stuffing. */
    private fun writePes(out: ByteArrayOutputStream, pid: Int, pes: ByteArray) {
        var off = 0
        var first = true
        while (off < pes.size) {
            val remaining = pes.size - off
            val take = minOf(184, remaining)
            val pkt = ByteArray(188) { 0xFF.toByte() }
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            pkt[2] = (pid and 0xFF).toByte()
            if (take == 184) {
                pkt[3] = 0x10
                pes.copyInto(pkt, 4, off, off + take)
            } else {
                pkt[3] = 0x30
                val afLen = 183 - take
                pkt[4] = afLen.toByte()
                if (afLen >= 1) pkt[5] = 0                       // flags; the rest stays 0xFF stuffing
                pes.copyInto(pkt, 5 + afLen, off, off + take)
            }
            out.write(pkt)
            off += take
            first = false
        }
    }

    private fun readTs(b: ByteArray, i: Int): Long =
        (((b[i].toLong() shr 1) and 0x07) shl 30) or
            ((b[i + 1].toLong() and 0xFF) shl 22) or
            (((b[i + 2].toLong() shr 1) and 0x7F) shl 15) or
            ((b[i + 3].toLong() and 0xFF) shl 7) or
            ((b[i + 4].toLong() shr 1) and 0x7F)

    private fun mod(v: Long): Long = ((v % WRAP) + WRAP) % WRAP
    private fun diff(a: Long, b: Long): Long = mod(a - b + HALF) - HALF
}
