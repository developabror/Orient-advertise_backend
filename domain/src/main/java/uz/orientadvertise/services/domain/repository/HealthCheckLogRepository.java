package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.orientadvertise.services.domain.model.HealthCheckLog;

public interface HealthCheckLogRepository extends JpaRepository<HealthCheckLog, Long> {

    List<HealthCheckLog> findByComponentAndCheckedAtAfter(String component, Instant since);
}
