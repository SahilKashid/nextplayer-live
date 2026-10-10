package dev.anilbeesetti.nextplayer.feature.player.service

import java.io.RandomAccessFile

/**
 * Small EBML ID / size / skip helper for probing Matroska without depending on
 * Media3 [androidx.media3.extractor.mkv.MatroskaExtractor] internals.
 */
object EbmlProbe {

    /** Sentinel: element size is "unknown" (all data bits 1 after the length mask). */
    const val UNKNOWN_SIZE: Long = -1L

    private val LENGTH_MASKS = longArrayOf(
        0x80L,
        0x40L,
        0x20L,
        0x10L,
        0x08L,
        0x04L,
        0x02L,
        0x01L,
    )

    private val UNKNOWN_SIZE_VALUES = longArrayOf(
        0x7FL,
        0x3FFFL,
        0x1FFFFFL,
        0x0FFFFFFFL,
        0x07FFFFFFFFL,
        0x03FFFFFFFFFFL,
        0x01FFFFFFFFFFFFL,
        0x00FFFFFFFFFFFFFFL,
    )

    data class Varint(
        val value: Long,
        val encodedLength: Int,
        val isUnknownSize: Boolean = false,
    )

    /**
     * Length of an EBML VINT from the first byte's length-mask bit, or `-1` if none (`0x00`).
     */
    fun varintLength(firstByte: Int): Int {
        val b = firstByte and 0xFF
        for (i in LENGTH_MASKS.indices) {
            if ((b.toLong() and LENGTH_MASKS[i]) != 0L) return i + 1
        }
        return -1
    }

    /**
     * Decode an EBML VINT at [offset].
     *
     * @param removeLengthMask `true` for element sizes (value is the payload length);
     *   `false` for element IDs (keep the length-mask bits so the ID matches the spec).
     */
    fun decodeVarint(
        data: ByteArray,
        offset: Int,
        removeLengthMask: Boolean,
        maxLength: Int = 8,
    ): Varint? {
        if (offset < 0 || offset >= data.size) return null
        val length = varintLength(data[offset].toInt())
        if (length < 1 || length > maxLength) return null
        if (offset + length > data.size) return null
        val assembled = assemble(data, offset, length, removeLengthMask)
        val unknown = removeLengthMask && isUnknownSizeValue(assembled, length)
        return Varint(
            value = if (unknown) UNKNOWN_SIZE else assembled,
            encodedLength = length,
            isUnknownSize = unknown,
        )
    }

    fun isUnknownSizeValue(value: Long, encodedLength: Int): Boolean {
        if (encodedLength !in 1..8) return false
        return value == UNKNOWN_SIZE_VALUES[encodedLength - 1]
    }

    fun decodeUnsignedInteger(data: ByteArray, offset: Int, size: Int): Long? {
        if (size !in 1..8) return null
        if (offset < 0 || offset + size > data.size) return null
        var value = 0L
        for (i in 0 until size) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return value
    }

    private fun assemble(
        data: ByteArray,
        offset: Int,
        length: Int,
        removeLengthMask: Boolean,
    ): Long {
        var value = data[offset].toLong() and 0xFFL
        if (removeLengthMask) {
            value = value and LENGTH_MASKS[length - 1].inv()
        }
        for (i in 1 until length) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return value
    }

    /**
     * Sequential EBML reader over a byte source (array or [RandomAccessFile]).
     */
    class Reader(
        val length: Long,
        private val readAt: (position: Long, dest: ByteArray, destOffset: Int, byteCount: Int) -> Int,
    ) {
        var position: Long = 0L
            private set

        fun seek(newPosition: Long) {
            position = newPosition.coerceIn(0L, length)
        }

        fun remaining(limit: Long = length): Long = (limit - position).coerceAtLeast(0L)

        fun readId(limit: Long = length): Varint? =
            readVarint(removeLengthMask = false, limit = limit)

        fun readSize(limit: Long = length): Varint? =
            readVarint(removeLengthMask = true, limit = limit)

        fun skip(byteCount: Long, limit: Long = length): Boolean {
            if (byteCount < 0L) return false
            val next = position + byteCount
            if (next > limit) return false
            seek(next)
            return true
        }

        fun readBytes(byteCount: Int, limit: Long = length): ByteArray? {
            if (byteCount < 0) return null
            if (position + byteCount.toLong() > limit) return null
            val dest = ByteArray(byteCount)
            if (!readFully(dest, 0, byteCount)) return null
            return dest
        }

        fun readUnsignedInteger(size: Int, limit: Long = length): Long? {
            val bytes = readBytes(size, limit) ?: return null
            return decodeUnsignedInteger(bytes, 0, size)
        }

        private fun readVarint(removeLengthMask: Boolean, limit: Long): Varint? {
            val start = position
            if (start >= limit) return null
            val first = ByteArray(1)
            if (!readFully(first, 0, 1)) {
                seek(start)
                return null
            }
            val encodedLength = varintLength(first[0].toInt())
            if (encodedLength < 1 || start + encodedLength > limit) {
                seek(start)
                return null
            }
            val buf = ByteArray(encodedLength)
            buf[0] = first[0]
            if (encodedLength > 1 && !readFully(buf, 1, encodedLength - 1)) {
                seek(start)
                return null
            }
            return decodeVarint(buf, 0, removeLengthMask, maxLength = encodedLength)
        }

        private fun readFully(dest: ByteArray, destOffset: Int, byteCount: Int): Boolean {
            var got = 0
            while (got < byteCount) {
                if (position >= length) return false
                val n = readAt(position, dest, destOffset + got, byteCount - got)
                if (n <= 0) return false
                position += n.toLong()
                got += n
            }
            return true
        }

        companion object {
            fun fromBytes(data: ByteArray): Reader {
                return Reader(data.size.toLong()) { pos, dest, destOffset, byteCount ->
                    val start = pos.toInt()
                    val available = data.size - start
                    if (available <= 0) return@Reader -1
                    val n = minOf(byteCount, available)
                    System.arraycopy(data, start, dest, destOffset, n)
                    n
                }
            }

            fun fromRaf(raf: RandomAccessFile): Reader {
                return Reader(raf.length()) { pos, dest, destOffset, byteCount ->
                    raf.seek(pos)
                    raf.read(dest, destOffset, byteCount)
                }
            }
        }
    }
}
