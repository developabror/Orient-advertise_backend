package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.Event;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByDeviceIdOrderByOccurredAtDesc(Long deviceId);

    List<Event> findByDeviceIdAndEventTypeAndOccurredAtAfter(Long deviceId, String eventType, Instant since);

    @Query("SELECT e FROM Event e WHERE " +
           "(:deviceId IS NULL OR e.device.id = :deviceId) AND " +
           "(:facilityId IS NULL OR e.device.facility.id = :facilityId) AND " +
           "(:priority IS NULL OR e.priority = :priority) AND " +
           "(:projectIds IS NULL OR e.device.region.project.id IN :projectIds) AND " +
           "e.occurredAt >= :from AND e.occurredAt <= :to " +
           "ORDER BY e.occurredAt DESC")
    Page<Event> findFiltered(@Param("deviceId") Long deviceId,
                              @Param("facilityId") Long facilityId,
                              @Param("priority") Event.Priority priority,
                              @Param("from") Instant from,
                              @Param("to") Instant to,
                              @Param("projectIds") Collection<Long> projectIds,
                              Pageable pageable);

    /**
     * Retention-cleanup helper. Returns ids of events older than {@code threshold} that
     * are NOT referenced by any non-RESOLVED incident — those are safe to delete.
     * An event is "linked" if it's the {@code firstEvent} or {@code lastEvent} of an
     * incident; we keep the linked event row so the incident's audit trail stays intact.
     */
    @Query("SELECT e.id FROM Event e WHERE e.occurredAt < :threshold " +
           "AND NOT EXISTS (SELECT 1 FROM Incident i WHERE " +
           "(i.firstEvent.id = e.id OR i.lastEvent.id = e.id) " +
           "AND i.status <> 'RESOLVED') " +
           "ORDER BY e.id ASC")
    List<Long> findExpiredIdsSkippingOpenIncidents(@Param("threshold") Instant threshold,
                                                    Pageable pageable);

    @Query("SELECT COUNT(e) FROM Event e WHERE " +
           "(:facilityId IS NULL OR e.device.facility.id = :facilityId) AND " +
           "e.occurredAt >= :from AND e.occurredAt <= :to")
    long countInRange(@Param("facilityId") Long facilityId,
                       @Param("from") Instant from,
                       @Param("to") Instant to);

    @Query("SELECT e.eventType, COUNT(e) FROM Event e WHERE " +
           "(:facilityId IS NULL OR e.device.facility.id = :facilityId) AND " +
           "e.occurredAt >= :from AND e.occurredAt <= :to " +
           "GROUP BY e.eventType ORDER BY COUNT(e) DESC")
    List<Object[]> countByTypeInRange(@Param("facilityId") Long facilityId,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to);

    @Query("SELECT e.device.id, e.device.name, COUNT(e) FROM Event e WHERE " +
           "(:facilityId IS NULL OR e.device.facility.id = :facilityId) AND " +
           "e.occurredAt >= :from AND e.occurredAt <= :to " +
           "GROUP BY e.device.id, e.device.name ORDER BY COUNT(e) DESC")
    List<Object[]> topAffectedDevices(@Param("facilityId") Long facilityId,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to,
                                        Pageable pageable);

    // --- Operator-scoped report aggregations (device.region.project.id IN :projectIds).
    //     Called only with a non-empty projectIds; admins/viewers use the unscoped variants above.

    @Query("SELECT COUNT(e) FROM Event e WHERE " +
           "(:facilityId IS NULL OR e.device.facility.id = :facilityId) AND " +
           "e.device.region.project.id IN :projectIds AND " +
           "e.occurredAt >= :from AND e.occurredAt <= :to")
    long countInRangeScoped(@Param("facilityId") Long facilityId,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            @Param("projectIds") Collection<Long> projectIds);

    @Query("SELECT e.eventType, COUNT(e) FROM Event e WHERE " +
           "(:facilityId IS NULL OR e.device.facility.id = :facilityId) AND " +
           "e.device.region.project.id IN :projectIds AND " +
           "e.occurredAt >= :from AND e.occurredAt <= :to " +
           "GROUP BY e.eventType ORDER BY COUNT(e) DESC")
    List<Object[]> countByTypeInRangeScoped(@Param("facilityId") Long facilityId,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to,
                                            @Param("projectIds") Collection<Long> projectIds);

    @Query("SELECT e.device.id, e.device.name, COUNT(e) FROM Event e WHERE " +
           "(:facilityId IS NULL OR e.device.facility.id = :facilityId) AND " +
           "e.device.region.project.id IN :projectIds AND " +
           "e.occurredAt >= :from AND e.occurredAt <= :to " +
           "GROUP BY e.device.id, e.device.name ORDER BY COUNT(e) DESC")
    List<Object[]> topAffectedDevicesScoped(@Param("facilityId") Long facilityId,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to,
                                            @Param("projectIds") Collection<Long> projectIds,
                                            Pageable pageable);
}
