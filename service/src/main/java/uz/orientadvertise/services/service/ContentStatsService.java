package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.AccessForbiddenException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.AdvertiserContentAccessRepository;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.OperatorContentAccessRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;

/**
 * Aggregated playback statistics for a content file.
 *
 * <p>Validation:
 * <ul>
 *   <li>{@code (to - from) <= 90 days}; missing bounds default to "last 7 days"</li>
 *   <li>{@code from <= to}</li>
 *   <li>Page size capped at {@value #MAX_PAGE_SIZE}</li>
 *   <li>If caller is an ADVERTISER, must have an explicit grant for this content file
 *       (404 if no grant — don't leak existence)</li>
 * </ul>
 *
 * <p>Edge case: if the requested {@code (to - from)} window exceeds
 * {@value #TIMESTAMP_RANGE_LIMIT_DAYS} days, individual timestamps are omitted from the
 * response — only counts are returned. Pulling 90 days of timestamps for a popular
 * content file would mean tens of thousands of rows; counts answer the typical
 * "how often did this play" question without that cost.
 */
@Service
public class ContentStatsService {

    public static final int MAX_RANGE_DAYS = 90;
    public static final int TIMESTAMP_RANGE_LIMIT_DAYS = 30;
    public static final int MAX_PAGE_SIZE = 100;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    private final ContentFileRepository contentFileRepository;
    private final PlaybackLogRepository playbackLogRepository;
    private final AdvertiserContentAccessRepository advertiserAccessRepository;
    private final OperatorContentAccessRepository operatorAccessRepository;
    private final AppUserRepository userRepository;
    private final DeviceRepository deviceRepository;

    public ContentStatsService(ContentFileRepository contentFileRepository,
                                PlaybackLogRepository playbackLogRepository,
                                AdvertiserContentAccessRepository advertiserAccessRepository,
                                OperatorContentAccessRepository operatorAccessRepository,
                                AppUserRepository userRepository,
                                DeviceRepository deviceRepository) {
        this.contentFileRepository = contentFileRepository;
        this.playbackLogRepository = playbackLogRepository;
        this.advertiserAccessRepository = advertiserAccessRepository;
        this.operatorAccessRepository = operatorAccessRepository;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
    }

