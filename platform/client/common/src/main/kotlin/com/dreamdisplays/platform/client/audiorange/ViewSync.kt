package com.dreamdisplays.platform.client.audiorange

import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.core.services.keys.AudioAcousticsServices
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.shared.audiorange.AudioRangeFeatures
import com.dreamdisplays.shared.audiorange.ViewEdit
import com.dreamdisplays.shared.audiorange.ViewOverride
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Volume, brightness, quality and the 3D sound mode an audiorange server forces on every viewer.
 *
 * Players follow the server and cannot change these. A sound admin changes them with the usual menu
 * controls: each change is sent to the server (debounced), which saves it and pushes it to everyone.
 * An admin's own screen only takes a server value when it is new, so the admin's slider never jumps
 * back while they are still dragging it.
 */
object ViewSync {
    private const val SEND_DELAY_MS = 350L
    private const val ECHO_MS = 5_000L

    /** Forced values per display with a revision that changes whenever the server's value changes. */
    private class Entry(val view: ViewOverride, val rev: Long)

    private val entries = ConcurrentHashMap<UUID, Entry>()
    private val applied = ConcurrentHashMap<UUID, Long>()
    private val pending = ConcurrentHashMap<String, Pair<ViewEdit, Long>>()
    private val lastSent = ConcurrentHashMap<UUID, Long>()

    @Volatile
    private var supported = false

    @Volatile
    private var serverAcoustics: AcousticQuality? = null
    private var acousticsRev = 0L
    private var acousticsApplied = -1L

    @Volatile
    private var acousticsSentAt = 0L
    private var revCounter = 0L

    /** True on an audiorange server for a player who is not a sound admin: the view controls are locked. */
    val locked: Boolean get() = supported && !AudioRangeClient.isSoundAdmin

    /** What the server forces on [id] for this player, or null when the player is free to choose. */
    fun forced(id: UUID): ViewOverride? = if (locked) entries[id]?.view else null

    @Synchronized
    fun onFeatures(f: AudioRangeFeatures) {
        supported = f.viewSupported
        val fresh = f.view.displays
        for ((id, view) in fresh) {
            val old = entries[id]
            if (old == null || old.view != view) entries[id] = Entry(view, ++revCounter)
        }
        entries.keys.retainAll(fresh.keys)
        val a3 = f.view.acoustics?.let { name -> AcousticQuality.entries.firstOrNull { it.name == name } }
        if (a3 != serverAcoustics) acousticsRev = ++revCounter
        serverAcoustics = a3
        if (supported && acousticsApplied < 0) acousticsRev = ++revCounter
    }

    @Synchronized
    fun reset() {
        supported = false
        serverAcoustics = null
        acousticsApplied = -1L
        entries.clear()
        applied.clear()
        pending.clear()
        lastSent.clear()
        // Back to the player's own 3D mode on servers without audiorange.
        pushAcoustics(ClientStateManager.config.audioAcoustics)
    }

    /**
     * The 3D sound mode in force: the server's choice on an audiorange server (Advanced when it sets
     * none, for players), otherwise the player's own setting.
     */
    val effectiveAcoustics: AcousticQuality
        get() {
            val own = ClientStateManager.config.audioAcoustics
            if (!supported) return own
            return serverAcoustics ?: if (AudioRangeClient.isSoundAdmin) own else AcousticQuality.ULTRA
        }

    /** Runs every game tick for every screen, before its audio update. */
    fun tick(ds: DisplayScreen) {
        flushPending()
        applyAcoustics()
        if (!supported) return
        val e = entries[ds.uuid] ?: return
        if (AudioRangeClient.isSoundAdmin) {
            if (applied[ds.uuid] == e.rev) return
            applied[ds.uuid] = e.rev
            val sent = lastSent[ds.uuid]
            if (sent != null && System.currentTimeMillis() - sent < ECHO_MS) return
        }
        apply(ds, e.view)
    }

    private fun apply(ds: DisplayScreen, v: ViewOverride) {
        v.volume?.toFloat()?.let { if (abs(ds.volume - it) > 0.001f) ds.volume = it }
        v.brightness?.toFloat()?.let { if (abs(ds.brightness - it) > 0.001f) ds.brightness = it }
        v.quality?.let { VideoQuality.parse(it) }?.let { if (ds.quality.serialize() != it.serialize()) ds.quality = it }
    }

    @Synchronized
    private fun applyAcoustics() {
        if (acousticsApplied == acousticsRev) return
        acousticsApplied = acousticsRev
        if (AudioRangeClient.isSoundAdmin && System.currentTimeMillis() - acousticsSentAt < ECHO_MS) return
        val mode = effectiveAcoustics
        if (AudioRangeClient.isSoundAdmin && serverAcoustics != null && mode != ClientStateManager.config.audioAcoustics) {
            ClientStateManager.config.audioAcoustics = mode
            ClientStateManager.config.save()
        }
        pushAcoustics(mode)
    }

    private fun pushAcoustics(mode: AcousticQuality) {
        DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.setGlobalQuality(mode)
    }

    // ---- admin edits -------------------------------------------------------------------------

    fun userVolume(id: UUID, value: Float) = queue(id, "vol", ViewEdit.Volume(id, value.toDouble()))
    fun userBrightness(id: UUID, value: Float) = queue(id, "bri", ViewEdit.Brightness(id, value.toDouble()))
    fun userQuality(id: UUID, value: VideoQuality) = queue(id, "q", ViewEdit.Quality(id, value.serialize()))

    fun userAcoustics(mode: AcousticQuality) {
        if (!supported || !AudioRangeClient.isSoundAdmin) return
        // Take it as the server's value right away, so the menu does not flick back before the echo.
        if (serverAcoustics != null) serverAcoustics = mode
        pending["-|a3"] = ViewEdit.Acoustics(mode.name) to System.currentTimeMillis()
    }

    private fun queue(id: UUID, key: String, edit: ViewEdit) {
        if (!supported || !AudioRangeClient.isSoundAdmin) return
        pending["$id|$key"] = edit to System.currentTimeMillis()
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((key, value) in pending) {
            val (edit, at) = value
            if (now - at < SEND_DELAY_MS || !pending.remove(key, value)) continue
            when (edit) {
                is ViewEdit.Acoustics -> acousticsSentAt = now
                is ViewEdit.Volume -> lastSent[edit.id] = now
                is ViewEdit.Brightness -> lastSent[edit.id] = now
                is ViewEdit.Quality -> lastSent[edit.id] = now
                is ViewEdit.Clear -> lastSent[edit.id] = now
            }
            AudioRangeNet.view(edit)
        }
    }
}
