package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.domain.model.Schedule;

/**
 * Listing projection for {@code GET /api/schedules}. {@code playlistId} is resolved
 * through {@link Schedule#getAssignment()} so the admin UI can link straight from a
 * schedule row to the underlying playlist without a second fetch.
 */
public record ScheduleSummary(
        Long id,
        Long assignmentId,
        Long playlistId,
        Instant startTimeUtc,
        Instant endTimeUtc,
        String repeatType,
        Instant repeatEndUtc,
        Instant createdAt
) {
    public static ScheduleSummary from(Schedule schedule) {
        var assignment = schedule.getAssignment();
        Long assignmentId = assignment != null ? assignment.getId() : null;
        Long playlistId = (assignment != null && assignment.getPlaylist() != null)
                ? assignment.getPlaylist().getId()
                : null;
        return new ScheduleSummary(
                schedule.getId(),
                assignmentId,
                playlistId,
                schedule.getStartTimeUtc(),
                schedule.getEndTimeUtc(),
                schedule.getRepeatType() != null ? schedule.getRepeatType().name() : null,
                schedule.getRepeatEndUtc(),
                schedule.getCreatedAt());
    }
}
