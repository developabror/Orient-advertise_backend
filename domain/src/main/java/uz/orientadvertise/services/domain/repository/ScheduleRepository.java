package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.Schedule;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    List<Schedule> findByAssignmentIdAndDeletedAtIsNull(Long assignmentId);

    Optional<Schedule> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Filtered, paginated lookup for {@code GET /api/schedules}. Soft-deleted rows
     * are always excluded. {@code assignmentId} and {@code repeatType} are optional —
     * pass {@code null} to skip that predicate. The {@code [from, to]} window filters
     * on {@link Schedule#getStartTimeUtc()}; the caller is responsible for resolving
     * defaults and capping the range (see {@code ScheduleQueryService}).
     */
    @Query("SELECT s FROM Schedule s " +
           "WHERE s.deletedAt IS NULL " +
           "AND (:assignmentId IS NULL OR s.assignment.id = :assignmentId) " +
           "AND (:repeatType IS NULL OR s.repeatType = :repeatType) " +
           "AND (:projectIds IS NULL OR s.assignment.playlist.project.id IN :projectIds) " +
           "AND s.startTimeUtc >= :from AND s.startTimeUtc <= :to")
    Page<Schedule> findFiltered(@Param("assignmentId") Long assignmentId,
                                 @Param("repeatType") Schedule.RepeatType repeatType,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to,
                                 @Param("projectIds") Collection<Long> projectIds,
                                 Pageable pageable);

    @Query("SELECT s FROM Schedule s " +
           "WHERE s.assignment.id = :assignmentId " +
           "AND s.deletedAt IS NULL " +
           "AND s.startTimeUtc < :end AND s.endTimeUtc > :start")
    List<Schedule> findOverlappingForAssignment(
            @Param("assignmentId") Long assignmentId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    @Query("SELECT s FROM Schedule s " +
           "WHERE s.deletedAt IS NULL " +
           "AND s.startTimeUtc <= :now " +
           "AND (s.endTimeUtc > :now OR s.repeatEndUtc > :now OR s.repeatType <> 'NONE')")
    List<Schedule> findPotentiallyActiveAt(@Param("now") Instant now);
}
