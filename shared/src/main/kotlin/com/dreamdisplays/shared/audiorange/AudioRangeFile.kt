package com.dreamdisplays.shared.audiorange

import com.dreamdisplays.api.Unstable
import java.util.UUID

/** One screen section of `audio_range.txt`: only the keys it lists override the global values. */
@Unstable
data class RangeOverride(
    val range: Double? = null,
    val scales: Map<RangeDirection, Double> = emptyMap(),
) {
    val isEmpty: Boolean get() = range == null && scales.isEmpty()

    fun applyTo(base: AudioRangeProfile): AudioRangeProfile {
        var p = base
        range?.let { p = p.copy(range = it) }
        scales.forEach { (dir, value) -> p = p.withScale(dir, value) }
        return p
    }

    companion object {
        fun full(profile: AudioRangeProfile): RangeOverride =
            RangeOverride(profile.range, RangeDirection.entries.associateWith { profile.scale(it) })
    }
}

/** What `audio_range.txt` says, with sections keyed by display uuid or by `x,y,z`. */
@Unstable
data class AudioRangeSettings(
    val global: AudioRangeProfile = AudioRangeProfile(),
    val absorbWool: Double = DEFAULT_ABSORB_WOOL,
    val absorbCarpet: Double = DEFAULT_ABSORB_CARPET,
    val sections: Map<String, RangeOverride> = emptyMap(),
) {
    /** The profile for a display, and whether a section of its own (by uuid, else by position) set it. */
    fun effective(id: UUID?, posKey: String?): Pair<AudioRangeProfile, Boolean> {
        val section = id?.let { sections[it.toString()] } ?: posKey?.let { sections[it] }
        return (section?.applyTo(global) ?: global) to (section != null)
    }

    companion object {
        const val DEFAULT_ABSORB_WOOL = 1.0
        const val DEFAULT_ABSORB_CARPET = 0.35
    }
}

/**
 * Reads and edits `plugins/DreamDisplays/audio_range.txt` line by line, so comments and the order an
 * admin wrote things in survive every edit made from the game.
 */
@Unstable
object AudioRangeFile {
    const val FILE_NAME = "audio_range.txt"

    private const val ABSORB_WOOL = "absorb_wool"
    private const val ABSORB_CARPET = "absorb_carpet"

    val HEADER: List<String> = listOf(
        "# Dream Displays sound limits (needs the audiorange client mod).",
        "# Applied to players when they join (or reconnect), and pushed to everyone when saved from the in-game editor.",
        "# range - max distance in blocks at which sound is audible. 0 = no limit.",
        "# Direction multipliers, relative to the screen (as seen by a viewer standing in front of it).",
        "# 1 = same as range, 2 = sound reaches half as far that way, 0.5 = twice as far.",
        "# front / back  - in front of the screen / behind it (behind the wall it hangs on).",
        "# left / right  - to the viewer's left / right of the screen.",
        "# vertical_up / vertical_down - above / below the screen centre.",
        "# Per-screen overrides (edited in the game: screen menu -> sound icon), only the keys you list are overridden:",
        "#   [display <uuid>]   or   [display x y z]",
        "#   range=3",
        "#   back=4",
        "# absorb_wool / absorb_carpet - how much one block of wool / carpet between the listener and the screen absorbs (like acoustic foam).",
        "#   1 = full silence behind one block, 0.5 = half the volume per block, 0 = off. Works in every 3D sound mode.",
    )

    private val ABSORB_COMMENT =
        "# absorb_wool / absorb_carpet: how much one wool / carpet block between listener and screen absorbs. 1 = silence, 0 = off."

    /** The file a fresh server starts with. */
    fun defaultLines(): List<String> = buildList {
        addAll(HEADER)
        add("range=${fmt(AudioRangeProfile.DEFAULT_RANGE)}")
        RangeDirection.entries.forEach { add("${it.key}=${fmt(it.defaultScale)}") }
        add("$ABSORB_WOOL=${fmt(AudioRangeSettings.DEFAULT_ABSORB_WOOL)}")
        add("$ABSORB_CARPET=${fmt(AudioRangeSettings.DEFAULT_ABSORB_CARPET)}")
    }

