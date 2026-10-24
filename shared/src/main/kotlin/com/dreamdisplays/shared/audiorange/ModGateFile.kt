package com.dreamdisplays.shared.audiorange

import com.dreamdisplays.api.Unstable

/** `mod_required.txt`: whether screens are hidden from players without the audiorange mod, and what they are told. */
@Unstable
data class ModGateSettings(
    val enabled: Boolean = true,
    val message: String = DEFAULT_MESSAGE,
    val messageEn: String = DEFAULT_MESSAGE_EN,
    val downloadUrl: String = "",
    val buttonLabel: String = DEFAULT_BUTTON,
) {
    companion object {
        const val DEFAULT_MESSAGE =
            "§cDream Displays: екрани на цьому сервері працюють лише з модом «audiorange». Встановіть його, щоб бачити екрани."
        const val DEFAULT_MESSAGE_EN = "§7Displays on this server require the «audiorange» version of the Dream Displays mod."
        const val DEFAULT_BUTTON = "Download"
    }
}

@Unstable
object ModGateFile {
    const val FILE_NAME = "mod_required.txt"

    val DEFAULT_LINES: List<String> = listOf(
        "# true = only the sound-limited mod (audiorange) may use displays; false = everyone",
        "true",
        "# Text for players without the mod. Color codes with § or &. Empty line = default.",
        "# message=",
        "# message_en=",
        "# Download link; if set, a clickable button is shown under the message.",
        "# download=https://example.com/your-mod-link",
        "# button=Завантажити мод",
    )

    private val OFF_WORDS = setOf("false", "0", "off", "no")

    /** The first bare line is the on / off switch; `key=value` lines set the texts. Empty values keep the defaults. */
    fun parse(lines: List<String>): ModGateSettings {
        var s = ModGateSettings()
        var switchSeen = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq < 0) {
                if (!switchSeen) {
                    s = s.copy(enabled = line.lowercase() !in OFF_WORDS)
                    switchSeen = true
                }
                continue
            }
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            if (value.isEmpty()) continue
            s = when (key) {
                "message" -> s.copy(message = value.replace('&', '§'))
                "message_en" -> s.copy(messageEn = value.replace('&', '§'))
                "download" -> s.copy(downloadUrl = value)
                "button" -> s.copy(buttonLabel = value)
                else -> s
            }
        }
        return s
    }
}
