package com.dreamdisplays.shared.audiorange

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioRangeFeaturesTest {
    private val id = UUID.fromString("0b0e9d5c-3c6e-4d61-9f43-0d2a1f1e7a10")

    @Test
    fun noTokensMeansNoServerAndNoLimit() {
        val f = AudioRangeFeatures.fromWire(listOf("modes", "watch_party"))
        assertFalse(f.rangeSupported)
        assertFalse(f.viewSupported)
        assertFalse(f.soundAdmin)
        assertEquals(1.0, f.profileFor(id, "0,0,0").first.fadeGain(1e6))
    }

    @Test
    fun roundTripsEverything() {
        val features = AudioRangeFeatures(
            rangeSupported = true,
            viewSupported = true,
            soundAdmin = true,
            revision = AudioRangeFeatures.CURRENT_REVISION,
            range = AudioRangeSettings(
                global = AudioRangeProfile(range = 6.0).withScale(RangeDirection.BACK, 3.0),
                absorbWool = 0.3,
                absorbCarpet = 0.0,
                sections = mapOf(
                    id.toString() to RangeOverride(2.0, mapOf(RangeDirection.LEFT to 0.5)),
                    "1,2,3" to RangeOverride(scales = mapOf(RangeDirection.UP to 1.0)),
                ),
            ),
            view = ViewSettings("ADVANCED", mapOf(id to ViewOverride(volume = 0.5, quality = "720"))),
        )
        assertEquals(features, AudioRangeFeatures.fromWire(features.toWire()))
    }

    @Test
    fun readsTokensOfTheOldPlugin() {
        val f = AudioRangeFeatures.fromWire(
            listOf(
                "modes", "audio_ui", "audio_range:5", "audio_sf:1", "audio_sb:4", "audio_sr:1", "audio_sl:1",
                "audio_vup:2", "audio_vdown:1", "audio_absorb_wool:1", "audio_absorb_carpet:0.35",
                "audio_d:$id:range=3;sb=2", "audio_d:4,70,-12:range=9",
                "view_ui", "view_g:a3=ULTRA;by=${UUID.randomUUID()}", "view_d:$id:v=0.500;b=1.000;by=${UUID.randomUUID()}",
            ),
        )
        assertTrue(f.rangeSupported)
        assertTrue(f.viewSupported)
        assertFalse(f.soundAdmin)
        assertEquals(0, f.revision)
        assertEquals(4.0, f.range.global.scale(RangeDirection.BACK))
        val (p, custom) = f.profileFor(id, null)
        assertTrue(custom)
        assertEquals(3.0, p.range)
        assertEquals(2.0, p.scale(RangeDirection.BACK))
        assertEquals(2.0, p.scale(RangeDirection.UP))
        assertEquals(9.0, f.profileFor(UUID.randomUUID(), "4,70,-12").first.range)
        assertEquals("ULTRA", f.view.acoustics)
        assertEquals(ViewOverride(volume = 0.5, brightness = 1.0), f.view.displays[id])
        // The old plugin never fixed the brightness of screens without their own value.
        assertEquals(null, f.view.playerBrightness)
    }

    @Test
    fun playerBrightnessRoundTrips() {
        for (b in listOf(null, 0.0, 1.0, 1.75)) {
            val features = AudioRangeFeatures(viewSupported = true, view = ViewSettings(playerBrightness = b))
            assertEquals(b, AudioRangeFeatures.fromWire(features.toWire()).view.playerBrightness)
        }
        assertEquals(2.0, AudioRangeFeatures.fromWire(listOf("view_ui", "view_bri:9")).view.playerBrightness)
        assertEquals(null, AudioRangeFeatures.fromWire(listOf("view_ui", "view_bri:bright")).view.playerBrightness)
    }

    @Test
    fun ignoresGarbage() {
        val f = AudioRangeFeatures.fromWire(listOf("audio_ui", "audio_range:abc", "audio_d:nope", "view_d:bad:v=1", "audio_zz:3"))
        assertTrue(f.rangeSupported)
        assertEquals(0.0, f.range.global.range)
        assertTrue(f.range.sections.isEmpty())
        assertTrue(f.view.displays.isEmpty())
    }
}
