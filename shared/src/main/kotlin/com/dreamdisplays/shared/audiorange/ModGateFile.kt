package com.dreamdisplays.shared.audiorange


/** `mod_required.txt`: whether screens are hidden from players without the AltDisplays mod, and what they are told. */
data class ModGateSettings(
    val enabled: Boolean = true,
    val message: String = DEFAULT_MESSAGE,
    val messageEn: String = DEFAULT_MESSAGE_EN,
    val downloadUrl: String = AudioRangeProtocol.RELEASES_URL,
    val buttonLabel: String = DEFAULT_BUTTON,
    /** Mods reporting a lower build get [updateMessage]; null or 0 = never (the GitHub check covers updates). */
    val minBuild: Int? = null,
    val updateMessage: String = DEFAULT_UPDATE_MESSAGE,
    val updateMessageEn: String = DEFAULT_UPDATE_MESSAGE_EN,
    /** Tell players whose mod is older than the newest AltDisplays release on GitHub. */
    val updateCheck: Boolean = true,
) {
    /** The build a mod needs to avoid the build reminder, or 0 when that reminder is off. */
    val requiredBuild: Int get() = minBuild ?: 0

    companion object {
        const val DEFAULT_MESSAGE =
            "§cНа цьому сервері екрани працюють лише з модом AltDisplays. Встановіть його, щоб бачити екрани."
        const val DEFAULT_MESSAGE_EN = ""
        const val DEFAULT_BUTTON = "Завантажити"
        const val DEFAULT_UPDATE_MESSAGE = "§eВийшла нова версія мода AltDisplays %need% (у вас %have%). Оновіть його, будь ласка."
        const val DEFAULT_UPDATE_MESSAGE_EN = ""
    }
}

object ModGateFile {
    const val FILE_NAME = "mod_required.txt"

    val DEFAULT_LINES: List<String> = listOf(
        "# true = only players with the AltDisplays mod see screens; false = everyone",
        "true",
        "# Text for players without the mod. Color codes with § or &. Empty line = default.",
        "# message_en= adds a second (English) line under it.",
        "# message=",
        "# message_en=",
        "# Download button under the messages (default: the newest AltDisplays release on GitHub).",
        "# download=${AudioRangeProtocol.RELEASES_URL}",
        "# button=Завантажити",
        "# Players whose mod is older than the newest AltDisplays release on GitHub get update_message,",
        "# once per join. %have% and %need% are replaced with their version and the newest one.",
        "# update_check=false turns this off.",
        "# update_check=true",
        "# update_message=",
        "# update_message_en=",
        "# min_build= also reminds mods below this build number (old way, off by default).",
        "# min_build=",
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
                "update_check" -> s.copy(updateCheck = value.lowercase() !in OFF_WORDS)
                else -> s
            }
        }
        return s
    }
}
