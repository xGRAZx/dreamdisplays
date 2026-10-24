package com.dreamdisplays.shared.audiorange

import com.dreamdisplays.api.Unstable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** Which absorbing material a block is. */
@Unstable
enum class Absorber { NONE, WOOL, CARPET }

/**
 * Wool and carpet between the listener and a screen work like acoustic foam. A ray is walked voxel by
 * voxel ([traverse]); every wool block it crosses keeps `(1 - absorbWool) ^ blocksCrossed` of the sound
 * (so `absorbWool = 1` silences anything behind a full block), and every carpet it actually touches
 * keeps `1 - absorbCarpet`.
 */
@Unstable
object SoundAbsorption {
    /** Hits shorter than this (a ray grazing a block's corner) do not count for full-strength wool. */
    const val MIN_WOOL_PATH = 0.05

    /** Rays shorter than this are not traced: the listener is basically at the screen. */
    const val MIN_RAY_LENGTH = 0.6

    /** Gain left after crossing [path] blocks of a block of [kind]. */
    fun blockGain(kind: Absorber, path: Double, absorbWool: Double, absorbCarpet: Double): Double = when (kind) {
        Absorber.NONE -> 1.0
        Absorber.CARPET -> 1.0 - absorbCarpet.coerceIn(0.0, 1.0)
        Absorber.WOOL -> {
            val a = absorbWool.coerceIn(0.0, 1.0)
            when {
                a <= 0.0 -> 1.0
                a >= 1.0 -> if (path > MIN_WOOL_PATH) 0.0 else 1.0
                else -> (1.0 - a).pow(path.coerceAtLeast(0.0))
            }
        }
    }

    /**
     * Calls [visit] with every voxel the segment [from] → [to] passes through, in order, and the length
     * of the segment inside it. Stops early when [visit] returns false. At most [maxSteps] voxels.
     */
    inline fun traverse(
        fx: Double, fy: Double, fz: Double,
        tx: Double, ty: Double, tz: Double,
        maxSteps: Int = 256,
        visit: (x: Int, y: Int, z: Int, enter: Double, exit: Double) -> Boolean,
    ) {
        val dx = tx - fx
        val dy = ty - fy
        val dz = tz - fz
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length <= 1e-9) return
        var x = floor(fx).toInt()
        var y = floor(fy).toInt()
        var z = floor(fz).toInt()
        val endX = floor(tx).toInt()
        val endY = floor(ty).toInt()
        val endZ = floor(tz).toInt()
        val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0
        val stepZ = if (dz > 0) 1 else if (dz < 0) -1 else 0
        // Distances along the ray (in blocks) to cross one voxel on each axis, and to the first crossing.
        val deltaX = if (stepX != 0) abs(length / dx) else Double.POSITIVE_INFINITY
        val deltaY = if (stepY != 0) abs(length / dy) else Double.POSITIVE_INFINITY
        val deltaZ = if (stepZ != 0) abs(length / dz) else Double.POSITIVE_INFINITY
        var nextX = if (stepX > 0) (x + 1 - fx) * deltaX else if (stepX < 0) (fx - x) * deltaX else Double.POSITIVE_INFINITY
        var nextY = if (stepY > 0) (y + 1 - fy) * deltaY else if (stepY < 0) (fy - y) * deltaY else Double.POSITIVE_INFINITY
        var nextZ = if (stepZ > 0) (z + 1 - fz) * deltaZ else if (stepZ < 0) (fz - z) * deltaZ else Double.POSITIVE_INFINITY
        var enter = 0.0
        var steps = 0
        while (steps++ < maxSteps) {
            val last = x == endX && y == endY && z == endZ
            val exit = if (last) length else minOf(nextX, nextY, nextZ, length)
            if (!visit(x, y, z, enter, exit) || last || exit >= length) return
            enter = exit
            when {
                nextX <= nextY && nextX <= nextZ -> { x += stepX; nextX += deltaX }
                nextY <= nextZ -> { y += stepY; nextY += deltaY }
                else -> { z += stepZ; nextZ += deltaZ }
            }
        }
    }
}
