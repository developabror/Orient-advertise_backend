package uz.orientadvertise.services.domain.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.PlaylistItem;

public interface PlaylistItemRepository extends JpaRepository<PlaylistItem, Long> {

    List<PlaylistItem> findByPlaylistIdOrderByPositionAsc(Long playlistId);

    /**
     * Number of items in a playlist. Drives the empty-playlist guard in
     * {@code ContentAssignmentService} — an assignment must never be created or confirmed
     * against a playlist with no items, or the device would resolve a non-null playlist
     * with an empty {@code playlistOrder} and play nothing. Counts in SQL rather than
     * loading the item rows.
     */
    long countByPlaylistId(Long playlistId);

    /**
     * Same as {@link #findByPlaylistIdOrderByPositionAsc} but eagerly fetches each
     * item's content file in a single round trip. Drives {@code GET /api/playlists/{id}},
     * which renders {@code contentFileName} alongside per-item fields — without the
     * fetch, Hibernate would emit one extra query per item to lazily resolve the file.
     */
    @Query("SELECT pi FROM PlaylistItem pi " +
           "LEFT JOIN FETCH pi.contentFile " +
           "WHERE pi.playlist.id = :playlistId " +
           "ORDER BY pi.position ASC")
    List<PlaylistItem> findByPlaylistIdWithContentFile(@Param("playlistId") Long playlistId);

    /**
     * Item count and total duration aggregates for a batch of playlists. Used by the
     * listing endpoint to fill {@code itemCount} / {@code totalDurationSeconds} in a
     * single SQL round trip — without this, each row in the page would fire its own
     * lazy-load on {@code playlist.items}, producing the classic N+1.
     *
     * <p>Per-item duration uses the playlist-level {@code durationSeconds} override when
     * present; otherwise falls back to the source content file's natural duration.
     * Returned tuples are {@code (playlistId, itemCount, totalDurationSeconds)} — all
     * three are {@code Number} subclasses to be safe across Postgres's {@code BIGINT}
     * mappings.
     */
    @Query("SELECT pi.playlist.id, COUNT(pi), " +
           "COALESCE(SUM(COALESCE(pi.durationSeconds, pi.contentFile.durationSeconds, 0)), 0) " +
           "FROM PlaylistItem pi WHERE pi.playlist.id IN :playlistIds " +
           "GROUP BY pi.playlist.id")
    List<Object[]> aggregateForPlaylists(@Param("playlistIds") Collection<Long> playlistIds);

    /**
     * Count of <i>active</i> playlists (i.e. not soft-deleted) currently referencing the
     * given content file. Drives the 409 Conflict response from
     * {@code DELETE /api/content/{id}} — a content file in use by N playlists is reported
     * as such instead of cascade-deleting them. {@code DISTINCT} ensures a playlist with
     * the same content at multiple positions counts once.
     */
    @Query("SELECT COUNT(DISTINCT pi.playlist.id) FROM PlaylistItem pi " +
           "WHERE pi.contentFile.id = :contentFileId " +
           "AND pi.playlist.deletedAt IS NULL")
    long countDistinctActivePlaylistsByContentFileId(@Param("contentFileId") Long contentFileId);

    // The four shifts below move a range by one in a single statement. They rely on
    // uq_playlist_position being checked per statement, not per row — V51 makes it DEFERRABLE
    // INITIALLY IMMEDIATE on Postgres for exactly this (LOGIC-09).
    @Modifying
    @Query("UPDATE PlaylistItem pi SET pi.position = pi.position + 1 " +
           "WHERE pi.playlist.id = :playlistId AND pi.position >= :fromPosition AND pi.position < :toPosition")
    void shiftPositionsDown(@Param("playlistId") Long playlistId,
                            @Param("fromPosition") int fromPosition,
                            @Param("toPosition") int toPosition);

    @Modifying
    @Query("UPDATE PlaylistItem pi SET pi.position = pi.position - 1 " +
           "WHERE pi.playlist.id = :playlistId AND pi.position > :fromPosition AND pi.position <= :toPosition")
    void shiftPositionsUp(@Param("playlistId") Long playlistId,
                          @Param("fromPosition") int fromPosition,
                          @Param("toPosition") int toPosition);

    @Modifying
    @Query("UPDATE PlaylistItem pi SET pi.position = pi.position - 1 " +
           "WHERE pi.playlist.id = :playlistId AND pi.position > :removedPosition")
    void compactPositionsAfterRemoval(@Param("playlistId") Long playlistId,
                                       @Param("removedPosition") int removedPosition);

    @Modifying
    @Query("UPDATE PlaylistItem pi SET pi.position = pi.position + 1 " +
           "WHERE pi.playlist.id = :playlistId AND pi.position >= :insertPosition")
    void shiftPositionsForInsert(@Param("playlistId") Long playlistId,
                                  @Param("insertPosition") int insertPosition);
}
