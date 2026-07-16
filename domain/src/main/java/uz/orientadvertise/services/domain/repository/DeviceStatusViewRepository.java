package uz.orientadvertise.services.domain.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceStatusView;

public interface DeviceStatusViewRepository extends JpaRepository<DeviceStatusView, Long> {

    /**
     * The {@code unassigned} parameter is a tri-state {@link Boolean}:
     * <ul>
     *   <li>{@code null} or {@code false} → no constraint, return rows with or without
     *       a device group;</li>
     *   <li>{@code true} → require {@code device_group_id IS NULL} (devices not yet
     *       placed in any group, the operator's "needs assignment" bucket).</li>
     * </ul>
     * Mutual exclusivity with a non-null {@code deviceGroupId} is enforced one layer up
     * (the service rejects that combination with 400) — both clauses still appear in the
     * WHERE so the query stays correct if a future caller passes both.
     *
     * <p>{@code hasActivePlaylist} is an independent tri-state {@link Boolean} (orthogonal to
     * {@code unassigned} — a group-less device can still resolve a playlist via a REGION-targeted
     * assignment, so the two are NOT mutually exclusive):
     * <ul>
     *   <li>{@code null} → no constraint;</li>
     *   <li>{@code true} → only devices with a resolved active playlist now
     *       ({@code active_playlist_id IS NOT NULL});</li>
     *   <li>{@code false} → only devices with no active playlist ({@code active_playlist_id IS NULL}),
     *       the "needs content" bucket.</li>
     * </ul>
     *
     * <p>{@code syncUnassigned} is a third independent tri-state {@link Boolean}, on a DIFFERENT
     * axis from {@code unassigned} ({@code device_group_id IS NULL}): {@code true} requires
     * {@code sync_group_id IS NULL} — the "not yet in a sync group / sales point" bucket that
     * feeds the FE sync-group member picker. {@code null}/{@code false} → no constraint.
     */
    @Query("SELECT v FROM DeviceStatusView v " +
           "WHERE (:status IS NULL OR v.computedStatus = :status) " +
           "AND (:regionId IS NULL OR v.regionId = :regionId) " +
           // Project-scoped device filter (decision 3). The view exposes only regionId, so we
           // narrow via a Region subselect — the same bridge used below for operator :projectIds.
           "AND (:projectId IS NULL OR v.regionId IN (SELECT r.id FROM Region r WHERE r.project.id = :projectId)) " +
           "AND (:facilityId IS NULL OR v.facilityId = :facilityId) " +
           "AND (:deviceGroupId IS NULL OR v.deviceGroupId = :deviceGroupId) " +
           "AND (:unassigned IS NULL OR :unassigned = FALSE OR v.deviceGroupId IS NULL) " +
           "AND (:hasActivePlaylist IS NULL " +
           "     OR (:hasActivePlaylist = TRUE  AND v.activePlaylistId IS NOT NULL) " +
           "     OR (:hasActivePlaylist = FALSE AND v.activePlaylistId IS NULL)) " +
           "AND (:syncUnassigned IS NULL OR :syncUnassigned = FALSE OR v.syncGroupId IS NULL) " +
           "AND (CAST(:serialContains AS string) IS NULL OR LOWER(v.serialNumber) LIKE LOWER(CONCAT('%', CAST(:serialContains AS string), '%'))) " +
           "AND (CAST(:nameContains AS string) IS NULL OR LOWER(v.name) LIKE LOWER(CONCAT('%', CAST(:nameContains AS string), '%'))) " +
           "AND (CAST(:facilityNameContains AS string) IS NULL OR LOWER(v.facilityName) LIKE LOWER(CONCAT('%', CAST(:facilityNameContains AS string), '%'))) " +
           // Operator project scope. The view exposes only regionId, so we narrow via a Region
           // subselect (Region is not soft-deleted, so it's a safe bridge). null = unrestricted.
           "AND (:projectIds IS NULL OR v.regionId IN (SELECT r.id FROM Region r WHERE r.project.id IN :projectIds))")
    Page<DeviceStatusView> findFiltered(
            @Param("status") Device.Status status,
            @Param("regionId") Long regionId,
            @Param("projectId") Long projectId,
            @Param("facilityId") Long facilityId,
            @Param("deviceGroupId") Long deviceGroupId,
            @Param("unassigned") Boolean unassigned,
            @Param("serialContains") String serialContains,
            @Param("nameContains") String nameContains,
            @Param("facilityNameContains") String facilityNameContains,
            @Param("hasActivePlaylist") Boolean hasActivePlaylist,
            @Param("syncUnassigned") Boolean syncUnassigned,
            @Param("projectIds") Collection<Long> projectIds,
            Pageable pageable);

    /**
     * Device counts grouped by the heartbeat-derived {@code computed_status}. Drives the
     * dashboard summary's top-level ON/OFFLINE/NO_CONTENT buckets. The view already filters
     * {@code deleted_at IS NULL}, so totals match the prior raw-column count. Returns tuples
     * {@code (computedStatus, count)}.
     */
    @Query("SELECT v.computedStatus, COUNT(v) FROM DeviceStatusView v GROUP BY v.computedStatus")
    List<Object[]> countByComputedStatusGrouped();

    /** Operator-scoped {@link #countByComputedStatusGrouped}; only regions in {@code projectIds}. Non-empty only. */
    @Query("SELECT v.computedStatus, COUNT(v) FROM DeviceStatusView v " +
           "WHERE v.regionId IN (SELECT r.id FROM Region r WHERE r.project.id IN :projectIds) " +
           "GROUP BY v.computedStatus")
    List<Object[]> countByComputedStatusGroupedScoped(@Param("projectIds") Collection<Long> projectIds);

    /**
     * Device counts per region, grouped by {@code computed_status}, for the dashboard's
     * per-region online breakdown. Returns tuples {@code (regionId, computedStatus, count)};
     * the caller sums ONLINE per region and merges with region names / totals. Regions with
     * zero devices won't appear here — the caller fills them from the region-name query.
     */
    @Query("SELECT v.regionId, v.computedStatus, COUNT(v) FROM DeviceStatusView v GROUP BY v.regionId, v.computedStatus")
    List<Object[]> countByRegionAndComputedStatus();

    /** Operator-scoped {@link #countByRegionAndComputedStatus}; only regions in {@code projectIds}. Non-empty only. */
    @Query("SELECT v.regionId, v.computedStatus, COUNT(v) FROM DeviceStatusView v " +
           "WHERE v.regionId IN (SELECT r.id FROM Region r WHERE r.project.id IN :projectIds) " +
           "GROUP BY v.regionId, v.computedStatus")
    List<Object[]> countByRegionAndComputedStatusScoped(@Param("projectIds") Collection<Long> projectIds);
}
