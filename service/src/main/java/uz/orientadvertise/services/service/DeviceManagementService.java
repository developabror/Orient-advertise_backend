package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceStatusView;
import uz.orientadvertise.services.domain.model.DeviceVolumeResolver;
import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.domain.model.Incident;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.FacilityRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

@Service
public class DeviceManagementService {

    private final DeviceRepository deviceRepository;
    private final DeviceStatusViewRepository statusViewRepository;
    private final RegionRepository regionRepository;
    private final FacilityRepository facilityRepository;
    private final IncidentRepository incidentRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public DeviceManagementService(DeviceRepository deviceRepository,
                                    DeviceStatusViewRepository statusViewRepository,
                                    RegionRepository regionRepository,
                                    FacilityRepository facilityRepository,
                                    IncidentRepository incidentRepository,
                                    OperatorScopeResolver operatorScopeResolver) {
        this.deviceRepository = deviceRepository;
        this.statusViewRepository = statusViewRepository;
        this.regionRepository = regionRepository;
        this.facilityRepository = facilityRepository;
        this.incidentRepository = incidentRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /** Guard a single device against the caller's operator scope — out-of-scope ⇒ 404. */
    private void assertInScope(Device device) {
        if (operatorScopeResolver.resolve().excludes(device.getRegion().getProject().getId())) {
            throw new ResourceNotFoundException("Device", device.getId());
        }
    }

    /**
     * Scope gate for the JWT device sub-resource endpoints (diagnostics, action-history,
     * issue-action, playlist-control) whose underlying services are shared with the
     * device-token path and so cannot carry the operator guard themselves. Unknown device
     * or out-of-scope ⇒ 404. Admins pass through (unrestricted).
     */
    @Transactional(readOnly = true)
    public void assertScopeForDevice(Long id) {
        var device = deviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
        assertInScope(device);
    }

    /**
     * Paginated, filterable list of devices.
     * Filter by computed status uses the DB view (precomputed for performance).
     * Soft-deleted devices are excluded from the view, so they never appear in lists.
     *
     * Search: serial / name / facilityName all support case-insensitive partial match.
     * Zero matches returns an empty page (never 404).
     *
     * <p>{@code hasActivePlaylist} (tri-state) filters on the device's resolved active playlist.
     * It is orthogonal to {@code unassigned} — there is deliberately NO mutual-exclusion guard
     * between them (a group-less device can still get a playlist via a REGION assignment).
     */
    @Transactional(readOnly = true)
    public Page<DeviceStatusView> list(Device.Status status, Long regionId, Long projectId,
                                        Long facilityId,
                                        Long deviceGroupId, Boolean unassigned,
                                        String serialContains, String nameContains,
                                        String facilityNameContains, Boolean hasActivePlaylist,
                                        Pageable pageable) {
        if (Boolean.TRUE.equals(unassigned) && deviceGroupId != null) {
            throw new IllegalArgumentException(
                    "unassigned=true is mutually exclusive with deviceGroupId");
        }
        ScopedProjects scope = operatorScopeResolver.resolve();
        if (scope.isEmptyScope()) {
            return Page.empty(pageable);
        }
        return statusViewRepository.findFiltered(status, regionId, projectId, facilityId, deviceGroupId,
                unassigned,
                trimmed(serialContains), trimmed(nameContains), trimmed(facilityNameContains),
                hasActivePlaylist,
                scope.narrowingIds(),
                pageable);
    }

    private String trimmed(String value) {
        if (value == null) return null;
        var t = value.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * The heartbeat-derived status for one device, read from {@code device_status_view}
     * (the same source the list endpoint uses, so every surface agrees). Returns null for an
     * unknown / soft-deleted device (the view filters {@code deleted_at IS NULL}).
     */
    @Transactional(readOnly = true)
    public Device.Status computedStatus(Long id) {
        return statusViewRepository.findById(id)
                .map(DeviceStatusView::getComputedStatus)
                .orElse(null);
    }

    /**
     * Heartbeat-derived statuses for a batch of device ids, keyed by id. Used by the
     * device-group and facility detail views to render members without a phantom ONLINE.
     * Ids absent from the view (soft-deleted / unknown) are simply omitted from the map.
     */
    @Transactional(readOnly = true)
    public Map<Long, Device.Status> computedStatuses(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return statusViewRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(DeviceStatusView::getId, DeviceStatusView::getComputedStatus));
    }

    /**
     * Resolved effective volume for one device (override ?? group volume ?? default). Computed
     * inside the transaction so the lazy {@code deviceGroup} / {@code group.volume} access is safe
     * under {@code open-in-view: false}. Mirrors {@link #computedStatus(Long)}.
     */
    @Transactional(readOnly = true)
    public int effectiveVolume(Long id) {
        var device = deviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
        return DeviceVolumeResolver.resolveEffectiveVolume(device);
    }

    /**
     * Resolved effective volumes for a batch of device ids, keyed by id — for the device-group /
     * facility member projections. Resolved inside the transaction (lazy group access is safe
     * here). Ids absent from the result (soft-deleted / unknown) are omitted; callers default to
     * {@link DeviceVolumeResolver#DEFAULT_VOLUME}. Mirrors {@link #computedStatuses(Collection)}.
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> effectiveVolumes(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return deviceRepository.findAllByIdInAndDeletedAtIsNull(ids).stream()
                .collect(Collectors.toMap(Device::getId, DeviceVolumeResolver::resolveEffectiveVolume));
    }

    /**
     * Get a device by ID — including soft-deleted ones for audit.
     * The list endpoint hides soft-deleted devices, but direct ID access remains for audit trails.
     */
    @Transactional(readOnly = true)
    public Device getById(Long id) {
        var device = deviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
        assertInScope(device);
        return device;
    }

    @Transactional
    public Device update(Long id, String name) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
        assertInScope(device);
        if (name != null) {
            device.setName(name);
        }
        return device;
    }

