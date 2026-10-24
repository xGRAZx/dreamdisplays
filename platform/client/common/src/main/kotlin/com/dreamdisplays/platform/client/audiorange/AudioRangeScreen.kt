package com.dreamdisplays.platform.client.audiorange

import com.dreamdisplays.api.display.geometry.DisplayGeometry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.ui.DisplayMenu
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase
import com.dreamdisplays.platform.client.ui.kit.drawPanel
import com.dreamdisplays.platform.client.ui.menu.SettingsSection
import com.dreamdisplays.platform.client.ui.widgets.IconButton
import com.dreamdisplays.platform.client.ui.widgets.ToggleSwitch
import com.dreamdisplays.platform.client.ui.widgets.ValueSlider
import com.dreamdisplays.shared.audiorange.AudioRangeProfile
import com.dreamdisplays.shared.audiorange.RangeDirection
import com.dreamdisplays.shared.audiorange.RangeMessage
import com.dreamdisplays.shared.audiorange.ScreenRef
import com.dreamdisplays.shared.audiorange.ZoneMessage
import net.minecraft.network.chat.Component
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * The admin's "Sound" window for one screen: whether it has its own limits, the range, how far the
 * sound goes towards each side, and a toggle that shows the zone in the world. Every change is sent to
 * the server right away; the server saves it and pushes it to every player.
 */
class AudioRangeScreen(private val ds: DisplayScreen) : UiScreenBase(Component.translatable("dreamdisplays.audio.title")) {
    private val screen = ScreenRef(ds.uuid, ds.pos.x, ds.pos.y, ds.pos.z)

    private var global = AudioRangeClient.features.range.global
    private var seen: Pair<AudioRangeProfile, Boolean> = AudioRangeClient.profileFor(ds.uuid, screen.x, screen.y, screen.z)
    private var custom = seen.second
    private var profile = seen.first

    /** True while the window mirrors a server push into its widgets, so that echo is not sent back. */
    private var syncing = false
    private var zoneOn = zoneAlive(ds.uuid)

    private lateinit var section: SettingsSection
    private lateinit var customToggle: ToggleSwitch
    private lateinit var rangeSlider: ValueSlider
    private val dirSliders = arrayOfNulls<ValueSlider>(RangeDirection.entries.size)

    override fun init() {
        super.init()
        val rows = ArrayList<SettingsSection.Row>()

        customToggle = addUi(ToggleSwitch(custom, ::onOff) { on -> if (!syncing) onCustomChanged(on) })
        val customReset = addUi(IconButton("refresh") { customToggle.set(false) })
        customReset.enabledWhen = { custom }
        rows += SettingsSection.Row("dreamdisplays.audio.custom", customToggle, customReset, 0) { tip("dreamdisplays.audio.tip.custom") }

        rangeSlider = addUi(
            ValueSlider(
                initial = fractionFromRange(profile.range),
                label = { f -> rangeFromFraction(f).let { if (it <= 0.0) unlimited() else blocks(it) } },
                step = 0.005,
            ) { f ->
                profile = profile.withRange(rangeFromFraction(f))
                syncSliders()
                send(null, profile.range)
            },
        )
        rangeSlider.enabledWhen = { custom }
        val rangeReset = addUi(IconButton("refresh") {
            profile = profile.withRange(global.range)
            syncSliders()
            send(null, profile.range)
        })
        rangeReset.enabledWhen = { custom }
        rows += SettingsSection.Row("dreamdisplays.audio.range", rangeSlider, rangeReset, 4) { tip("dreamdisplays.audio.tip.range") }

        for (dir in RangeDirection.entries) {
            val slider = addUi(
                ValueSlider(
                    initial = fractionFromReach(profile.reach(dir)),
                    label = { f -> if (profile.range <= 0.0) unlimited() else blocks(reachFromFraction(f)) },
                    step = 1.0 / 199.0,
                ) { f ->
                    if (profile.range > 0.0) {
                        profile = profile.withScale(dir, profile.range / reachFromFraction(f).coerceAtLeast(MIN_REACH))
                        send(dir, profile.scale(dir))
                    }
                },
            )
            slider.enabledWhen = { custom && profile.range > 0.0 }
            dirSliders[dir.ordinal] = slider
            val reset = addUi(IconButton("refresh") {
                profile = profile.withScale(dir, global.scale(dir))
                slider.value = fractionFromReach(profile.reach(dir))
                send(dir, profile.scale(dir))
            })
            reset.enabledWhen = { custom && profile.range > 0.0 }
            val key = when (dir) {
                RangeDirection.UP -> "up"
                RangeDirection.DOWN -> "down"
                else -> dir.key
            }
            rows += SettingsSection.Row("dreamdisplays.audio.$key", slider, reset, if (dir.ordinal == 0) 4 else 0) {
                tip("dreamdisplays.audio.tip.dir")
            }
        }

        val zoneToggle = addUi(ToggleSwitch(zoneOn, ::onOff) { on -> if (on) { zoneOn = true; pushZone() } else zoneOff() })
        val zoneReset = addUi(IconButton("refresh") { zoneToggle.set(false) })
        rows += SettingsSection.Row("dreamdisplays.audio.zone", zoneToggle, zoneReset, 4) { tip("dreamdisplays.audio.tip.zone") }

        val back = addUi(IconButton("left") { DisplayMenu.open(ds) })
        section = SettingsSection(rows, listOf(back), listOf(back to { tip("dreamdisplays.audio.back_tip") }))
    }

