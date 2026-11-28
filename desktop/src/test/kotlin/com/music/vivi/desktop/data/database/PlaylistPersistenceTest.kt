package com.music.vivi.desktop.data.database

import com.music.vivi.desktop.data.model.Playlist
import com.music.vivi.desktop.data.model.Song
import com.music.vivi.desktop.data.store.LocalLibraryStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class PlaylistPersistenceTest {

    private val directory: File = Files.createTempDirectory("vivi-playlist-test").toFile()
    private val storeFile = File(directory, "device-sync.json")
    private val legacy = File(directory, "vivimusicde.db")

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun store() = LocalLibraryStore(storeFile, legacy)

    @Test
    fun everyCoverAttributeSurvivesAReopen() = runBlocking {
        val playlist = Playlist(
            id = "playlist-1",
            name = "Night drive",
            songIds = listOf("song-1"),
            coverImageUri = "/covers/night.jpg",
            coverColorArgb = 0xFF223344.toInt(),
            coverIconName = "Headphones",
            coverShapeType = "RotatedPill",
            coverShapeDetail1 = 0.25f,
            coverShapeDetail2 = 12f,
            coverShapeDetail3 = 1.1f,
            coverShapeDetail4 = 6f
        )

        LocalPlaylistDao(store()).insertPlaylist(playlist)

        val reopened = LocalPlaylistDao(store()).getPlaylistById("playlist-1")
        assertEquals(playlist.coverImageUri, reopened?.coverImageUri)
        assertEquals(playlist.coverColorArgb, reopened?.coverColorArgb)
        assertEquals(playlist.coverIconName, reopened?.coverIconName)
        assertEquals(playlist.coverShapeType, reopened?.coverShapeType)
        assertEquals(playlist.coverShapeDetail1, reopened?.coverShapeDetail1)
        assertEquals(playlist.coverShapeDetail2, reopened?.coverShapeDetail2)
        assertEquals(playlist.coverShapeDetail3, reopened?.coverShapeDetail3)
        assertEquals(playlist.coverShapeDetail4, reopened?.coverShapeDetail4)
    }

    @Test
    fun importingTheSameAccountPlaylistTwiceKeepsOneRow() = runBlocking {
        val dao = LocalPlaylistDao(store())
        val first = Playlist(
            id = "ytpl:account:PL1",
            name = "Imported",
            songIds = listOf("yt:one"),
            createdAt = 10L,
            source = "YOUTUBE:account"
        )

        dao.upsertPlaylists(listOf(first))
        dao.upsertPlaylists(listOf(first.copy(name = "Imported again", songIds = listOf("yt:two"))))

        val playlists = dao.getAllPlaylists()
        assertEquals(1, playlists.size)
        assertEquals("Imported again", playlists.single().name)
        assertEquals(listOf("yt:two"), playlists.single().songIds)
        assertEquals(10L, playlists.single().createdAt)
    }

    @Test
    fun aCatalogueTrackIsResolvedAfterAReopenWithoutEnteringTheLibrary() = runBlocking {
        val musicDao = MusicDao(store())
        val youtubeSong = Song(
            id = "yt:video-1",
            title = "Catalogue track",
            artist = "Artist",
            path = "ytmusic://video-1",
            contentUriString = "ytmusic://video-1"
        )

        musicDao.upsertCatalogueSongs(listOf(youtubeSong))

        val reopened = MusicDao(store())
        assertEquals(listOf(youtubeSong), reopened.getSongsByIds(listOf("yt:video-1")))
        assertEquals(emptyList(), reopened.getAllSongs())
    }
}
