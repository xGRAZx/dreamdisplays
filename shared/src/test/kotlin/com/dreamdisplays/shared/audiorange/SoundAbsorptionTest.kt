package com.dreamdisplays.shared.audiorange

import kotlin.test.Test
import kotlin.test.assertEquals

class SoundAbsorptionTest {
    private val eps = 1e-9

    private fun voxels(fx: Double, fy: Double, fz: Double, tx: Double, ty: Double, tz: Double): List<List<Double>> {
        val out = ArrayList<List<Double>>()
        SoundAbsorption.traverse(fx, fy, fz, tx, ty, tz) { x, y, z, enter, exit ->
            out += listOf(x.toDouble(), y.toDouble(), z.toDouble(), enter, exit); true
        }
        return out
    }

    @Test
    fun walksAStraightLineThroughEveryVoxel() {
        val v = voxels(0.5, 0.5, 0.5, 3.5, 0.5, 0.5)
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), v.map { it[0] })
        assertEquals(listOf(0.0, 0.5, 1.5, 2.5), v.map { it[3] })
        assertEquals(listOf(0.5, 1.5, 2.5, 3.0), v.map { it[4] })
    }

    @Test
    fun walksBackwardsAndDiagonally() {
        val back = voxels(2.5, 0.5, 0.5, -0.5, 0.5, 0.5)
        assertEquals(listOf(2.0, 1.0, 0.0, -1.0), back.map { it[0] })
        val diag = voxels(0.2, 0.2, 0.5, 2.8, 1.8, 0.5)
        // Path lengths add up to the whole segment and every step moves to a face neighbour.
        assertEquals(kotlin.math.hypot(2.6, 1.6), diag.sumOf { it[4] - it[3] }, 1e-9)
        diag.zipWithNext().forEach { (a, b) ->
            assertEquals(1.0, kotlin.math.abs(a[0] - b[0]) + kotlin.math.abs(a[1] - b[1]) + kotlin.math.abs(a[2] - b[2]))
        }
    }

    @Test
    fun canStopEarly() {
        var n = 0
        SoundAbsorption.traverse(0.5, 0.5, 0.5, 50.5, 0.5, 0.5) { _, _, _, _, _ -> ++n < 3 }
        assertEquals(3, n)
    }

    @Test
    fun woolAndCarpetGains() {
        assertEquals(0.0, SoundAbsorption.blockGain(Absorber.WOOL, 1.0, 1.0, 0.35), eps)
        assertEquals(1.0, SoundAbsorption.blockGain(Absorber.WOOL, 0.01, 1.0, 0.35), eps)
        assertEquals(0.5, SoundAbsorption.blockGain(Absorber.WOOL, 1.0, 0.5, 0.35), eps)
        assertEquals(0.25, SoundAbsorption.blockGain(Absorber.WOOL, 2.0, 0.5, 0.35), eps)
        assertEquals(1.0, SoundAbsorption.blockGain(Absorber.WOOL, 1.0, 0.0, 0.35), eps)
        assertEquals(0.65, SoundAbsorption.blockGain(Absorber.CARPET, 0.1, 1.0, 0.35), eps)
        assertEquals(1.0, SoundAbsorption.blockGain(Absorber.NONE, 1.0, 1.0, 1.0), eps)
    }
}