    private fun onCustomChanged(on: Boolean) {
        custom = on
        if (on) {
            AudioRangeNet.range(RangeMessage.Custom(screen))
        } else {
            profile = global
            syncSliders()
            AudioRangeNet.range(RangeMessage.Clear(screen))
        }
        pushZone()
    }

    private fun send(direction: RangeDirection?, value: Double) {
        if (!syncing && custom) AudioRangeNet.range(RangeMessage.Key(screen, direction, value))
        pushZone()
    }

    private fun syncSliders() {
        rangeSlider.value = fractionFromRange(profile.range)
        RangeDirection.entries.forEach { dirSliders[it.ordinal]?.value = fractionFromReach(profile.reach(it)) }
    }

    /** Mirrors the server's latest values (another admin's edit, or our own echo) into the widgets. */
    private fun pollServer() {
        val now = AudioRangeClient.profileFor(ds.uuid, screen.x, screen.y, screen.z)
        if (now == seen) return
        seen = now
        global = AudioRangeClient.features.range.global
        syncing = true
        try {
            custom = now.second
            profile = now.first
            customToggle.set(custom)
            syncSliders()
        } finally {
            syncing = false
        }
        pushZone()
    }

    private fun pushZone() {
        if (!zoneOn) return
        val pose = DisplayGeometry.pose(screen.x, screen.y, screen.z, ds.width, ds.height, ds.facing, ds.depth)
        val frame = listOf(
            pose.centerX, pose.centerY, pose.centerZ,
            pose.normalX, pose.normalY, pose.normalZ,
            pose.uAxisX, pose.uAxisY, pose.uAxisZ,
            pose.vAxisX, pose.vAxisY, pose.vAxisZ,
        )
        AudioRangeNet.zone(ZoneMessage.Show(ds.uuid, frame, profile))
        zones[ds.uuid] = System.currentTimeMillis()
    }

    private fun zoneOff() {
        zoneOn = false
        zones.remove(ds.uuid)
        AudioRangeNet.zone(ZoneMessage.Hide(ds.uuid))
    }

    /** The server drops a zone after two minutes; refresh it while the window stays open. */
    private fun keepZoneAlive() {
        val at = zones[ds.uuid]
        if (zoneOn && (at == null || System.currentTimeMillis() - at > ZONE_REFRESH_MS)) pushZone()
    }

    override fun minContentSize(): Pair<Int, Int> = (PANEL_W + 20) to (PANEL_H + 10)

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        pollServer()
        drawScreenBackground(g)
        val panel = UiRect((width - PANEL_W) / 2, (height - PANEL_H) / 2, PANEL_W, PANEL_H)
        g.drawPanel(font, panel, title.string)
        section.render(g, panel)
        keepZoneAlive()
        drawChildren(g, mouseX, mouseY, partialTick)
        section.renderTooltips(g, mouseX, mouseY, toRealX(mouseX), toRealY(mouseY))
    }

    override fun onClose() {
        DisplayMenu.open(ds)
    }

    companion object {
        private const val PANEL_W = 310
        private const val PANEL_H = 309
        private const val MAX_BLOCKS = 100.0
        private const val MIN_REACH = 0.5
        private const val ZONE_REFRESH_MS = 60_000L

        /** Zones this player has switched on, so reopening the window shows the toggle as on. */
        private val zones = ConcurrentHashMap<UUID, Long>()

        private fun zoneAlive(id: UUID): Boolean {
            val at = zones[id] ?: return false
            if (System.currentTimeMillis() - at < 115_000L) return true
            zones.remove(id)
            return false
        }

        /** Half-block steps from 0 (no limit) to [MAX_BLOCKS]. */
        internal fun rangeFromFraction(f: Double): Double = (f.coerceIn(0.0, 1.0) * MAX_BLOCKS * 2).roundToLong() / 2.0
        internal fun fractionFromRange(r: Double): Double = (r / MAX_BLOCKS).coerceIn(0.0, 1.0)
        internal fun reachFromFraction(f: Double): Double =
            ((MIN_REACH + f.coerceIn(0.0, 1.0) * (MAX_BLOCKS - MIN_REACH)) * 2).roundToLong() / 2.0
        internal fun fractionFromReach(r: Double): Double = ((r - MIN_REACH) / (MAX_BLOCKS - MIN_REACH)).coerceIn(0.0, 1.0)

        private fun onOff(on: Boolean): Component = Component.translatable(if (on) "options.on" else "options.off")
        private fun unlimited(): Component = Component.translatable("dreamdisplays.audio.unlimited")

        private fun blocks(value: Double): Component = Component.translatable(
            "dreamdisplays.audio.blocks",
            if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value),
        )

        /** Splits a translated tooltip into one line per sentence. */
        private fun tip(key: String): List<Component> =
            Component.translatable(key).string.split(Regex("(?<=[.:]) ")).map { Component.literal(it) }
    }
}
