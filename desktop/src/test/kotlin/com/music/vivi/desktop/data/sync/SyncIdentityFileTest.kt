package com.music.vivi.desktop.data.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SyncIdentityFileTest {

    @Test
    fun twoFilesKeepIndependentIdentitiesAndPairs() {
        val root = Files.createTempDirectory("sync-identity-test").toFile()
        try {
            val first = SyncIdentityFile(root.resolve("A.json"))
            val second = SyncIdentityFile(root.resolve("B.json"))

            val a = first.update {
                it.copy(deviceId = "device-A", deviceName = "Desktop A", pairId = "pair-A", serverUrl = "wss://relay")
            }
            val b = second.update {
                it.copy(deviceId = "device-B", deviceName = "Desktop B", pairId = "pair-B", serverUrl = "wss://relay")
            }

            assertNotEquals(a.deviceId, b.deviceId)
            assertEquals("pair-A", first.load().pairId)
            assertEquals("pair-B", second.load().pairId)
            assertEquals("Desktop A", first.load().deviceName)
            assertEquals("Desktop B", second.load().deviceName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sharingSwitchSurvivesAReopen() {
        val root = Files.createTempDirectory("sync-identity-sharing-test").toFile()
        try {
            val file = root.resolve("device-sync.json")
            SyncIdentityFile(file).update { it.copy(sharingEnabled = false) }
            assertEquals(false, SyncIdentityFile(file).load().sharingEnabled)
        } finally {
            root.deleteRecursively()
        }
    }
}
