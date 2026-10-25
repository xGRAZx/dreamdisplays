package com.dreamdisplays.platform.client.diagnostics

import com.dreamdisplays.media.player.AudioDiagnostics
import com.dreamdisplays.platform.client.audiorange.AudioRangeClient
import com.dreamdisplays.platform.client.audiorange.ViewSync
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.managers.PerformanceGovernor
import com.dreamdisplays.shared.audiorange.AudioRangeProtocol
import com.dreamdisplays.util.GeneralUtil
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `/ddreport`: what a player sends the server admin when screens lag or stay silent. Collects the game's
 * speed, the screen audio's health (it plays through Java Sound, apart from Minecraft's own sounds),
 * the server's audiorange settings and the state of every nearby screen, then copies the report to the
 * clipboard and saves it to `logs/dreamdisplays-report.txt`. The report is English on purpose: it is
 * read by whoever fixes the problem, while the chat lines tell the player what to do with it.
 */
object DiagnosticsReport {
    const val COMMAND = "ddreport"

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Builds the report, copies and saves it, and tells the player in chat. Main thread. */
    fun run() {
        val mc = Minecraft.getInstance()
        val text = runCatching { build(mc) }.getOrElse { "Dream Displays report failed: $it" }
        logger.info("Dream Displays report:\n{}", text)
        val copied = runCatching { mc.keyboardHandler.setClipboard(text) }.isSuccess
        val file = runCatching {
            File(mc.gameDirectory, "logs/dreamdisplays-report.txt").also {
                it.parentFile.mkdirs()
                it.writeText(text)
            }
        }.getOrNull()

        ClientChat.show(Component.translatable("dreamdisplays.report.title").withStyle(ChatFormatting.GOLD))
        summary(mc).forEach { ClientChat.show(Component.literal(" - $it").withStyle(ChatFormatting.GRAY)) }
        when {
            copied -> ClientChat.show(Component.translatable("dreamdisplays.report.copied").withStyle(ChatFormatting.GREEN))
            file != null -> ClientChat.show(Component.translatable("dreamdisplays.report.saved").withStyle(ChatFormatting.GREEN))
            else -> ClientChat.show(Component.translatable("dreamdisplays.report.failed").withStyle(ChatFormatting.RED))
        }
    }

    /** A few short lines for the chat: the numbers a player can read at a glance. */
    private fun summary(mc: Minecraft): List<String> = buildList {
        add("FPS %.0f".format(PerformanceGovernor.averageFps) +
            if (PerformanceGovernor.qualitySteps > 0) ", auto quality -${PerformanceGovernor.qualitySteps}" else "")
        add(audioVerdict())
        val screens = nearScreens(mc)
        add("screens playing: ${screens.count { it.isVideoStarted && !it.isPaused }} / ${screens.size}")
    }

    private fun audioVerdict(): String {
        val device = AudioDiagnostics.outputDeviceName() ?: return "audio: NO OUTPUT DEVICE"
        val failures = AudioDiagnostics.openFailures.get()
        return if (failures > 0) "audio: $failures failed to start ($device)" else "audio: ok ($device)"
    }

    private fun nearScreens(mc: Minecraft): List<DisplayScreen> {
        val pos = mc.player?.blockPosition() ?: return emptyList()
        return DisplayRegistry.getScreens()
            .filter { !it.isDormant }
            .sortedBy { it.getDistanceToScreen(pos) }
    }

    internal fun build(mc: Minecraft): String = buildString {
        val runtime = Runtime.getRuntime()
        val mib = 1024 * 1024
        line("Dream Displays report, ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
        line("mod ${GeneralUtil.getModVersion()}, audiorange build ${AudioRangeProtocol.BUILD}")
        line(
            "java ${System.getProperty("java.version")}, os ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, " +
                "cpus ${runtime.availableProcessors()}, memory used ${(runtime.totalMemory() - runtime.freeMemory()) / mib}" +
                " of ${runtime.maxMemory() / mib} MiB",
        )
        line("game: ${PerformanceGovernor.describe()}, window active ${mc.isWindowActive}")
        line()

        line("[audio]")
        line("output device: ${AudioDiagnostics.outputDeviceName() ?: "NONE (no device can play 44.1 kHz stereo)"}")
        line(
            "lines opened ${AudioDiagnostics.linesOpened.get()}, failed ${AudioDiagnostics.openFailures.get()}, " +
                "underruns ${AudioDiagnostics.underruns.get()}, chunks played ${AudioDiagnostics.chunksWritten.get()}",
        )
        AudioDiagnostics.lastFailure?.let {
            val ago = (System.currentTimeMillis() - AudioDiagnostics.lastFailureAtMillis) / 1000
            line("last failure ${ago}s ago: $it")
        }
        line("3D sound ${ViewSync.effectiveAcoustics}")
        line()

        val features = AudioRangeClient.features
        line("[server]")
        line(
            "audiorange plugin ${if (features.rangeSupported) "yes, revision ${features.revision}" else "NO"}, " +
                "settings received ${AudioRangeClient.settingsReceived}, sound admin ${AudioRangeClient.isSoundAdmin}, " +
                "view locked ${ViewSync.locked}",
        )
        line(
            "range %s, players' brightness %s, sound radius setting %d blocks".format(
                Locale.ROOT,
                if (features.range.global.range <= 0.0) "unlimited" else "%.1f".format(Locale.ROOT, features.range.global.range),
                features.view.playerBrightness?.let { "%.2f".format(Locale.ROOT, it) } ?: "free",
                ClientStateManager.config.defaultDistance,
            ),
        )
        line()

        val pos = mc.player?.blockPosition()
        val screens = nearScreens(mc)
        line("[screens] ${screens.size} loaded, ${DisplayRegistry.getScreens().size} known")
        for (ds in screens.take(MAX_SCREENS)) {
            val distance = pos?.let { ds.getDistanceToScreen(it) } ?: -1.0
            val state = when {
                ds.errored -> "ERROR ${ds.mediaError?.message}"
                !ds.isVideoStarted -> "loading"
                ds.isPaused -> "paused"
                else -> "playing"
            }
            val gain = runCatching { ds.rangeGain() }.getOrDefault(-1.0)
            line(
                "%s at %d %d %d, %.0f blocks: %s, quality %s (asked %s)%s, volume %.2f%s, range gain %.2f".format(
                    Locale.ROOT,
                    ds.uuid.toString().take(8), ds.pos.x, ds.pos.y, ds.pos.z, distance, state,
                    ds.playbackQuality.serialize(), ds.quality.serialize(),
                    if (ds.hiddenForAWhile) " hidden" else "",
                    ds.volume, if (ds.muted) " MUTED" else "", gain,
                ),
            )
            hint(ds, gain)?.let { line("  -> $it") }
        }
        if (screens.size > MAX_SCREENS) line("... ${screens.size - MAX_SCREENS} more")
    }

    /** The likely reason a screen is silent, when its own numbers show one. */
    private fun hint(ds: DisplayScreen, gain: Double): String? = when {
        ds.muted -> "muted by the player"
        ds.volume <= 0.001f -> "volume is 0"
        gain == 0.0 -> "outside the server's sound range (or behind wool)"
        !AudioRangeClient.settingsReceived -> "server settings not received: sound waits for them"
        else -> null
    }

    private fun StringBuilder.line(text: String = "") {
        append(text).append('\n')
    }

    private const val MAX_SCREENS = 12
}
