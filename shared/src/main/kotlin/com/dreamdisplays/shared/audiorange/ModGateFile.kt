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
    /** Mods reporting a lower build get [updateMessage]; null = this plugin's own build, 0 = never. */
    val minBuild: Int? = null,
    val updateMessage: String = DEFAULT_UPDATE_MESSAGE,
    val updateMessageEn: String = DEFAULT_UPDATE_MESSAGE_EN,
) {
    /** The build a mod needs to avoid the update reminder, or 0 when the reminder is off. */
    val requiredBuild: Int get() = minBuild ?: AudioRangeProtocol.BUILD

    companion object {
        const val DEFAULT_MESSAGE =
            "§cDream Displays: екрани на цьому сервері працюють лише з модом «audiorange». Встановіть його, щоб бачити екрани."
        const val DEFAULT_MESSAGE_EN = "§7Displays on this server require the «audiorange» version of the Dream Displays mod."
        const val DEFAULT_BUTTON = "Download"
        const val DEFAULT_UPDATE_MESSAGE =
            "§eDream Displays: вийшла нова версія мода «audiorange» (у вас %have%, нова %need%). Оновіть його, будь ласка."
        const val DEFAULT_UPDATE_MESSAGE_EN = "§7A newer audiorange Dream Displays mod is available (you have %have%, latest is %need%)."
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
        "# Reminder for players whose mod is older than this plugin (screens keep working).",
        "# min_build= sets the lowest mod build that gets no reminder; empty = this plugin's build, 0 = never remind.",
        "# %have% and %need% are replaced with the player's build and the required one.",
        "# min_build=",
        "# update_message=",
        "# update_message_en=",
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
                "min_build" -> value.toIntOrNull()?.takeIf { it >= 0 }?.let { s.copy(minBuild = it) } ?: s
                "update_message" -> s.copy(updateMessage = value.replace('&', '§'))
                "update_message_en" -> s.copy(updateMessageEn = value.replace('&', '§'))
                else -> s
            }
        }
        return s
    }
}