    /** `[display <uuid>]` / `[display x y z]` (spaces or commas) to a section key, or null when malformed. */
    fun sectionKey(header: String): String? {
        val parts = header.trim().split(Regex("[\\s,]+"))
        if (parts.size < 2 || !parts[0].equals("display", ignoreCase = true)) return null
        return runCatching {
            when (parts.size) {
                2 -> UUID.fromString(parts[1]).toString()
                4 -> posKey(parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
                else -> null
            }
        }.getOrNull()
    }

    fun posKey(x: Int, y: Int, z: Int): String = "$x,$y,$z"

    private fun headerKey(line: String): String? {
        val end = line.indexOf(']')
        return if (end > 0) sectionKey(line.substring(1, end)) else null
    }

    /** Splits `key=value` (lower-cased key); a bare value line is the range, as in the oldest format. */
    private fun keyValue(line: String): Pair<String, String> {
        val eq = line.indexOf('=')
        return if (eq > 0) line.substring(0, eq).trim().lowercase() to line.substring(eq + 1).trim()
        else "range" to line
    }

    fun parse(lines: List<String>): AudioRangeSettings {
        var global = AudioRangeProfile()
        var wool = AudioRangeSettings.DEFAULT_ABSORB_WOOL
        var carpet = AudioRangeSettings.DEFAULT_ABSORB_CARPET
        val sections = LinkedHashMap<String, RangeOverride>()
        var inSections = false
        var current: String? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                inSections = true
                current = headerKey(line)?.also { sections.putIfAbsent(it, RangeOverride()) }
                continue
            }
            if (inSections && line.indexOf('=') <= 0) continue
            val (key, text) = keyValue(line)
            val value = text.toDoubleOrNull() ?: continue

            if (inSections) {
                val id = current ?: continue
                val old = sections.getValue(id)
                sections[id] = when {
                    key == "range" -> old.copy(range = value)
                    else -> RangeDirection.of(key)?.let { old.copy(scales = old.scales + (it to value)) } ?: old
                }
                continue
            }
            when (key) {
                "range" -> global = global.copy(range = value)
                ABSORB_WOOL -> wool = clampAbsorb(value)
                ABSORB_CARPET -> carpet = clampAbsorb(value)
                else -> RangeDirection.of(key)?.let { global = global.withScale(it, value) }
            }
        }
        return AudioRangeSettings(global, wool, carpet, sections)
    }

    /**
     * Adds every global key the file is missing (an older file, or one an admin trimmed) just above the
     * first section, so new settings show up with their defaults. Returns the lines unchanged when complete.
     */
    fun withMissingDefaults(lines: List<String>): List<String> {
        val present = HashSet<String>()
        var firstSection = -1
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") -> if (firstSection < 0) firstSection = i
                firstSection >= 0 || line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val key = keyValue(line).first
                    present += RangeDirection.of(key)?.key ?: key
                }
            }
        }
        val missing = buildList {
            if ("range" !in present) add("range=${fmt(AudioRangeProfile.DEFAULT_RANGE)}")
            RangeDirection.entries.filter { it.key !in present }.forEach { add("${it.key}=${fmt(it.defaultScale)}") }
            val absorb = listOf(
                ABSORB_WOOL to AudioRangeSettings.DEFAULT_ABSORB_WOOL,
                ABSORB_CARPET to AudioRangeSettings.DEFAULT_ABSORB_CARPET,
            ).filter { it.first !in present }
            if (absorb.isNotEmpty()) add(ABSORB_COMMENT)
            absorb.forEach { add("${it.first}=${fmt(it.second)}") }
        }
        if (missing.isEmpty()) return lines
        val at = if (firstSection >= 0) firstSection else lines.size
        return buildList {
            addAll(lines.subList(0, at))
            addAll(missing)
            if (firstSection >= 0) add("")
            addAll(lines.subList(at, lines.size))
        }
    }

    /**
     * Drops the `[display <id>]` section and, when [profile] is not null, appends a full one in its
     * place at the end of the file. Sections addressed by position are left alone.
     */
    fun withSection(lines: List<String>, id: UUID, profile: AudioRangeProfile?): List<String> {
        val target = id.toString()
        val out = ArrayList<String>(lines.size + 9)
        var skipping = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                skipping = headerKey(line) == target
                if (skipping) continue
            } else if (skipping) continue
            out += raw
        }
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)
        if (profile != null) {
            val p = profile.clamped()
            out += ""
            out += "[display $target]"
            out += "range=${fmt(p.range)}"
            RangeDirection.entries.forEach { out += "${it.key}=${fmt(p.scale(it))}" }
        }
        return out
    }

    private fun clampAbsorb(value: Double): Double = if (value.isNaN()) 0.0 else value.coerceIn(0.0, 1.0)

    /** Whole numbers without a fraction, the rest with up to three decimals and no trailing zeros. */
    fun fmt(value: Double): String =
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e9) value.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
}
