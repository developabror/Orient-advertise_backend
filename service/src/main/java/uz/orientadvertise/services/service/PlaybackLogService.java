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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.PlaybackLog;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;

@Service
public class PlaybackLogService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackLogService.class);

    public static final Duration RETENTION_AGE = Duration.ofDays(90);
    public static final int MAX_BATCH_SIZE = 500;

    private final PlaybackLogRepository repository;
    private final DeviceRepository deviceRepository;
    private final ContentFileRepository contentFileRepository;
    private final ContentAssignmentService assignmentService;
    private final PlaylistItemRepository playlistItemRepository;
    private final Duration maxClockSkew;

    public PlaybackLogService(PlaybackLogRepository repository,
                              DeviceRepository deviceRepository,
                              ContentFileRepository contentFileRepository,
                              ContentAssignmentService assignmentService,
                              PlaylistItemRepository playlistItemRepository,
                              @Value("${app.playback.max-clock-skew-seconds:30}") int maxClockSkewSeconds) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.contentFileRepository = contentFileRepository;
        this.assignmentService = assignmentService;
        this.playlistItemRepository = playlistItemRepository;
        this.maxClockSkew = Duration.ofSeconds(maxClockSkewSeconds);
    }

    /**
     * Record a playback event.
     * Edge cases:
     * 1. played_at in the future beyond clock skew tolerance → rejected
     * 2. played_at older than 90 days → rejected (matches retention cleanup window)
     * 3. Duplicate (device_id, content_file_id, played_at) → silently ignored (idempotent)
     */
    @Transactional
    public PlaybackLogResult record(Device device, ContentFile contentFile,
                                     ContentAssignment assignment, Instant playedAt,
                                     Integer durationSeconds) {
        // Clock skew check: reject played_at more than maxClockSkew into the future
        var now = Instant.now();
        var maxAllowed = now.plus(maxClockSkew);
        if (playedAt.isAfter(maxAllowed)) {
            log.warn("Rejected playback log — played_at {} is {} seconds in the future (max allowed: {}s), device={}",
                    playedAt, Duration.between(now, playedAt).getSeconds(),
                    maxClockSkew.getSeconds(), device.getId());
            return PlaybackLogResult.rejected("played_at is in the future beyond clock skew tolerance (%ds)".formatted(
                    maxClockSkew.getSeconds()));
        }

        // Retention check: reject played_at older than 90 days. The nightly retention
        // job would purge the row anyway, and accepting it would muddy analytics —
        // there's no signal to be had from a playback report we'd delete this week.
        var minAllowed = now.minus(RETENTION_AGE);
        if (playedAt.isBefore(minAllowed)) {
            log.warn("Rejected playback log — played_at {} is older than {} days, device={}",
                    playedAt, RETENTION_AGE.toDays(), device.getId());
            return PlaybackLogResult.rejected("played_at is older than the %d-day retention window".formatted(
                    RETENTION_AGE.toDays()));
        }

        // Dedup: try to save, catch unique constraint violation
        try {
            var entry = new PlaybackLog(device, contentFile, assignment, playedAt, durationSeconds);
            var saved = repository.save(entry);
            return PlaybackLogResult.created(saved);
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateViolation(e)) {
                log.debug("Duplicate playback log ignored [device={}, content={}, played_at={}]",
                        device.getId(), contentFile.getId(), playedAt);
                return PlaybackLogResult.duplicate();
            }
            throw e;
        }
    }

    /**
     * Batch-record playback events. Each entry is processed independently so a single
     * bad timestamp doesn't poison the rest. Result tallies created / duplicate / rejected.
     *
     * <p>Edge case: batch over {@value #MAX_BATCH_SIZE} entries → IllegalArgumentException
     * (mapped to 400 by the global handler). Forces the device to chunk submissions so
     * one giant POST can't consume excessive memory or transaction time.
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

        // Defense in depth (analytics-forgery): when the device has a resolved assignment,
        // only accept playback for content actually in its assigned playlist. If no
        // assignment resolves we can't validate, so we don't reject (pre-existing behavior).
        Set<Long> assignedFileIds = resolveAssignedContentFileIds(device);
        boolean enforceAssigned = !assignedFileIds.isEmpty();

        // Cache content files per request so a batch with 500 events for the same 5
        // playlist items doesn't issue 500 lookups.
        var fileCache = new java.util.HashMap<Long, ContentFile>();

        int created = 0;
        int duplicate = 0;
        int rejected = 0;
        var rejections = new java.util.ArrayList<EntryRejection>();

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

            if (enforceAssigned && !assignedFileIds.contains(entry.contentFileId())) {
                rejected++;
                rejections.add(new EntryRejection(i,
                        "contentFileId not assigned to device: " + entry.contentFileId()));
                continue;
            }

            var result = record(device, file, null, entry.playedAt(), entry.durationSeconds());
            if (result instanceof PlaybackLogResult.Created) created++;
            else if (result instanceof PlaybackLogResult.Duplicate) duplicate++;
            else if (result instanceof PlaybackLogResult.Rejected r) {
                rejected++;
                rejections.add(new EntryRejection(i, r.reason()));
            }
        }

        log.info("Playback batch [device={}, total={}, created={}, duplicate={}, rejected={}]",
                deviceId, entries.size(), created, duplicate, rejected);
        return new BatchRecordResult(created, duplicate, rejected, rejections);
    }

    /**
     * Content file ids in the device's currently-resolved assigned playlist, or an empty
     * set when no assignment resolves (caller then skips the assignment check).
     */
    private Set<Long> resolveAssignedContentFileIds(Device device) {
        var assignment = assignmentService.resolveForDevice(device, Instant.now());
        if (assignment == null || assignment.getPlaylist() == null) {
            return Set.of();
        }
        return playlistItemRepository
                .findByPlaylistIdOrderByPositionAsc(assignment.getPlaylist().getId()).stream()
                .map(PlaylistItem::getContentFile)
                .filter(Objects::nonNull)
                .map(ContentFile::getId)
                .collect(Collectors.toSet());
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

    private boolean isDuplicateViolation(DataIntegrityViolationException e) {
        var message = e.getMessage();
        return message != null && (message.contains("uq_playback_dedup")
                || message.contains("Unique index or primary key violation")
                || message.contains("duplicate key"));
    }

    public sealed interface PlaybackLogResult {

        record Created(PlaybackLog log) implements PlaybackLogResult {}
        record Duplicate() implements PlaybackLogResult {}
        record Rejected(String reason) implements PlaybackLogResult {}

        static PlaybackLogResult created(PlaybackLog log) { return new Created(log); }
        static PlaybackLogResult duplicate() { return new Duplicate(); }
        static PlaybackLogResult rejected(String reason) { return new Rejected(reason); }
    }
}
