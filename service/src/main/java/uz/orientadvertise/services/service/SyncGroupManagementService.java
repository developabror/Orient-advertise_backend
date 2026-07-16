package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.SyncGroup;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.SyncGroupRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Lifecycle + read paths for {@link SyncGroup} — the project-scoped "sales point" grouping.
 * Cloned from {@link DeviceGroupManagementService}, minus volume and minus the assignment
 * delete guard (a sync group can never be a {@code ContentAssignment} target).
 *
 * <p>Validation rules:
 * <ul>
 *   <li>Page size capped at {@value #MAX_PAGE_SIZE} (mapped to 400).</li>
 *   <li>Duplicate {@code (project_id, name)} on create or rename → 409 (app pre-check backed by
 *       the DB UNIQUE constraint).</li>
 *   <li>Delete is a HARD delete refused with 409 while any active member device remains.</li>
 *   <li>Operator scope (V35): out-of-scope always collapses to 404, never 403.</li>
 * </ul>
 */
@Service
public class SyncGroupManagementService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(SyncGroupManagementService.class);

    private final SyncGroupRepository groupRepository;
    private final DeviceRepository deviceRepository;
    private final ProjectRepository projectRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public SyncGroupManagementService(SyncGroupRepository groupRepository,
                                      DeviceRepository deviceRepository,
                                      ProjectRepository projectRepository,
                                      OperatorScopeResolver operatorScopeResolver) {
        this.groupRepository = groupRepository;
        this.deviceRepository = deviceRepository;
        this.projectRepository = projectRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /** Service-layer view: a group plus its computed active-device count. */
    public record SyncGroupView(SyncGroup group, long deviceCount) {}

    /** Detail variant: includes the active member device list. */
    public record SyncGroupDetailView(SyncGroup group, List<Device> devices) {}

    @Transactional(readOnly = true)
    public Page<SyncGroupView> list(Long projectId, String name, Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        ScopedProjects scope = operatorScopeResolver.resolve();
        if (scope.isEmptyScope()) {
            return Page.empty(pageable);
        }
        String normalizedName = (name == null || name.isBlank()) ? null : name.trim();

        Page<SyncGroup> page = groupRepository.findFiltered(projectId, normalizedName, scope.narrowingIds(), pageable);
        if (page.isEmpty()) {
            return page.map(g -> new SyncGroupView(g, 0L));
        }
        Map<Long, Long> counts = countsFor(page.getContent().stream().map(SyncGroup::getId).toList());
        return page.map(g -> new SyncGroupView(g, counts.getOrDefault(g.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public SyncGroupDetailView getDetail(Long id) {
        // findByIdWithProject eagerly fetches the project so the controller-side DTO can read
        // project.getName() after the transaction closes (open-in-view: false).
        var group = groupRepository.findByIdWithProject(id)
                .orElseThrow(() -> new ResourceNotFoundException("SyncGroup", id));
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("SyncGroup", id);
        }
        var devices = deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(id);
        return new SyncGroupDetailView(group, devices);
    }

    @Transactional
    public SyncGroupDetailView create(Long projectId, String name) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        // Operator-scope guard: a restricted operator may not create a group in a project
        // outside their assigned set. Collapse to the same "Project not found" 404 as a
        // genuinely-missing project so the API doesn't leak the existence of out-of-scope
        // projects. Mirrors rename/delete/addDevices/removeDevice — create is a mutation too.
        if (operatorScopeResolver.resolve().excludes(projectId)) {
            throw new ResourceNotFoundException("Project", projectId);
        }
        if (groupRepository.existsByProjectIdAndName(projectId, name)) {
            throw new IllegalStateException(
                    "Sync group with name '" + name + "' already exists in project " + projectId);
        }
        SyncGroup saved = groupRepository.save(new SyncGroup(project, name));
        log.info("Created sync group [id={} project={} name='{}']", saved.getId(), projectId, name);
        return new SyncGroupDetailView(saved, List.of());
    }

    @Transactional
    public SyncGroupDetailView rename(Long id, String name) {
        SyncGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SyncGroup", id));
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("SyncGroup", id);
        }
        if (group.getName().equals(name)) {
            // No-op rename: skip the duplicate guard and the DB write entirely.
            return getDetail(id);
        }
        if (groupRepository.existsDuplicateExcluding(group.getProject().getId(), name, id)) {
            throw new IllegalStateException(
                    "Sync group with name '" + name + "' already exists in project "
                            + group.getProject().getId());
        }
        group.setName(name);
        log.info("Renamed sync group [id={} newName='{}']", id, name);
        return getDetail(id);
    }

    /**
     * HARD-delete a sync group. Refused with 409 while any active member device still
     * references it — the operator must remove the members first. Sync groups are never
     * assignment targets and are not referenced by {@code playback_sync_schedule}, so once
     * the member check passes nothing dangles. Any already soft-deleted device that still
     * carries this group's FK is detached first ({@link DeviceRepository#bulkClearSyncGroup})
     * so the DELETE can't hit a foreign-key violation.
     */
    @Transactional
    public void delete(Long id) {
        SyncGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SyncGroup", id));
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("SyncGroup", id);
        }

        long activeDevices = deviceRepository.countBySyncGroupIdAndDeletedAtIsNull(id);
        if (activeDevices > 0) {
            throw new IllegalStateException(
                    "Sync group has " + activeDevices + " active device(s); remove them first");
        }

        deviceRepository.bulkClearSyncGroup(id, Instant.now());
        groupRepository.delete(group);
        log.info("Deleted sync group [id={} name='{}']", id, group.getName());
    }

    /** Result of an {@link #addDevices} call. */
    public record AddDevicesResult(int addedCount, List<Long> alreadyMember,
                                   Map<Long, Long> movedFrom) {}

    /**
     * Make the supplied devices the members of this sync group. One all-or-nothing
     * transaction; the input is de-duplicated. Move-not-reject: a device already in a
     * <i>different</i> sync group is silently moved (its previous sync-group id is reported
     * in {@code movedFrom}) — "make these the members" implies detaching from any prior sync
     * group.
     *
     * <p>Validation:
     * <ul>
     *   <li>Every id must resolve to a non-soft-deleted device — missing/soft-deleted → 404.</li>
     *   <li>Every device must live in the same project as the group (its region's project).
     *       A sync group spans the project's regions, so same-project cross-region devices ARE
     *       accepted; only cross-<i>project</i> devices are rejected with 400.</li>
     * </ul>
     */
    @Transactional
    public AddDevicesResult addDevices(Long groupId, List<Long> deviceIds) {
        SyncGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("SyncGroup", groupId));
        // Operator-scope guard: out-of-scope collapses to 404 (mirrors getDetail).
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("SyncGroup", groupId);
        }

        List<Long> distinctIds = deviceIds.stream().distinct().toList();
        List<Device> found = deviceRepository.findAllByIdInAndDeletedAtIsNull(distinctIds);
        Set<Long> foundIds = found.stream().map(Device::getId).collect(Collectors.toSet());

        List<Long> missing = distinctIds.stream()
                .filter(id -> !foundIds.contains(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new ResourceNotFoundException("Devices", missing);
        }

        Long groupProjectId = group.getProject() != null ? group.getProject().getId() : null;
        List<Long> crossProject = found.stream()
                .filter(d -> d.getRegion() == null
                        || d.getRegion().getProject() == null
                        || !d.getRegion().getProject().getId().equals(groupProjectId))
                .map(Device::getId)
                .toList();
        if (!crossProject.isEmpty()) {
            throw new IllegalArgumentException(
                    "Devices belong to a different project (move cross-project first): "
                            + crossProject);
        }

        var alreadyMember = new ArrayList<Long>();
        var movedFrom = new HashMap<Long, Long>();
        int addedCount = 0;
        for (Device d : found) {
            SyncGroup current = d.getSyncGroup();
            if (current != null && current.getId().equals(groupId)) {
                alreadyMember.add(d.getId());
                continue;
            }
            if (current != null) {
                // Silent move — record the previous sync-group id for audit.
                movedFrom.put(d.getId(), current.getId());
            }
            d.setSyncGroup(group);
            addedCount++;
        }
        log.info("Added devices to sync group [groupId={} added={} alreadyMember={} moved={}]",
                groupId, addedCount, alreadyMember.size(), movedFrom.size());
        return new AddDevicesResult(addedCount, alreadyMember, movedFrom);
    }

    /**
     * Remove a single device from this sync group (sets {@code device.syncGroup = null}; the
     * device returns to the project's unassigned pool, not deleted). The {@code (groupId,
     * deviceId)} pair must match the device's current membership — a wrong group id collapses
     * to 404 (never 400), so the API can't leak the device's actual sync group. Removing the
     * last member is allowed; empty sync groups stay valid.
     */
    @Transactional
    public void removeDevice(Long groupId, Long deviceId) {
        SyncGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("SyncGroup", groupId));
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("SyncGroup", groupId);
        }

        Device device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        SyncGroup current = device.getSyncGroup();
        if (current == null || !current.getId().equals(groupId)) {
            throw new ResourceNotFoundException("Device", deviceId);
        }
        device.setSyncGroup(null);
        log.info("Removed device from sync group [groupId={} deviceId={}]", groupId, deviceId);
    }

    private Map<Long, Long> countsFor(List<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        var rows = deviceRepository.countActiveDevicesPerSyncGroup(groupIds);
        var out = new HashMap<Long, Long>();
        for (Object[] row : rows) {
            out.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }
}
