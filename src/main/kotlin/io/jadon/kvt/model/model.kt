package io.jadon.kvt.model

interface Entity

// Alias for clarity
typealias SongId = Int
typealias ArtistId = Int
typealias AlbumId = Int
typealias UserId = Int
typealias PlaylistId = Int

// Content

data class Song(
        val id: SongId?,
        val name: String,
        val artistIds: List<ArtistId>
) : Entity

data class Artist(
        val id: ArtistId?,
        val name: String
) : Entity

data class Album(
        val id: AlbumId?,
        val name: String,
        val artistIds: List<ArtistId>,
        val songIds: List<SongId>
) : Entity


// User content

data class User(
        val id: UserId?,
        val name: String
) : Entity

data class Playlist(
        val id: PlaylistId?,
        val name: String,
        val userId: UserId,
        val songIds: List<SongId>
) : Entity
