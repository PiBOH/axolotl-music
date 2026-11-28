package com.music.vivi.desktop.utils

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyDataMigrationTest {

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("axolotl-migration-test-").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun copiesTheWholeLegacyTreeAndLeavesTheOriginalInPlace() {
        val legacy = File(root, ".vivimusic")
        write(File(legacy, "settings.json"), """{"theme":"dark"}""")
        write(File(legacy, "device-sync.json"), """{"deviceId":"uuid-1","pairId":"pair-1"}""")
        write(File(legacy, "vivimusicde.db"), "legacy-database")
        val cover = byteArrayOf(1, 2, 3, 4)
        File(legacy, "cache/artworks").mkdirs()
        File(legacy, "cache/artworks/cover.bin").writeBytes(cover)

        val target = File(root, ".axolotlmusic")
        assertTrue(LegacyDataMigration.migrateIfMissing(target, listOf(legacy)))

        assertEquals("""{"theme":"dark"}""", File(target, "settings.json").readText())
        assertEquals(
            """{"deviceId":"uuid-1","pairId":"pair-1"}""",
            File(target, "device-sync.json").readText(),
        )
        assertEquals("legacy-database", File(target, "vivimusicde.db").readText())
        assertContentEquals(cover, File(target, "cache/artworks/cover.bin").readBytes())

        // The source is never moved or deleted, so a downgrade still finds it.
        assertTrue(File(legacy, "settings.json").isFile)
        assertTrue(File(legacy, "device-sync.json").isFile)
        assertTrue(File(legacy, "vivimusicde.db").isFile)
        assertContentEquals(cover, File(legacy, "cache/artworks/cover.bin").readBytes())
    }

    @Test
    fun anExistingDestinationIsNotTouchedAtAll() {
        val legacy = File(root, ".vivimusic")
        write(File(legacy, "settings.json"), """{"theme":"old"}""")
        write(File(legacy, "device-sync.json"), """{"deviceId":"legacy"}""")

        val target = File(root, ".axolotlmusic")
        write(File(target, "settings.json"), """{"theme":"new"}""")

        assertFalse(LegacyDataMigration.migrateIfMissing(target, listOf(legacy)))

        assertEquals("""{"theme":"new"}""", File(target, "settings.json").readText())
        assertFalse(File(target, "device-sync.json").exists())
        assertTrue(File(legacy, "device-sync.json").isFile)
    }

    @Test
    fun theFirstLegacySourceWinsAndNothingIsOverwritten() {
        val appData = File(root, "appdata")
        val home = File(root, ".vivimusic")
        write(File(appData, "settings.json"), """{"theme":"preferred"}""")
        write(File(home, "settings.json"), """{"theme":"fallback"}""")
        write(File(home, "device-sync.json"), """{"pairId":"p"}""")

        val target = File(root, ".axolotlmusic")
        assertTrue(LegacyDataMigration.migrateIfMissing(target, listOf(appData, home)))

        assertEquals("""{"theme":"preferred"}""", File(target, "settings.json").readText())
        assertEquals("""{"pairId":"p"}""", File(target, "device-sync.json").readText())
    }

    @Test
    fun aMissingLegacyFolderStillCreatesTheDestination() {
        val target = File(root, ".axolotlmusic")
        assertTrue(LegacyDataMigration.migrateIfMissing(target, listOf(File(root, "absent"))))
        assertTrue(target.isDirectory)
        assertEquals(emptyList(), target.list().orEmpty().toList())
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }
}
