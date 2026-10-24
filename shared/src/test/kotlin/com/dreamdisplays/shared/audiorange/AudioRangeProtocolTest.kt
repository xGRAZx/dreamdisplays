package com.dreamdisplays.shared.audiorange

import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioRangeProtocolTest {
    private val id = UUID.fromString("0b0e9d5c-3c6e-4d61-9f43-0d2a1f1e7a10")
    private val screen = ScreenRef(id, 10, 64, -5)

    /** The frame exactly as the audiorange 1-21 builds wrote it by hand. */
    private fun legacyFrame(type: Int, text: String): ByteArray {
        fun varint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (v and -128L != 0L) {
                out.write((v and 0x7F or 0x80).toInt()); v = v ushr 7
            }
            out.write(v.toInt())
        }
        val body = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(8); varint(out, type.toLong()); out.write(18); varint(out, body.size.toLong()); out.write(body)
        return out.toByteArray()
    }

    @Test
    fun framesAreByteCompatibleWithOldBuilds() {
        val text = "zone|off|$id"
        assertContentEquals(legacyFrame(9002, text), AudioRangeProtocol.frame(9002, text))
        assertEquals(9002 to text, AudioRangeProtocol.unframe(legacyFrame(9002, text)))
        assertEquals(9003, AudioRangeProtocol.peekType(AudioRangeProtocol.frame(9003, AudioRangeProtocol.HELLO)))
        val long = "x".repeat(300)
        assertEquals(9004 to long, AudioRangeProtocol.unframe(AudioRangeProtocol.frame(9004, long)))
    }

    @Test
    fun rejectsBrokenFrames() {
        assertNull(AudioRangeProtocol.unframe(byteArrayOf(8)))
        assertNull(AudioRangeProtocol.unframe(byteArrayOf(8, 1, 18, 50, 1)))
        assertNull(AudioRangeProtocol.unframe(byteArrayOf(0x0D, 1, 2, 3, 4)))
        assertNull(AudioRangeProtocol.peekType(byteArrayOf(0x12, 0)))
        assertNull(AudioRangeProtocol.unframe(ByteArray(0)))
    }

    @Test
    fun rangeMessagesRoundTrip() {
        val profile = AudioRangeProfile(range = 7.5).withScale(RangeDirection.UP, 3.0)
        val messages = listOf(
            RangeMessage.Set(screen, profile),
            RangeMessage.Clear(screen),
            RangeMessage.Custom(screen),
            RangeMessage.Key(screen, null, 12.0),
            RangeMessage.Key(screen, RangeDirection.DOWN, 0.5),
        )
        messages.forEach { assertEquals(it, RangeMessage.decode(it.encode())) }
        assertEquals("key|$id|10|64|-5|vertical_down|0.5000", messages[4].encode())
    }

    @Test
    fun decodesWhatOldModsSend() {
        val set = RangeMessage.decode("set|$id|1|2|3|4.0000|1.0000|2.0000|1.0000|1.0000|2.0000|1.0000")
        assertEquals(RangeMessage.Set(ScreenRef(id, 1, 2, 3), AudioRangeProfile(4.0, listOf(1.0, 2.0, 1.0, 1.0, 2.0, 1.0))), set)
        assertNull(RangeMessage.decode("set|$id|1|2|3|4"))
        assertNull(RangeMessage.decode("key|$id|1|2|3|sideways|2"))
        assertNull(RangeMessage.decode("bogus"))
    }

    @Test
    fun zoneMessagesRoundTrip() {
        val pose = List(12) { it * 0.5 }
        val show = ZoneMessage.Show(id, pose, AudioRangeProfile(range = 6.0))
        assertEquals(show, ZoneMessage.decode(show.encode()))
        assertEquals(ZoneMessage.Hide(null), ZoneMessage.decode("zone|off"))
        assertEquals(ZoneMessage.Hide(id), ZoneMessage.decode("zone|off|$id"))
        // Old builds without a display id.
        val anonymous = ZoneMessage.Show(null, pose, AudioRangeProfile(range = 6.0))
        assertEquals(anonymous, ZoneMessage.decode(anonymous.encode()))
        assertNull(ZoneMessage.decode("zone|on|$id|1|2"))
        assertNull(ZoneMessage.decode(show.encode().replace("6.0000", "NaN")))
    }

    @Test
    fun viewMessagesRoundTrip() {
        val edits = listOf(
            ViewEdit.Acoustics("ULTRA"),
            ViewEdit.Acoustics(null),
            ViewEdit.Volume(id, 0.75),
            ViewEdit.Brightness(id, 1.0),
            ViewEdit.Quality(id, "1080"),
            ViewEdit.Clear(id),
        )
        edits.forEach { assertEquals(it, ViewMessage.decode(ViewMessage.encode(it))) }
        assertEquals(ViewEdit.Volume(id, 0.5), ViewMessage.decode("v|$id|vol|0.5"))
        assertNull(ViewMessage.decode("v|-|vol|0.5"))
        assertNull(ViewMessage.decode("v|$id|vol|loud"))
        assertTrue(ViewMessage.decode("x|$id|vol|0.5") == null)
    }
}
