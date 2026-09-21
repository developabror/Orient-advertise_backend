package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.orientadvertise.services.domain.model.RemoteSession;
import uz.orientadvertise.services.domain.model.RemoteSession.Status;

public interface RemoteSessionRepository extends JpaRepository<RemoteSession, Long> {

    /** Lookup by the opaque {@code rs_…} key — the only identifier that ever leaves the server. */
    Optional<RemoteSession> findBySessionKey(String sessionKey);

    /**
     * The device's live session, if any. "One non-terminal session per device" is enforced in
     * the service layer on top of this — exactly like {@code remote_action}'s
     * one-PENDING-per-type rule — because H2 (the test database) does not support the partial
     * unique index that would express it in DDL.
     *
     * <p>{@code DeviceId} resolves to the {@code device.id} path; {@link RemoteSession}
     * deliberately has no {@code getDeviceId()} getter that could shadow it.
     */
    Optional<RemoteSession> findFirstByDeviceIdAndStatusIn(Long deviceId, Collection<Status> statuses);

    /** Janitor query for {@code RemoteSessionExpirationJob}. */
    List<RemoteSession> findByStatusInAndExpiresAtBefore(Collection<Status> statuses, Instant cutoff);
}
