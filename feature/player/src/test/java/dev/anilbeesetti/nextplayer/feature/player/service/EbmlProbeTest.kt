package dev.anilbeesetti.nextplayer.feature.player.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EbmlProbeTest {

    @Test
    fun varintLength_readsMaskBits() {
        assertEquals(-1, EbmlProbe.varintLength(0x00))
        assertEquals(1, EbmlProbe.varintLength(0x80))
        assertEquals(1, EbmlProbe.varintLength(0x81))
        assertEquals(1, EbmlProbe.varintLength(0xFF))
        assertEquals(2, EbmlProbe.varintLength(0x40))
        assertEquals(3, EbmlProbe.varintLength(0x20))
        assertEquals(4, EbmlProbe.varintLength(0x10))
        assertEquals(8, EbmlProbe.varintLength(0x01))
    }

    @Test
    fun decodeVarint_size_oneByte() {
        val decoded = EbmlProbe.decodeVarint(
            data = byteArrayOf(0x85.toByte()),
            offset = 0,
            removeLengthMask = true,
        )
        assertNotNull(decoded)
        assertEquals(5L, decoded!!.value)
        assertEquals(1, decoded.encodedLength)
        assertFalse(decoded.isUnknownSize)
    }

    @Test
    fun decodeVarint_size_rejectsZeroMask() {
        assertNull(
            EbmlProbe.decodeVarint(
                data = byteArrayOf(0x00),
                offset = 0,
                removeLengthMask = true,
            ),
        )
    }

    @Test
    fun decodeVarint_size_unknownIsAllOnes() {
        val oneByte = EbmlProbe.decodeVarint(
            data = byteArrayOf(0xFF.toByte()),
            offset = 0,
            removeLengthMask = true,
        )
        assertNotNull(oneByte)
        assertTrue(oneByte!!.isUnknownSize)
        assertEquals(EbmlProbe.UNKNOWN_SIZE, oneByte.value)

        val eight = EbmlProbe.decodeVarint(
            data = byteArrayOf(
                0x01,
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
            ),
            offset = 0,
            removeLengthMask = true,
        )
        assertNotNull(eight)
        assertTrue(eight!!.isUnknownSize)
        assertEquals(8, eight.encodedLength)
    }

    @Test
    fun decodeVarint_id_keepsLengthMask() {
        val cluster = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
        val decoded = EbmlProbe.decodeVarint(
            data = cluster,
            offset = 0,
            removeLengthMask = false,
        )
        assertNotNull(decoded)
        assertEquals(0x1F43B675L, decoded!!.value)
        assertEquals(4, decoded.encodedLength)
    }

    @Test
    fun decodeUnsignedInteger_rejectsBadSize() {
        assertNull(EbmlProbe.decodeUnsignedInteger(byteArrayOf(0x01), 0, 0))
        assertNull(EbmlProbe.decodeUnsignedInteger(ByteArray(16), 0, 9))
        assertEquals(0x03E8L, EbmlProbe.decodeUnsignedInteger(byteArrayOf(0x03, 0xE8.toByte()), 0, 2))
    }

    @Test
    fun reader_readsIdAndSizeThenSkips() {
        val payload = ByteArray(5) { 0x11 }
        val data = byteArrayOf(0xE7.toByte(), 0x85.toByte()) + payload + byteArrayOf(0x22)
        val reader = EbmlProbe.Reader.fromBytes(data)
        val id = reader.readId()
        val size = reader.readSize()
        assertEquals(0xE7L, id!!.value)
        assertEquals(5L, size!!.value)
        assertTrue(reader.skip(size.value))
        assertEquals(data.size - 1L, reader.position)
    }
}
