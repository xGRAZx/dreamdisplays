package com.dreamdisplays.platform.server.audiorange

import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.shared.audiorange.AudioRangeProtocol
import com.dreamdisplays.util.asJsonObjectOrNull
import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.net.DreamHttpClient
import com.dreamdisplays.util.optString
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The newest AltDisplays release on GitHub (tag `alt-<version>`), fetched in the background at most
 * once an hour, so the mod gate can tell players with an older mod to update.
 */
@PaperOnly
@NullMarked
internal object ReleaseCheck {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val API = "https://api.github.com/repos/xGRAZx/dreamdisplays/releases/latest"
    private const val REFRESH_MS = 60L * 60 * 1000
    private const val RETRY_MS = 10L * 60 * 1000

    /** The newest released version, like `1.2`, or null until a check succeeded. */
    @Volatile
    private var latest: String? = null

    @Volatile
    private var nextCheckAt = 0L
    private val running = AtomicBoolean(false)

    /** The newest known release; starts a background refresh when the last one is old. */
    fun latest(): String? {
        refresh()
        return latest
    }

    fun refresh() {
        if (System.currentTimeMillis() < nextCheckAt || !running.compareAndSet(false, true)) return
        runCatching { Scheduler.runAsync { fetch() } }.onFailure { running.set(false) }
    }

    private fun fetch() {
        try {
            val body = DreamHttpClient.readText(
                API,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(
                        "User-Agent" to "AltDisplays/${AudioRangeProtocol.ALT_VERSION}",
                        "Accept" to "application/vnd.github+json",
                    ),
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 8_000,
                ),
            )
            val tag = DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()?.optString("tag_name")
            val version = tag?.takeIf { it.startsWith("alt-") }?.removePrefix("alt-")
                ?.takeIf { AudioRangeProtocol.parseVersion(it) != null }
            if (version != null) latest = version
            nextCheckAt = System.currentTimeMillis() + REFRESH_MS
        } catch (e: Exception) {
            logger.debug("AltDisplays release check failed: {}", e.message)
            nextCheckAt = System.currentTimeMillis() + RETRY_MS
        } finally {
            running.set(false)
        }
    }
}
