package com.dreamdisplays.platform.server.audiorange

import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.shared.audiorange.AudioRangeFeatures
import com.dreamdisplays.shared.audiorange.AudioRangeFile
import com.dreamdisplays.shared.audiorange.AudioRangeProtocol
import com.dreamdisplays.shared.audiorange.RangeMessage
import com.dreamdisplays.shared.audiorange.ViewEdit
import com.dreamdisplays.shared.audiorange.ViewMessage
import com.dreamdisplays.shared.audiorange.ViewSettings
import com.dreamdisplays.shared.audiorange.ViewSettingsFile
import com.dreamdisplays.shared.audiorange.ZoneMessage
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * The audiorange extension on Paper: server-set sound range per screen (`audio_range.txt`), view values
 * forced on every player (`display_view.txt`), the zone preview and the mod gate (`mod_required.txt`).
 * [PaperV2Networking] calls in at four points: the hello, the feature list, inbound messages and the
 * outbound packet filter.
 */
@PaperOnly
@NullMarked
object AudioRangeServer {
    const val PERMISSION = "dreamdisplays.audio"

    private val logger = LoggerFactory.getLogger(javaClass)

    private val plugin: PaperServer by lazy { PaperServer.getInstance() }

    private val range by lazy {
        SettingsFile(
            File(plugin.dataFolder, AudioRangeFile.FILE_NAME),
            AudioRangeFile::defaultLines,
            AudioRangeFile::withMissingDefaults,
            AudioRangeFile::parse,
        )
    }

    private val view by lazy {
        SettingsFile(File(plugin.dataFolder, ViewSettingsFile.FILE_NAME), defaults = null, parse = ViewSettingsFile::parse)
    }

    private val gate by lazy { ModGate(plugin.dataFolder) }
    private val zones by lazy { ZoneVisualizer(plugin) }

    /** Feature tokens for [player]'s `ServerHello`. */
    fun featureWires(player: Player): List<String> = runCatching {
        AudioRangeFeatures(
            rangeSupported = true,
            viewSupported = true,
            soundAdmin = isSoundAdmin(player),
            revision = AudioRangeFeatures.CURRENT_REVISION,
            range = range.get(),
            view = view.get(),
        ).toWire()
    }.onFailure { logger.warn("Could not build audiorange features", it) }.getOrDefault(emptyList())

    /** Drops the players the mod gate keeps away from [packet]. */
    fun filter(players: List<Player?>, packet: DreamPacket): List<Player?> =
        runCatching { gate.filter(players, packet) }.getOrDefault(players)

    /** The client sent the protocol hello (a fresh join or a reconnect). */
    fun onClientHello(player: Player) {
        runCatching { gate.onClientHello(player) }.onFailure { logger.warn("Mod gate hello failed", it) }
    }

    fun onQuit(player: UUID) {
        gate.forget(player)
        zones.stopAll(player)
    }

    /** Handles an audiorange message; returns false for anything else, which then goes to the normal decoder. */
    fun intercept(player: Player, message: ByteArray): Boolean {
        val type = AudioRangeProtocol.peekType(message) ?: return false
        if (type !in AudioRangeProtocol.TYPES) return false
        val text = AudioRangeProtocol.unframe(message)?.second ?: return true
        runCatching {
            when (type) {
                AudioRangeProtocol.TYPE_HELLO -> gate.onModHello(player)
                AudioRangeProtocol.TYPE_RANGE -> RangeMessage.decode(text)?.let { handleRange(player, it) }
                AudioRangeProtocol.TYPE_ZONE -> ZoneMessage.decode(text)?.let { zones.handle(player, it, isSoundAdmin(player)) }
                AudioRangeProtocol.TYPE_VIEW -> ViewMessage.decode(text)?.let { handleView(player, it) }
            }
        }.onFailure { logger.warn("Bad audiorange message from {}", player.name, it) }
        return true
    }

    fun isSoundAdmin(player: Player): Boolean =
        player.isOp || player.hasPermission(PERMISSION) ||
            PaperServer.config.permissions.deleteOthers.let { it.isNotEmpty() && player.hasPermission(it) }

    private fun canEdit(player: Player, display: UUID): Boolean =
        DisplayManager.getDisplayData(display) != null && isSoundAdmin(player)

    private fun deny(player: Player) {
        player.sendMessage(
            Component.text(
                "Dream Displays: changing screen sound and view for everyone is for admins only (permission $PERMISSION).",
                NamedTextColor.RED,
            ),
        )
    }

    private fun handleRange(player: Player, message: RangeMessage) {
        val screen = message.screen
        if (!canEdit(player, screen.id)) return deny(player)
        range.edit { lines, settings ->
            val current = settings.effective(screen.id, screen.posKey).first
            val profile = when (message) {
                is RangeMessage.Clear -> null
                is RangeMessage.Set -> message.profile
                // Starting a section of its own: keep what the screen uses now, unless it already has one.
                is RangeMessage.Custom -> if (settings.sections.containsKey(screen.id.toString())) return@edit null else current
                is RangeMessage.Key -> message.direction?.let { current.withScale(it, message.value) } ?: current.withRange(message.value)
            }
            AudioRangeFile.withSection(lines, screen.id, profile?.clamped())
        }
        broadcast()
    }

    private fun handleView(player: Player, edit: ViewEdit) {
        val allowed = when (edit) {
            is ViewEdit.Acoustics -> isSoundAdmin(player)
            is ViewEdit.Volume -> canEdit(player, edit.id)
            is ViewEdit.Brightness -> canEdit(player, edit.id)
            is ViewEdit.Quality -> canEdit(player, edit.id)
            is ViewEdit.Clear -> canEdit(player, edit.id)
        }
        if (!allowed) return deny(player)
        view.edit { _, settings: ViewSettings ->
            ViewSettingsFile.apply(settings, edit)?.let(ViewSettingsFile::render)
        }
        broadcast()
    }

    /** Pushes a fresh `ServerHello` (and so the new settings) to every connected v2 player. */
    fun broadcast() {
        Scheduler.forEachTrackedPlayer { player ->
            if (V2PlayerTracker.isV2(player.uniqueId)) {
                PaperV2Networking.send(listOf(player), PaperV2Networking.buildServerHello(player))
            }
        }
    }
}
