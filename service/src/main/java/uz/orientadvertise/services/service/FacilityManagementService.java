package uz.orientadvertise.services.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.FacilityRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Lifecycle + read paths for {@link Facility}.
 *
 * <p>Facilities sit at level 3 of the org tree and are <b>not</b> soft-deleted —
 * see the README's "Org Tree" section. Deletion is hard, gated by two independent
 * 409 guards described in {@link #delete}.
 *
 * <p>Cross-region moves are intentionally not supported by this service — see
 * {@link #rename} for the rationale.
 */
@Service
public class FacilityManagementService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(FacilityManagementService.class);

    private final FacilityRepository facilityRepository;
    private final RegionRepository regionRepository;
    private final DeviceRepository deviceRepository;
    private final ContentAssignmentRepository assignmentRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public FacilityManagementService(FacilityRepository facilityRepository,
                                       RegionRepository regionRepository,
                                       DeviceRepository deviceRepository,
                                       ContentAssignmentRepository assignmentRepository,
                                       OperatorScopeResolver operatorScopeResolver) {
        this.facilityRepository = facilityRepository;
        this.regionRepository = regionRepository;
        this.deviceRepository = deviceRepository;
        this.assignmentRepository = assignmentRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /** Service-layer view: a facility plus its computed device count. */
    public record FacilityView(Facility facility, long deviceCount) {}

    /** Detail variant: includes the active member device list. */
    public record FacilityDetailView(Facility facility, List<Device> devices) {}

    @Transactional(readOnly = true)
    public Page<FacilityView> list(Long regionId, String name, Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        ScopedProjects scope = operatorScopeResolver.resolve();
        if (scope.isEmptyScope()) {
            return Page.empty(pageable);
        }
        String normalizedName = (name == null || name.isBlank()) ? null : name.trim();
        Page<Facility> page = facilityRepository.findFiltered(regionId, normalizedName, scope.narrowingIds(), pageable);
        if (page.isEmpty()) {
            return page.map(f -> new FacilityView(f, 0L));
        }
        // Per-facility counts. Same scale rationale as the region listing — pageable cap
        // of 100 keeps this bounded; a batched aggregate would shave a round trip but
        // isn't load-bearing yet.
        Map<Long, Long> deviceCounts = new HashMap<>();
        for (Facility f : page.getContent()) {
            deviceCounts.put(f.getId(), deviceRepository.countByFacilityIdAndDeletedAtIsNull(f.getId()));
        }
        return page.map(f -> new FacilityView(f, deviceCounts.getOrDefault(f.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public FacilityDetailView getDetail(Long id) {
        // findByIdWithRegion (vs. plain findById) so the controller-side DTO mapping
        // can read region.getName() after the transaction closes — see the repository
        // method's javadoc for the open-in-view: false rationale.
        Facility facility = facilityRepository.findByIdWithRegion(id)
                .orElseThrow(() -> new ResourceNotFoundException("Facility", id));
        if (operatorScopeResolver.resolve().excludes(facility.getRegion().getProject().getId())) {
            throw new ResourceNotFoundException("Facility", id);
        }
        var devices = deviceRepository.findByFacilityIdAndDeletedAtIsNull(id);
        return new FacilityDetailView(facility, devices);
    }

    @Transactional
    public FacilityDetailView create(Long regionId, String name, String address) {
        Region region = regionRepository.findById(regionId)
                .orElseThrow(() -> new ResourceNotFoundException("Region", regionId));
        if (facilityRepository.existsByRegionIdAndName(regionId, name)) {
            throw new IllegalStateException(
                    "Facility with name '" + name + "' already exists in region " + regionId);
        }
        if (address != null && address.isBlank()) {
            throw new IllegalArgumentException("Address must not be blank");
        }
        Facility saved = facilityRepository.save(new Facility(region, name, address));
        log.info("Created facility [id={} region={} name='{}']", saved.getId(), regionId, name);
        return new FacilityDetailView(saved, List.of());
    }

    /**
     * PATCH-style update: rename and/or change address within the existing region. Both
     * fields are independently optional — null means "leave unchanged" so the caller can
     * submit a partial body. Whitespace-only values are rejected with 400; {@code name}
     * is NOT NULL at the DB level and {@code address}, while nullable, should never be
     * silently set to whitespace (callers that want to clear it should send the field
     * unset, not blank).
     *
     * <p><b>Cross-region moves are not supported through this method.</b> The
     * {@code regionId} is sourced from the existing row, never from the request — moving
     * a facility between regions touches device FKs and assignment targets, which are
     * out of scope for an update. Operators who need a cross-region move should
     * hard-delete the facility (after reassigning its devices) and create a new one in
     * the target region, preserving the audit trail of the move.
     */
    @Transactional
    public FacilityDetailView update(Long id, String name, String address) {
        Facility facility = facilityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Facility", id));

        if (name != null) {
            if (name.isBlank()) {
                throw new IllegalArgumentException("Name must not be blank");
            }
            if (!name.equals(facility.getName())) {
                Long regionId = facility.getRegion().getId();
                if (facilityRepository.existsDuplicateExcluding(regionId, name, id)) {
                    throw new IllegalStateException(
                            "Facility with name '" + name + "' already exists in region " + regionId);
                }
                facility.setName(name);
            }
        }
        if (address != null) {
            if (address.isBlank()) {
                throw new IllegalArgumentException("Address must not be blank");
            }
            facility.setAddress(address);
        }

        log.info("Updated facility [id={} name='{}' address='{}']",
                id, facility.getName(), facility.getAddress());
        return getDetail(id);
    }

    /**
     * Hard-delete a facility. Two independent 409 guards run in fixed order:
     *
     * <ol>
     *   <li>Active devices ({@code device.facility_id == this} and
     *       {@code device.deletedAt IS NULL}). Devices may legitimately have a null
     *       {@code facility_id} (the FK is nullable), so reassigning to "no facility"
     *       is the typical operator workflow before delete.</li>
     *   <li>CONFIRMED, non-soft-deleted {@code ContentAssignment}s targeting this
     *       facility. Same logic as the device-group delete guard.</li>
     * </ol>
     */
    @Transactional
    public void delete(Long id) {
        Facility facility = facilityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Facility", id));

        long activeDevices = deviceRepository.countByFacilityIdAndDeletedAtIsNull(id);
        if (activeDevices > 0) {
            throw new IllegalStateException(
                    "Facility has " + activeDevices + " active device(s); reassign first");
        }

        long activeAssignments = assignmentRepository.countConfirmedAssignmentsForFacility(id);
        if (activeAssignments > 0) {
            throw new IllegalStateException(
                    "Facility is targeted by " + activeAssignments + " assignment(s)");
        }

        facilityRepository.delete(facility);
        log.info("Hard-deleted facility [id={} name='{}']", id, facility.getName());
    }
}
