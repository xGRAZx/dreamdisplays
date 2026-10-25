package com.dreamdisplays.platform.client.audiorange

import com.dreamdisplays.api.media.audio.ListenerPose
import com.dreamdisplays.api.media.audio.SourcePlane
import com.dreamdisplays.shared.audiorange.Absorber
import com.dreamdisplays.shared.audiorange.SoundAbsorption
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-screen gain from wool and carpet between the listener and the screen, traced on the game thread
 * every [PROBE_EVERY_TICKS] ticks along five rays (centre and four points inset on the screen) and
 * eased towards the new value so walking behind a wool wall fades instead of cutting.
 */
internal object WoolAbsorptionProbe {
    private const val PROBE_EVERY_TICKS = 3
    private const val MAX_DISTANCE = 128.0
    private const val SAMPLE_INSET = 0.35
    private const val EASE = 0.5

    /** Blocks this close to the screen end of a ray are the screen's own mounting, not a wall. */
    private const val SCREEN_MARGIN = 0.6

    private class State(var gain: Double = 1.0, var target: Double = 1.0, var countdown: Int = 0)

    private val states = ConcurrentHashMap<UUID, State>()

    /** Advances [id]'s state by one tick and returns its current gain. */
    fun tick(id: UUID, plane: SourcePlane, listener: ListenerPose, absorbWool: Double, absorbCarpet: Double): Double {
        val s = states.getOrPut(id) { State() }
        if (absorbWool <= 0.0 && absorbCarpet <= 0.0) {
            s.gain = 1.0; s.target = 1.0
            return 1.0
        }
        if (--s.countdown <= 0) {
            s.countdown = PROBE_EVERY_TICKS
            s.target = runCatching { compute(plane, listener, absorbWool, absorbCarpet) }.getOrDefault(1.0)
        }
        s.gain += (s.target - s.gain) * EASE
        if (kotlin.math.abs(s.target - s.gain) < 0.002) s.gain = s.target
        return s.gain
    }

    fun forget(id: UUID) {
        states.remove(id)
    }

    fun clear() = states.clear()

    private fun compute(plane: SourcePlane, listener: ListenerPose, wool: Double, carpet: Double): Double {
        val level = Minecraft.getInstance().level ?: return 1.0
        val dx = plane.centerX - listener.x
        val dy = plane.centerY - listener.y
        val dz = plane.centerZ - listener.z
        if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE * MAX_DISTANCE) return 1.0
        val du = plane.width * SAMPLE_INSET
        val dv = plane.height * SAMPLE_INSET
        var sum = 0.0
        for (i in 0 until 5) {
            val (su, sv) = when (i) {
                1 -> du to 0.0
                2 -> -du to 0.0
                3 -> 0.0 to dv
                4 -> 0.0 to -dv
                else -> 0.0 to 0.0
            }
            val tx = plane.centerX + plane.uAxisX * su + plane.vAxisX * sv
            val ty = plane.centerY + plane.uAxisY * su + plane.vAxisY * sv
            val tz = plane.centerZ + plane.uAxisZ * su + plane.vAxisZ * sv
            sum += ray(level, listener.x, listener.y, listener.z, tx, ty, tz, wool, carpet)
        }
        return (sum / 5).coerceIn(0.0, 1.0)
    }

    private fun ray(
        level: Level,
        fx: Double, fy: Double, fz: Double,
        tx: Double, ty: Double, tz: Double,
        wool: Double, carpet: Double,
    ): Double {
        val length = Vec3(fx, fy, fz).distanceTo(Vec3(tx, ty, tz))
        if (length < SoundAbsorption.MIN_RAY_LENGTH) return 1.0
        val stopAt = length - SCREEN_MARGIN
        val pos = BlockPos.MutableBlockPos()
        var gain = 1.0
        SoundAbsorption.traverse(fx, fy, fz, tx, ty, tz) { x, y, z, enter, exit ->
            if (enter >= stopAt) return@traverse false
            pos.set(x, y, z)
            val state = level.getBlockState(pos)
            val kind = kindOf(state)
            if (kind != Absorber.NONE) {
                // A carpet only counts where the ray really goes through its thin slab.
                val touches = kind == Absorber.WOOL ||
                    state.getShape(level, pos).clip(Vec3(fx, fy, fz), Vec3(tx, ty, tz), pos) != null
                if (touches) gain *= SoundAbsorption.blockGain(kind, exit - enter, wool, carpet)
            }
            gain > 0.001
        }
        return if (gain <= 0.001) 0.0 else gain
    }

    private fun kindOf(state: BlockState): Absorber = when {
        state.isAir -> Absorber.NONE
        state.`is`(BlockTags.WOOL) -> Absorber.WOOL
        state.`is`(BlockTags.WOOL_CARPETS) -> Absorber.CARPET
        else -> Absorber.NONE
    }
}
