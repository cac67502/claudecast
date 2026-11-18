package com.aide.claudecast.playback

/**
 * Reads just enough of an MPEG-TS chunk to describe it: which streams it carries, the video
 * size, the audio format, and whether it opens on a keyframe. Diagnostics only: it never
 * changes the data. Used to see what differs between a show and an ad when a TV drops.
 */
object TsInspector {
    /** [text] is for humans; [signature] changes only when the stream format changes. */
    data class Info(val text: String, val signature: String)

    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun describe(chunk: ByteArray): Info =
        try {
            inspect(chunk)
        } catch (e: Exception) {
            Info("unreadable (${e.javaClass.simpleName})", "unreadable")
        }

    private class State {
        var pmtPid = -1
        var videoPid = -1
        var videoType = 0
        var audioPid = -1
        var audioType = 0
        val streams = StringBuilder()
        var width = 0
        var height = 0
        var rate = 0
        var channels = 0
        var vPes = 0
        var aPes = 0
        var firstVcl = ""
        var vPts = -1L
        var aPts = -1L
    }

    private fun inspect(b: ByteArray): Info {
        var start = 0
        while (start < b.size && b[start] != 0x47.toByte()) start++
        val end = start + (b.size - start) / 188 * 188
        val s = State()
        var p = start
        while (p + 188 <= end) {
            val pid = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)
            val pusi = (b[p + 1].toInt() and 0x40) != 0
            val afc = (b[p + 3].toInt() shr 4) and 0x3
            var pl = p + 4
            if (afc == 2 || afc == 3) pl = p + 5 + (b[p + 4].toInt() and 0xFF)
            val limit = p + 188
            if ((afc == 1 || afc == 3) && pl < limit) {
                when {
                    pid == 0 && pusi && s.pmtPid < 0 -> parsePat(b, pl, limit, s)
                    pid == s.pmtPid && pusi && s.streams.isEmpty() -> parsePmt(b, pl, limit, s)
                    pid == s.videoPid -> video(b, pl, limit, pusi, s)
                    pid == s.audioPid && pusi -> audio(b, pl, limit, s)
                }
            }
            p += 188
        }

