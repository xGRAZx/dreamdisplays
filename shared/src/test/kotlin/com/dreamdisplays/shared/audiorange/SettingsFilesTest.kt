package com.dreamdisplays.shared.audiorange

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsFilesTest {
    private val id = UUID.fromString("0b0e9d5c-3c6e-4d61-9f43-0d2a1f1e7a10")

    @Test
    fun viewSettingsRoundTripThroughTheFile() {
        val s = ViewSettings("ULTRA", mapOf(id to ViewOverride(1.5, 0.8, "1080")))
        assertEquals(s, ViewSettingsFile.parse(ViewSettingsFile.render(s)))
    }

    @Test
    fun viewEditsAreClampedAndValidated() {
        var s = ViewSettings()
        s = ViewSettingsFile.apply(s, ViewEdit.Volume(id, 9.0))!!
        s = ViewSettingsFile.apply(s, ViewEdit.Brightness(id, -1.0))!!
        s = ViewSettingsFile.apply(s, ViewEdit.Quality(id, "720p"))!!
        s = ViewSettingsFile.apply(s, ViewEdit.Acoustics("advanced"))!!
        assertEquals(ViewSettings("ADVANCED", mapOf(id to ViewOverride(4.0, 0.0, "720"))), s)
        assertNull(ViewSettingsFile.apply(s, ViewEdit.Quality(id, "huge")))
        assertNull(ViewSettingsFile.apply(s, ViewEdit.Acoustics("LOUD")))
        assertNull(ViewSettingsFile.apply(s, ViewEdit.Volume(id, Double.NaN)))
        assertNull(ViewSettingsFile.apply(s, ViewEdit.Acoustics(null))!!.acoustics)
        assertTrue(ViewSettingsFile.apply(s, ViewEdit.Clear(id))!!.displays.isEmpty())
    }

    @Test
    fun viewFileIgnoresUnknownLines() {
        val s = ViewSettingsFile.parse(listOf("a3=loud", "[display nope]", "volume=1", "[display $id]", "quality=auto", "colour=red"))
        assertNull(s.acoustics)
        assertEquals(mapOf(id to ViewOverride(quality = "auto")), s.displays)
    }

    @Test
    fun modGateDefaultsAndOverrides() {
        val d = ModGateFile.parse(ModGateFile.DEFAULT_LINES)
        assertTrue(d.enabled)
        assertEquals(ModGateSettings(), d)

        val s = ModGateFile.parse(listOf("off", "true", "message=&aHi", "message_en=", "download=https://x.y/z", "button=Get"))
        assertFalse(s.enabled)
        assertEquals("§aHi", s.message)
        assertEquals(ModGateSettings.DEFAULT_MESSAGE_EN, s.messageEn)
        assertEquals("https://x.y/z", s.downloadUrl)
        assertEquals("Get", s.buttonLabel)
    }
}
