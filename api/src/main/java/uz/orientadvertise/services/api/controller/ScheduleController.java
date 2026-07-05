package uz.orientadvertise.services.api.controller;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.api.dto.ScheduleDetail;
import uz.orientadvertise.services.api.dto.ScheduleSummary;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.Schedule;
import uz.orientadvertise.services.domain.model.Schedule.RepeatType;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.service.ScheduleQueryService;
import uz.orientadvertise.services.service.ScheduleService;
import uz.orientadvertise.services.service.ScheduleService.ScheduleResult;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ScheduleQueryService scheduleQueryService;
    private final ContentAssignmentRepository assignmentRepository;

    public ScheduleController(ScheduleService scheduleService,
                               ScheduleQueryService scheduleQueryService,
                               ContentAssignmentRepository assignmentRepository) {
        this.scheduleService = scheduleService;
        this.scheduleQueryService = scheduleQueryService;
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * Filtered, paginated schedule listing. Validation, page-size capping, and the
     * default trailing-90-day window live in {@link ScheduleQueryService} (which throws
     * {@code IllegalArgumentException} → 400 via the global handler):
     *
     * <ul>
     *   <li>Window filter applies to {@code startTimeUtc}; default is the trailing
     *       90 days ending at "now".</li>
     *   <li>Date range capped at 90 days; {@code from > to} rejected.</li>
     *   <li>Page size ≤ 100.</li>
     *   <li>Soft-deleted schedules are never returned.</li>
     * </ul>
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<Page<ScheduleSummary>> list(
            @RequestParam(required = false) Long assignmentId,
            @RequestParam(required = false) RepeatType repeatType,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {
        Page<Schedule> page = scheduleQueryService.findFiltered(
                assignmentId, repeatType, from, to, pageable);
        return ResponseEntity.ok(page.map(ScheduleSummary::from));
    }

    /**
     * Single-schedule detail. Adds {@code nextOccurrenceUtc} to the listing fields —
     * computed inline from the schedule's recurrence rules so the FE doesn't have to
     * re-derive them. Mid-recurrence semantics live in {@link ScheduleDetail#from}.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<ScheduleDetail> detail(@PathVariable Long id) {
        Schedule s = scheduleQueryService.getDetail(id);
        return ResponseEntity.ok(ScheduleDetail.from(s, Instant.now()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<ScheduleResponse> create(@Valid @RequestBody CreateScheduleRequest req) {
        ContentAssignment assignment = assignmentRepository.findById(req.assignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("ContentAssignment", req.assignmentId()));

        ScheduleResult result = scheduleService.createSchedule(
                assignment, req.startTimeUtc(), req.endTimeUtc(),
                req.repeatType(), req.repeatEndUtc());

        return ResponseEntity.status(201).body(ScheduleResponse.from(result));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<ScheduleResponse> update(@PathVariable Long id,
                                                    @Valid @RequestBody UpdateScheduleRequest req) {
        var schedule = scheduleService.update(id, req.startTimeUtc(), req.endTimeUtc(),
                req.repeatType(), req.repeatEndUtc());
        return ResponseEntity.ok(ScheduleResponse.from(schedule, java.util.List.of()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        scheduleService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateScheduleRequest(
            @NotNull Long assignmentId,
            @NotNull Instant startTimeUtc,
            @NotNull Instant endTimeUtc,
            @NotNull RepeatType repeatType,
            Instant repeatEndUtc
    ) {}

    public record UpdateScheduleRequest(
            @NotNull Instant startTimeUtc,
            @NotNull Instant endTimeUtc,
            @NotNull RepeatType repeatType,
            Instant repeatEndUtc
    ) {}

    public record ScheduleResponse(
            Long id, Long assignmentId,
            Instant startTimeUtc, Instant endTimeUtc,
            String repeatType, Instant repeatEndUtc,
            java.util.List<OverlapWarningDto> overlapWarnings
    ) {
        public static ScheduleResponse from(ScheduleResult result) {
            return from(result.schedule(),
                    result.warnings().stream()
                            .map(w -> new OverlapWarningDto(w.existingScheduleId(),
                                    w.newWindow().start(), w.newWindow().end(),
                                    w.existingWindow().start(), w.existingWindow().end()))
                            .toList());
        }

        public static ScheduleResponse from(Schedule s, java.util.List<OverlapWarningDto> warnings) {
            return new ScheduleResponse(
                    s.getId(),
                    s.getAssignment() != null ? s.getAssignment().getId() : null,
                    s.getStartTimeUtc(), s.getEndTimeUtc(),
                    s.getRepeatType().name(), s.getRepeatEndUtc(),
                    warnings);
        }
    }

    public record OverlapWarningDto(Long existingScheduleId,
                                     Instant newStart, Instant newEnd,
                                     Instant existingStart, Instant existingEnd) {}
}
