package com.music.vivi.desktop.data.youtube

import com.music.vivi.desktop.data.model.Playlist
import com.music.vivi.desktop.data.model.Song

/** The source prefix keeps each account's mirrored playlists separate. */
internal const val YOUTUBE_PLAYLIST_SOURCE = "YOUTUBE"

internal fun accountPlaylistSource(accountId: String): String =
    "$YOUTUBE_PLAYLIST_SOURCE:$accountId"

/**
 * Stable across restarts and re-logins. YouTube playlist ids are global, so the
 * row cannot collide with another account; keying on the account id would make
 * a rotated cookie (and therefore a new account id) grow a second copy.
 */
internal fun accountPlaylistLocalId(remoteId: String): String = "ytpl:$remoteId"

/**
 * The local row for one account playlist. Custom cover attributes already on
 * the row survive a refresh; only the name and the ordered tracks follow
 * YouTube Music.
 */
internal fun buildImportedPlaylist(
    accountId: String,
    remote: RemotePlaylist,
    songs: List<Song>,
    existing: Playlist?,
    now: Long = System.currentTimeMillis()
): Playlist {
    val name = remote.title.trim().ifBlank { "YouTube Music playlist" }
    val songIds = songs.map { it.id }.distinct()
    val source = accountPlaylistSource(accountId)
    if (existing == null) {
        return Playlist(
            id = accountPlaylistLocalId(remote.id),
            name = name,
            songIds = songIds,
            createdAt = now,
            lastModified = now,
            coverImageUri = remote.thumbnail,
            source = source
        )
    }
    val changed = existing.name != name || existing.songIds != songIds || existing.source != source
    return existing.copy(
        name = name,
        songIds = songIds,
        lastModified = if (changed) now else existing.lastModified,
        // A custom cover always wins; a row that never had one picks up the
        // account thumbnail when YouTube Music starts exposing it.
        coverImageUri = existing.coverImageUri ?: remote.thumbnail,
        source = source
    )
}
