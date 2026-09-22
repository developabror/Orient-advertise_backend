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
     * Retention-cleanup helper. Returns ids of events older than {@code threshold} that no
     * incident references as its {@code firstEvent} or {@code lastEvent} — those are safe to
     * delete. A referenced event is kept for as long as its incident exists, whatever the
     * incident's status: {@code incident.first_event_id}/{@code last_event_id} are plain
     * foreign keys (no {@code ON DELETE}) and incidents are never deleted, so deleting the event
     * of a RESOLVED incident fails the whole batch. Skipping only open incidents (DATA-03) made
     * that batch the first one every night — ordering is {@code id ASC} — so event retention
     * stopped for good ~90 days after the first resolved incident.
     */
    @Query("SELECT e.id FROM Event e WHERE e.occurredAt < :threshold " +
           "AND NOT EXISTS (SELECT 1 FROM Incident i WHERE " +
           "i.firstEvent.id = e.id OR i.lastEvent.id = e.id) " +
           "ORDER BY e.id ASC")
    List<Long> findExpiredIdsNotReferencedByIncidents(@Param("threshold") Instant threshold,
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
