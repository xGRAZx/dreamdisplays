package com.dreamdisplays.media.player

import com.dreamdisplays.api.Unstable
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Process-wide audio health counters for the in-game report: whether screen audio reaches the sound
 * card at all. Screen audio plays through Java Sound, not Minecraft's sound engine, so a missing or
 * switched output device shows up here and nowhere in the game's own options.
 */
@Unstable
object AudioDiagnostics {
    /** Audio lines opened successfully, one per screen session. */
    val linesOpened = AtomicLong()

    /** Lines that could not be opened (device busy, missing or unsupported format). */
    val openFailures = AtomicLong()

    /** Times a playing line ran empty, which is heard as a crackle or a gap. */
    val underruns = AtomicLong()

    /** PCM chunks (1/20 s each) handed to the sound card. */
    val chunksWritten = AtomicLong()

    @Volatile
    var lastFailure: String? = null
        private set

    @Volatile
    var lastFailureAtMillis: Long = 0L
        private set

    internal fun recordFailure(reason: String) {
        openFailures.incrementAndGet()
        lastFailure = reason
        lastFailureAtMillis = System.currentTimeMillis()
    }

    /**
     * Name of the output Java Sound picks for screen audio (the first mixer that can play it, which is
     * how [AudioSystem.getLine] chooses), or null when no output can.
     */
    fun outputDeviceName(): String? = runCatching {
        val format = AudioFormat(44_100f, 16, 2, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        AudioSystem.getMixerInfo().firstOrNull { AudioSystem.getMixer(it).isLineSupported(info) }?.name
    }.getOrNull()
}
