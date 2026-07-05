package uz.orientadvertise.services.api.dto;

import java.util.List;

import uz.orientadvertise.services.domain.model.HealthStatus;

public record HealthResponse(String overallStatus, List<ComponentStatus> components) {

    public record ComponentStatus(String name, String status, String timestamp) {}

    public static HealthResponse from(List<HealthStatus> statuses) {
        boolean allUp = statuses.stream().allMatch(HealthStatus::isUp);
        var components = statuses.stream()
                .map(s -> new ComponentStatus(s.component(), s.statusName(), s.timestamp()))
                .toList();
        return new HealthResponse(allUp ? "UP" : "DEGRADED", components);
    }
}