    /**
     * Soft delete only — no physical row removal.
     * Idempotent: deleting an already-deleted device throws ResourceNotFoundException
     * (since we filter by deletedAt IS NULL).
     *
     * <p>Reconciles incidents at the deletion boundary: a removed device is out of the
     * operational fleet, so its still-open incidents are auto-resolved by the system
     * resolver rather than left dangling (a ghost OPEN incident an operator can never act
     * on). This is the producer-side complement to the read-side {@code deletedAt IS NULL}
     * filters in {@link IncidentRepository}; together they ensure a deleted device never
     * surfaces in the open-incident list, dashboard counts, or live feed. Reuses
     * {@code findByDeviceIdOrderByUpdatedAtDesc} (deliberately unfiltered — the per-device
     * audit view) and resolves only the open ones, so already-resolved incidents stay sealed.
     */
    @Transactional
    public void softDelete(Long id) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
        device.softDelete();
        for (Incident incident : incidentRepository.findByDeviceIdOrderByUpdatedAtDesc(id)) {
            if (incident.isOpen()) {
                incident.resolve(Incident.SYSTEM_RESOLVER);
            }
        }
    }

    /**
     * Move a device into a different region (and optionally a facility within that region).
     *
     * <p>Validation order — cheapest checks first, and the device-group cross-project guard
     * runs LAST so a caller fixing a typo in the URL gets the obvious 404 before the
     * stateful 409.
     * <ul>
     *   <li>404 — device, region, or (non-null) facility missing or soft-deleted.</li>
     *   <li>400 — {@code facilityId} is non-null but its region differs from {@code regionId}.
     *       The org tree forbids cross-region facilities, so this would otherwise produce
     *       an inconsistent device row.</li>
     *   <li>409 — the device currently belongs to a {@code DeviceGroup} whose project differs
     *       from the new region's project. Device groups are project-scoped (they may span
     *       regions within a project): a same-project cross-region move SUCCEEDS, but moving
     *       to a region in a different project would break the group invariant. Operator must
     *       clear the membership first via {@code DELETE /api/device-groups/{gid}/devices/{id}}.</li>
     * </ul>
     */
    @Transactional
    public Device setLocation(Long deviceId, Long regionId, Long facilityId) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
        assertInScope(device);

        var region = regionRepository.findById(regionId)
                .orElseThrow(() -> new ResourceNotFoundException("Region", regionId));

        Facility facility = null;
        if (facilityId != null) {
            facility = facilityRepository.findById(facilityId)
                    .orElseThrow(() -> new ResourceNotFoundException("Facility", facilityId));
            if (!facility.getRegion().getId().equals(regionId)) {
                throw new IllegalArgumentException(
                        "Facility " + facilityId + " is not in region " + regionId);
            }
        }

        var currentGroup = device.getDeviceGroup();
        if (currentGroup != null
                && !currentGroup.getProject().getId().equals(region.getProject().getId())) {
            throw new IllegalStateException(
                    "Device " + deviceId + " is in device group " + currentGroup.getId()
                    + " from a different project; remove from the group before relocating");
        }

        device.setRegion(region);
        device.setFacility(facility);
        return device;
    }

    /**
     * Set the per-device volume override. The device picks up the new target on its next
     * heartbeat (persistent desired state). Out-of-scope ⇒ 404. The value is range-validated
     * (0-100) at the controller boundary via Bean Validation.
     */
    @Transactional
    public void setVolume(Long id, Integer volume) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
        assertInScope(device);
        device.setDesiredVolume(volume);
    }

    /**
     * Clear the per-device override so the device inherits its group's volume (or the default
     * when the group has none). Out-of-scope ⇒ 404.
     */
    @Transactional
    public void clearVolume(Long id) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
        assertInScope(device);
        device.setDesiredVolume(null);
    }

    /**
     * "Apply to all" — set the per-device override on every non-soft-deleted device in the
     * caller's operator scope. An ADMIN (unrestricted) hits every device; a restricted operator
     * touches only devices in their assigned projects; an operator with zero projects affects
     * nothing (returns 0). Done as a single bulk {@code @Modifying} update to avoid loading every
     * device. Returns the number of devices updated.
     */
    @Transactional
    public int setVolumeForAll(Integer volume) {
        ScopedProjects scope = operatorScopeResolver.resolve();
        if (scope.isEmptyScope()) {
            return 0;
        }
        return deviceRepository.bulkSetDesiredVolume(volume, Instant.now(), scope.narrowingIds());
    }
}
