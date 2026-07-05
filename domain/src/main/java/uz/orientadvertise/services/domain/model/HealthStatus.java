package uz.orientadvertise.services.domain.model;

public record HealthStatus(String component, Status status, String timestamp) {

    public sealed interface Status permits Status.Up, Status.Down, Status.Unknown {
        record Up() implements Status {}
        record Down(String reason) implements Status {}
        record Unknown() implements Status {}
    }

    public boolean isUp() {
        return status instanceof Status.Up;
    }

    public String statusName() {
        return switch (status) {
            case Status.Up up -> "UP";
            case Status.Down(var reason) -> "DOWN: " + reason;
            case Status.Unknown unknown -> "UNKNOWN";
        };
    }
}
