package com.dreamdisplays.platform.client.audiorange

import com.dreamdisplays.api.media.audio.ListenerPose
import com.dreamdisplays.api.media.audio.SourcePlane
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.shared.audiorange.AudioRangeFeatures
import com.dreamdisplays.shared.audiorange.AudioRangeFile
import com.dreamdisplays.shared.audiorange.AudioRangeProfile
import java.util.UUID

/**
 * Client side of the audiorange extension: keeps what the server sent in its `ServerHello` and turns
 * it into a per-screen gain (sound range fade x wool / carpet absorption). Without an audiorange server
 * every gain is 1 and the mod behaves exactly like the original.
 */
object AudioRangeClient {
    /** How long sound stays muted after joining while the server's settings have not arrived yet. */
    private const val WAIT_FOR_SERVER_MS = 5_000L

    @Volatile
    var features: AudioRangeFeatures = AudioRangeFeatures()
        private set

    @Volatile
    private var helloReceived = false

    @Volatile
    private var waitingSince = 0L

    /** True when this player may edit the server's sound and view settings. */
    val isSoundAdmin: Boolean
        get() = features.soundAdmin || (features.rangeSupported && features.revision == 0 && legacyAdmin)

    /** An audiorange 1-21 plugin sends no admin flag; fall back to the original admin flag there. */
    @Volatile
    private var legacyAdmin = false

    val serverHasEditor: Boolean get() = features.rangeSupported

    /** True once this connection's server settings (or the lack of an audiorange plugin) are known. */
    val settingsReceived: Boolean get() = helloReceived

    /** Called for every `ServerHello` (join, and again whenever an admin saves a change). */
    fun onServerHello(hello: ServerHello) {
        val parsed = AudioRangeFeatures.fromWire(hello.allowedFeatures)
        val first = !helloReceived
        features = parsed
        legacyAdmin = hello.isAdmin
        helloReceived = true
        // Identify as the audiorange mod once per connection, which also opens the server's mod gate.
        if (first && parsed.rangeSupported) AudioRangeNet.hello()
        ViewSync.onFeatures(parsed)
    }

    fun reset() {
        features = AudioRangeFeatures()
        helloReceived = false
        waitingSince = 0L
        legacyAdmin = false
        WoolAbsorptionProbe.clear()
        ViewSync.reset()
    }

    /** False for the first seconds of a connection, until the server's limits are known, so nobody hears a blip. */
    private fun soundAllowed(): Boolean {
        if (helloReceived) return true
        val now = System.currentTimeMillis()
        if (waitingSince == 0L) waitingSince = now
        return now - waitingSince > WAIT_FOR_SERVER_MS
    }

    /** The profile for a screen and whether it has its own section. */
    fun profileFor(id: UUID?, x: Int, y: Int, z: Int): Pair<AudioRangeProfile, Boolean> =
        features.profileFor(id, AudioRangeFile.posKey(x, y, z))

    /**
     * The extra gain for a screen this tick, `0.0`..`1.0`. Called once per screen per game tick.
     * [x] / [y] / [z] is the screen's block position, used for sections addressed by position.
     */
    fun gainFor(id: UUID, x: Int, y: Int, z: Int, plane: SourcePlane, listener: ListenerPose): Double {
        if (!soundAllowed()) return 0.0
        val f = features
        if (!f.rangeSupported) return 1.0
        val profile = profileFor(id, x, y, z).first
        val ox = listener.x - plane.centerX
        val oy = listener.y - plane.centerY
        val oz = listener.z - plane.centerZ
        val fade = profile.fadeGain(
            profile.shapedDistance(
                n = ox * plane.normalX + oy * plane.normalY + oz * plane.normalZ,
                u = ox * plane.uAxisX + oy * plane.uAxisY + oz * plane.uAxisZ,
                v = ox * plane.vAxisX + oy * plane.vAxisY + oz * plane.vAxisZ,
            ),
        )
        if (fade <= 0.0) return 0.0
        return fade * WoolAbsorptionProbe.tick(id, plane, listener, f.range.absorbWool, f.range.absorbCarpet)
    }

    fun forget(id: UUID) = WoolAbsorptionProbe.forget(id)
}
