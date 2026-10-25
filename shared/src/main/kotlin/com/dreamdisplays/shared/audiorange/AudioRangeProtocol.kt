package com.dreamdisplays.shared.audiorange

import com.dreamdisplays.api.Unstable
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Wire format of the audiorange extension, kept byte-compatible with the audiorange 1-21 builds so
 * old and new mods and plugins work together. Messages travel on the normal `dreamdisplays:v2`
 * channel inside the usual envelope (field 1 = type, field 2 = payload) with type ids the original
 * mod does not use; their payload is a `|`-separated UTF-8 string. Server settings reach the client
 * as extra tokens in `ServerHello.allowedFeatures`.
 */
@Unstable
object AudioRangeProtocol {
    /** Client to server: edit a screen's sound range ([RangeMessage]). */
    const val TYPE_RANGE = 9001

    /** Client to server: show or hide the zone particles ([ZoneMessage]). */
    const val TYPE_ZONE = 9002

    /** Client to server: "I am the audiorange mod" ([HELLO]); unlocks screens behind the mod gate. */
    const val TYPE_HELLO = 9003

    /** Client to server: an admin changed a forced view value ([ViewEdit]). */
    const val TYPE_VIEW = 9004

    val TYPES: Set<Int> = setOf(TYPE_RANGE, TYPE_ZONE, TYPE_HELLO, TYPE_VIEW)

    /**
     * Release number of this mod and plugin, continuing the audiorange 1-21 builds. Raise it with every
     * release: the release workflow checks that the tag (`ar<BUILD>`) matches, and the plugin reminds
     * players whose mod reports a lower number.
     */
    const val BUILD = 22

    /**
     * The hello text: `hello|<extension revision>|<build>`. The audiorange 1-21 mods send only the
     * revision, so a missing build means one of those.
     */
    const val HELLO = "hello|16|$BUILD"

    /** Build number of the audiorange 1-21 mods, which send no build in their hello. */
    const val LEGACY_BUILD = 21

    /** The build a client reported in its hello [text], or [LEGACY_BUILD] when it sent none. */
    fun helloBuild(text: String): Int =
        text.split('|').getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: LEGACY_BUILD

    /** Wraps [text] in an envelope of [type]. */
    fun frame(type: Int, text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(body.size + 8)
        out.write(0x08)
        writeVarint(out, type.toLong())
        out.write(0x12)
        writeVarint(out, body.size.toLong())
        out.write(body, 0, body.size)
        return out.toByteArray()
    }