    @Transactional(readOnly = true)
    public ContentStats getStats(Long contentFileId, Long deviceId,
                                  Instant from, Instant to, Pageable pageable,
                                  String callerUsername, boolean callerIsAdvertiser,
                                  boolean callerIsOperator, Collection<Long> operatorProjectIds) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size cannot exceed " + MAX_PAGE_SIZE);
        }

        ContentFile content = contentFileRepository.findById(contentFileId)
                .filter(f -> f.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ContentFile", contentFileId));

        // Advertiser scoping: must have an explicit access grant for this content. We
        // surface "no access" as 403 (not 404) only AFTER confirming the content exists,
        // so admins still get 404 for truly missing ids while advertisers see "forbidden"
        // for content that exists but isn't theirs.
        if (callerIsAdvertiser) {
            var user = userRepository.findByUsername(callerUsername)
                    .orElseThrow(() -> new AccessForbiddenException("Unknown advertiser"));
            boolean hasAccess = advertiserAccessRepository.existsByUserIdAndContentFileId(
                    user.getId(), contentFileId);
            if (!hasAccess) {
                throw new AccessForbiddenException(
                        "Advertiser does not have access to content " + contentFileId);
            }
        } else if (callerIsOperator) {
            // Operator content gate: visible iff owned OR admin-granted. A real-but-inaccessible
            // row throws 404 (NOT 403) — operators get no existence oracle (per-role asymmetry).
            boolean owned = callerUsername != null && callerUsername.equals(content.getUploadedBy());
            boolean granted = false;
            if (!owned) {
                var user = userRepository.findByUsername(callerUsername).orElse(null);
                granted = user != null
                        && operatorAccessRepository.existsByUserIdAndContentFileId(user.getId(), contentFileId);
            }
            if (!owned && !granted) {
                throw new ResourceNotFoundException("ContentFile", contentFileId);
            }
        }

        Instant resolvedTo = to != null ? to : Instant.now();
        Instant resolvedFrom = from != null ? from : resolvedTo.minus(DEFAULT_WINDOW);

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("from must be before to");
        }
        long days = Duration.between(resolvedFrom, resolvedTo).toDays();
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Date range cannot exceed " + MAX_RANGE_DAYS + " days");
        }
        boolean timestampsIncluded = days <= TIMESTAMP_RANGE_LIMIT_DAYS;

        // Operator device-dimension intersection: even for a cross-project granted file, the
        // per-device rows / supplied deviceId are constrained to the operator's project devices
        // so foreign deviceNames don't leak. totalPlayCount is summed from the VISIBLE rows —
        // never the unscoped scalar — so the total can't leak foreign-project plays either.
        if (callerIsOperator) {
            boolean emptyScope = operatorProjectIds == null || operatorProjectIds.isEmpty();
            if (deviceId != null
                    && (emptyScope || !deviceRepository.existsByIdAndProjectIdIn(deviceId, operatorProjectIds))) {
                throw new ResourceNotFoundException("Device", deviceId);   // out-of-scope deviceId → 404
            }
            if (emptyScope) {
                return new ContentStats(content.getId(), content.getName(), resolvedFrom, resolvedTo,
                        0L, List.of(), timestampsIncluded, new PageImpl<>(List.of(), pageable, 0L));
            }
            List<DeviceCount> perDevice = playbackLogRepository.countPerDeviceForContentScoped(
                            contentFileId, deviceId, resolvedFrom, resolvedTo, operatorProjectIds).stream()
                    .map(row -> new DeviceCount(
                            (Long) row[0], (String) row[1], ((Number) row[2]).longValue()))
                    .toList();
            long totalPlayCount = perDevice.stream().mapToLong(DeviceCount::playCount).sum();
            Page<Instant> timestamps = timestampsIncluded
                    ? playbackLogRepository.findTimestampsForContentScoped(
                            contentFileId, deviceId, resolvedFrom, resolvedTo, operatorProjectIds, pageable)
                    : new PageImpl<>(List.of(), pageable, totalPlayCount);
            return new ContentStats(content.getId(), content.getName(), resolvedFrom, resolvedTo,
                    totalPlayCount, perDevice, timestampsIncluded, timestamps);
        }

        long totalPlayCount = playbackLogRepository.countByContentInRange(
                contentFileId, deviceId, resolvedFrom, resolvedTo);

        List<DeviceCount> perDevice = playbackLogRepository.countPerDeviceForContent(
                        contentFileId, deviceId, resolvedFrom, resolvedTo).stream()
                .map(row -> new DeviceCount(
                        (Long) row[0],
                        (String) row[1],
                        ((Number) row[2]).longValue()))
                .toList();

        // Edge case: range > 30 days → counts only, no individual timestamps. Skips the
        // (potentially huge) row scan and signals to the caller via timestampsIncluded=false.
        Page<Instant> timestamps = timestampsIncluded
                ? playbackLogRepository.findTimestampsForContent(
                        contentFileId, deviceId, resolvedFrom, resolvedTo, pageable)
                : new PageImpl<>(List.of(), pageable, totalPlayCount);

        return new ContentStats(
                content.getId(),
                content.getName(),
                resolvedFrom,
                resolvedTo,
                totalPlayCount,
                perDevice,
                timestampsIncluded,
                timestamps);
    }

    public record DeviceCount(Long deviceId, String deviceName, long playCount) {}

    public record ContentStats(Long contentFileId, String contentFileName,
                                Instant from, Instant to,
                                long totalPlayCount,
                                List<DeviceCount> perDevice,
                                boolean timestampsIncluded,
                                Page<Instant> timestamps) {}
}
