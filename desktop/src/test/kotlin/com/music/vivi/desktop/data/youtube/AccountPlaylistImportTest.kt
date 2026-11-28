package com.music.vivi.desktop.data.youtube

import com.music.vivi.desktop.data.model.Playlist
import com.music.vivi.desktop.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AccountPlaylistImportTest {

    private val remote = RemotePlaylist(
        id = "PL123",
        title = "Road trip",
        songCountText = "2 songs",
        thumbnail = "https://example.test/cover.jpg",
        isEditable = true
    )

    private fun song(videoId: String) = Song(
        id = "yt:$videoId",
        title = "Track $videoId",
        artist = "Artist",
        path = "ytmusic://$videoId"
    )

    @Test
    fun localIdIsStableWhenTheAccountIdRotates() {
        assertEquals("ytpl:PL123", accountPlaylistLocalId("PL123"))
        // A rotated cookie gives a new account id, but the same YouTube playlist
        // must still update its one row instead of creating a duplicate.
        assertEquals(accountPlaylistLocalId("PL123"), accountPlaylistLocalId("PL123"))
    }

    @Test
    fun aNewImportKeepsTheRemoteTitleTracksAndCover() {
        val imported = buildImportedPlaylist(
            accountId = "account-a",
            remote = remote,
            songs = listOf(song("one"), song("two")),
            existing = null,
            now = 42L
        )

        assertEquals("ytpl:PL123", imported.id)
        assertEquals("Road trip", imported.name)
        assertEquals(listOf("yt:one", "yt:two"), imported.songIds)
        assertEquals("https://example.test/cover.jpg", imported.coverImageUri)
        assertEquals(accountPlaylistSource("account-a"), imported.source)
        assertEquals(42L, imported.createdAt)
    }

    @Test
    fun aRefreshUpdatesTheTracksButKeepsCustomCoverAttributes() {
        val existing = Playlist(
            id = "ytpl:PL123",
            name = "Old name",
            songIds = listOf("yt:old"),
            createdAt = 7L,
            coverImageUri = "/covers/custom.jpg",
            coverColorArgb = 0xFF112233.toInt(),
            coverIconName = "Star",
            coverShapeType = "Star",
            source = accountPlaylistSource("account-a")
        )

        val imported = buildImportedPlaylist(
            accountId = "account-a",
            remote = remote,
            songs = listOf(song("one")),
            existing = existing,
            now = 99L
        )

        assertEquals("Road trip", imported.name)
        assertEquals(listOf("yt:one"), imported.songIds)
        assertEquals(7L, imported.createdAt)
        assertEquals("/covers/custom.jpg", imported.coverImageUri)
        assertEquals(0xFF112233.toInt(), imported.coverColorArgb)
        assertEquals("Star", imported.coverIconName)
        assertEquals("Star", imported.coverShapeType)
        assertEquals(99L, imported.lastModified)
    }

    @Test
    fun anUnchangedRefreshDoesNotMoveTheModificationTime() {
        val existing = Playlist(
            id = "ytpl:PL123",
            name = "Road trip",
            songIds = listOf("yt:one"),
            lastModified = 5L,
            source = accountPlaylistSource("account-a")
        )

        val imported = buildImportedPlaylist(
            accountId = "account-a",
            remote = remote.copy(title = "Road trip"),
            songs = listOf(song("one")),
            existing = existing,
            now = 100L
        )

        assertEquals(5L, imported.lastModified)
        assertNotNull(imported.coverImageUri)
    }
}