        val v = when (s.videoType) {
            0x1B -> "H264"; 0x24 -> "HEVC"; 0 -> "no video"; else -> "video type ${s.videoType}"
        }
        val a = when (s.audioType) {
            0x0F -> "AAC"; 0x11 -> "AAC-LATM"; 0x03, 0x04 -> "MPEG audio"; 0x81 -> "AC3"; 0x87 -> "EAC3"
            0 -> "no audio"; else -> "audio type ${s.audioType}"
        }
        val size = if (s.width > 0) "${s.width}x${s.height}" else "size ?"
        val fmt = if (s.rate > 0) "${s.rate}Hz/${s.channels}ch" else "format ?"
        val key = if (s.firstVcl.isEmpty()) "no frame start" else "starts ${s.firstVcl}"
        val av = if (s.vPts >= 0 && s.aPts >= 0) {
            val ms = ((s.aPts - s.vPts) * 1000 / 90000)
            ", audio starts ${if (ms >= 0) "+" else ""}${ms}ms vs video"
        } else ""
        val text = "PMT[${s.streams.toString().trim()}] $v $size $key, $a $fmt, " +
            "${s.vPes} video/${s.aPes} audio PES$av"
        val signature = "${s.streams.toString().trim()}|$size|${s.rate}/${s.channels}"
        return Info(text, signature)
    }

    private fun parsePat(b: ByteArray, pl: Int, limit: Int, s: State) {
        val t = pl + 1 + (b[pl].toInt() and 0xFF)
        if (t + 8 > limit) return
        val sectionLen = ((b[t + 1].toInt() and 0x0F) shl 8) or (b[t + 2].toInt() and 0xFF)
        val end = minOf(t + 3 + sectionLen - 4, limit)
        var x = t + 8
        while (x + 4 <= end) {
            val program = ((b[x].toInt() and 0xFF) shl 8) or (b[x + 1].toInt() and 0xFF)
            if (program != 0) {
                s.pmtPid = ((b[x + 2].toInt() and 0x1F) shl 8) or (b[x + 3].toInt() and 0xFF)
                return
            }
            x += 4
        }
    }

    private fun parsePmt(b: ByteArray, pl: Int, limit: Int, s: State) {
        val t = pl + 1 + (b[pl].toInt() and 0xFF)
        if (t + 12 > limit) return
        val sectionLen = ((b[t + 1].toInt() and 0x0F) shl 8) or (b[t + 2].toInt() and 0xFF)
        val programInfo = ((b[t + 10].toInt() and 0x0F) shl 8) or (b[t + 11].toInt() and 0xFF)
        val end = minOf(t + 3 + sectionLen - 4, limit)
        var x = t + 12 + programInfo
        while (x + 5 <= end) {
            val type = b[x].toInt() and 0xFF
            val pid = ((b[x + 1].toInt() and 0x1F) shl 8) or (b[x + 2].toInt() and 0xFF)
            val esInfo = ((b[x + 3].toInt() and 0x0F) shl 8) or (b[x + 4].toInt() and 0xFF)
            s.streams.append("$type:$pid ")
            if ((type == 0x1B || type == 0x24) && s.videoPid < 0) { s.videoPid = pid; s.videoType = type }
            if (type in intArrayOf(0x0F, 0x11, 0x03, 0x04, 0x81, 0x87) && s.audioPid < 0) {
                s.audioPid = pid; s.audioType = type
            }
            x += 5 + esInfo
        }
    }

    private fun video(b: ByteArray, pl: Int, limit: Int, pusi: Boolean, s: State) {
        var es = pl
        if (pusi && pl + 14 <= limit && b[pl] == 0.toByte() && b[pl + 1] == 0.toByte() && b[pl + 2] == 1.toByte()) {
            s.vPes++
            if (s.vPts < 0 && ((b[pl + 7].toInt() shr 6) and 0x2) != 0) s.vPts = readTs(b, pl + 9)
            es = pl + 9 + (b[pl + 8].toInt() and 0xFF)
        }
        if (s.firstVcl.isNotEmpty() && s.width > 0) return
        var i = es
        while (i + 4 <= limit) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) {
                val nal = b[i + 3].toInt() and 0x1F
                if (nal == 7 && s.width == 0) {
                    runCatching { spsSize(b, i + 4, limit) }.getOrNull()?.let { s.width = it.first; s.height = it.second }
                }
                if (s.firstVcl.isEmpty()) {
                    if (nal == 5) s.firstVcl = "IDR keyframe"
                    if (nal == 1) s.firstVcl = "non-keyframe"
                }
                i += 3
            } else i++
        }
    }

    private fun audio(b: ByteArray, pl: Int, limit: Int, s: State) {
        if (pl + 14 > limit || b[pl] != 0.toByte() || b[pl + 1] != 0.toByte() || b[pl + 2] != 1.toByte()) return
        s.aPes++
        if (s.aPts < 0 && ((b[pl + 7].toInt() shr 6) and 0x2) != 0) s.aPts = readTs(b, pl + 9)
        val es = pl + 9 + (b[pl + 8].toInt() and 0xFF)
        if (s.rate == 0 && es + 4 <= limit && b[es] == 0xFF.toByte() && (b[es + 1].toInt() and 0xF0) == 0xF0) {
            val idx = (b[es + 2].toInt() shr 2) and 0x0F
            if (idx < RATES.size) s.rate = RATES[idx]
            s.channels = ((b[es + 2].toInt() and 1) shl 2) or ((b[es + 3].toInt() and 0xFF) shr 6)
        }
    }

    /** Width and height from an H.264 sequence parameter set starting at [from] (after the NAL header byte). */
    private fun spsSize(b: ByteArray, from: Int, limit: Int): Pair<Int, Int>? {
        val raw = ArrayList<Byte>()
        var zeros = 0
        var i = from
        while (i < limit && raw.size < 64) {
            val v = b[i]
            if (zeros >= 2 && v == 3.toByte()) { zeros = 0; i++; continue }   // emulation prevention byte
            zeros = if (v == 0.toByte()) zeros + 1 else 0
            raw.add(v); i++
        }
        val r = Bits(raw.toByteArray())
        val profile = r.u(8)
        r.u(8); r.u(8)
        r.ue()
        var chromaFormat = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.u(1)
            r.ue(); r.ue(); r.u(1)
            if (r.u(1) == 1) return null          // scaling matrices: not worth parsing here
        }
        r.ue()
        when (r.ue()) {
            0 -> r.ue()
            1 -> { r.u(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        }
        r.ue(); r.u(1)
        val wMbs = r.ue() + 1
        val hMapUnits = r.ue() + 1
        val frameMbsOnly = r.u(1)
        if (frameMbsOnly == 0) r.u(1)
        r.u(1)
        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (r.u(1) == 1) { cropL = r.ue(); cropR = r.ue(); cropT = r.ue(); cropB = r.ue() }
        val cropX = if (chromaFormat == 0 || chromaFormat == 3) 1 else 2
        val cropY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
        val w = wMbs * 16 - (cropL + cropR) * cropX
        val h = (2 - frameMbsOnly) * hMapUnits * 16 - (cropT + cropB) * cropY
        return if (w in 1..8192 && h in 1..8192) w to h else null
    }

    private class Bits(private val d: ByteArray) {
        private var pos = 0
        fun bit(): Int {
            val byte = pos shr 3
            if (byte >= d.size) throw IndexOutOfBoundsException()
            val v = (d[byte].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return v
        }
        fun u(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) { zeros++; if (zeros > 31) throw IllegalStateException() }
            return if (zeros == 0) 0 else (1 shl zeros) - 1 + u(zeros)
        }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    private fun readTs(b: ByteArray, i: Int): Long =
        (((b[i].toLong() shr 1) and 0x07) shl 30) or
            ((b[i + 1].toLong() and 0xFF) shl 22) or
            (((b[i + 2].toLong() shr 1) and 0x7F) shl 15) or
            ((b[i + 3].toLong() and 0xFF) shl 7) or
            ((b[i + 4].toLong() shr 1) and 0x7F)
}
