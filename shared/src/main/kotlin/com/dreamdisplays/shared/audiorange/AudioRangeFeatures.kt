package com.dreamdisplays.shared.audiorange

import com.dreamdisplays.api.Unstable
import java.util.UUID

/**
 * Everything the audiorange server tells a client, carried as `ServerHello.allowedFeatures` tokens:
 *
 * - `audio_ui`, `audio_range:<n>`, `audio_<sf|sb|sr|sl|vup|vdown>:<n>`, `audio_absorb_wool:<n>`,
 *   `audio_absorb_carpet:<n>`, `audio_d:<uuid or x,y,z>:range=<n>;sf=<n>;…` (sound range);
 * - `view_ui`, `view_g:a3=<mode>[;by=<uuid>]`, `view_d:<uuid>:v=<n>;b=<n>;q=<q>[;by=<uuid>]` (forced view);
 * - `audio_rev:<n>` and `audio_admin` (new in the source rewrite; older mods ignore them): the plugin
 *   revision, and the flag sent only to players allowed to edit;
 * - `view_bri:<n>` (revision 3): the brightness players get on screens without their own. Without it
 *   players choose, which is also what older plugins mean.
 */
@Unstable
data class AudioRangeFeatures(
    /** The server runs the audiorange plugin (sound range part). */
    val rangeSupported: Boolean = false,
    /** The server runs the audiorange plugin (forced view part). */
    val viewSupported: Boolean = false,
    /** This player may edit sound and view settings for everyone. */
    val soundAdmin: Boolean = false,
    /** Plugin revision; 0 for the audiorange 1-21 plugins, which did not send `audio_admin` at all. */
    val revision: Int = 0,
    val range: AudioRangeSettings = AudioRangeSettings(global = AudioRangeProfile.UNLIMITED),
    val view: ViewSettings = ViewSettings(),
) {
    /** Profile for a display, by uuid first, then by its block position, else the global one. */
    fun profileFor(id: UUID?, posKey: String?): Pair<AudioRangeProfile, Boolean> = range.effective(id, posKey)

    fun toWire(): List<String> = buildList {
        if (rangeSupported) {
            add(RANGE_UI)
            if (revision > 0) add("$REVISION$revision")
            val g = range.global
            add("audio_range:${AudioRangeFile.fmt(g.range)}")
            RangeDirection.entries.forEach { add("audio_${it.wire}:${AudioRangeFile.fmt(g.scale(it))}") }
            add("$ABSORB_WOOL${AudioRangeFile.fmt(range.absorbWool)}")
            add("$ABSORB_CARPET${AudioRangeFile.fmt(range.absorbCarpet)}")
            range.sections.forEach { (key, o) ->
                if (o.isEmpty) return@forEach
                val parts = buildList {
                    o.range?.let { add("range=${AudioRangeFile.fmt(it)}") }
                    // Keys in the canonical order, whatever order the admin wrote them in.
                    RangeDirection.entries.forEach { d -> o.scales[d]?.let { add("${d.wire}=${AudioRangeFile.fmt(it)}") } }
                }
                add("$RANGE_DISPLAY$key:${parts.joinToString(";")}")
            }
        }
        if (viewSupported) {
            add(VIEW_UI)
            view.acoustics?.let { add("${VIEW_GLOBAL}a3=$it") }
            view.playerBrightness?.let { add("$VIEW_BRIGHTNESS${fmt3(it)}") }
            view.displays.forEach { (id, o) ->
                if (o.isEmpty) return@forEach
                val parts = buildList {
                    o.volume?.let { add("v=${fmt3(it)}") }
                    o.brightness?.let { add("b=${fmt3(it)}") }
                    o.quality?.let { add("q=$it") }
                }
                add("$VIEW_DISPLAY$id:${parts.joinToString(";")}")
            }
        }
        if (soundAdmin) add(SOUND_ADMIN)
    }

    companion object {
        const val RANGE_UI = "audio_ui"
        const val VIEW_UI = "view_ui"
        const val SOUND_ADMIN = "audio_admin"
        const val CURRENT_REVISION = 3
        private const val REVISION = "audio_rev:"
        private const val RANGE_GLOBAL = "audio_range:"
        private const val ABSORB_WOOL = "audio_absorb_wool:"
        private const val ABSORB_CARPET = "audio_absorb_carpet:"
        private const val RANGE_DISPLAY = "audio_d:"
        private const val VIEW_GLOBAL = "view_g:"
        private const val VIEW_DISPLAY = "view_d:"
        private const val VIEW_BRIGHTNESS = "view_bri:"

        /** True for tokens this extension owns, so they can be told apart from the original feature set. */
        fun isOwnToken(token: String): Boolean = token.startsWith("audio_") || token.startsWith("view_")

        fun fromWire(tokens: List<String>): AudioRangeFeatures {
            var rangeUi = false
            var viewUi = false
            var admin = false
            var revision = 0
            var global = AudioRangeProfile.UNLIMITED
            var wool = AudioRangeSettings.DEFAULT_ABSORB_WOOL
            var carpet = AudioRangeSettings.DEFAULT_ABSORB_CARPET
            val sections = LinkedHashMap<String, RangeOverride>()
            var a3: String? = null
            var playerBrightness: Double? = null
            val views = LinkedHashMap<UUID, ViewOverride>()

            for (token in tokens) {
                runCatching {
                    when {
                        token == RANGE_UI -> rangeUi = true
                        token == VIEW_UI -> viewUi = true
                        token == SOUND_ADMIN -> admin = true
                        token.startsWith(REVISION) -> revision = token.substring(REVISION.length).trim().toInt()
                        token.startsWith(RANGE_GLOBAL) -> global = global.copy(range = token.substring(RANGE_GLOBAL.length).trim().toDouble())
                        token.startsWith(ABSORB_WOOL) -> wool = absorb(token.substring(ABSORB_WOOL.length), wool)
                        token.startsWith(ABSORB_CARPET) -> carpet = absorb(token.substring(ABSORB_CARPET.length), carpet)
                        token.startsWith(RANGE_DISPLAY) -> parseRangeSection(token.substring(RANGE_DISPLAY.length))?.let {
                            sections[it.first] = it.second
                        }
                        token.startsWith(VIEW_GLOBAL) -> pairs(token.substring(VIEW_GLOBAL.length))["a3"]?.let {
                            a3 = ViewSettingsFile.normalizeAcoustics(it)
                        }
                        token.startsWith(VIEW_BRIGHTNESS) -> playerBrightness = token.substring(VIEW_BRIGHTNESS.length).trim()
                            .toDouble().takeUnless { it.isNaN() }?.coerceIn(0.0, ViewSettingsFile.MAX_BRIGHTNESS)
                        token.startsWith(VIEW_DISPLAY) -> parseViewSection(token.substring(VIEW_DISPLAY.length))?.let {
                            views[it.first] = it.second
                        }
                        token.startsWith("audio_") -> {
                            val colon = token.indexOf(':')
                            val dir = if (colon > 6) RangeDirection.of(token.substring(6, colon)) else null
                            if (dir != null) global = global.withScale(dir, token.substring(colon + 1).trim().toDouble())
                        }
                    }
                }
            }
            return AudioRangeFeatures(
                rangeSupported = rangeUi,
                viewSupported = viewUi,
                soundAdmin = admin,
                revision = revision,
                range = AudioRangeSettings(global, wool, carpet, sections),
                view = ViewSettings(a3, views, playerBrightness),
            )
        }

        private fun absorb(text: String, fallback: Double): Double =
            text.trim().toDoubleOrNull()?.let { if (it.isNaN()) fallback else it.coerceIn(0.0, 1.0) } ?: fallback

        private fun pairs(text: String): Map<String, String> = text.split(';').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq).trim().lowercase() to it.substring(eq + 1).trim()
        }.toMap()

        private fun parseRangeSection(text: String): Pair<String, RangeOverride>? {
            val colon = text.indexOf(':')
            if (colon < 0) return null
            val rawKey = text.substring(0, colon).trim().replace(" ", "")
            val key = if (rawKey.contains(',')) rawKey else UUID.fromString(rawKey).toString()
            var o = RangeOverride()
            pairs(text.substring(colon + 1)).forEach { (k, v) ->
                val value = v.toDoubleOrNull() ?: return@forEach
                o = if (k == "range") o.copy(range = value)
                else RangeDirection.of(k)?.let { o.copy(scales = o.scales + (it to value)) } ?: o
            }
            return key to o
        }

        private fun parseViewSection(text: String): Pair<UUID, ViewOverride>? {
            val colon = text.indexOf(':')
            if (colon < 0) return null
            val id = UUID.fromString(text.substring(0, colon))
            val p = pairs(text.substring(colon + 1))
            return id to ViewOverride(
                volume = p["v"]?.toDoubleOrNull(),
                brightness = p["b"]?.toDoubleOrNull(),
                quality = p["q"]?.let(ViewSettingsFile::normalizeQuality),
            )
        }

        private fun fmt3(value: Double): String = String.format(java.util.Locale.ROOT, "%.3f", value)
    }
}
