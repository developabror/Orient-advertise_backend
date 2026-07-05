package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.RemoteAction;

public interface RemoteActionRepository extends JpaRepository<RemoteAction, Long> {

    @Query("SELECT ra FROM RemoteAction ra " +
           "WHERE ra.device.id = :deviceId AND ra.actionType = :actionType " +
           "AND ra.status = 'PENDING'")
    List<RemoteAction> findPendingByDeviceAndType(
            @Param("deviceId") Long deviceId,
            @Param("actionType") String actionType);

    @Query("SELECT ra FROM RemoteAction ra " +
           "WHERE ra.status = 'PENDING' AND ra.expiresAt < :now")
    List<RemoteAction> findExpired(@Param("now") Instant now);

    List<RemoteAction> findByDeviceIdOrderByIssuedAtDesc(Long deviceId);

    @Query("SELECT ra FROM RemoteAction ra " +
           "WHERE ra.device.id = :deviceId AND ra.status = 'PENDING' " +
           "ORDER BY ra.issuedAt ASC")
    List<RemoteAction> findPendingByDevice(@Param("deviceId") Long deviceId);

    @Query("SELECT COUNT(ra) FROM RemoteAction ra " +
           "WHERE ra.device.id = :deviceId AND ra.status = 'PENDING'")
    long countPendingByDevice(@Param("deviceId") Long deviceId);

    /**
     * Filtered, paginated history for {@code GET /api/devices/{id}/actions}. Both
     * {@code status} and {@code actionType} are optional via the {@code (:param IS NULL OR …)}
     * idiom. The window filters on {@code issuedAt}; the caller resolves defaults and
     * caps the range. The {@code Pageable} carries the sort — the service forces it to
     * {@code issuedAt DESC} regardless of caller-supplied {@code ?sort=…} so the
     * operator console contract stays stable.
     */
    @Query("SELECT ra FROM RemoteAction ra " +
           "WHERE ra.device.id = :deviceId " +
           "AND (:status IS NULL OR ra.status = :status) " +
           "AND (:actionType IS NULL OR ra.actionType = :actionType) " +
           "AND ra.issuedAt >= :from AND ra.issuedAt <= :to")
    Page<RemoteAction> findHistory(@Param("deviceId") Long deviceId,
                                     @Param("status") RemoteAction.Status status,
                                     @Param("actionType") String actionType,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                     Pageable pageable);
}
