package com.music.vivi.desktop.utils

import java.io.File

/**
 * The one-time move of the 1.55 data trees into `~/.axolotlmusic`.
 *
 * It is a pure file operation, separated from [DesktopPathProvider] so the
 * copy rules can be tested with temporary folders: the migration runs only
 * when the destination does not exist yet, the first source wins when several
 * legacy folders carry the same file, and the old folder is never modified or
 * deleted.
 */
object LegacyDataMigration {

    /**
     * Copies [legacyDirs] into [target] only when [target] is absent.
     *
     * @return true when the target was created and the migration ran, false
     * when the destination already existed (in which case nothing is touched).
     */
    fun migrateIfMissing(target: File, legacyDirs: List<File>): Boolean {
        if (target.exists()) return false
        if (!target.mkdirs() && !target.isDirectory) return false
        legacyDirs
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .filter { it.isDirectory }
            .forEach { legacy -> copyTree(legacy, target) }
        return true
    }

    /**
     * Copies every file of [source] that does not already exist under [target].
     * The legacy folder is left untouched, so a downgrade still finds its data.
     */
    internal fun copyTree(source: File, target: File) {
        val sourceRoot = runCatching { source.canonicalFile }.getOrDefault(source)
        sourceRoot.walkTopDown().forEach { entry ->
            val relative = entry.relativeTo(sourceRoot).path
            if (relative.isEmpty()) return@forEach
            val destination = File(target, relative)
            if (entry.isDirectory) {
                destination.mkdirs()
            } else if (entry.isFile && !destination.exists()) {
                destination.parentFile?.mkdirs()
                runCatching { entry.copyTo(destination, overwrite = false) }
            }
        }
    }
}
