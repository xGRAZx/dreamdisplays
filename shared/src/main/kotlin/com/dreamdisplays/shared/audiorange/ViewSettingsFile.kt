package com.dreamdisplays.shared.audiorange

import com.dreamdisplays.api.Unstable
import java.util.Locale
import java.util.UUID

/** Values an admin forces on one screen for every viewer; a null value is left to each player. */
@Unstable
data class ViewOverride(
    val volume: Double? = null,
    val brightness: Double? = null,
    val quality: String? = null,
) {
    val isEmpty: Boolean get() = volume == null && brightness == null && quality == null
}

/** `display_view.txt`: the server-wide 3D sound mode and brightness, and the per-screen forced view values. */
@Unstable
data class ViewSettings(
    /** `OFF`, `BASIC`, `ADVANCED` or `ULTRA`, or null when the server does not force one. */
    val acoustics: String? = null,
    val displays: Map<UUID, ViewOverride> = emptyMap(),
    /**
     * Brightness players get on every screen without its own brightness, or null when players choose
     * it themselves. Sound admins are never held to it.
     */
    val playerBrightness: Double? = DEFAULT_PLAYER_BRIGHTNESS,
) {
    companion object {
        /** Players see screens at 100 % unless the file says otherwise, as with the audiorange 1-21 player mod. */
        const val DEFAULT_PLAYER_BRIGHTNESS = 1.0
    }
}

/** One change an admin made from the game, as sent in a view-settings message. */
@Unstable
sealed interface ViewEdit {
    data class Acoustics(val mode: String?) : ViewEdit
    data class Volume(val id: UUID, val value: Double) : ViewEdit
    data class Brightness(val id: UUID, val value: Double) : ViewEdit
    data class Quality(val id: UUID, val value: String) : ViewEdit
    data class Clear(val id: UUID) : ViewEdit
}

@Unstable
object ViewSettingsFile {
    const val FILE_NAME = "display_view.txt"
    const val MAX_VOLUME = 4.0
    const val MAX_BRIGHTNESS = 2.0

    val HEADER: List<String> = listOf(
        "# Dream Displays view settings (needs the audiorange client mod).",
        "# Set from the game by an admin (permission dreamdisplays.audio) with the usual screen menu sliders,",
        "# and forced on every player: players can not change them.",
        "# a3 - 3D sound mode for everyone: OFF, ADVANCED (Enhanced) or ULTRA (Advanced). Without the line players get ULTRA (Advanced).",
        "# brightness - brightness (0-2) players get on screens without their own; 1 = 100 %. off = players choose it.",
        "# [display <uuid>] sections: volume (0-4), brightness (0-2), quality (auto or a height like 1080).",
        "# A key that is missing is not forced. Delete a section (or a line) to stop forcing it.",
    )

    private val ACOUSTIC_MODES = setOf("OFF", "BASIC", "ADVANCED", "ULTRA")

    fun normalizeAcoustics(value: String): String? =
        value.trim().uppercase(Locale.ROOT).takeIf { it in ACOUSTIC_MODES }

    /** `auto`, or a pixel height between 64 and 8640 (a trailing `p` is fine); null when it is neither. */
    fun normalizeQuality(value: String): String? {
        val s = value.trim().lowercase(Locale.ROOT).removeSuffix("p")
        if (s == "auto") return "auto"
        return s.toIntOrNull()?.takeIf { it in 64..8640 }?.toString()
    }

    fun parse(lines: List<String>): ViewSettings {
        var acoustics: String? = null
        var playerBrightness: Double? = ViewSettings.DEFAULT_PLAYER_BRIGHTNESS
        val displays = LinkedHashMap<UUID, ViewOverride>()
        var current: UUID? = null
        var inSection = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                inSection = true
                val inner = line.substring(1, line.indexOf(']').takeIf { it > 0 } ?: line.length).trim()
                current = if (inner.lowercase(Locale.ROOT).startsWith("display")) {
                    runCatching { UUID.fromString(inner.substring(7).trim()) }.getOrNull()
                } else null
                current?.let { displays.putIfAbsent(it, ViewOverride()) }
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase(Locale.ROOT)
            val value = line.substring(eq + 1).trim()
            if (!inSection) {
                when (key) {
                    "a3" -> normalizeAcoustics(value)?.let { acoustics = it }
                    "brightness" -> parsePlayerBrightness(value)?.let { playerBrightness = it.getOrNull() }
                }
                continue
            }
            val id = current ?: continue
            val old = displays.getValue(id)
            displays[id] = when (key) {
                "volume" -> value.toDoubleOrNull()?.let { old.copy(volume = it.coerceIn(0.0, MAX_VOLUME)) } ?: old
                "brightness" -> value.toDoubleOrNull()?.let { old.copy(brightness = it.coerceIn(0.0, MAX_BRIGHTNESS)) } ?: old
                "quality" -> normalizeQuality(value)?.let { old.copy(quality = it) } ?: old
                else -> old
            }
        }
        return ViewSettings(acoustics, displays, playerBrightness)
    }

    /** `off` gives a success holding null (players choose); a number its clamped value; anything else null (ignored). */
    private fun parsePlayerBrightness(value: String): Result<Double?>? {
        if (value.lowercase(Locale.ROOT) in FREE_WORDS) return Result.success(null)
        val n = value.toDoubleOrNull()?.takeUnless { it.isNaN() } ?: return null
        return Result.success(n.coerceIn(0.0, MAX_BRIGHTNESS))
    }

    private val FREE_WORDS = setOf("off", "false", "no", "none", "free")

    fun render(settings: ViewSettings): List<String> = buildList {
        addAll(HEADER)
        add("")
        settings.acoustics?.let { add("a3=$it") }
        add("brightness=${settings.playerBrightness?.let { fmt(it) } ?: "off"}")
        settings.displays.forEach { (id, o) ->
            if (o.isEmpty) return@forEach
            add("")
            add("[display $id]")
            o.volume?.let { add("volume=${fmt(it)}") }
            o.brightness?.let { add("brightness=${fmt(it)}") }
            o.quality?.let { add("quality=$it") }
        }
    }

    /** Applies [edit]; null when the edit carries an invalid value and must be ignored. */
    fun apply(settings: ViewSettings, edit: ViewEdit): ViewSettings? {
        fun update(id: UUID, f: (ViewOverride) -> ViewOverride) =
            settings.copy(displays = LinkedHashMap(settings.displays).apply { put(id, f(get(id) ?: ViewOverride())) })
        return when (edit) {
            is ViewEdit.Acoustics -> settings.copy(acoustics = edit.mode?.let { normalizeAcoustics(it) ?: return null })
            is ViewEdit.Volume -> if (edit.value.isNaN()) null else update(edit.id) { it.copy(volume = edit.value.coerceIn(0.0, MAX_VOLUME)) }
            is ViewEdit.Brightness -> if (edit.value.isNaN()) null else update(edit.id) { it.copy(brightness = edit.value.coerceIn(0.0, MAX_BRIGHTNESS)) }
            is ViewEdit.Quality -> normalizeQuality(edit.value)?.let { q -> update(edit.id) { it.copy(quality = q) } }
            is ViewEdit.Clear -> settings.copy(displays = settings.displays - edit.id)
        }
    }

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
}
