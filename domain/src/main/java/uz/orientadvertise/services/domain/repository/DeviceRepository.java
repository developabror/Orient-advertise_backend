package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.Device;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    List<Device> findBySyncPendingSinceLessThan(Instant threshold);

    @Query("SELECT d FROM Device d WHERE d.deletedAt IS NULL AND d.registeredAt IS NOT NULL " +
           "AND d.lastHeartbeatAt IS NOT NULL AND d.lastHeartbeatAt < :threshold")
    List<Device> findRegisteredWithStaleHeartbeat(@Param("threshold") Instant threshold);

    @Query("SELECT d FROM Device d WHERE d.deletedAt IS NULL AND d.registeredAt IS NOT NULL " +
           "AND d.contentMismatchSince IS NOT NULL AND d.contentMismatchSince < :threshold")
    List<Device> findRegisteredWithStaleContentMismatch(@Param("threshold") Instant threshold);


    List<Device> findByRegionIdAndDeletedAtIsNull(Long regionId);

    List<Device> findByFacilityIdAndDeletedAtIsNull(Long facilityId);

    List<Device> findByDeviceGroupIdAndDeletedAtIsNull(Long deviceGroupId);

    long countByRegionIdAndDeletedAtIsNull(Long regionId);

    long countByFacilityIdAndDeletedAtIsNull(Long facilityId);

    long countByDeviceGroupIdAndDeletedAtIsNull(Long deviceGroupId);

    /**
     * Per-group active-device counts in a single round trip. Drives the device-group
     * listing's {@code deviceCount} column without firing a per-row query for every
     * page entry. {@code (groupId, count)} tuples are returned only for groups that
     * have at least one non-deleted device — the caller zero-fills missing groups so
     * the listing always reports a count, even for empty groups.
     */
    @Query("SELECT d.deviceGroup.id, COUNT(d) FROM Device d " +
           "WHERE d.deviceGroup.id IN :groupIds AND d.deletedAt IS NULL " +
           "GROUP BY d.deviceGroup.id")
    List<Object[]> countActiveDevicesPerGroup(@Param("groupIds") Collection<Long> groupIds);

    /**
     * Synchronized-playback readiness rollup (§1.4): among the given group device ids, how many have
     * already downloaded AND confirmed the target content version — i.e. {@code currentContentVersion}
     * equals the target and no sync is still pending. That is the coordinated-cut-over "devices ready"
     * signal, derived from state the confirm flow already maintains on {@code Device}. Soft-deleted
     * devices are excluded so they never hold up a group's readiness.
     */
    @Query("SELECT COUNT(d) FROM Device d WHERE d.id IN :deviceIds AND d.deletedAt IS NULL " +
           "AND d.currentContentVersion = :version AND d.syncPendingVersion IS NULL")
    long countReadyForVersion(@Param("deviceIds") Collection<Long> deviceIds,
                              @Param("version") String version);

    Optional<Device> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Batch fetch by ids, soft-deleted excluded. Drives membership-add — passing each id
     * to {@link #findByIdAndDeletedAtIsNull} would issue one query per device; this
     * collapses to a single {@code IN (?, ?, ...)} round trip. The caller compares the
     * returned ids to the input set to detect missing or soft-deleted devices.
     */
    List<Device> findAllByIdInAndDeletedAtIsNull(Collection<Long> ids);

    Optional<Device> findBySerialNumberAndDeletedAtIsNull(String serialNumber);

    boolean existsByRegionIdAndDeletedAtIsNull(Long regionId);

    long countByRegionIdAndStatusAndDeletedAtIsNull(Long regionId, Device.Status status);

    Optional<Device> findByDeviceTokenAndDeletedAtIsNull(String deviceToken);

    boolean existsByDeviceTokenAndDeletedAtIsNull(String deviceToken);

    @Query("SELECT d FROM Device d WHERE d.deletedAt IS NULL " +
           "AND (:facilityId IS NULL OR d.facility.id = :facilityId) " +
           "ORDER BY d.id ASC")
    Page<Device> findActivePaged(@Param("facilityId") Long facilityId, Pageable pageable);

    /** Operator-scoped {@link #findActivePaged}; only devices in {@code projectIds}. Non-empty only. */
    @Query("SELECT d FROM Device d WHERE d.deletedAt IS NULL " +
           "AND (:facilityId IS NULL OR d.facility.id = :facilityId) " +
           "AND d.region.project.id IN :projectIds " +
           "ORDER BY d.id ASC")
    Page<Device> findActivePagedScoped(@Param("facilityId") Long facilityId,
                                       @Param("projectIds") Collection<Long> projectIds,
                                       Pageable pageable);

    /**
     * Aggregate device counts grouped by status for the dashboard summary. Excludes
     * soft-deleted rows. Only present statuses are returned — the caller zero-fills
     * any missing buckets so the API response keeps a stable shape.
     */
    @Query("SELECT d.status, COUNT(d) FROM Device d WHERE d.deletedAt IS NULL " +
           "GROUP BY d.status")
    List<Object[]> countByStatusGrouped();

    /**
     * Per-region device totals: {@code [regionId, regionName, totalCount]}. Uses LEFT JOIN
     * so regions with zero registered devices still appear (total 0) — required by the
     * dashboard contract (FE-11/FE-12). The per-region <em>online</em> count is no longer
     * derived from the raw {@code status} column (which is non-authoritative); the dashboard
     * sources it from {@code device_status_view} via
     * {@code DeviceStatusViewRepository.countByRegionAndComputedStatus()}.
     */
    @Query("SELECT r.id, r.name, COUNT(d.id) " +
           "FROM Region r LEFT JOIN Device d ON d.region = r AND d.deletedAt IS NULL " +
           "GROUP BY r.id, r.name " +
           "ORDER BY r.name ASC")
    List<Object[]> countDevicesPerRegion();

    /**
     * Operator-scoped variant of {@link #countDevicesPerRegion}: only regions in the
     * operator's projects ({@code r.project.id IN :projectIds}). Only called with a
     * non-empty {@code projectIds}.
     */
    @Query("SELECT r.id, r.name, COUNT(d.id) " +
           "FROM Region r LEFT JOIN Device d ON d.region = r AND d.deletedAt IS NULL " +
           "WHERE r.project.id IN :projectIds " +
           "GROUP BY r.id, r.name " +
           "ORDER BY r.name ASC")
    List<Object[]> countDevicesPerRegionScoped(@Param("projectIds") Collection<Long> projectIds);

    /** True iff the (non-deleted-agnostic) device belongs to one of the given projects — the stats device-scope guard. */
    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM Device d " +
           "WHERE d.id = :deviceId AND d.region.project.id IN :projectIds")
    boolean existsByIdAndProjectIdIn(@Param("deviceId") Long deviceId,
                                     @Param("projectIds") Collection<Long> projectIds);

    /**
     * Operator-scoped "apply volume to all": set the per-device override on every non-deleted
     * device in scope in a single round trip, instead of loading and mutating each entity.
     * {@code null} projectIds = unrestricted (ADMIN); a restricted operator passes
     * {@code scope.narrowingIds()} (same idiom as the scoped list queries). Returns the number
     * of rows updated. {@code clearAutomatically} prevents the persistence context from serving
     * stale {@link Device} entities after the bulk write.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Device d SET d.desiredVolume = :volume, d.updatedAt = :now " +
           "WHERE d.deletedAt IS NULL " +
           "AND (:projectIds IS NULL OR d.region.project.id IN :projectIds)")
    int bulkSetDesiredVolume(@Param("volume") int volume, @Param("now") Instant now,
                             @Param("projectIds") Collection<Long> projectIds);

    /**
     * Clear the per-device override on every non-deleted member of a group in a single round trip.
     * Used by "apply volume to group": after the group's own {@code volume} is set, wiping the
     * members' {@code desiredVolume} makes them all inherit the group value via
     * {@link uz.orientadvertise.services.domain.model.DeviceVolumeResolver} — so a freshly applied
     * group volume overrides any manual per-device override. {@code clearAutomatically} prevents the
     * persistence context from serving stale {@link Device} entities after the bulk write.
     *
     * <p>{@code flushAutomatically = true} is essential here: the caller
     * ({@code DeviceGroupManagementService.setVolume}) mutates the managed {@code DeviceGroup.volume}
     * in the same transaction <i>before</i> this call. This bulk JPQL UPDATE touches only the
     * {@code device} table, so Hibernate's auto-flush would NOT flush that dirty {@code device_group}
     * row on its own — and the {@code clearAutomatically} clear would then evict it, silently
     * discarding the new group volume on commit. Flushing first persists the group volume before the
     * context is cleared.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Device d SET d.desiredVolume = NULL, d.updatedAt = :now " +
           "WHERE d.deviceGroup.id = :groupId AND d.deletedAt IS NULL")
    int bulkClearDesiredVolumeByGroup(@Param("groupId") Long groupId, @Param("now") Instant now);
}
