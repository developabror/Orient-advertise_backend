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
import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.FacilityRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Lifecycle + read paths for {@link Region}.
 *
 * <p>Regions are <b>not</b> soft-deleted — only devices carry a {@code deletedAt}.
 * Deleting a region is a hard delete, gated by three independent guards described in
 * {@link #delete}.
 *
 * <p>Validation rules:
 * <ul>
 *   <li>Page size capped at {@value #MAX_PAGE_SIZE} (mapped to 400).</li>
 *   <li>Duplicate {@code (project_id, code)} on create or recode → 409. The DB UNIQUE
 *       constraint {@code uq_region_code_per_project} catches this too, but the
 *       app-level pre-check turns the error into a clean message.</li>
 *   <li>Non-null but blank {@code code} or {@code name} on update → 400.</li>
 * </ul>
 */
@Service
public class RegionManagementService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(RegionManagementService.class);

    private final RegionRepository regionRepository;
    private final ProjectRepository projectRepository;
    private final FacilityRepository facilityRepository;
    private final DeviceRepository deviceRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public RegionManagementService(RegionRepository regionRepository,
                                     ProjectRepository projectRepository,
                                     FacilityRepository facilityRepository,
                                     DeviceRepository deviceRepository,
                                     OperatorScopeResolver operatorScopeResolver) {
        this.regionRepository = regionRepository;
        this.projectRepository = projectRepository;
        this.facilityRepository = facilityRepository;
        this.deviceRepository = deviceRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /** Service-layer view: a region plus computed counts for the listing. */
    public record RegionView(Region region, long facilityCount, long deviceCount) {}

    /**
     * Detail variant: includes the public-field facility list. The list is flat —
     * operators clicking through to a facility hit the dedicated detail endpoint.
     * Device groups are no longer region-scoped (they belong to the project) and are
     * surfaced on the project detail, not here.
     */
    public record RegionDetailView(Region region, long facilityCount, long deviceCount,
                                     List<Facility> facilities) {}

    @Transactional(readOnly = true)
    public Page<RegionView> list(Long projectId, String name, Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        ScopedProjects scope = operatorScopeResolver.resolve();
        if (scope.isEmptyScope()) {
            return Page.empty(pageable);                 // operator with zero projects ⇒ empty, repo not called
        }
        String normalizedName = (name == null || name.isBlank()) ? null : name.trim();
        Page<Region> page = regionRepository.findFiltered(projectId, normalizedName, scope.narrowingIds(), pageable);
        if (page.isEmpty()) {
            return page.map(r -> new RegionView(r, 0L, 0L));
        }
        // Per-region counts are computed lazily here. The list endpoint is pageable —
        // even at the 100-row cap, that's at most 200 lookups. A batched aggregate would
        // shave a round trip but isn't load-bearing at this scale; preserve the option
        // to introduce one if the listing ever moves into a hot path.
        Map<Long, Long> facilityCounts = new HashMap<>();
        Map<Long, Long> deviceCounts = new HashMap<>();
        for (Region r : page.getContent()) {
            facilityCounts.put(r.getId(), facilityRepository.countByRegionId(r.getId()));
            deviceCounts.put(r.getId(), deviceRepository.countByRegionIdAndDeletedAtIsNull(r.getId()));
        }
        return page.map(r -> new RegionView(r,
                facilityCounts.getOrDefault(r.getId(), 0L),
                deviceCounts.getOrDefault(r.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public RegionDetailView getDetail(Long id) {
        Region region = regionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Region", id));
        if (operatorScopeResolver.resolve().excludes(region.getProject().getId())) {
            throw new ResourceNotFoundException("Region", id);   // out-of-scope ⇒ 404, not 403
        }
        var facilities = facilityRepository.findByRegionId(id);
        long deviceCount = deviceRepository.countByRegionIdAndDeletedAtIsNull(id);
        return new RegionDetailView(region, facilities.size(), deviceCount, facilities);
    }

    @Transactional
    public RegionDetailView create(Long projectId, String code, String name) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        if (regionRepository.existsByProjectIdAndCode(projectId, code)) {
            throw new IllegalStateException(
                    "Region with code '" + code + "' already exists in project " + projectId);
        }
        Region saved = regionRepository.save(new Region(project, name, code));
        log.info("Created region [id={} project={} code='{}' name='{}']",
                saved.getId(), projectId, code, name);
        // Fresh region has no facilities, no devices — skip the count queries.
        return new RegionDetailView(saved, 0L, 0L, List.of());
    }

    /**
     * Rename and/or recode. Both fields are optional — null means "leave unchanged" so
     * the caller can submit a partial PATCH-style body. Whitespace-only values are
     * rejected with 400 because the entity's {@code code} and {@code name} are NOT NULL
     * at the DB level and a blank string would silently corrupt the row.
     *
     * <p>Recode is duplicate-checked against the same project, excluding self.
     */
    @Transactional
    public RegionDetailView update(Long id, String code, String name) {
        Region region = regionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Region", id));

        if (code != null) {
            if (code.isBlank()) {
                throw new IllegalArgumentException("Code must not be blank");
            }
            if (!code.equals(region.getCode())) {
                if (regionRepository.existsDuplicateCodeExcluding(
                        region.getProject().getId(), code, id)) {
                    throw new IllegalStateException(
                            "Region with code '" + code + "' already exists in project "
                                    + region.getProject().getId());
                }
                region.setCode(code);
            }
        }
        if (name != null) {
            if (name.isBlank()) {
                throw new IllegalArgumentException("Name must not be blank");
            }
            region.setName(name);
        }

        log.info("Updated region [id={} code='{}' name='{}']", id, region.getCode(), region.getName());
        return getDetail(id);
    }

    /**
     * Hard-delete a region. Two independent 409 guards run in order so the operator
     * sees one issue at a time and can address them sequentially:
     *
     * <ol>
     *   <li>Active devices ({@code device.region_id == this} and {@code deletedAt IS NULL}).
     *       The DB-level {@code ON DELETE RESTRICT} on {@code device.region_id} would
     *       block the delete anyway; the app-level guard surfaces this as a clean 409
     *       with the count rather than letting Hibernate's
     *       {@code DataIntegrityViolationException} bubble.</li>
     *   <li>Facilities. Facilities are not soft-deletable — the only way to clear them
     *       is to remove them, which the FE must do first.</li>
     * </ol>
     *
     * <p>There is no device-group guard: device groups now belong to the project, not the
     * region (V37), so deleting a region cannot orphan a group.
     *
     * <p>Note on soft-deleted devices: a soft-deleted device still carries
     * {@code region_id}, so the DB {@code RESTRICT} would still block the delete. The
     * app-level check here counts only ACTIVE devices, matching the user-visible
     * contract; if the DB nonetheless rejects, Hibernate's exception bubbles as 500 —
     * that's an unusual edge worth fixing only if it shows up in practice.
     */
    @Transactional
    public void delete(Long id) {
        Region region = regionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Region", id));

        long deviceCount = deviceRepository.countByRegionIdAndDeletedAtIsNull(id);
        if (deviceCount > 0) {
            throw new IllegalStateException(
                    "Region has " + deviceCount + " active device(s); reassign first");
        }

        long facilityCount = facilityRepository.countByRegionId(id);
        if (facilityCount > 0) {
            throw new IllegalStateException(
                    "Region has " + facilityCount + " facility(ies); remove first");
        }

        regionRepository.delete(region);
        log.info("Hard-deleted region [id={} code='{}' name='{}']",
                id, region.getCode(), region.getName());
    }
}
