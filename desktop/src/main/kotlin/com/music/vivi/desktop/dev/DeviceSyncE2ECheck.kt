package com.music.vivi.desktop.dev

import com.music.vivi.desktop.data.database.FavoritesDao
import com.music.vivi.desktop.data.database.LocalPlaylistDao
import com.music.vivi.desktop.data.database.MusicDao
import com.music.vivi.desktop.data.model.Playlist
import com.music.vivi.desktop.data.model.Song
import com.music.vivi.desktop.data.repository.MusicRepositoryImpl
import com.music.vivi.desktop.data.repository.SettingsRepositoryImpl
import com.music.vivi.desktop.data.store.LocalLibraryStore
import com.music.vivi.desktop.data.sync.DesktopSyncManager
import com.music.vivi.desktop.data.sync.SyncIdentityFile
import com.music.vivi.sync.PlaybackSnapshot
import com.music.vivi.sync.SyncConnectionState
import com.music.vivi.sync.SyncServer
import com.music.vivi.sync.TrackRef
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * A real, two-client Device Sync run against the fixed production relay.
 *
 * This is deliberately not a protocol fixture: both sides instantiate the same
 * [DesktopSyncManager] the window uses, with the real JSON library store and
 * the real `SettingsRepositoryImpl`, each on its own state directory. The only
 * thing that differs from the window is the UI: the harness observes the
 * manager flows (`paired`, `peerOnline`, `incomingPlayback`) and the two real
 * repositories, which is exactly what `DeviceSyncRuntime` and the settings /
 * library screens observe.
 *
 * Run it with:
 * `./gradlew :desktop:run --args="--device-sync-e2e-check"`
 *
 * It covers pairing, bilateral presence, playback + settings + playlist, the
 * Stop sharing gate, resuming, and a restart that must reconnect from the saved
 * pair without a new code. It writes `[E2E]` lines and exits non-zero on the
 * first failed assertion.
 */
object DeviceSyncE2ECheck {

    const val FLAG = "--device-sync-e2e-check"

    private const val PLAYBACK_TRACK = "yt:e2e-playback"
    private const val SETTINGS_TRACK = "yt:e2e-settings"
    private const val BLOCKED_TRACK = "yt:e2e-blocked"
    private const val RESUMED_TRACK = "yt:e2e-resumed"
    private const val RESTARTED_TRACK = "yt:e2e-restarted"
    private const val PLAYLIST_NAME = "E2E Sync Playlist"
    private const val BLOCKED_PLAYLIST = "E2E Blocked Playlist"

    fun handle(args: Array<String>): Boolean {
        if (!args.contains(FLAG)) return false
        val passed = runCatching { runBlocking { run() } }
            .onFailure { it.printStackTrace() }
            .getOrDefault(false)
        println("[E2E] RESULT: ${if (passed) "PASS" else "FAIL"}")
        exitProcess(if (passed) 0 else 1)
    }

