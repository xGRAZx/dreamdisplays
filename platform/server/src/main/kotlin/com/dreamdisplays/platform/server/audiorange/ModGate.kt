package com.dreamdisplays.platform.server.audiorange

import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.utils.net.DisplayActions
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
 * mod, so nobody hears a screen without the server's sound limits. Players are told how to get the mod.
 */
@PaperOnly
@NullMarked
internal class ModGate(dataFolder: File) {
    private val file = SettingsFile(File(dataFolder, ModGateFile.FILE_NAME), { ModGateFile.DEFAULT_LINES }, parse = ModGateFile::parse)
    private val verified: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    val settings: ModGateSettings get() = file.get()

    private fun allowed(player: Player): Boolean =
        player.uniqueId in verified || (player.isPermissionSet(BYPASS) && player.hasPermission(BYPASS))

    /** [players] without the ones that may not receive [packet]; the hello always goes through. */
    fun filter(players: List<Player?>, packet: DreamPacket): List<Player?> {
        if (packet is ServerHello || !settings.enabled) return players
        return if (players.all { it == null || allowed(it) }) players else players.filter { it != null && allowed(it) }
    }

    /** A client sent the protocol hello: forget any earlier verification and warn it later if it never identifies. */
    fun onClientHello(player: Player) {
        verified.remove(player.uniqueId)
        if (!settings.enabled) return
        Scheduler.runPlayerLater(player, NOTICE_DELAY_TICKS) {
            if (player.uniqueId !in verified && !allowed(player)) notify(player, settings)
        }
    }

    /** The client identified as the audiorange mod: let it in and send it the screens it was denied. */
    fun onModHello(player: Player) {
        if (verified.add(player.uniqueId)) DisplayActions.sendAllDisplays(player)
    }

    fun forget(player: UUID) {
        verified.remove(player)
    }

    private fun notify(player: Player, s: ModGateSettings) {
        val legacy = LegacyComponentSerializer.legacySection()
        var message = Component.empty()
            .append(legacy.deserialize(s.message).colorIfAbsent(NamedTextColor.RED))
            .append(Component.newline())
            .append(legacy.deserialize(s.messageEn).colorIfAbsent(NamedTextColor.GRAY))
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
