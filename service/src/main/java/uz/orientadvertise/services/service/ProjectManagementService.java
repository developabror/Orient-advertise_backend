package uz.orientadvertise.services.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Lifecycle + read paths for {@link Project} — the root of the org tree.
 *
 * <p>Projects are a small set in practice (single-digit to low-double-digit), so the
 * list endpoint returns the full set unpaginated. Mutations are ADMIN-only;
 * see the README's "Org Tree" section for why projects are hard-deleted.
 *
 * <p>Validation:
 * <ul>
 *   <li>Duplicate name on create or rename → 409. The DB-level
 *       {@code uq_project_name} constraint (V30 migration) catches this too, but the
 *       app-level pre-check turns the error into a clean message.</li>
 *   <li>Delete refuses with 409 when any region references the project — see
 *       {@link #delete}.</li>
 * </ul>
 */
@Service
public class ProjectManagementService {

    private static final Logger log = LoggerFactory.getLogger(ProjectManagementService.class);

    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final DeviceGroupRepository deviceGroupRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public ProjectManagementService(ProjectRepository projectRepository,
                                      RegionRepository regionRepository,
                                      DeviceGroupRepository deviceGroupRepository,
                                      OperatorScopeResolver operatorScopeResolver) {
        this.projectRepository = projectRepository;
        this.regionRepository = regionRepository;
        this.deviceGroupRepository = deviceGroupRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /** Service-layer view: a project plus its computed region count. */
    public record ProjectView(Project project, long regionCount) {}

    /** Detail variant: includes the immediate child regions and the project's device groups. */
    public record ProjectDetailView(Project project, long regionCount, List<Region> regions,
                                      List<DeviceGroup> deviceGroups) {}

    @Transactional(readOnly = true)
    public List<ProjectView> list() {
        ScopedProjects scope = operatorScopeResolver.resolve();
        List<Project> projects;
        if (scope.restricted()) {
            if (scope.projectIds().isEmpty()) {
                return List.of();                       // operator with zero projects ⇒ empty
            }
            projects = projectRepository.findByIdInOrderByNameAsc(scope.projectIds());
        } else {
            projects = projectRepository.findAllByOrderByNameAsc();
        }
        if (projects.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = regionCountsByProjectId();
        return projects.stream()
                .map(p -> new ProjectView(p, counts.getOrDefault(p.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectDetailView getDetail(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", id));
        if (operatorScopeResolver.resolve().excludes(id)) {
            throw new ResourceNotFoundException("Project", id);   // out-of-scope ⇒ 404
        }
        var regions = regionRepository.findByProjectId(id);
        var groups = deviceGroupRepository.findByProjectIdAndDeletedAtIsNull(id);
        return new ProjectDetailView(project, regions.size(), regions, groups);
    }

    @Transactional
    public ProjectDetailView create(String name) {
        if (projectRepository.existsByName(name)) {
            throw new IllegalStateException("Project with name '" + name + "' already exists");
        }
        Project saved = projectRepository.save(new Project(name, null));
        log.info("Created project [id={} name='{}']", saved.getId(), name);
        return new ProjectDetailView(saved, 0L, List.of(), List.of());
    }

    @Transactional
    public ProjectDetailView rename(Long id, String name) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", id));
        if (project.getName().equals(name)) {
            // No-op — skip the duplicate guard and DB write entirely.
            return getDetail(id);
        }
        if (projectRepository.existsDuplicateNameExcluding(name, id)) {
            throw new IllegalStateException("Project with name '" + name + "' already exists");
        }
        project.setName(name);
        log.info("Renamed project [id={} newName='{}']", id, name);
        return getDetail(id);
    }

    /**
     * Hard-delete a project. Two sequential 409 guards, each surfaced cleanly instead of
     * letting a DB foreign-key violation bubble as a 500:
     * <ol>
     *   <li>The project must have zero regions ({@code Project has N region(s); remove first}).
     *       The region cascade would otherwise need to handle facilities and devices in their
     *       own delete order — too much policy for a single endpoint, so we push that to the
     *       operator.</li>
     *   <li>The project must have zero active device groups ({@code Project has N device
     *       group(s); remove first}). Since V37, device groups are a direct child of the project
     *       (FK {@code fk_device_group_project}, no cascade), so an attached group would
     *       otherwise make the {@code DELETE} fail at the DB. Counts active groups only,
     *       consistent with the region-delete-vs-soft-deleted-device pattern — a project whose
     *       sole groups are soft-deleted is the same rare DB-{@code RESTRICT} edge documented
     *       there.</li>
     * </ol>
     * Once a project is empty of both, removal is a literal {@code DELETE} against the table.
     */
    @Transactional
    public void delete(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", id));
        long regionCount = regionRepository.countByProjectId(id);
        if (regionCount > 0) {
            throw new IllegalStateException(
                    "Project has " + regionCount + " region(s); remove first");
        }
        long groupCount = deviceGroupRepository.findByProjectIdAndDeletedAtIsNull(id).size();
        if (groupCount > 0) {
            throw new IllegalStateException(
                    "Project has " + groupCount + " device group(s); remove first");
        }
        projectRepository.delete(project);
        log.info("Hard-deleted project [id={} name='{}']", id, project.getName());
    }

    private Map<Long, Long> regionCountsByProjectId() {
        var rows = regionRepository.countRegionsPerProject();
        var out = new HashMap<Long, Long>();
        for (Object[] row : rows) {
            out.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }
}