    private suspend fun run(): Boolean {
        val root = Files.createTempDirectory("axolotl-device-sync-e2e-").toFile()
        println("[E2E] relay=${SyncServer.DEFAULT_URL}")
        println("[E2E] root=${root.absolutePath}")
        val a = Peer("A", File(root, "A"))
        val b = Peer("B", File(root, "B"))
        val receivedA = Collections.synchronizedList(mutableListOf<PlaybackSnapshot>())
        val receivedB = Collections.synchronizedList(mutableListOf<PlaybackSnapshot>())
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        observerScope.launch { a.manager.incomingPlayback.collect { receivedA.add(it) } }
        observerScope.launch { b.manager.incomingPlayback.collect { receivedB.add(it) } }

        try {
            println("[E2E] device A=${a.manager.deviceId} device B=${b.manager.deviceId}")
            step("1", "both clients connect to the default relay")
            a.manager.beginPairing("relay", SyncServer.DEFAULT_URL)
            val code = withTimeout(180_000) { a.manager.pairCode.first { it.isNotBlank() } }
            await("A is connected") { a.manager.connectionState.value == SyncConnectionState.CONNECTED }
            b.manager.connect(SyncServer.DEFAULT_URL)
            await("B is connected") { b.manager.connectionState.value == SyncConnectionState.CONNECTED }
            println("[E2E] [1] A state=${a.manager.connectionState.value} B state=${b.manager.connectionState.value} code=$code")

            step("2", "cloud pairing + bilateral presence")
            b.manager.joinPair(code)
            await("A paired") { a.manager.paired.value }
            await("B paired") { b.manager.paired.value }
            await("A sees B online") { a.manager.peerOnline.value }
            await("B sees A online") { b.manager.peerOnline.value }
            println(
                "[E2E] [2] paired A=${a.manager.paired.value} B=${b.manager.paired.value} " +
                    "online A=${a.manager.peerOnline.value} B=${b.manager.peerOnline.value} " +
                    "peerA=${a.manager.peerDeviceName.value} peerB=${b.manager.peerDeviceName.value}",
            )

            step("3a", "playback A -> B")
            val pushed = a.manager.updatePlayback(
                PlaybackSnapshot(
                    trackId = PLAYBACK_TRACK,
                    trackTitle = "E2E playback",
                    positionMs = 42_000L,
                    isPlaying = true,
                    volume = 0.73f,
                    queue = listOf(TrackRef(PLAYBACK_TRACK, "E2E playback", "E2E")),
                    queueIndex = 0,
                ),
            )
            check(pushed) { "A did not push playback (sharing or pairing inactive)" }
            val playbackOnB = awaitPlayback(receivedB, PLAYBACK_TRACK)
            check(playbackOnB.positionMs == 42_000L) { "B received position ${playbackOnB.positionMs}" }
            check(playbackOnB.isPlaying) { "B received isPlaying=false" }
            println("[E2E] [3a] A pushed track=${playbackOnB.trackTitle} pos=${playbackOnB.positionMs} playing=${playbackOnB.isPlaying}")

            step("3b", "settings A -> B")
            a.settings.setDarkMode(false)
            await("B applied darkMode=false") { !b.settings.settingsFlow.value.isDarkMode }
            println("[E2E] [3b] B darkMode=${b.settings.settingsFlow.value.isDarkMode}")

            step("3c", "playlist A -> B, once")
            a.music.rememberCatalogueSongs(
                listOf(Song(id = PLAYBACK_TRACK, title = "E2E playback", artist = "E2E", path = "/e2e/e2e.mp3")),
            )
            val playlist = a.music.createPlaylist(PLAYLIST_NAME, listOf(PLAYBACK_TRACK))
            val applied = awaitPlaylist(b.music, PLAYLIST_NAME)
            check(applied.songIds == listOf(PLAYBACK_TRACK)) { "B playlist songs=${applied.songIds}" }
            a.music.updatePlaylist(playlist.copy(name = "$PLAYLIST_NAME v2"))
            awaitPlaylist(b.music, "$PLAYLIST_NAME v2")
            val copies = b.music.getAllPlaylists().count { it.name.startsWith(PLAYLIST_NAME) }
            check(copies == 1) { "B has $copies copies of the synced playlist" }
            println("[E2E] [3c] B playlist='${b.music.getAllPlaylists().first { it.name.startsWith(PLAYLIST_NAME) }.name}' copies=$copies")

            step("4", "Stop sharing gates A, B stays paired and receiving")
            a.manager.setSharingEnabled(false)
            delay(300)
            check(!a.manager.sharingEnabled.value) { "sharing flag did not turn off" }
            val baselineDark = b.settings.settingsFlow.value.isDarkMode
            a.manager.updatePlayback(
                PlaybackSnapshot(
                    trackId = BLOCKED_TRACK,
                    trackTitle = "E2E blocked",
                    positionMs = 11_000L,
                    isPlaying = true,
                ),
            )
            a.settings.setDarkMode(true)
            a.music.createPlaylist(BLOCKED_PLAYLIST, listOf(SETTINGS_TRACK))
            delay(3_000)
            check(receivedB.none { it.trackId == BLOCKED_TRACK }) { "B received playback while sharing was stopped" }
            check(b.settings.settingsFlow.value.isDarkMode == baselineDark) { "B received settings while sharing was stopped" }
            check(b.music.getAllPlaylists().none { it.name == BLOCKED_PLAYLIST }) { "B received a playlist while sharing was stopped" }
            check(a.manager.paired.value && b.manager.paired.value) { "pairing was lost when sharing stopped" }
            check(a.manager.connectionState.value == SyncConnectionState.CONNECTED) { "A disconnected" }
            check(b.manager.connectionState.value == SyncConnectionState.CONNECTED) { "B disconnected" }
            println("[E2E] [4] B still paired=${b.manager.paired.value} connected=${b.manager.connectionState.value} lastPlaybackB=${receivedB.lastOrNull()?.trackId}")

            step("4b", "B -> A receive path works while A is stopped")
            val bPushed = b.manager.updatePlayback(
                PlaybackSnapshot(
                    trackId = "yt:e2e-from-b",
                    trackTitle = "E2E from B",
                    positionMs = 7_500L,
                    isPlaying = false,
                ),
            )
            check(bPushed) { "B could not push with A stopped" }
            awaitPlayback(receivedA, "yt:e2e-from-b")
            println("[E2E] [4b] A received B's playback; pairing still valid")

            step("5", "Start sharing resumes A -> B")
            a.manager.setSharingEnabled(true)
            check(a.manager.sharingEnabled.value) { "sharing flag did not turn on" }
            a.settings.setDarkMode(true)
            a.manager.updatePlayback(
                PlaybackSnapshot(
                    trackId = RESUMED_TRACK,
                    trackTitle = "E2E resumed",
                    positionMs = 3_000L,
                    isPlaying = true,
                ),
            )
            awaitPlayback(receivedB, RESUMED_TRACK)
            await("B applied resumed settings") { b.settings.settingsFlow.value.isDarkMode }
            println("[E2E] [5] B received resumed track and darkMode=${b.settings.settingsFlow.value.isDarkMode}")

            step("6", "restart A: saved pair reconnects without QR")
            val pairFile = a.identityFile
            val pairBefore = SyncIdentityFile(pairFile).load().pairId
            check(pairBefore.isNotBlank()) { "A has no saved pair before the restart" }
            a.manager.shutdown()
            delay(1_000)
            val restarted = DesktopSyncManager(
                musicRepository = a.music,
                settingsRepository = a.settings,
                identityFile = pairFile,
            )
            check(restarted.paired.value) { "restarted A did not load its saved pair" }
            await("restarted A is connected", 30_000) {
                restarted.connectionState.value == SyncConnectionState.CONNECTED
            }
            await("A2 sees B online", 20_000) { restarted.peerOnline.value }
            await("B sees A2 online", 20_000) { b.manager.peerOnline.value }
            check(SyncIdentityFile(pairFile).load().pairId == pairBefore) { "pair id changed across the restart" }
            check(restarted.pairCode.value.isBlank()) { "restart asked for a new pairing code" }
            restarted.updatePlayback(
                PlaybackSnapshot(
                    trackId = RESTARTED_TRACK,
                    trackTitle = "E2E after restart",
                    positionMs = 9_500L,
                    isPlaying = true,
                ),
            )
            awaitPlayback(receivedB, RESTARTED_TRACK)
            println("[E2E] [6] reconnected pair=${SyncIdentityFile(pairFile).load().pairId} code='${restarted.pairCode.value}' B got after-restart playback")
            restarted.shutdown()
            a.manager.shutdown()
            b.manager.shutdown()
            return true
        } finally {
            observerScope.cancel()
            runCatching { a.store.close() }
            runCatching { b.store.close() }
            println("[E2E] state root kept for inspection: ${root.absolutePath}")
        }
    }

