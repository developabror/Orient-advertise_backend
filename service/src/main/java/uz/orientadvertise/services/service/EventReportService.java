package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;

/**
 * Aggregated event/incident report for a facility (or system-wide).
 *
 * <p>Two execution paths:
 * <ul>
 *   <li><b>Sync</b> — for ranges where {@link EventRepository#countInRange} is at or below
 *       {@value #ASYNC_THRESHOLD}. Runs the aggregation inline and returns the result.</li>
 *   <li><b>Async</b> — for larger ranges. Submits the work to {@code reportExecutor},
 *       returns a job ID immediately. The polling endpoint returns
 *       {@code PENDING} → {@code COMPLETED}/{@code FAILED}.</li>
 * </ul>
 *
 * <p>Edge cases:
 * <ul>
 *   <li>No events in range → zero-filled response with empty maps/lists, total counts of 0
 *       and {@code avgResolutionSeconds: null}. Never returns 404.</li>
 *   <li>Average resolution time excludes incidents that are still OPEN/ACKNOWLEDGED — only
 *       fully-resolved incidents have a meaningful (resolvedAt − openedAt) duration.
 *       When no resolved incidents exist in the range the value is {@code null} (rather
 *       than 0, which would falsely imply "instant resolution").</li>
 *   <li>{@code from}/{@code to} default to the trailing 30 days; max range is 90 days.</li>
 * </ul>
 *
 * <p>Job store is in-memory ({@link ConcurrentHashMap}) — single-instance deploy is the
 * baseline for this app. Jobs auto-expire after {@value #JOB_TTL_MINUTES} minutes; expired
 * jobs are cleaned up lazily on access (avoids a dedicated scheduler).
 */
@Service
public class EventReportService {

    private static final Logger log = LoggerFactory.getLogger(EventReportService.class);

    public static final int MAX_RANGE_DAYS = 90;
    public static final long ASYNC_THRESHOLD = 10_000L;
    public static final int TOP_DEVICES_LIMIT = 10;
    public static final int JOB_TTL_MINUTES = 30;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final EventRepository eventRepository;
    private final IncidentRepository incidentRepository;
    private final EventReportService self;

    private final Map<String, ReportJob> jobs = new ConcurrentHashMap<>();

    public EventReportService(EventRepository eventRepository,
                                IncidentRepository incidentRepository,
                                @Lazy EventReportService self) {
        this.eventRepository = eventRepository;
        this.incidentRepository = incidentRepository;
        this.self = self;
    }

