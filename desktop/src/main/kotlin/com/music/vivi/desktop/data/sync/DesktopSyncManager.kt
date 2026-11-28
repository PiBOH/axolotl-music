package com.music.vivi.desktop.data.sync

import com.music.vivi.desktop.data.model.Playlist
import com.music.vivi.desktop.data.model.Song
import com.music.vivi.desktop.data.preferences.UserSettings
import com.music.vivi.desktop.domain.repository.MusicRepository
import com.music.vivi.desktop.domain.repository.SettingsRepository
import com.music.vivi.sync.LibrarySnapshot
import com.music.vivi.sync.PlaybackSnapshot
import com.music.vivi.sync.SyncClient
import com.music.vivi.sync.SyncConnectionState
import com.music.vivi.sync.SyncEvent
import com.music.vivi.sync.SyncSnapshot
import com.music.vivi.sync.SyncedPlaylist
import com.music.vivi.sync.SyncedSong
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One line of the live terminal on the Device Sync screen. */
data class SyncLogLine(val at: Long, val text: String)

/**
 * Persistent device-sync bridge for the desktop edition, carried over from
 * 1.55.0.
 *
 * The [SyncClient] (cloud relay) and the [LanSyncRelay] (same Wi-Fi) are
 * unchanged: this class owns the pairing state, pushes playback, settings and
 * the local library, applies incoming ones and suppresses the echo of a remote
 * apply.
 *
 * A saved pairing is restored at startup. The relay is a free Render instance
 * that sleeps, so the first handshake can take most of a minute; the client
 * keeps waiting and asks for the pairing code as soon as it is connected,
 * instead of giving up after the old ten-second window.
 */
