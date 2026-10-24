package com.dreamdisplays.shared.audiorange

import com.dreamdisplays.api.Unstable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A side of the screen a direction multiplier applies to, with its `audio_range.txt` key and wire token. */
@Unstable
enum class RangeDirection(val key: String, val wire: String, val defaultScale: Double) {
    FRONT("front", "sf", 1.0),
    BACK("back", "sb", 1.0),
    RIGHT("right", "sr", 1.0),
    LEFT("left", "sl", 1.0),
    UP("vertical_up", "vup", 2.0),
    DOWN("vertical_down", "vdown", 1.0);

    companion object {
        /** Resolves a config key (also the short `up` / `down` aliases) or a wire token. */
        fun of(name: String): RangeDirection? {
            val n = name.trim().lowercase()
            return when (n) {
                "up" -> UP
                "down" -> DOWN
                else -> entries.firstOrNull { it.key == n || it.wire == n }
            }
        }
    }
}

/**
 * How far a screen's sound carries: [range] blocks (0 = no limit), shrunk per side by the direction
 * multipliers. A multiplier of 2 makes the sound reach half as far that way, 0.5 twice as far.
 */
@Unstable
data class AudioRangeProfile(
    val range: Double = DEFAULT_RANGE,
    val scales: List<Double> = RangeDirection.entries.map { it.defaultScale },
) {
    init {
        require(scales.size == RangeDirection.entries.size) { "Expected ${RangeDirection.entries.size} scales." }
    }

    fun scale(direction: RangeDirection): Double = scales[direction.ordinal]

    /** Blocks the sound reaches towards [direction], or 0 when the range is unlimited. */
    fun reach(direction: RangeDirection): Double = if (range > 0.0) range / scale(direction) else 0.0

    fun withRange(value: Double): AudioRangeProfile = copy(range = clampRange(value))

    fun withScale(direction: RangeDirection, value: Double): AudioRangeProfile =
        copy(scales = scales.toMutableList().also { it[direction.ordinal] = clampScale(value) })

    /** The same profile with every value forced into its legal range. */
    fun clamped(): AudioRangeProfile = AudioRangeProfile(clampRange(range), scales.map(::clampScale))

    /**
     * Listener distance to the screen centre, with each axis of the screen's frame stretched by the
     * multiplier of the side the listener is on. [n], [u] and [v] are the listener offset projected
     * onto the screen normal, its right axis and its up axis.
     */
    fun shapedDistance(n: Double, u: Double, v: Double): Double {
        val sn = n * if (n > 0.0) scale(RangeDirection.FRONT) else scale(RangeDirection.BACK)
        val su = u * if (u > 0.0) scale(RangeDirection.RIGHT) else scale(RangeDirection.LEFT)
        val sv = v * if (v > 0.0) scale(RangeDirection.UP) else scale(RangeDirection.DOWN)
        return sqrt(sn * sn + su * su + sv * sv)
    }

    /**
     * Gain for a listener at [shapedDistance]: 1 inside the range, fading linearly to 0 over the last
     * `min(2, range / 2)` blocks, so the edge of the zone never clicks. Always 1 for an unlimited range.
     */
    fun fadeGain(shapedDistance: Double): Double {
        if (range <= 0.0) return 1.0
        val fade = min(MAX_FADE_BLOCKS, range * 0.5)
        return ((range - shapedDistance) / fade).coerceIn(0.0, 1.0)
    }

    companion object {
        const val DEFAULT_RANGE = 5.0
        const val MAX_RANGE = 1000.0
        const val MIN_SCALE = 0.001
        const val MAX_SCALE = 1000.0
        const val MAX_FADE_BLOCKS = 2.0

        /** No limit at all; what a client uses before (or without) a server that sets one. */
        val UNLIMITED = AudioRangeProfile(range = 0.0)

        fun clampRange(value: Double): Double = if (value.isNaN() || value < 0.0) 0.0 else min(value, MAX_RANGE)

        /** Non-positive or NaN multipliers fall back to 1, the rest are kept within [MIN_SCALE]..[MAX_SCALE]. */
        fun clampScale(value: Double): Double =
            if (value.isNaN() || value <= 0.0) 1.0 else max(MIN_SCALE, min(MAX_SCALE, value))
    }
}