    /**
     * Either compute the report inline (and return {@link Outcome#sync}) or queue it
     * (and return {@link Outcome#async} with a job id). The decision is based on the
     * facility's expected event count for the range — a cheap pre-count keeps small
     * requests in the request thread while protecting it from minute-long aggregations.
     */
    /**
     * @param projectIds operator scope, resolved by the controller on the request thread
     *                   (the async path has no SecurityContext): {@code null} = unrestricted;
     *                   a non-empty set restricts the aggregation; an EMPTY set (operator with
     *                   no projects) short-circuits to an all-zero report.
     */
    @Transactional(readOnly = true)
    public Outcome run(Long facilityId, Instant from, Instant to, Collection<Long> projectIds) {
        var resolvedTo = to != null ? to : Instant.now();
        var resolvedFrom = from != null ? from : resolvedTo.minus(DEFAULT_WINDOW);

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("from must be before to");
        }
        long days = Duration.between(resolvedFrom, resolvedTo).toDays();
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Date range cannot exceed " + MAX_RANGE_DAYS + " days");
        }

        if (projectIds != null && projectIds.isEmpty()) {
            // operator with zero projects ⇒ nothing visible
            return Outcome.sync(new EventReport(facilityId, resolvedFrom, resolvedTo,
                    0L, Map.of(), 0L, null, List.of()));
        }

        long expectedEvents = projectIds == null
                ? eventRepository.countInRange(facilityId, resolvedFrom, resolvedTo)
                : eventRepository.countInRangeScoped(facilityId, resolvedFrom, resolvedTo, projectIds);
        if (expectedEvents <= ASYNC_THRESHOLD) {
            var report = computeReport(facilityId, resolvedFrom, resolvedTo, expectedEvents, projectIds);
            return Outcome.sync(report);
        }

        // Above threshold: queue and return a job id. We must call submitAsync via the
        // proxy (`self`) so Spring's @Async advice fires — calling it directly would
        // bypass the proxy and execute synchronously on this thread.
        var jobId = UUID.randomUUID().toString();
        var job = new ReportJob(jobId, JobStatus.PENDING, null, null,
                Instant.now().plus(Duration.ofMinutes(JOB_TTL_MINUTES)));
        jobs.put(jobId, job);
        log.info("Queued async event report [jobId={} facilityId={} expectedEvents={}]",
                jobId, facilityId, expectedEvents);
        self.submitAsync(jobId, facilityId, resolvedFrom, resolvedTo, expectedEvents,
                projectIds == null ? null : List.copyOf(projectIds));
        return Outcome.async(jobId);
    }

    @Async("reportExecutor")
    @Transactional(readOnly = true)
    public void submitAsync(String jobId, Long facilityId, Instant from, Instant to,
                              long expectedEvents, Collection<Long> projectIds) {
        try {
            var report = computeReport(facilityId, from, to, expectedEvents, projectIds);
            jobs.computeIfPresent(jobId, (id, existing) ->
                    new ReportJob(id, JobStatus.COMPLETED, report, null, existing.expiresAt()));
            log.info("Completed async event report [jobId={}]", jobId);
        } catch (Exception e) {
            jobs.computeIfPresent(jobId, (id, existing) ->
                    new ReportJob(id, JobStatus.FAILED, null, e.getMessage(), existing.expiresAt()));
            log.error("Async event report failed [jobId={}]: {}", jobId, e.getMessage(), e);
        }
    }

    public ReportJob getJob(String jobId) {
        var job = jobs.get(jobId);
        if (job == null || job.expiresAt().isBefore(Instant.now())) {
            jobs.remove(jobId);
            throw new ResourceNotFoundException("ReportJob", jobId);
        }
        return job;
    }

    EventReport computeReport(Long facilityId, Instant from, Instant to, long totalEvents,
                              Collection<Long> projectIds) {
        boolean scoped = projectIds != null;   // non-empty by contract (empty short-circuits in run)

        Map<String, Long> countsByType = new LinkedHashMap<>();
        var typeRows = scoped
                ? eventRepository.countByTypeInRangeScoped(facilityId, from, to, projectIds)
                : eventRepository.countByTypeInRange(facilityId, from, to);
        for (Object[] row : typeRows) {
            countsByType.put((String) row[0], ((Number) row[1]).longValue());
        }

        long incidentCount = scoped
                ? incidentRepository.countOpenedInRangeScoped(facilityId, from, to, projectIds)
                : incidentRepository.countOpenedInRange(facilityId, from, to);
        Double avgSec = scoped
                ? incidentRepository.avgResolutionSecondsScoped(facilityId, from, to, projectIds)
                : incidentRepository.avgResolutionSeconds(facilityId, from, to);

        var deviceRows = scoped
                ? eventRepository.topAffectedDevicesScoped(facilityId, from, to, projectIds, PageRequest.of(0, TOP_DEVICES_LIMIT))
                : eventRepository.topAffectedDevices(facilityId, from, to, PageRequest.of(0, TOP_DEVICES_LIMIT));
        List<DeviceImpact> topDevices = deviceRows.stream()
                .map(row -> new DeviceImpact(
                        (Long) row[0],
                        (String) row[1],
                        ((Number) row[2]).longValue()))
                .toList();

        return new EventReport(facilityId, from, to,
                totalEvents, countsByType, incidentCount, avgSec, topDevices);
    }

    public enum JobStatus { PENDING, COMPLETED, FAILED }

    public record DeviceImpact(Long deviceId, String deviceName, long eventCount) {}

    public record EventReport(Long facilityId, Instant from, Instant to,
                                long totalEvents,
                                Map<String, Long> countsByType,
                                long incidentCount,
                                Double avgResolutionSeconds,
                                List<DeviceImpact> topAffectedDevices) {}

    public record ReportJob(String jobId, JobStatus status, EventReport result,
                              String error, Instant expiresAt) {}

    public record Outcome(boolean async, EventReport report, String jobId) {
        public static Outcome sync(EventReport report) { return new Outcome(false, report, null); }
        public static Outcome async(String jobId) { return new Outcome(true, null, jobId); }
    }
}
