package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.platform.client.displays.DisplayRegistry
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

/**
 * Automatic quality for slow computers: while a video plays and the game stays below [LOW_FPS], every
 * display's quality steps down one rung of the quality ladder (at most [MAX_STEPS]); once the game runs
 * at [HIGH_FPS] or more for long enough, it steps back up. It only ever lowers the quality the viewer
 * or the server asked for, and only on this computer.
 *
 * A step back up that makes the game slow again soon after doubles the wait before the next one, so a
 * computer right at the edge settles instead of switching back and forth.
 *
 * Disabled with `-Ddreamdisplays.autoQuality=false`.
 */
object PerformanceGovernor {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val ENABLED: Boolean = System.getProperty("dreamdisplays.autoQuality")?.toBooleanStrictOrNull() ?: true

    const val MAX_STEPS = 2
    private const val LOW_FPS = 24.0
    private const val HIGH_FPS = 40.0
    private const val STEP_DOWN_AFTER_S = 8
    private const val STEP_UP_AFTER_S = 60
    private const val MAX_STEP_UP_AFTER_S = 480
    private const val REGRET_WINDOW_NANOS = 120_000_000_000L
    private const val HISTORY_S = 10

    /** Rungs every display currently sits below its requested quality because the game is slow. */
    @Volatile
    var qualitySteps: Int = 0
        private set

    /** Frames per second over the last full second, or 0 before the first one. */
    @Volatile
    var lastFps: Double = 0.0
        private set

    private var frames = 0
    private var windowStartNanos = 0L
    private var lowSeconds = 0
    private var highSeconds = 0
    private var stepUpAfterS = STEP_UP_AFTER_S
    private var lastStepUpNanos = 0L
    private val history = DoubleArray(HISTORY_S)
    private var historyCount = 0
    private var historyNext = 0

    /** Frames per second averaged over the last [HISTORY_S] seconds, or 0 before the first one. */
    val averageFps: Double
        get() = synchronized(history) {
            if (historyCount == 0) 0.0 else (0 until historyCount).sumOf { history[it] } / historyCount
        }

    /** Called once per rendered frame (render thread, which is the main thread). */
    fun onFrame() {
        val now = System.nanoTime()
        if (windowStartNanos == 0L) {
            windowStartNanos = now
            return
        }
        frames++
        val elapsed = now - windowStartNanos
        if (elapsed < 1_000_000_000L) return
        val fps = frames * 1_000_000_000.0 / elapsed
        frames = 0
        windowStartNanos = now
        onSecond(fps, now)
    }

    private fun onSecond(fps: Double, now: Long) {
        lastFps = fps
        synchronized(history) {
            history[historyNext] = fps
            historyNext = (historyNext + 1) % HISTORY_S
            if (historyCount < HISTORY_S) historyCount++
        }
        // An unfocused or minimized game is throttled on purpose, and with nothing playing the video is
        // not what makes the game slow: neither says anything about the video quality.
        if (!ENABLED || !Minecraft.getInstance().isWindowActive || !anyVideoPlaying()) {
            lowSeconds = 0
            highSeconds = 0
            return
        }
        when {
            fps < LOW_FPS -> {
                highSeconds = 0
                if (++lowSeconds >= STEP_DOWN_AFTER_S && qualitySteps < MAX_STEPS) {
                    lowSeconds = 0
                    if (lastStepUpNanos != 0L && now - lastStepUpNanos < REGRET_WINDOW_NANOS) {
                        stepUpAfterS = (stepUpAfterS * 2).coerceAtMost(MAX_STEP_UP_AFTER_S)
                    }
                    qualitySteps++
                    logger.info("Game at {} FPS while a video plays: display quality lowered by {} step(s).", fps.toInt(), qualitySteps)
                }
            }
            fps >= HIGH_FPS -> {
                lowSeconds = 0
                if (qualitySteps > 0 && ++highSeconds >= stepUpAfterS) {
                    highSeconds = 0
                    qualitySteps--
                    lastStepUpNanos = now
                    logger.info("Game back at {} FPS: display quality now {} step(s) below the requested one.", fps.toInt(), qualitySteps)
                }
            }
            else -> {
                lowSeconds = 0
                highSeconds = 0
            }
        }
    }

    private fun anyVideoPlaying(): Boolean =
        DisplayRegistry.getScreens().any { !it.isDormant && !it.isPaused && it.isVideoStarted }

    /** One line for the report. */
    fun describe(): String =
        "fps now=%.0f avg10s=%.0f, auto quality %s, steps down=%d/%d, wait to step up=%ds".format(
            lastFps, averageFps, if (ENABLED) "on" else "off", qualitySteps, MAX_STEPS, stepUpAfterS,
        )
}
