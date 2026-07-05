package uz.orientadvertise.services.api.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.api.dto.HealthResponse;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.service.HealthService;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        List<HealthStatus> statuses = healthService.checkAll();
        HealthResponse response = HealthResponse.from(statuses);
        return ResponseEntity.ok(response);
    }
}
