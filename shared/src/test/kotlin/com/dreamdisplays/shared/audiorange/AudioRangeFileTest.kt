package com.dreamdisplays.shared.audiorange

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AudioRangeFileTest {
    private val id = UUID.fromString("0b0e9d5c-3c6e-4d61-9f43-0d2a1f1e7a10")

    @Test
    fun defaultFileParsesToDefaults() {
        val s = AudioRangeFile.parse(AudioRangeFile.defaultLines())
        assertEquals(AudioRangeProfile(), s.global)
        assertEquals(1.0, s.absorbWool)
        assertEquals(0.35, s.absorbCarpet)
        assertTrue(s.sections.isEmpty())
        assertSame(AudioRangeFile.defaultLines().size, AudioRangeFile.withMissingDefaults(AudioRangeFile.defaultLines()).size)
    }

    @Test
    fun readsGlobalSectionsAndAliases() {
        val s = AudioRangeFile.parse(
            listOf(
                "# comment",
                "12",
                "back=4",
                "up=3",
                "absorb_wool=0.3",
                "absorb_carpet=7",
                "",
                "[display $id]",
                "range=3",
                "left=2",
                "absorb_wool=0", // not a section key: ignored
                "[display 10, 64 -5]",
                "range=8",
                "[garbage]",
                "range=99",
            ),
        )
        assertEquals(12.0, s.global.range)
        assertEquals(4.0, s.global.scale(RangeDirection.BACK))
        assertEquals(3.0, s.global.scale(RangeDirection.UP))
        assertEquals(0.3, s.absorbWool)
        assertEquals(1.0, s.absorbCarpet)
        assertEquals(RangeOverride(3.0, mapOf(RangeDirection.LEFT to 2.0)), s.sections[id.toString()])
        assertEquals(RangeOverride(8.0), s.sections["10,64,-5"])
        assertEquals(2, s.sections.size)

        val (byId, custom) = s.effective(id, "10,64,-5")
        assertTrue(custom)
        assertEquals(3.0, byId.range)
        assertEquals(2.0, byId.scale(RangeDirection.LEFT))
        assertEquals(4.0, byId.scale(RangeDirection.BACK)) // inherited
        assertEquals(8.0, s.effective(UUID.randomUUID(), "10,64,-5").first.range)
        val (plain, notCustom) = s.effective(UUID.randomUUID(), "0,0,0")
        assertFalse(notCustom)
        assertEquals(s.global, plain)
    }

    @Test
    fun addsMissingKeysAboveTheFirstSection() {
        val out = AudioRangeFile.withMissingDefaults(listOf("range=7", "[display $id]", "range=2"))
        assertEquals("range=7", out[0])
        val sectionAt = out.indexOf("[display $id]")
        assertEquals("", out[sectionAt - 1])
        assertTrue(out.subList(0, sectionAt).contains("vertical_up=2"))
        assertTrue(out.subList(0, sectionAt).contains("absorb_carpet=0.35"))
        val s = AudioRangeFile.parse(out)
        assertEquals(7.0, s.global.range)
        assertEquals(2.0, s.sections.getValue(id.toString()).range)
    }

    @Test
    fun replacesOnlyTheEditedSection() {
        val other = UUID.randomUUID()
        val lines = listOf(
            "range=5",
            "",
            "[display $id]",
            "range=1",
            "# a note",
            "",
            "[display $other]",
            "range=9",
            "",
        )
        val edited = AudioRangeFile.withSection(lines, id, AudioRangeProfile(range = 2.5).withScale(RangeDirection.BACK, 3.0))
        val s = AudioRangeFile.parse(edited)
        assertEquals(9.0, s.sections.getValue(other.toString()).range)
        assertEquals(2.5, s.sections.getValue(id.toString()).range)
        assertEquals(3.0, s.sections.getValue(id.toString()).scales[RangeDirection.BACK])
        assertEquals("[display $id]", edited[edited.size - 8])

        val cleared = AudioRangeFile.withSection(edited, id, null)
        assertNull(AudioRangeFile.parse(cleared).sections[id.toString()])
        assertEquals("range=9", cleared.last())
    }

    @Test
    fun formatsNumbersCompactly() {
        assertEquals("5", AudioRangeFile.fmt(5.0))
        assertEquals("0.35", AudioRangeFile.fmt(0.35))
        assertEquals("0.333", AudioRangeFile.fmt(1.0 / 3.0))
        assertEquals("-2", AudioRangeFile.fmt(-2.0))
    }

    @Test
    fun sectionKeys() {
        assertEquals(id.toString(), AudioRangeFile.sectionKey("display $id"))
        assertEquals("1,2,3", AudioRangeFile.sectionKey("DISPLAY 1 2 3"))
        assertEquals("1,2,3", AudioRangeFile.sectionKey("display 1,2,3"))
        assertNull(AudioRangeFile.sectionKey("display not-a-uuid"))
        assertNull(AudioRangeFile.sectionKey("screen 1 2 3"))
    }
}
