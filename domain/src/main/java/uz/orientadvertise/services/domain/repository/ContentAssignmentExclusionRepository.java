package uz.orientadvertise.services.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.ContentAssignmentExclusion;

public interface ContentAssignmentExclusionRepository extends JpaRepository<ContentAssignmentExclusion, Long> {

    List<ContentAssignmentExclusion> findByAssignmentId(Long assignmentId);

    /**
     * Excluded device ids for an assignment, as a flat id projection. Used by the device-aware
     * overlap check to compute an assignment's effective device set without initializing the lazy
     * {@code device}/{@code assignment} proxies or selecting full {@code Device} rows.
     */
    @Query("SELECT e.device.id FROM ContentAssignmentExclusion e WHERE e.assignment.id = :assignmentId")
    List<Long> findDeviceIdsByAssignmentId(@Param("assignmentId") Long assignmentId);

    boolean existsByAssignmentIdAndDeviceId(Long assignmentId, Long deviceId);

    List<ContentAssignmentExclusion> findByDeviceId(Long deviceId);
}
