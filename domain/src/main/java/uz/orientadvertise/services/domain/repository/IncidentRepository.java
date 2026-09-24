package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.Incident;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    /**
     * Whether any open incident exists for the pair. "One open incident per pair" is enforced
     * only in the service layer (H2 has no partial unique index), so duplicates can exist
     * (LOGIC-16); an exists check cannot throw on them. There is deliberately no single-result
     * {@code Optional} lookup for the pair — it threw on duplicates.
     */
    @Query("SELECT CASE WHEN COUNT(i) > 0 THEN true ELSE false END FROM Incident i " +
           "WHERE i.device.id = :deviceId AND i.eventType = :eventType " +
           "AND i.status <> 'RESOLVED'")
    boolean existsOpenByDeviceAndEventType(
            @Param("deviceId") Long deviceId,
            @Param("eventType") String eventType);

    /**
     * Every open incident for {@code (deviceId, eventType)} — usually zero or one, but duplicates
     * can exist (see {@link #existsOpenByDeviceAndEventType}). An auto-resolve walks the whole list
     * so duplicates heal; {@code processEvent} picks the oldest.
     */
    @Query("SELECT i FROM Incident i " +
           "WHERE i.device.id = :deviceId AND i.eventType = :eventType " +
           "AND i.status <> 'RESOLVED'")
    List<Incident> findAllOpenByDeviceAndEventType(
            @Param("deviceId") Long deviceId,
            @Param("eventType") String eventType);

    /**
     * Ids of devices that still have an open {@code eventType} incident although their last
     * heartbeat is newer than {@code threshold} — recoveries the heartbeat path did not close.
     * Scalar ids on purpose: the caller runs outside a transaction and {@code Incident.device}
     * is LAZY.
     */
    @Query("SELECT DISTINCT i.device.id FROM Incident i " +
           "WHERE i.eventType = :eventType AND i.status <> 'RESOLVED' " +
           "AND i.device.lastHeartbeatAt > :threshold")
    List<Long> findDeviceIdsWithOpenIncidentAndHeartbeatAfter(
            @Param("eventType") String eventType,
            @Param("threshold") Instant threshold);

    /**
     * Ids of devices with an open {@code eventType} incident, whatever their heartbeat. Used by the
     * mismatch sweep, whose recovery condition is "no content is expected any more" rather than
     * anything about the heartbeat — the device may well never beat again (VG-15). Scalar ids for
     * the same reason as above: the caller runs outside a transaction.
     */
    @Query("SELECT DISTINCT i.device.id FROM Incident i "
           + "WHERE i.eventType = :eventType AND i.status <> 'RESOLVED' "
           + "AND i.device.deletedAt IS NULL")
    List<Long> findDeviceIdsWithOpenIncident(@Param("eventType") String eventType);

    List<Incident> findByDeviceIdOrderByUpdatedAtDesc(Long deviceId);

    /**
     * Incidents in a given status whose device is still active. Excludes incidents of
     * soft-deleted devices, honouring the same {@code deletedAt IS NULL} contract every
     * {@code DeviceRepository} listing query already enforces — a removed device must not
     * surface in the operator-facing open-incidents list ({@code GET /api/incidents/open})
     * or the dashboard WebSocket snapshot. (Pre-existing incidents of devices deleted
     * before the soft-delete cascade shipped are covered here, not just newly-deleted ones.)
     */
    @Query("SELECT i FROM Incident i JOIN i.device d " +
           "WHERE i.status = :status AND d.deletedAt IS NULL")
    List<Incident> findByStatus(@Param("status") Incident.Status status);

    /**
     * Open incidents narrowed to a set of projects (operator scope), reached via
     * {@code device.region.project} (region is the non-null anchor; facility is optional).
     * Keeps the same {@code deletedAt IS NULL} device guard as {@link #findByStatus}. Only
     * ever called with a non-empty {@code projectIds} — the service short-circuits the empty
     * set so this never binds an empty {@code IN ()}.
     */
    @Query("SELECT i FROM Incident i JOIN i.device d " +
           "WHERE i.status = 'OPEN' AND d.deletedAt IS NULL " +
           "AND d.region.project.id IN :projectIds")
    List<Incident> findOpenByProjectIds(@Param("projectIds") Collection<Long> projectIds);

    @Query("SELECT COUNT(i) FROM Incident i " +
           "WHERE i.device.id = :deviceId AND i.eventType = :eventType " +
           "AND i.status <> 'RESOLVED'")
    long countOpenByDeviceAndEventType(
            @Param("deviceId") Long deviceId,
            @Param("eventType") String eventType);

    @Query("SELECT COUNT(i) FROM Incident i WHERE " +
           "(:facilityId IS NULL OR i.device.facility.id = :facilityId) AND " +
           "i.device.deletedAt IS NULL AND " +
           "i.openedAt >= :from AND i.openedAt <= :to")
    long countOpenedInRange(@Param("facilityId") Long facilityId,
                              @Param("from") Instant from,
                              @Param("to") Instant to);

    /** Operator-scoped {@link #countOpenedInRange}; non-empty {@code projectIds} only. */
    @Query("SELECT COUNT(i) FROM Incident i WHERE " +
           "(:facilityId IS NULL OR i.device.facility.id = :facilityId) AND " +
           "i.device.deletedAt IS NULL AND i.device.region.project.id IN :projectIds AND " +
           "i.openedAt >= :from AND i.openedAt <= :to")
    long countOpenedInRangeScoped(@Param("facilityId") Long facilityId,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  @Param("projectIds") Collection<Long> projectIds);

    /**
     * Average resolution latency in seconds across incidents <em>opened</em> in the range
     * that have been resolved. Unresolved incidents (OPEN/ACKNOWLEDGED) are excluded —
     * including them would either bias toward 0 or require a synthetic "now-openedAt"
     * which contaminates the metric. Returns {@code null} when no resolved incidents fit.
     */
    @Query(value = "SELECT AVG(EXTRACT(EPOCH FROM (i.resolved_at - i.opened_at))) " +
                   "FROM incident i " +
                   "JOIN device d ON d.id = i.device_id " +
                   "WHERE (:facilityId IS NULL OR d.facility_id = :facilityId) " +
                   "AND d.deleted_at IS NULL " +
                   "AND i.opened_at >= :from AND i.opened_at <= :to " +
                   "AND i.status = 'RESOLVED' AND i.resolved_at IS NOT NULL",
            nativeQuery = true)
    Double avgResolutionSeconds(@Param("facilityId") Long facilityId,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to);

    /** Operator-scoped {@link #avgResolutionSeconds}; joins region for the project filter. Non-empty {@code projectIds} only. */
    @Query(value = "SELECT AVG(EXTRACT(EPOCH FROM (i.resolved_at - i.opened_at))) " +
                   "FROM incident i " +
                   "JOIN device d ON d.id = i.device_id " +
                   "JOIN region r ON r.id = d.region_id " +
                   "WHERE (:facilityId IS NULL OR d.facility_id = :facilityId) " +
                   "AND d.deleted_at IS NULL " +
                   "AND r.project_id IN (:projectIds) " +
                   "AND i.opened_at >= :from AND i.opened_at <= :to " +
                   "AND i.status = 'RESOLVED' AND i.resolved_at IS NOT NULL",
            nativeQuery = true)
    Double avgResolutionSecondsScoped(@Param("facilityId") Long facilityId,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to,
                                      @Param("projectIds") Collection<Long> projectIds);

    /**
     * Open-incident counts grouped by priority for the dashboard summary. "Open" here
     * means {@code status &lt;&gt; RESOLVED} — both OPEN and ACKNOWLEDGED incidents are
     * still demanding operator attention, so they roll up together. Only present
     * priorities are returned; the caller zero-fills the buckets so CRITICAL/WARNING/etc.
     * always appear in the response.
     */
    @Query("SELECT i.priority, COUNT(i) FROM Incident i JOIN i.device d " +
           "WHERE i.status <> 'RESOLVED' AND d.deletedAt IS NULL " +
           "GROUP BY i.priority")
    java.util.List<Object[]> countOpenByPriority();

    /** Operator-scoped {@link #countOpenByPriority}; only devices in {@code projectIds}. Non-empty only. */
    @Query("SELECT i.priority, COUNT(i) FROM Incident i JOIN i.device d " +
           "WHERE i.status <> 'RESOLVED' AND d.deletedAt IS NULL " +
           "AND d.region.project.id IN :projectIds " +
           "GROUP BY i.priority")
    java.util.List<Object[]> countOpenByPriorityScoped(@Param("projectIds") Collection<Long> projectIds);
}
