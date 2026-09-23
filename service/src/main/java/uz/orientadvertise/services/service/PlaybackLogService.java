package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.PlaybackLog;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.repository.ContentAssignmentExclusionRepository;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;

@Service
public class PlaybackLogService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackLogService.class);

    public static final int MAX_BATCH_SIZE = 500;

    private final PlaybackLogRepository repository;
    private final DeviceRepository deviceRepository;
    private final ContentFileRepository contentFileRepository;
    private final ContentAssignmentRepository assignmentRepository;
    private final ContentAssignmentExclusionRepository exclusionRepository;
    private final PlaylistItemRepository playlistItemRepository;
    private final RetentionProperties retentionProperties;
    private final Duration maxClockSkew;
    private final Duration assignmentGrace;

    public PlaybackLogService(PlaybackLogRepository repository,
                              DeviceRepository deviceRepository,
                              ContentFileRepository contentFileRepository,
                              ContentAssignmentRepository assignmentRepository,
                              ContentAssignmentExclusionRepository exclusionRepository,
                              PlaylistItemRepository playlistItemRepository,
                              RetentionProperties retentionProperties,
                              @Value("${app.playback.max-clock-skew-seconds:30}") int maxClockSkewSeconds,
                              @Value("${app.playback.assignment-grace:PT30M}") Duration assignmentGrace) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.contentFileRepository = contentFileRepository;
        this.assignmentRepository = assignmentRepository;
        this.exclusionRepository = exclusionRepository;
        this.playlistItemRepository = playlistItemRepository;
        this.retentionProperties = retentionProperties;
        this.maxClockSkew = Duration.ofSeconds(maxClockSkewSeconds);
        this.assignmentGrace = assignmentGrace;
    }

    /**
     * Record a playback event.
     * Edge cases:
     * 1. played_at in the future beyond clock skew tolerance → rejected
     * 2. played_at older than {@code app.retention.playback} (90 days by default) → rejected,
     *    reading the same property the nightly cleanup deletes by
     * 3. Duplicate (device_id, content_file_id, played_at) → skipped by the database
     *    (ON CONFLICT DO NOTHING) and reported as Duplicate — idempotent, as advertised
     *
     * <p>Dedup is performed BY the INSERT, never by catching its failure. PlaybackLog uses
     * IDENTITY generation, so save() would issue the INSERT eagerly inside the caller's
     * transaction; a unique violation there aborts the PostgreSQL transaction and marks the
     * Hibernate session rollback-only before any catch block runs, destroying every other row
     * in the batch. Do not reintroduce a try/catch here.
     */
    @Transactional
    public PlaybackLogResult record(Device device, ContentFile contentFile,
                                     ContentAssignment assignment, Instant playedAt,
                                     Integer durationSeconds) {
        // Clock skew check: reject played_at more than maxClockSkew into the future
        var now = Instant.now();
        var maxAllowed = now.plus(maxClockSkew);
        if (playedAt.isAfter(maxAllowed)) {
            // INFO, not WARN: a skewed device clock is a CLIENT-driven refusal, and one returning
            // box flushing a 500-entry queue would otherwise emit 500 WARNs — every one of which is
            // forwarded to Telegram (the v1.0.137 rule).
            log.info("Rejected playback log — played_at {} is {} seconds in the future (max allowed: {}s), device={}",
                    playedAt, Duration.between(now, playedAt).getSeconds(),
                    maxClockSkew.getSeconds(), device.getId());
            return PlaybackLogResult.rejected("played_at is in the future beyond clock skew tolerance (%ds)".formatted(
                    maxClockSkew.getSeconds()));
        }

        // Retention check: reject played_at older than the CONFIGURED playback window (90 days by
        // default). The nightly retention job would purge the row anyway, and accepting it would
        // muddy analytics — there's no signal to be had from a playback report we'd delete this
        // week. Read from app.retention.playback so the bound and the sweep cannot disagree, and
        // so the day count in the message is always the one actually enforced.
        var retention = retentionProperties.getPlayback();
        if (playedAt.isBefore(now.minus(retention))) {
            log.info("Rejected playback log — played_at {} is older than {} days, device={}",
                    playedAt, retention.toDays(), device.getId());
            return PlaybackLogResult.rejected("played_at is older than the %d-day retention window".formatted(
                    retention.toDays()));
        }

        // Dedup is the database's job: ON CONFLICT DO NOTHING skips an existing
        // (device, content, played_at) without raising, so one duplicate cannot poison the
        // rest of the batch transaction. 1 row affected = created, 0 = duplicate.
        int inserted = repository.insertIgnoringDuplicate(
                device.getId(),
                contentFile.getId(),
                assignment != null ? assignment.getId() : null,
                playedAt,
                durationSeconds,
                now);                    // reuse the `now` above — same value the entity ctor set
        if (inserted == 0) {
            log.debug("Duplicate playback log ignored [device={}, content={}, played_at={}]",
                    device.getId(), contentFile.getId(), playedAt);
            return PlaybackLogResult.duplicate();
        }
        return PlaybackLogResult.created();
    }

    /**
     * Batch-record playback events. Each entry is processed independently so a single
     * bad timestamp doesn't poison the rest. Result tallies created / duplicate / rejected.
     *
     * <p>Edge case: batch over {@value #MAX_BATCH_SIZE} entries → IllegalArgumentException
     * (mapped to 400 by the global handler). Forces the device to chunk submissions so
     * one giant POST can't consume excessive memory or transaction time.
     *
     * <p>The whole batch is deliberately ONE transaction — a duplicate no longer aborts it,
     * so every non-duplicate entry commits.
     */
    @Transactional
    public BatchRecordResult recordBatch(Long deviceId, List<PlaybackEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new BatchRecordResult(0, 0, 0, List.of());
        }
        if (entries.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Batch size " + entries.size() + " exceeds maximum " + MAX_BATCH_SIZE);
        }

        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        // Which campaign was on screen when each entry was PLAYED — not which is on now. A batch
        // routinely arrives minutes after the fact and can straddle a campaign switch (VG-03).
        var history = loadHistory(device, entries);

        // Cache content files per request so a batch with 500 events for the same 5
        // playlist items doesn't issue 500 lookups.
        var fileCache = new java.util.HashMap<Long, ContentFile>();

        int created = 0;
        int duplicate = 0;
        int rejected = 0;
        var rejections = new java.util.ArrayList<EntryRejection>();

        // Same-request duplicates: a device's local queue can hold the same
        // (contentFileId, playedAt) twice. ON CONFLICT already returns 0 for the second one,
        // so this set just saves a round trip. Only ACCEPTED keys enter the set, so a repeated
        // entry that was *rejected* (future / past the retention window) is rejected again.
        var seenInRequest = new java.util.HashSet<List<Object>>();

        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            ContentFile file;
            try {
                file = fileCache.computeIfAbsent(entry.contentFileId(), id ->
                        contentFileRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("ContentFile", id)));
            } catch (ResourceNotFoundException e) {
                rejected++;
                rejections.add(new EntryRejection(i, "contentFileId not found: " + entry.contentFileId()));
                continue;
            }

            // Attribute the play to the campaign that was live at playedAt. A null playedAt is
            // left to record() to refuse, exactly as before.
            Attribution attribution = entry.playedAt() == null
                    ? Attribution.unknown()
                    : history.attribute(entry.contentFileId(), entry.playedAt());
            if (attribution.rejected()) {
                rejected++;
                rejections.add(new EntryRejection(i,
                        "contentFileId not assigned to device at playedAt: " + entry.contentFileId()));
                continue;
            }

            // Null-safe: List.of rejects nulls, and a null contentFileId/playedAt already fails
            // downstream exactly as it does today. Skipping the set for those preserves behavior.
            List<Object> key = (entry.contentFileId() != null && entry.playedAt() != null)
                    ? List.of(entry.contentFileId(), entry.playedAt())
                    : null;
            if (key != null && seenInRequest.contains(key)) {
                duplicate++;                       // already accepted earlier in THIS payload
                continue;
            }

            var result = record(device, file, attribution.assignment(), entry.playedAt(),
                    entry.durationSeconds());
            if (result instanceof PlaybackLogResult.Created) {
                created++;
                if (key != null) seenInRequest.add(key);
            } else if (result instanceof PlaybackLogResult.Duplicate) {
                duplicate++;
                if (key != null) seenInRequest.add(key);
            } else if (result instanceof PlaybackLogResult.Rejected r) {
                rejected++;
                rejections.add(new EntryRejection(i, r.reason()));
            }
        }

        log.info("Playback batch [device={}, total={}, created={}, duplicate={}, rejected={}]",
                deviceId, entries.size(), created, duplicate, rejected);
        return new BatchRecordResult(created, duplicate, rejected, rejections);
    }

    /**
     * Load every assignment that could have been driving this device across the batch's span,
     * with the per-device facts the plays are judged against: the device's exclusions and each
     * candidate playlist's content files. One query each, whatever the batch size.
     */
    private DeviceHistory loadHistory(Device device, List<PlaybackEntry> entries) {
        Instant earliest = null;
        Instant latest = null;
        for (var entry : entries) {
            var playedAt = entry.playedAt();
            if (playedAt == null) continue;
            if (earliest == null || playedAt.isBefore(earliest)) earliest = playedAt;
            if (latest == null || playedAt.isAfter(latest)) latest = playedAt;
        }
        if (earliest == null) {
            return new DeviceHistory(List.of(), maxClockSkew, assignmentGrace);
        }

        var region = device.getRegion();
        var facility = device.getFacility();
        var group = device.getDeviceGroup();
        var assignments = assignmentRepository.findHistoricalCandidates(
                region != null ? region.getId() : null,
                facility != null ? facility.getId() : null,
                group != null ? group.getId() : null,
                earliest.minus(assignmentGrace),
                latest);

        // An exclusion takes a device off an assignment from the moment it is written, so for
        // THIS device that assignment effectively ended then. Earliest wins if there are several.
        var excludedFrom = new java.util.HashMap<Long, Instant>();
        for (var exclusion : exclusionRepository.findByDeviceId(device.getId())) {
            var assignment = exclusion.getAssignment();
            if (assignment == null || assignment.getId() == null) continue;
            excludedFrom.merge(assignment.getId(), exclusion.getCreatedAt(),
                    (a, b) -> a == null || (b != null && b.isBefore(a)) ? b : a);
        }

        var filesByPlaylist = new java.util.HashMap<Long, Set<Long>>();
        var candidates = new java.util.ArrayList<Candidate>(assignments.size());
        for (var assignment : assignments) {
            var playlist = assignment.getPlaylist();
            if (playlist == null) continue;
            var fileIds = filesByPlaylist.computeIfAbsent(playlist.getId(), this::contentFileIdsOf);
            var excluded = excludedFrom.get(assignment.getId());
            var end = assignment.effectiveEnd();
            candidates.add(new Candidate(assignment,
                    excluded != null && excluded.isBefore(end) ? excluded : end,
                    fileIds));
        }
        return new DeviceHistory(List.copyOf(candidates), maxClockSkew, assignmentGrace);
    }

    private Set<Long> contentFileIdsOf(Long playlistId) {
        return playlistItemRepository.findByPlaylistIdOrderByPositionAsc(playlistId).stream()
                .map(PlaylistItem::getContentFile)
                .filter(Objects::nonNull)
                .map(ContentFile::getId)
                .collect(Collectors.toSet());
    }

    /**
     * One assignment this device could have been playing, with the instant it stopped applying
     * <em>to this device</em> — its end, its deletion, or the exclusion that took the device off
     * it, whichever came first.
     */
    private record Candidate(ContentAssignment assignment, Instant appliedUntil, Set<Long> fileIds) {

        /** Live for this device at some instant in {@code [from, to]}. */
        boolean appliedDuring(Instant from, Instant to, Duration skew) {
            return assignment.wasLiveDuring(from, to, skew) && appliedUntil.isAfter(from);
        }
    }

    /**
     * The batch's view of what this device was playing, and the rule that attributes one play.
     *
     * <p>Why not simply re-resolve at {@code playedAt}: the live resolution path filters
     * {@code deletedAt IS NULL} and ignores when an exclusion was written, so a play from a
     * campaign that has since been cancelled, replaced or narrowed would find nothing and be
     * thrown away — which is the undercount VG-03 is about.
     */
    private record DeviceHistory(List<Candidate> candidates, Duration skew, Duration grace) {

        Attribution attribute(Long contentFileId, Instant playedAt) {
            if (candidates.isEmpty()) {
                // Nothing ever targeted this device in that span, so there is nothing to check
                // the play against. Keep it, unattributed — the pre-VG-03 behaviour.
                return Attribution.unknown();
            }

            // What the device SHOULD have been playing: the winner among the campaigns live at
            // that instant, by the same precedence the device itself resolves with.
            var winner = candidates.stream()
                    .filter(c -> c.appliedDuring(playedAt, playedAt, skew))
                    .max(java.util.Comparator.comparing(Candidate::assignment, ContentAssignment.PRECEDENCE));
            if (winner.isPresent() && winner.get().fileIds().contains(contentFileId)) {
                return Attribution.of(winner.get().assignment());
            }

            // Otherwise the device may still have been finishing the previous campaign: it keeps
            // playing the old content until it has downloaded the new one and reached the shared
            // cut-over. Credit the campaign that ended most recently and actually held this clip.
            var fallback = candidates.stream()
                    .filter(c -> c.fileIds().contains(contentFileId))
                    .filter(c -> c.appliedDuring(playedAt.minus(grace), playedAt, skew))
                    .max(java.util.Comparator.comparing(Candidate::appliedUntil)
                            .thenComparing(Candidate::assignment, ContentAssignment.PRECEDENCE));
            return fallback.map(c -> Attribution.of(c.assignment())).orElseGet(Attribution::refused);
        }
    }

    /**
     * The outcome for one entry: which campaign to credit, or a refusal. An accepted play with a
     * null assignment is "kept but unattributed" — the device had no campaign we can check.
     */
    private record Attribution(ContentAssignment assignment, boolean rejected) {

        static Attribution of(ContentAssignment assignment) { return new Attribution(assignment, false); }

        static Attribution unknown() { return new Attribution(null, false); }

        static Attribution refused() { return new Attribution(null, true); }
    }

    public record PlaybackEntry(Long contentFileId, Instant playedAt, Integer durationSeconds) {}

    public record EntryRejection(int index, String reason) {}

    public record BatchRecordResult(int created, int duplicate, int rejected,
                                     List<EntryRejection> rejections) {}

    @Transactional(readOnly = true)
    public List<PlaybackLog> getByDevice(Long deviceId, Instant from, Instant to) {
        return repository.findByDeviceIdAndPlayedAtBetweenOrderByPlayedAtDesc(deviceId, from, to);
    }

    @Transactional(readOnly = true)
    public List<PlaybackLog> getByContentFile(Long contentFileId) {
        return repository.findByContentFileIdOrderByPlayedAtDesc(contentFileId);
    }

    public sealed interface PlaybackLogResult {

        record Created() implements PlaybackLogResult {}
        record Duplicate() implements PlaybackLogResult {}
        record Rejected(String reason) implements PlaybackLogResult {}

        static PlaybackLogResult created() { return new Created(); }
        static PlaybackLogResult duplicate() { return new Duplicate(); }
        static PlaybackLogResult rejected(String reason) { return new Rejected(reason); }
    }
}
