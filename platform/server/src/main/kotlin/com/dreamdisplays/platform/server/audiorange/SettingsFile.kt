package com.dreamdisplays.platform.server.audiorange

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * A small text settings file in the plugin folder, parsed into [T]. The parsed value is cached and only
 * re-read when the file changes on disk, so admins can edit it by hand while the server runs without
 * every player join paying for a re-read.
 */
internal class SettingsFile<T : Any>(
    private val file: File,
    /** Lines a missing file is created with, or null to leave it missing. */
    private val defaults: (() -> List<String>)?,
    /** Upgrades an existing file (e.g. adds missing keys); returns the same list when nothing changes. */
    private val migrate: (List<String>) -> List<String> = { it },
    private val parse: (List<String>) -> T,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    private var cached: T? = null
    private var stamp = Long.MIN_VALUE to -1L

    val name: String get() = file.name

    /** The current parsed value. */
    fun get(): T = synchronized(lock) { load().second }

    /** Current lines and value, for read-modify-write edits under [edit]. */
    private fun load(): Pair<List<String>, T> {
        val lines = readLines()
        val now = file.lastModified() to file.length()
        val value = cached?.takeIf { now == stamp } ?: parse(lines).also { cached = it; stamp = now }
        return lines to value
    }

    /** Runs [change] on the current lines and writes the result when it returns a different list. */
    fun edit(change: (lines: List<String>, value: T) -> List<String>?): T = synchronized(lock) {
        val (lines, value) = load()
        val next = change(lines, value) ?: return value
        if (next != lines) write(next)
        load().second
    }

    private fun readLines(): List<String> {
        if (!file.isFile) {
            val initial = defaults?.invoke() ?: return emptyList()
            write(initial)
            return initial
        }
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }
            .onFailure { logger.warn("Could not read {}", file, it) }
            .getOrDefault(emptyList())
        val migrated = migrate(lines)
        if (migrated != lines) write(migrated)
        return migrated
    }

    private fun write(lines: List<String>) {
        // The timestamp may not move within one clock tick, so never trust the cache across our own write.
        cached = null
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(lines.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { logger.warn("Could not write {}", file, it) }
    }
}
