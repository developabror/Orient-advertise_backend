package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;

@Service
public class RemoteActionService {

    private static final Logger log = LoggerFactory.getLogger(RemoteActionService.class);

    public static final int MAX_HISTORY_RANGE_DAYS = 90;
    public static final int MAX_HISTORY_PAGE_SIZE = 100;
    private static final Duration DEFAULT_HISTORY_WINDOW = Duration.ofDays(30);

    private final RemoteActionRepository repository;
    private final DeviceRepository deviceRepository;

    public RemoteActionService(RemoteActionRepository repository,
                                 DeviceRepository deviceRepository) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
    }

    /**
     * Issue a remote action to a device.
     * Edge case: no duplicate PENDING actions of the same type per device.
     * If a PENDING action of this type already exists, reject with IllegalStateException.
     */
    @Transactional
    public RemoteAction issue(Device device, String actionType, String payload, String issuedBy) {
        return issue(device, actionType, payload, issuedBy, RemoteAction.DEFAULT_TIMEOUT);
    }

    @Transactional
    public RemoteAction issue(Device device, String actionType, String payload, String issuedBy,
                               Duration timeout) {
        var pendingDuplicates = repository.findPendingByDeviceAndType(device.getId(), actionType);

        if (!pendingDuplicates.isEmpty()) {
            throw new IllegalStateException(
                    "A PENDING '%s' action already exists for device %d (id=%d)".formatted(
                            actionType, device.getId(), pendingDuplicates.getFirst().getId()));
        }

        var action = new RemoteAction(device, actionType, payload, issuedBy, timeout);
        var saved = repository.save(action);
        log.info("Issued remote action [id={}, device={}, type={}, expires={}]",
                saved.getId(), device.getId(), actionType, saved.getExpiresAt());
        return saved;
    }

    /**
     * Device confirms it has executed the action.
     */
    @Transactional
    public RemoteAction confirm(Long actionId, String resultPayload) {
        var action = repository.findById(actionId)
                .orElseThrow(() -> new ResourceNotFoundException("RemoteAction", actionId));

        if (action.isExpired()) {
            action.markExpired();
            throw new IllegalStateException("Action %d has expired and cannot be confirmed".formatted(actionId));
        }

        if (action.getStatus() != RemoteAction.Status.PENDING) {
            throw new IllegalStateException("Action %d is not PENDING (current: %s)".formatted(
                    actionId, action.getStatus()));
        }

        action.confirm(resultPayload);
        log.info("Confirmed remote action [id={}, device={}]", actionId, action.getDevice().getId());
        return action;
    }

    /**
     * Expire all PENDING actions past their deadline.
     * Designed to be called periodically (e.g., by a scheduled task).
     */
    @Transactional
    public int expireStale() {
        var expired = repository.findExpired(Instant.now());
        for (var action : expired) {
            action.markExpired();
            log.info("Expired remote action [id={}, device={}, type={}]",
                    action.getId(), action.getDevice().getId(), action.getActionType());
        }
        return expired.size();
    }

    @Transactional(readOnly = true)
    public List<RemoteAction> getByDevice(Long deviceId) {
        return repository.findByDeviceIdOrderByIssuedAtDesc(deviceId);
    }

    @Transactional(readOnly = true)
    public List<RemoteAction> getPendingByDevice(Long deviceId) {
        return repository.findPendingByDevice(deviceId);
    }

    /**
     * Operator-facing action history for {@code GET /api/devices/{id}/actions}. Distinct
     * from {@link #getPendingByDevice}: that one is for the device itself (only PENDING,
     * permitAll), this one is for human operators reviewing what's been issued and how
     * it landed.
     *
     * <p>Validation:
     * <ul>
     *   <li>Unknown {@code deviceId} → {@link ResourceNotFoundException} (404). The
     *       check fires <i>before</i> any range/page validation; a caller probing an
     *       unknown id should not learn that it is unknown only after defeating the
     *       page-size cap.</li>
     *   <li>Page size capped at {@value #MAX_HISTORY_PAGE_SIZE} → 400.</li>
     *   <li>{@code (to - from)} must be ≤ {@value #MAX_HISTORY_RANGE_DAYS} days; missing
     *       bounds default to "trailing 30 days ending at now". Larger windows could
     *       mean huge result sets even with paging.</li>
     *   <li>{@code from > to} → 400. Silently swapping would mask a caller bug.</li>
     * </ul>
     *
     * <p>Sort is forced to {@code issuedAt DESC} regardless of any caller-supplied
     * {@code ?sort=…}. The operator console depends on most-recent-first ordering;
     * letting callers reverse it would invalidate the contract.
     */
    @Transactional(readOnly = true)
    public Page<RemoteAction> getHistory(Long deviceId, RemoteAction.Status status,
                                          String actionType, Instant from, Instant to,
                                          Pageable pageable) {
        deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        if (pageable.getPageSize() > MAX_HISTORY_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size cannot exceed " + MAX_HISTORY_PAGE_SIZE);
        }

        Instant resolvedTo = to != null ? to : Instant.now();
        Instant resolvedFrom = from != null ? from : resolvedTo.minus(DEFAULT_HISTORY_WINDOW);

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(resolvedFrom, resolvedTo).toDays() > MAX_HISTORY_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "Date range cannot exceed " + MAX_HISTORY_RANGE_DAYS + " days");
        }

        // Override any caller-supplied sort. PageRequest preserves page number and size
        // from the inbound Pageable; only the Sort is replaced.
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "issuedAt"));

        return repository.findHistory(deviceId, status, actionType,
                resolvedFrom, resolvedTo, sorted);
    }

    /**
     * Device-side confirmation. Resilient to:
     * <ul>
     *   <li><b>Unknown action id</b> — log and return {@link DeviceConfirmOutcome#UNKNOWN}.
     *       Callers (the controller) translate this to 200 with {@code status: UNKNOWN} so
     *       a confused/restarted device can retry confirmations without crashing on 404.</li>
     *   <li><b>Late confirmation</b> — if the action is past its deadline (already EXPIRED,
     *       or PENDING-but-deadline-passed) and the device reports SUCCESS, the action
     *       transitions to CONFIRMED_LATE rather than throwing. FAILED takes precedence
     *       and is recorded as FAILED regardless of timing.</li>
     *   <li><b>Wrong device</b> — if the action does not belong to the calling device,
     *       treat as UNKNOWN (don't leak existence).</li>
     *   <li><b>Already finalized</b> — duplicate confirms log a warning and return the
     *       current state without mutating.</li>
     * </ul>
     */
    @Transactional
    public DeviceConfirmResult processDeviceConfirmation(Long deviceId, Long actionId,
                                                          DeviceConfirmStatus reportedStatus,
                                                          String result) {
        var actionOpt = repository.findById(actionId);
        if (actionOpt.isEmpty()) {
            log.warn("Confirm for unknown action [device={}, actionId={}]", deviceId, actionId);
            return new DeviceConfirmResult(actionId, DeviceConfirmOutcome.UNKNOWN, null);
        }
        var action = actionOpt.get();
        if (!action.getDevice().getId().equals(deviceId)) {
            log.warn("Confirm for action {} not owned by device {} (actual device {})",
                    actionId, deviceId, action.getDevice().getId());
            return new DeviceConfirmResult(actionId, DeviceConfirmOutcome.UNKNOWN, null);
        }

        var current = action.getStatus();
        if (current == RemoteAction.Status.CONFIRMED
                || current == RemoteAction.Status.CONFIRMED_LATE
                || current == RemoteAction.Status.FAILED) {
            log.warn("Confirm for already-finalized action [id={}, current={}]", actionId, current);
            return new DeviceConfirmResult(actionId, DeviceConfirmOutcome.ALREADY_FINALIZED, current);
        }

        boolean pastDeadline = current == RemoteAction.Status.EXPIRED
                || (current == RemoteAction.Status.PENDING && action.isExpired());

        if (reportedStatus == DeviceConfirmStatus.FAILED) {
            action.markFailed(result);
            log.info("Action FAILED [id={}, device={}]", actionId, deviceId);
            return new DeviceConfirmResult(actionId, DeviceConfirmOutcome.FAILED,
                    RemoteAction.Status.FAILED);
        }

        if (pastDeadline) {
            action.confirmLate(result);
            log.info("Action CONFIRMED_LATE [id={}, device={}]", actionId, deviceId);
            return new DeviceConfirmResult(actionId, DeviceConfirmOutcome.CONFIRMED_LATE,
                    RemoteAction.Status.CONFIRMED_LATE);
        }

        action.confirm(result);
        log.info("Action CONFIRMED [id={}, device={}]", actionId, deviceId);
        return new DeviceConfirmResult(actionId, DeviceConfirmOutcome.CONFIRMED,
                RemoteAction.Status.CONFIRMED);
    }

    public enum DeviceConfirmStatus { SUCCESS, FAILED }

    public enum DeviceConfirmOutcome { CONFIRMED, CONFIRMED_LATE, FAILED, UNKNOWN, ALREADY_FINALIZED }

    public record DeviceConfirmResult(Long actionId, DeviceConfirmOutcome outcome,
                                       RemoteAction.Status finalStatus) {}
}
