package com.dreamdisplays.platform.server.audiorange

import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.utils.net.DisplayActions
import com.dreamdisplays.shared.audiorange.AudioRangeProtocol
import com.dreamdisplays.shared.audiorange.ModGateFile
import com.dreamdisplays.shared.audiorange.ModGateSettings
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `mod_required.txt`: hides every screen from players whose client did not identify as the audiorange
 * mod, so nobody hears a screen without the server's sound limits. Players are told how to get the mod,
 * and players with an older build of it are reminded to update. Each message is sent once per join:
 * the client repeats its protocol hello on every world change, which must not repeat the messages.
 */
@PaperOnly
@NullMarked
internal class ModGate(dataFolder: File) {
    private val file = SettingsFile(File(dataFolder, ModGateFile.FILE_NAME), { ModGateFile.DEFAULT_LINES }, parse = ModGateFile::parse)
    private val verified: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val noticeSent: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val reminderSent: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    val settings: ModGateSettings get() = file.get()

    private fun allowed(player: Player): Boolean =
        player.uniqueId in verified || (player.isPermissionSet(BYPASS) && player.hasPermission(BYPASS))

    /** [players] without the ones that may not receive [packet]; the hello always goes through. */
    fun filter(players: List<Player?>, packet: DreamPacket): List<Player?> {
        if (packet is ServerHello || !settings.enabled) return players
        return if (players.all { it == null || allowed(it) }) players else players.filter { it != null && allowed(it) }
    }

    /**
     * A client sent the protocol hello: on join, and again after every world change. Warn it once per
     * join if it never identifies as the audiorange mod.
     */
    fun onClientHello(player: Player) {
        if (!settings.enabled || player.uniqueId in verified) return
        Scheduler.runPlayerLater(player, NOTICE_DELAY_TICKS) {
            if (player.uniqueId !in verified && !allowed(player) && noticeSent.add(player.uniqueId)) {
                notify(player, settings.message, settings.messageEn, settings)
            }
        }
    }

    /** The client identified as the audiorange mod: let it in, send it the screens it was denied, and remind it to update if old. */
    fun onModHello(player: Player, text: String) {
        if (verified.add(player.uniqueId)) DisplayActions.sendAllDisplays(player)
        val s = settings
        val build = AudioRangeProtocol.helloBuild(text)
        if (build < s.requiredBuild && reminderSent.add(player.uniqueId)) {
            fun fill(t: String) = t.replace("%have%", build.toString()).replace("%need%", s.requiredBuild.toString())
            notify(player, fill(s.updateMessage), fill(s.updateMessageEn), s)
        }
    }

    fun forget(player: UUID) {
        verified.remove(player)
        noticeSent.remove(player)
        reminderSent.remove(player)
    }

    private fun notify(player: Player, text: String, textEn: String, s: ModGateSettings) {
        val legacy = LegacyComponentSerializer.legacySection()
        var message = Component.empty()
            .append(legacy.deserialize(text).colorIfAbsent(NamedTextColor.RED))
            .append(Component.newline())
            .append(legacy.deserialize(textEn).colorIfAbsent(NamedTextColor.GRAY))
        if (s.downloadUrl.isNotEmpty()) {
            message = message.append(Component.newline()).append(
                Component.text("[ ${s.buttonLabel} ]", NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(s.downloadUrl)),
            )
        }
        player.sendMessage(message)
    }

    companion object {
        const val BYPASS = "dreamdisplays.audio.bypass"
        private const val NOTICE_DELAY_TICKS = 100L
    }
}