    /** Type and payload text of an envelope, or null when [bytes] is not a well-formed envelope. */
    fun unframe(bytes: ByteArray): Pair<Int, String>? {
        var i = 0
        var type = -1L
        var payload: ByteArray? = null
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (i >= bytes.size || shift > 63) throw IndexOutOfBoundsException()
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        return try {
            while (i < bytes.size) {
                val tag = varint()
                val field = (tag ushr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> varint().let { if (field == 1) type = it }
                    2 -> {
                        val len = varint()
                        if (len < 0 || i + len > bytes.size) return null
                        if (field == 2) payload = bytes.copyOfRange(i, i + len.toInt())
                        i += len.toInt()
                    }
                    else -> return null
                }
            }
            if (type < 0 || type > Int.MAX_VALUE) null
            else type.toInt() to (payload?.toString(Charsets.UTF_8) ?: "")
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /** Type id of an envelope without decoding it, or null when it is not one. Cheap: reads one varint. */
    fun peekType(bytes: ByteArray): Int? {
        if (bytes.size < 2 || bytes[0].toInt() != 0x08) return null
        var result = 0L
        var shift = 0
        var i = 1
        while (i < bytes.size && shift <= 35) {
            val b = bytes[i++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result.toInt()
            shift += 7
        }
        return null
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    internal fun num(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
}

/** Where a screen sits, sent with every range edit so a section can be matched by position too. */
@Unstable
data class ScreenRef(val id: UUID, val x: Int, val y: Int, val z: Int) {
    val posKey: String get() = AudioRangeFile.posKey(x, y, z)
    internal fun wire(): String = "$id|$x|$y|$z"
}

/** A range edit from the in-game editor ([AudioRangeProtocol.TYPE_RANGE]). */
@Unstable
sealed interface RangeMessage {
    val screen: ScreenRef

    /** Replace the screen's section with [profile]. */
    data class Set(override val screen: ScreenRef, val profile: AudioRangeProfile) : RangeMessage

    /** Drop the screen's section; it follows the global values again. */
    data class Clear(override val screen: ScreenRef) : RangeMessage

    /** Give the screen its own section, starting from what it uses now. */
    data class Custom(override val screen: ScreenRef) : RangeMessage

    /** Change one value of the screen's section; [direction] null means the range itself. */
    data class Key(override val screen: ScreenRef, val direction: RangeDirection?, val value: Double) : RangeMessage

    fun encode(): String = when (this) {
        is Set -> buildString {
            append("set|").append(screen.wire()).append('|').append(AudioRangeProtocol.num(profile.range))
            profile.scales.forEach { append('|').append(AudioRangeProtocol.num(it)) }
        }
        is Clear -> "clear|${screen.wire()}"
        is Custom -> "custom|${screen.wire()}"
        is Key -> "key|${screen.wire()}|${direction?.key ?: "range"}|${AudioRangeProtocol.num(value)}"
    }

    companion object {
        fun decode(text: String): RangeMessage? = runCatching {
            val a = text.split('|')
            if (a.size < 5) return null
            val screen = ScreenRef(UUID.fromString(a[1]), a[2].trim().toInt(), a[3].trim().toInt(), a[4].trim().toInt())
            when (a[0]) {
                "set" -> {
                    if (a.size < 12) return null
                    val v = (5 until 12).map { a[it].toDouble() }
                    Set(screen, AudioRangeProfile(v[0], v.drop(1)).clamped())
                }
                "clear" -> Clear(screen)
                "custom" -> Custom(screen)
                "key" -> {
                    if (a.size < 7) return null
                    val name = a[5].trim().lowercase()
                    val direction = if (name == "range") null else RangeDirection.of(name) ?: return null
                    Key(screen, direction, a[6].trim().toDouble())
                }
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * Show / hide the zone particles for one screen ([AudioRangeProtocol.TYPE_ZONE]). [pose] is the screen
 * frame as centre, normal, right axis and up axis (12 numbers).
 */
@Unstable
sealed interface ZoneMessage {
    data class Show(val display: UUID?, val pose: List<Double>, val profile: AudioRangeProfile) : ZoneMessage {
        init {
            require(pose.size == 12) { "pose needs 12 numbers" }
        }
    }

    /** Hide one screen's zone, or all of the player's zones when [display] is null. */
    data class Hide(val display: UUID?) : ZoneMessage

    fun encode(): String = when (this) {
        is Show -> buildString {
            append("zone|on")
            display?.let { append('|').append(it) }
            (pose + profile.range + profile.scales).forEach { append('|').append(AudioRangeProtocol.num(it)) }
        }
        is Hide -> if (display == null) "zone|off" else "zone|off|$display"
    }

    companion object {
        fun decode(text: String): ZoneMessage? = runCatching {
            val a = text.split('|')
            if (a.size < 2 || a[0] != "zone") return null
            var first = 2
            var display: UUID? = null
            if (a.size >= 3 && a[2].indexOf('-') > 0) {
                display = runCatching { UUID.fromString(a[2].trim()) }.getOrNull()
                if (display != null) first = 3
            }
            when (a[1]) {
                "off" -> Hide(display)
                "on" -> {
                    if (a.size < first + 19) return null
                    val v = (first until first + 19).map { a[it].toDouble() }
                    if (v.any { it.isNaN() || it.isInfinite() }) return null
                    Show(display, v.subList(0, 12), AudioRangeProfile(v[12], v.subList(13, 19)))
                }
                else -> null
            }
        }.getOrNull()
    }
}

/** View edits on the wire: `v|<display uuid or ->|<vol|bri|q|a3|clear>|<value>`. */
@Unstable
object ViewMessage {
    fun encode(edit: ViewEdit): String = when (edit) {
        is ViewEdit.Acoustics -> "v|-|a3|${edit.mode ?: "-"}"
        is ViewEdit.Volume -> "v|${edit.id}|vol|${edit.value}"
        is ViewEdit.Brightness -> "v|${edit.id}|bri|${edit.value}"
        is ViewEdit.Quality -> "v|${edit.id}|q|${edit.value}"
        is ViewEdit.Clear -> "v|${edit.id}|clear|-"
    }

    fun decode(text: String): ViewEdit? = runCatching {
        val a = text.split('|')
        if (a.size < 4 || a[0] != "v") return null
        val key = a[2].trim().lowercase(Locale.ROOT)
        val value = a[3].trim()
        if (a[1] == "-") {
            return if (key == "a3") ViewEdit.Acoustics(value.takeUnless { it.isEmpty() || it == "-" }) else null
        }
        val id = UUID.fromString(a[1])
        when (key) {
            "vol" -> ViewEdit.Volume(id, value.toDouble())
            "bri" -> ViewEdit.Brightness(id, value.toDouble())
            "q" -> ViewEdit.Quality(id, value)
            "clear" -> ViewEdit.Clear(id)
            else -> null
        }
    }.getOrNull()
}
