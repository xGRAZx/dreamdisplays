package com.dreamdisplays.platform.server.audiorange

import com.dreamdisplays.platform.server.utils.PlatformUtil.isFolia
import com.dreamdisplays.shared.audiorange.AudioRangeProfile
import com.dreamdisplays.shared.audiorange.RangeDirection as Dir
import com.dreamdisplays.shared.audiorange.ZoneMessage
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.jspecify.annotations.NullMarked
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a screen's sound zone as particles that only the admin who asked can see: an orange shell
 * where the sound stops and a green one where it starts to fade. Each zone lives [LIFETIME_MS] unless
 * the editor refreshes it, and a player can have at most [MAX_ZONES_PER_PLAYER] at once.
 */
@PaperOnly
@NullMarked
internal class ZoneVisualizer(private val plugin: Plugin) {
    private class Zone(@Volatile var pose: List<Double>, @Volatile var profile: AudioRangeProfile) {
        @Volatile
        var until: Long = 0L
        var cancel: (() -> Unit)? = null
    }

    private val zones = ConcurrentHashMap<String, Zone>()
    private val outer = Particle.DustOptions(Color.fromRGB(255, 120, 0), 1.4f)
    private val inner = Particle.DustOptions(Color.fromRGB(60, 220, 90), 1.1f)

    fun handle(player: Player, message: ZoneMessage, isAdmin: Boolean) {
        val owner = player.uniqueId
        when (message) {
            is ZoneMessage.Hide -> if (message.display == null) stopAll(owner) else stop(key(owner, message.display))
            is ZoneMessage.Show -> {
                if (!isAdmin) return stopAll(owner)
                val key = key(owner, message.display)
                val p = message.profile
                if (p.range <= 0.0) return stop(key)
                val profile = AudioRangeProfile(min(p.range, MAX_RANGE), p.scales).clamped()
                val existing = zones[key]
                if (existing != null) {
                    existing.pose = message.pose
                    existing.profile = profile
                    existing.until = System.currentTimeMillis() + LIFETIME_MS
                    return
                }
                if (zones.keys.count { it.startsWith("$owner|") } >= MAX_ZONES_PER_PLAYER) return
                val zone = Zone(message.pose, profile).also { it.until = System.currentTimeMillis() + LIFETIME_MS }
                zones[key] = zone
                zone.cancel = repeat(player) { tick(player, key, zone) }
            }
        }
    }

    fun stopAll(owner: UUID) {
        zones.keys.filter { it.startsWith("$owner|") }.forEach(::stop)
    }

    private fun stop(key: String) {
        zones.remove(key)?.cancel?.invoke()
    }

    private fun tick(player: Player, key: String, zone: Zone) {
        if (zones[key] !== zone) return zone.cancel?.invoke() ?: Unit
        if (!player.isOnline || System.currentTimeMillis() > zone.until) return stop(key)
        runCatching { draw(player, zone.pose, zone.profile) }.onFailure { stop(key) }
    }

    /** Runs [task] every [PERIOD_TICKS] on the player's own thread; returns how to cancel it. */
    private fun repeat(player: Player, task: () -> Unit): () -> Unit = if (isFolia) {
        val handle = player.scheduler.runAtFixedRate(plugin, { task() }, null, 1L, PERIOD_TICKS)
        ({ handle?.cancel() })
    } else {
        val handle = plugin.server.scheduler.runTaskTimer(plugin, Runnable { task() }, 1L, PERIOD_TICKS)
        ({ handle.cancel() })
    }

    private fun draw(player: Player, pose: List<Double>, profile: AudioRangeProfile) {
        val range = profile.range
        val ringPoints = max(48.0, min(240.0, range * 6.0)).toInt()
        val fade = min(AudioRangeProfile.MAX_FADE_BLOCKS, range * 0.5)
        for (p in shellPoints(pose, profile, range, ringPoints, withLatitudes = true)) {
            player.spawnParticle(Particle.DUST, p[0], p[1], p[2], 1, 0.0, 0.0, 0.0, 0.0, outer)
        }
        for (p in shellPoints(pose, profile, max(0.1, range - fade), max(24, ringPoints / 2), withLatitudes = false)) {
            player.spawnParticle(Particle.DUST, p[0], p[1], p[2], 1, 0.0, 0.0, 0.0, 0.0, inner)
        }
    }

    companion object {
        const val LIFETIME_MS = 120_000L
        const val MAX_ZONES_PER_PLAYER = 6
        private const val PERIOD_TICKS = 10L
        private const val MAX_RANGE = 100.0

        private fun key(owner: UUID, display: UUID?): String = "$owner|${display ?: "-"}"

        /**
         * Points on the zone surface: three great circles (screen plane, horizontal, side) and, with
         * [withLatitudes], two extra rings above and below. [pose] is centre, normal, right, up.
         */
        fun shellPoints(
            pose: List<Double>,
            profile: AudioRangeProfile,
            radius: Double,
            n: Int,
            withLatitudes: Boolean,
        ): List<DoubleArray> {
            fun point(a: Double, b: Double, c: Double): DoubleArray {
                // a / b / c are a unit direction in the screen frame (normal, right, up); stretch by reach.
                val dn = radius * a / profile.scale(if (a > 0) Dir.FRONT else Dir.BACK)
                val du = radius * b / profile.scale(if (b > 0) Dir.RIGHT else Dir.LEFT)
                val dv = radius * c / profile.scale(if (c > 0) Dir.UP else Dir.DOWN)
                return DoubleArray(3) { i -> pose[i] + dn * pose[3 + i] + du * pose[6 + i] + dv * pose[9 + i] }
            }
            val out = ArrayList<DoubleArray>(n * if (withLatitudes) 5 else 3)
            for (ring in 0 until 3) for (j in 0 until n) {
                val t = 2 * PI * j / n
                val c = cos(t)
                val s = sin(t)
                out += when (ring) {
                    0 -> point(c, s, 0.0)
                    1 -> point(c, 0.0, s)
                    else -> point(0.0, c, s)
                }
            }
            if (withLatitudes) for (h in doubleArrayOf(0.6, -0.6)) {
                val r = sqrt(1 - h * h)
                for (j in 0 until n) {
                    val t = 2 * PI * j / n
                    out += point(r * cos(t), r * sin(t), h)
                }
            }
            return out
        }
    }
}
