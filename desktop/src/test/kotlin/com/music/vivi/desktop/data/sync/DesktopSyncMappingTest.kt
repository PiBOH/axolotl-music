package com.music.vivi.desktop.data.sync

import com.music.vivi.desktop.data.model.Playlist
import com.music.vivi.desktop.data.model.Song
import com.music.vivi.sync.SyncEnvelope
import com.music.vivi.sync.SyncMessageTypes
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSyncMappingTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun accountPlaylistIdsSurviveTheDesktopStore() {
        assertEquals(
            "PL123",
            Playlist(id = "ytpl:PL123", name = "Mix", source = "YOUTUBE:account").syncRemoteId(),
        )
        assertEquals(
            "PL123",
            Playlist(id = "yt-PL123", name = "Mix").syncRemoteId(),
        )
        assertEquals(
            null,
            Playlist(id = "LP12345678", name = "Local").syncRemoteId(),
        )
    }

    @Test
    fun aTrackKeepsTheMetadataThePhoneNeeds() {
        val synced = Song(
            id = "yt:abc",
            title = "Track",
            artist = "Artist",
            albumArtUriString = "https://example.invalid/cover.jpg",
        ).toSyncedSong()

        assertEquals("yt:abc", synced.id)
        assertEquals("Track", synced.title)
        assertEquals("Artist", synced.artist)
        assertEquals("https://example.invalid/cover.jpg", synced.thumbnail)
    }

    @Test
    fun presenceIsForwardedWithThePeerState() {
        val encoded = json.encodeToString(
            SyncEnvelope.serializer(),
            SyncEnvelope(
                type = SyncMessageTypes.PRESENCE,
                deviceId = "phone",
                deviceName = "Pixel",
                online = false,
            ),
        )
        val decoded = json.decodeFromString(SyncEnvelope.serializer(), encoded)

        assertEquals(SyncMessageTypes.PRESENCE, decoded.type)
        assertEquals("phone", decoded.deviceId)
        assertEquals(false, decoded.online)
        assertTrue(encoded.contains("\"online\":false"))
    }
}
