package com.dreamdisplays.shared.audiorange

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioRangeProfileTest {
    private val eps = 1e-9

    @Test
    fun unlimitedRangeNeverFades() {
        assertEquals(1.0, AudioRangeProfile.UNLIMITED.fadeGain(10_000.0))
    }

    @Test
    fun fadesOverTheLastTwoBlocks() {
        val p = AudioRangeProfile(range = 10.0)
        assertEquals(1.0, p.fadeGain(7.9), eps)
        assertEquals(1.0, p.fadeGain(8.0), eps)
        assertEquals(0.5, p.fadeGain(9.0), eps)
        assertEquals(0.0, p.fadeGain(10.0), eps)
        assertEquals(0.0, p.fadeGain(50.0), eps)
    }

    @Test
    fun smallRangesFadeOverHalfTheirLength() {
        val p = AudioRangeProfile(range = 2.0)
        assertEquals(1.0, p.fadeGain(1.0), eps)
        assertEquals(0.5, p.fadeGain(1.5), eps)
    }

    @Test
    fun directionMultipliersShrinkTheirSide() {
        val p = AudioRangeProfile(range = 10.0)
            .withScale(RangeDirection.FRONT, 1.0)
            .withScale(RangeDirection.BACK, 4.0)
            .withScale(RangeDirection.RIGHT, 2.0)
            .withScale(RangeDirection.LEFT, 0.5)
            .withScale(RangeDirection.UP, 2.0)
            .withScale(RangeDirection.DOWN, 1.0)
        assertEquals(5.0, p.shapedDistance(5.0, 0.0, 0.0), eps)
        assertEquals(20.0, p.shapedDistance(-5.0, 0.0, 0.0), eps)
        assertEquals(10.0, p.shapedDistance(0.0, 5.0, 0.0), eps)
        assertEquals(2.5, p.shapedDistance(0.0, -5.0, 0.0), eps)
        assertEquals(6.0, p.shapedDistance(0.0, 0.0, 3.0), eps)
        assertEquals(3.0, p.shapedDistance(0.0, 0.0, -3.0), eps)
        assertEquals(2.5, p.reach(RangeDirection.BACK), eps)
    }

    @Test
    fun clampsLikeTheServer() {
        val p = AudioRangeProfile(range = -3.0, scales = listOf(0.0, Double.NaN, 5000.0, 0.0001, 1.0, 2.0)).clamped()
        assertEquals(0.0, p.range)
        assertEquals(listOf(1.0, 1.0, 1000.0, 0.001, 1.0, 2.0), p.scales)
        assertEquals(1000.0, AudioRangeProfile().withRange(1e6).range)
    }

    @Test
    fun directionAliases() {
        assertEquals(RangeDirection.UP, RangeDirection.of("up"))
        assertEquals(RangeDirection.UP, RangeDirection.of("vertical_up"))
        assertEquals(RangeDirection.DOWN, RangeDirection.of("vdown"))
        assertEquals(RangeDirection.LEFT, RangeDirection.of(" Left "))
        assertEquals(null, RangeDirection.of("range"))
    }
}