    private suspend fun awaitPlayback(
        received: List<PlaybackSnapshot>,
        trackId: String,
    ): PlaybackSnapshot = withTimeout(15_000) {
        while (true) {
            received.firstOrNull { it.trackId == trackId }?.let { return@withTimeout it }
            delay(100)
        }
        error("unreachable")
    }

    private suspend fun awaitPlaylist(
        repository: MusicRepositoryImpl,
        name: String,
    ): Playlist = withTimeout(15_000) {
        while (true) {
            repository.getAllPlaylists().firstOrNull { it.name == name }?.let { return@withTimeout it }
            delay(100)
        }
        error("unreachable")
    }

    private suspend fun await(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val reached = withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(100)
            true
        }
        check(reached == true) { "timed out waiting for $message" }
    }

    private fun step(number: String, title: String) {
        println("[E2E] STEP $number: $title")
    }

    private class Peer(label: String, directory: File) {
        val identityFile = File(directory, "device-sync.json").apply { parentFile.mkdirs() }
        val store = LocalLibraryStore(File(directory, "device-sync.json"), File(directory, "legacy.db"))
        val music = MusicRepositoryImpl(
            store = store,
            musicDao = MusicDao(store),
            favoritesDao = FavoritesDao(store),
            playlistDao = LocalPlaylistDao(store),
        )
        val settings = SettingsRepositoryImpl(settingsFile = File(directory, "settings.json"))
        val manager: DesktopSyncManager

        init {
            SyncIdentityFile(identityFile).update {
                it.copy(
                    deviceId = "e2e-$label-${UUID.randomUUID()}",
                    deviceName = "E2E Desktop $label",
                    pairId = "",
                    serverUrl = "",
                    sharingEnabled = true,
                )
            }
            manager = DesktopSyncManager(
                musicRepository = music,
                settingsRepository = settings,
                identityFile = identityFile,
            )
        }
    }
}