class DesktopSyncManager(
    private val musicRepository: MusicRepository? = null,
    private val settingsRepository: SettingsRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relay = LanSyncRelay()
    private val identity = SyncIdentityStore.load()

    val deviceId: String = identity.deviceId
    val deviceName: String = identity.deviceName

    private val _connectionState = MutableStateFlow(SyncConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SyncConnectionState> = _connectionState.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _pairCode = MutableStateFlow("")
    val pairCode: StateFlow<String> = _pairCode.asStateFlow()

    /** Epoch millis when the current pairing code expires (0 = no active code). */
    private val _pairCodeExpiresAt = MutableStateFlow(0L)
    val pairCodeExpiresAt: StateFlow<Long> = _pairCodeExpiresAt.asStateFlow()

    private val _paired = MutableStateFlow(identity.pairId.isNotEmpty())
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    /** Whether the paired phone currently has an open relay socket. */
    private val _peerOnline = MutableStateFlow(false)
    val peerOnline: StateFlow<Boolean> = _peerOnline.asStateFlow()

    /** Last moment the peer was seen (snapshot or presence), 0 = never. */
    private val _peerLastSeenAt = MutableStateFlow(0L)
    val peerLastSeenAt: StateFlow<Long> = _peerLastSeenAt.asStateFlow()

    /** Name/model of the paired peer device (e.g. the phone's manufacturer + model). */
    private val _peerDeviceName = MutableStateFlow("")
    val peerDeviceName: StateFlow<String> = _peerDeviceName.asStateFlow()

    private val _peerDeviceId = MutableStateFlow("")
    val peerDeviceId: StateFlow<String> = _peerDeviceId.asStateFlow()

    private val _lanRunning = MutableStateFlow(false)
    val lanRunning: StateFlow<Boolean> = _lanRunning.asStateFlow()

    private val _lanAddress = MutableStateFlow("")
    val lanAddress: StateFlow<String> = _lanAddress.asStateFlow()

    private val _syncedSettings = MutableStateFlow<Map<String, String>>(emptyMap())
    val syncedSettings: StateFlow<Map<String, String>> = _syncedSettings.asStateFlow()

    private val _logs = MutableStateFlow<List<SyncLogLine>>(emptyList())
    val logs: StateFlow<List<SyncLogLine>> = _logs.asStateFlow()

    /** Incoming playback snapshots to apply to the player. */
    private val _incomingPlayback = MutableSharedFlow<PlaybackSnapshot>(extraBufferCapacity = 8)
    val incomingPlayback: SharedFlow<PlaybackSnapshot> = _incomingPlayback.asSharedFlow()

    /** Incoming settings snapshots to apply to the theme/language layer. */
    private val _incomingSettings = MutableSharedFlow<Map<String, String>>(extraBufferCapacity = 8)
    val incomingSettings: SharedFlow<Map<String, String>> = _incomingSettings.asSharedFlow()

    /** Incoming library snapshots to apply to the local library. */
    private val _incomingLibrary = MutableSharedFlow<LibrarySnapshot?>(extraBufferCapacity = 8)
    val incomingLibrary: SharedFlow<LibrarySnapshot?> = _incomingLibrary.asSharedFlow()

    private val _syncedLibrary = MutableStateFlow<LibrarySnapshot?>(null)
    val syncedLibrary: StateFlow<LibrarySnapshot?> = _syncedLibrary.asStateFlow()

    /** Stop sends local playback/settings/library while keeping the pair. */
    private val _sharingEnabled = MutableStateFlow(identity.sharingEnabled)
    val sharingEnabled: StateFlow<Boolean> = _sharingEnabled.asStateFlow()

    private var client: SyncClient? = null

    private val libraryMutex = Mutex()
    private val applyMutex = Mutex()

    private var lastPlayback: PlaybackSnapshot? = null
    private var lastSettings: Map<String, String> = emptyMap()
    private var lastLibrary: LibrarySnapshot? = null

    /** Queue fingerprint + last-write-wins timestamp (shared relay-time frame). */
    private var lastQueueFingerprint: String = ""
    private var queueUpdatedAt: Long = 0L

    @Volatile
    private var suppressPushUntil = 0L

    /** True while the peer's snapshot is being written into the local stores. */
    @Volatile
    private var applyingRemote = false

    /** Resolving state of the last snapshot we actually sent. */
    private var lastPushedResolving: Boolean? = null

    /** A user seek is still waiting to leave this device. */
    private var userSeekPending = false

    /** A pairing code was requested and has not arrived yet. */
    @Volatile
    private var pairingRequested = false

    /** Liked ids of the last local read, and the unlike tombstones of this run. */
    private var lastLikedIds: Set<String> = emptySet()
    private val likeTombstones = mutableMapOf<String, Long>()

    /** Periodic re-sync; the Device Sync card's Automatic resync switch. */
    @Volatile
    var autoResync: Boolean = true
        set(value) {
            field = value
            log("CFG", "automatic resync = $value")
        }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { relay.shutdownNotify() } })
        scope.launch {
            while (isActive) {
                delay(5_000L)
                if (autoResync && _paired.value && _connectionState.value == SyncConnectionState.CONNECTED) {
                    pushSnapshot()
                }
            }
        }
        observeSettings()
        observeLibrary()
        restoreSavedConnection()
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    fun connect(serverUrl: String) {
        val url = serverUrl.trim()
        if (url.isEmpty()) return
        teardownClient()
        val created = SyncClient(
            serverUrl = url,
            deviceId = deviceId,
            deviceName = deviceName,
        )
        client = created
        log("NET", "connecting to $url")
        scope.launch { created.connectionState.collect { _connectionState.value = it } }
        scope.launch { created.events.collect { handleEvent(it) } }
        created.connect()
        if (!url.startsWith("ws://localhost")) {
            SyncIdentityStore.update { it.copy(serverUrl = url) }
        }
    }

    /**
     * Starts the local LAN relay, connects the desktop to it, and optionally
     * generates a pairing code so the phone can pair right away.
     */
    fun startLan(requestCode: Boolean = true) {
        scope.launch {
            try {
                val port = relay.start()
                _lanRunning.value = true
                _lanAddress.value = "ws://${lanIpAddress()}:$port"
                log("LAN", "relay listening on ${_lanAddress.value}")
                connect("ws://localhost:$port")
                if (!requestCode) return@launch
                val c = client ?: return@launch
                repeat(5) {
                    if (_pairCode.value.isNotEmpty()) return@launch
                    withTimeoutOrNull(3_000L) {
                        c.connectionState.first { it == SyncConnectionState.CONNECTED }
                    }
                    if (c.connectionState.value == SyncConnectionState.CONNECTED) {
                        requestPairingCode()
                    }
                    delay(1_000L)
                }
            } catch (e: Exception) {
                _status.value = "LAN error: ${e.message}"
                log("LAN", "error: ${e.message}")
                _lanRunning.value = false
                _lanAddress.value = ""
            }
        }
    }

    fun stopLan() {
        scope.launch { runCatching { relay.stop() } }
        _lanRunning.value = false
        _lanAddress.value = ""
        teardownClient()
        clearPairing()
    }

    /**
     * The one action behind "Genera QR Code": pick the transport, connect and
     * ask the relay for a code. The retry survives the relay's cold start.
     */
    fun beginPairing(mode: String, relayUrl: String) {
        log("PAIR", "begin pairing over ${if (mode == "lan") "LAN" else "relay"}")
        pairingRequested = true
        _peerOnline.value = false
        if (mode == "lan") {
            if (!_lanRunning.value) startLan()
            return
        }
        connect(relayUrl.ifBlank { com.music.vivi.sync.SyncServer.DEFAULT_URL })
        scope.launch {
            repeat(60) {
                if (_pairCode.value.isNotEmpty() || _paired.value) return@launch
                if (_connectionState.value == SyncConnectionState.CONNECTED) {
                    requestPairingCode()
                }
                delay(1_000L)
            }
        }
    }

    fun requestPairingCode() {
        ensureConnected()
        log("PAIR", "requesting a pairing code")
        client?.requestPairingCode()
    }

    fun joinPair(code: String) {
        ensureConnected()
        client?.joinPair(code)
    }

    fun disconnect() {
        runCatching { client?.unpair() }
        teardownClient()
        clearPairing()
        _status.value = ""
        log("NET", "disconnected")
    }

    fun unpair() {
        client?.unpair()
        clearPairing()
        log("PAIR", "pairing cleared")
    }

    /** Stops or resumes sending local state; the pairing and receive path stay. */
    fun setSharingEnabled(enabled: Boolean) {
        if (_sharingEnabled.value == enabled) return
        _sharingEnabled.value = enabled
        SyncIdentityStore.update { it.copy(sharingEnabled = enabled) }
        log("CFG", if (enabled) "sharing resumed" else "sharing stopped")
        if (enabled) pushSnapshot(force = true)
    }

    fun shutdown() {
        runCatching { client?.disconnect() }
        runCatching { relay.shutdownNotify() }
    }

    private fun clearPairing() {
        SyncIdentityStore.update { it.copy(pairId = "") }
        _paired.value = false
        pairingRequested = false
        _peerOnline.value = false
        _pairCode.value = ""
        _pairCodeExpiresAt.value = 0L
        _peerDeviceName.value = ""
        _peerDeviceId.value = ""
    }

    /**
     * Update the local playback snapshot and push it to the peer (if paired).
     *
     * @return true if the snapshot was actually sent this call.
     */
    fun updatePlayback(playback: PlaybackSnapshot?): Boolean {
        if (playback?.userSeek == true) userSeekPending = true
        lastPlayback = playback?.let { p ->
            val withSeek = if (userSeekPending) p.copy(userSeek = true) else p
            val stamp = withSeek.positionAtMs == 0L && client?.hasServerOffset == true
            val snap = if (stamp) withSeek.copy(positionAtMs = serverNowMs()) else withSeek
            val fp = queueFingerprint(snap)
            if (fp.isNotEmpty() && fp != lastQueueFingerprint) {
                lastQueueFingerprint = fp
                queueUpdatedAt = serverNowMs()
            }
            snap.copy(queueUpdatedAt = queueUpdatedAt)
        }
        val resolving = lastPlayback?.isResolving
        val force = userSeekPending || resolving != lastPushedResolving
        val sent = pushSnapshot(force = force)
        if (sent) userSeekPending = false
        return sent
    }

    /** Last-write-wins timestamp of the local queue (relay frame); 0 = none. */
    fun queueUpdatedAt(): Long = queueUpdatedAt

    /** Adopts the remote queue's fingerprint + timestamp right after applying it. */
    fun noteQueueApplied(snapshot: PlaybackSnapshot) {
        val fp = queueFingerprint(snapshot)
        if (fp.isNotEmpty()) {
            lastQueueFingerprint = fp
            if (snapshot.queueUpdatedAt > 0L) queueUpdatedAt = snapshot.queueUpdatedAt
        }
    }

    private fun queueFingerprint(p: PlaybackSnapshot): String =
        if (p.queue.isEmpty()) "" else p.queue.joinToString("|") { it.id } + "@" + p.queueIndex

    fun updateSettings(settings: Map<String, String>) {
        lastSettings = settings
        pushSnapshot()
    }

    fun updateLibrary(library: LibrarySnapshot?) {
        lastLibrary = library
        pushSnapshot()
    }

    val serverOffsetMs: Long get() = client?.serverOffsetMs ?: 0L

    fun serverNowMs(): Long = System.currentTimeMillis() + serverOffsetMs

    fun effectivePosition(snapshot: PlaybackSnapshot): Long {
        val base = snapshot.positionMs.coerceAtLeast(0L)
        val at = snapshot.positionAtMs
        if (at <= 0L || !snapshot.isPlaying || snapshot.isResolving) return base
        val elapsed = (serverNowMs() - at).coerceAtLeast(0L)
        return (base + elapsed).coerceAtLeast(0L)
    }

    fun snapshotAgeMs(snapshot: PlaybackSnapshot): Long? =
        if (snapshot.positionAtMs > 0L) (serverNowMs() - snapshot.positionAtMs).coerceAtLeast(0L) else null

    // ---------------------------------------------------------------------
    // Startup, settings and library
    // ---------------------------------------------------------------------

    private fun restoreSavedConnection() {
        val saved = SyncIdentityStore.load()
        if (saved.pairId.isBlank() || saved.serverUrl.isBlank()) return
        // A LAN relay gets a new ephemeral port on every start; its saved
        // localhost URL is stale. The phone has to rescan, so do not dial it.
        if (saved.serverUrl.startsWith("ws://localhost:")) return
        scope.launch {
            delay(1_200L)
            log("NET", "restoring the saved pairing")
            connect(saved.serverUrl)
        }
    }

    private fun observeSettings() {
        val repository = settingsRepository ?: return
        scope.launch {
            repository.settingsFlow
                .collect { settings ->
                    if (applyingRemote) return@collect
                    lastSettings = desktopSettingsMap(settings)
                    pushSnapshot()
                }
        }
    }

    private fun observeLibrary() {
        val repository = musicRepository ?: return
        scope.launch {
            combine(
                repository.getFavoriteSongsFlow(),
                repository.getAllPlaylistsFlow(),
            ) { favorites, playlists -> favorites to playlists }
                .distinctUntilChanged()
                .collect { (favorites, playlists) ->
                    if (applyingRemote) return@collect
                    val snapshot = libraryMutex.withLock {
                        buildLibrarySnapshot(repository, favorites, playlists)
                    }
                    lastLibrary = snapshot
                    pushSnapshot()
                }
        }
    }

    private suspend fun buildLibrarySnapshot(
        repository: MusicRepository,
        favorites: List<Song>,
        playlists: List<Playlist>,
    ): LibrarySnapshot {
        val favoriteDates = runCatching { repository.getFavoriteDates() }.getOrDefault(emptyMap())
        val now = System.currentTimeMillis()
        val currentIds = favorites.mapTo(LinkedHashSet()) { it.id }
        for (id in lastLikedIds - currentIds) likeTombstones.putIfAbsent(id, now)
        for (song in favorites) {
            val likedAt = favoriteDates[song.id] ?: 0L
            val tombstone = likeTombstones[song.id] ?: continue
            if (likedAt > tombstone) likeTombstones.remove(song.id)
        }
        lastLikedIds = currentIds

        val likedSongs = favorites.map { song ->
            SyncedSong(
                id = song.id,
                title = song.title,
                artist = song.displayArtist,
                thumbnail = song.albumArtUriString,
                updatedAt = favoriteDates[song.id] ?: 0L,
            )
        } + likeTombstones.map { (id, at) ->
            SyncedSong(id = id, updatedAt = at, deleted = true)
        }

        val songIds = playlists.flatMapTo(LinkedHashSet()) { it.songIds }
        val catalog = runCatching { repository.getSongsByIds(songIds.toList()) }
            .getOrDefault(emptyList())
            .associateBy { it.id }
        val syncedPlaylists = playlists.map { playlist ->
            val remoteId = playlist.syncRemoteId()
            SyncedPlaylist(
                id = playlist.id,
                name = playlist.name,
                songs = playlist.songIds.map { id ->
                    catalog[id]?.toSyncedSong() ?: SyncedSong(id = id)
                },
                updatedAt = playlist.lastModified,
                remoteId = remoteId,
                needsYoutubeSync = remoteId == null && playlist.source == "LOCAL",
            )
        }.filterNot { it.remoteId in SPECIAL_ACCOUNT_PLAYLIST_IDS }

        return LibrarySnapshot(
            songIds = favorites.map { it.id },
            likedSongs = likedSongs,
            playlistIds = playlists.map { it.id },
            playlists = syncedPlaylists,
        )
    }

    private fun desktopSettingsMap(settings: UserSettings): Map<String, String> = buildMap {
        put("darkMode", if (settings.isDarkMode) "ON" else "OFF")
        put("dynamicTheme", settings.isDynamicColorEnabled.toString())
        put("syncViviVolume", settings.deviceSyncViviVolume.toString())
        put("crossfadeEnabled", settings.crossfadeEnabled.toString())
        put("crossfadeDuration", settings.crossfadeDurationSeconds.toString())
        put("audioQuality", settings.audioQuality.uppercase(Locale.ROOT))
    }

    private suspend fun applyRemoteSettings(settings: Map<String, String>) {
        val repository = settingsRepository ?: return
        applyingRemote = true
        suppressPushUntil = System.currentTimeMillis() + ECHO_SUPPRESS_MS
        try {
            settings["darkMode"]?.let { mode ->
                when (mode) {
                    "ON" -> repository.setDarkMode(true)
                    "OFF" -> repository.setDarkMode(false)
                }
            }
            settings["dynamicTheme"]?.toBooleanStrictOrNull()?.let {
                repository.setDynamicColoringEnabled(it)
            }
            settings["syncViviVolume"]?.toBooleanStrictOrNull()?.let {
                repository.setDeviceSyncViviVolume(it)
            }
            settings["crossfadeEnabled"]?.toBooleanStrictOrNull()?.let {
                repository.setCrossfadeEnabled(it)
            }
            settings["crossfadeDuration"]?.toIntOrNull()?.let {
                repository.setCrossfadeDurationSeconds(it.coerceIn(0, 12))
            }
            settings["audioQuality"]?.lowercase(Locale.ROOT)?.takeIf {
                it == "auto" || it == "high" || it == "low"
            }?.let { repository.setAudioQuality(it) }
        } finally {
            applyingRemote = false
            // The setters above already changed the live settings flow; keep
            // the next automatic push from sending the pre-remote map back.
            lastSettings = desktopSettingsMap(repository.settingsFlow.first())
        }
    }

    private suspend fun applyRemoteLibrary(library: LibrarySnapshot) {
        val repository = musicRepository ?: return
        libraryMutex.withLock {
            applyingRemote = true
            suppressPushUntil = System.currentTimeMillis() + ECHO_SUPPRESS_MS
            try {
                library.playlists.forEach { remote -> applyRemotePlaylist(repository, remote) }
                applyRemoteLikedSongs(repository, library)
                // Rebuild from what is now stored, so a later resync does not
                // push the old library back over the phone's changes.
                lastLibrary = buildLibrarySnapshot(
                    repository = repository,
                    favorites = repository.getFavoriteSongsFlow().first(),
                    playlists = repository.getAllPlaylistsFlow().first(),
                )
            } finally {
                applyingRemote = false
            }
        }
    }

    private suspend fun applyRemotePlaylist(repository: MusicRepository, remote: SyncedPlaylist) {
        if (remote.remoteId in SPECIAL_ACCOUNT_PLAYLIST_IDS) return
        val all = repository.getAllPlaylists()
        val existing = all.firstOrNull { it.id == remote.id }
            ?: remote.remoteId?.let { account -> all.firstOrNull { it.syncRemoteId() == account } }
        if (remote.deleted) {
            if (existing != null && remote.updatedAt > existing.lastModified) {
                repository.deletePlaylist(existing.id)
            }
            return
        }
        if (existing != null && remote.updatedAt <= existing.lastModified) return
        // Encode the account id in the id (the 1.55 `yt-` shape is read too), so
        // the next snapshot still carries `remoteId` and the phone does not
        // import a second copy of this playlist.
        val localId = existing?.id
            ?: remote.remoteId?.takeIf { it.isNotBlank() }?.let { "ytpl:$it" }
            ?: remote.id.ifBlank { newPlaylistId() }
        val now = System.currentTimeMillis()
        val updated = (existing ?: Playlist(id = localId, name = remote.name)).copy(
            id = localId,
            name = remote.name.trim().ifBlank { existing?.name ?: "Playlist" },
            songIds = remote.songs.map { it.id }.distinct(),
            lastModified = if (remote.updatedAt > 0L) remote.updatedAt else now,
            source = existing?.source ?: if (remote.remoteId != null) "YOUTUBE:sync" else "LOCAL",
        )
        repository.upsertPlaylists(listOf(updated))
    }

    private suspend fun applyRemoteLikedSongs(repository: MusicRepository, library: LibrarySnapshot) {
        val entries = LinkedHashMap<String, SyncedSong>()
        library.likedSongs.forEach { if (it.id.isNotBlank()) entries[it.id] = it }
        library.songIds.forEach { id -> entries.putIfAbsent(id, SyncedSong(id = id)) }
        if (entries.isEmpty()) return

        val favoriteDates = runCatching { repository.getFavoriteDates() }.getOrDefault(emptyMap())
        for ((id, entry) in entries) {
            val likedAt = favoriteDates[id] ?: 0L
            val tombstoneAt = likeTombstones[id] ?: 0L
            if (entry.deleted) {
                if (entry.updatedAt <= 0L || entry.updatedAt <= likedAt || entry.updatedAt <= tombstoneAt) continue
                likeTombstones[id] = entry.updatedAt
                repository.setFavorite(id, false)
                lastLikedIds = lastLikedIds - id
                continue
            }
            val wins = if (entry.updatedAt > 0L) {
                entry.updatedAt > maxOf(likedAt, tombstoneAt)
            } else {
                likedAt == 0L && tombstoneAt == 0L
            }
            if (!wins) continue
            likeTombstones.remove(id)
            if (repository.getSongById(id) == null) {
                repository.rememberCatalogueSongs(
                    listOf(
                        Song(
                            id = id,
                            title = entry.title,
                            artist = entry.artist,
                            album = "",
                            albumArtUriString = entry.thumbnail,
                        )
                    )
                )
            }
            // Keep the remote edit time so a re-like wins against an older
            // unlike instead of being pinned to this machine's clock.
            repository.setFavorite(id, true, entry.updatedAt)
            lastLikedIds = lastLikedIds + id
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun ensureConnected() {
        val c = client
        if (c == null) {
            val saved = SyncIdentityStore.load().serverUrl
            if (saved.isNotBlank()) connect(saved)
            return
        }
        if (c.connectionState.value != SyncConnectionState.CONNECTED) c.connect()
    }

    private fun teardownClient() {
        client?.disconnect()
        client = null
        _connectionState.value = SyncConnectionState.DISCONNECTED
        _peerOnline.value = false
    }

    private fun pushSnapshot(force: Boolean = false): Boolean {
        if (!_sharingEnabled.value) return false
        if (!force && System.currentTimeMillis() < suppressPushUntil) return false
        val c = client ?: return false
        if (c.connectionState.value != SyncConnectionState.CONNECTED) return false
        if (!_paired.value) return false
        lastPlayback?.let { p ->
            val kind = when {
                p.userSeek -> "USER SEEK"
                p.isResolving -> "resolving"
                else -> "tick/state"
            }
            log(
                "TX",
                "sync_push [$kind] track='${p.trackTitle ?: p.trackId}' queue=${p.queue.size} " +
                    "index=${p.queueIndex} playing=${p.isPlaying} pos=${p.positionMs}ms",
            )
        }
        c.pushSnapshot(
            SyncSnapshot(
                deviceId = c.deviceId,
                deviceName = deviceName,
                updatedAt = System.currentTimeMillis(),
                settings = lastSettings,
                playback = lastPlayback,
                library = lastLibrary,
            )
        )
        lastPushedResolving = lastPlayback?.isResolving
        return true
    }

    private fun handleEvent(event: SyncEvent) {
        when (event) {
            is SyncEvent.Connected -> {
                _status.value = "Connected"
                log("NET", "relay connected")
                if (_paired.value) client?.pullSnapshot()
                if (pairingRequested && !_paired.value && _pairCode.value.isEmpty()) {
                    requestPairingCode()
                }
            }
            is SyncEvent.Disconnected -> {
                _status.value = "Disconnected"
                _peerOnline.value = false
                log("NET", "relay disconnected")
            }
            is SyncEvent.PairCode -> {
                _pairCode.value = event.code
                _pairCodeExpiresAt.value = System.currentTimeMillis() + PAIR_CODE_TTL_MS
                _status.value = event.code
                log("RX", "pair_code ${event.code} (5 min)")
            }
            is SyncEvent.Paired -> {
                pairingRequested = false
                _paired.value = true
                _pairCode.value = ""
                _pairCodeExpiresAt.value = 0L
                _peerOnline.value = event.peerOnline
                _peerDeviceName.value = event.peerDeviceName.ifBlank { "APK Android" }
                _peerDeviceId.value = event.peerDeviceId
                SyncIdentityStore.update { it.copy(pairId = event.pairId) }
                _status.value = "Paired with ${_peerDeviceName.value}"
                log("PAIR", "pair_joined peer='${_peerDeviceName.value}' (${event.peerDeviceId})")
                pushSnapshot(force = true)
            }
            is SyncEvent.NoSnapshot -> {
                _paired.value = true
                pairingRequested = false
                _peerOnline.value = false
                _status.value = "Paired"
                log("PAIR", "reconnected as paired (no mailbox snapshot yet)")
            }
            is SyncEvent.Presence -> {
                if (event.deviceId.isNotBlank() && event.deviceId != deviceId) {
                    if (event.online) {
                        _peerOnline.value = true
                        _peerLastSeenAt.value = System.currentTimeMillis()
                        event.deviceName.takeIf { it.isNotBlank() }?.let { _peerDeviceName.value = it }
                        _status.value = "Peer online"
                        log("RX", "peer online '${event.deviceName}'")
                    } else {
                        _peerOnline.value = false
                        _status.value = "Peer offline"
                        log("RX", "peer offline '${event.deviceName}'")
                    }
                }
            }
            is SyncEvent.SnapshotReceived -> {
                _paired.value = true
                _peerOnline.value = true
                _peerLastSeenAt.value = System.currentTimeMillis()
                event.snapshot.deviceName.takeIf { it.isNotBlank() }?.let { _peerDeviceName.value = it }
                suppressPushUntil = System.currentTimeMillis() + ECHO_SUPPRESS_MS
                _status.value = "Snapshot received"
                event.snapshot.playback?.let { p ->
                    log(
                        "RX",
                        "sync from '${event.snapshot.deviceName}': track='${p.trackTitle ?: p.trackId}' " +
                            "queue=${p.queue.size} index=${p.queueIndex} playing=${p.isPlaying} " +
                            "pos=${p.positionMs}ms seek=${p.userSeek}",
                    )
                } ?: log("RX", "sync from '${event.snapshot.deviceName}' (no playback)")
                val remoteSettings = event.snapshot.settings
                if (remoteSettings.isNotEmpty()) {
                    _syncedSettings.value = remoteSettings
                    scope.launch { _incomingSettings.emit(remoteSettings) }
                }
                event.snapshot.playback?.let { pb ->
                    scope.launch { _incomingPlayback.emit(pb) }
                }
                event.snapshot.library?.let { lib ->
                    _syncedLibrary.value = lib
                    scope.launch { _incomingLibrary.emit(lib) }
                }
                // Apply the two stores in one serialised pass. Running them in
                // parallel let one finish and clear `applyingRemote` while the
                // other was still writing, and the observer could then echo a
                // half-applied snapshot back to the peer.
                if (remoteSettings.isNotEmpty() || event.snapshot.library != null) {
                    scope.launch {
                        applyMutex.withLock {
                            if (remoteSettings.isNotEmpty()) applyRemoteSettings(remoteSettings)
                            event.snapshot.library?.let { applyRemoteLibrary(it) }
                        }
                    }
                }
            }
            is SyncEvent.Error -> {
                _status.value = event.message
                if (event.message.contains("unpaired", ignoreCase = true) ||
                    event.message.contains("not paired", ignoreCase = true)
                ) {
                    log("PAIR", "peer unpaired us — dropping the local pairing")
                    _paired.value = false
                    pairingRequested = false
                    _peerOnline.value = false
                    _pairCode.value = ""
                    _pairCodeExpiresAt.value = 0L
                    _peerDeviceName.value = ""
                    _peerDeviceId.value = ""
                    SyncIdentityStore.update { it.copy(pairId = "") }
                } else {
                    log("ERR", event.message)
                }
            }
        }
    }

    private fun log(direction: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = SyncLogLine(System.currentTimeMillis(), "$time  $direction  $message")
        _logs.update { current ->
            (current + line).takeLast(MAX_LOG_LINES)
        }
    }

    companion object {
        private const val ECHO_SUPPRESS_MS = 3_000L
        private const val MAX_LOG_LINES = 400

        /** Pairing codes are valid for 5 minutes (matches the relay servers). */
        const val PAIR_CODE_TTL_MS = 5 * 60 * 1000L

        private val SPECIAL_ACCOUNT_PLAYLIST_IDS = setOf("LM", "SE")

        private fun newPlaylistId(): String =
            "LP" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.ROOT)
    }
}

internal fun Playlist.syncRemoteId(): String? = when {
    source.startsWith("YOUTUBE:") && id.startsWith("ytpl:") -> id.removePrefix("ytpl:")
    id.startsWith("yt-") -> id.removePrefix("yt-")
    else -> null
}

internal fun Song.toSyncedSong(): SyncedSong = SyncedSong(
    id = id,
    title = title,
    artist = displayArtist,
    thumbnail = albumArtUriString,
)
