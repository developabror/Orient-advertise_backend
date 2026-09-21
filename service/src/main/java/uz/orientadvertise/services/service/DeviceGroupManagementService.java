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
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Lifecycle + read paths for {@link DeviceGroup}.
 *
 * <p>Validation rules:
 * <ul>
 *   <li>Page size capped at {@value #MAX_PAGE_SIZE} (mapped to 400).</li>
 *   <li>Duplicate {@code (project_id, name)} on create or rename → 409. The DB UNIQUE
 *       constraint catches this too, but the app-level pre-check turns the error into
 *       a clean message.</li>
 *   <li>Soft-deleted rows are invisible to every read path.</li>
 *   <li>Delete is refused with 409 when active devices are still attached <i>or</i> a
 *       CONFIRMED assignment targets the group — see {@link #softDelete}.</li>
 * </ul>
 */
@Service
public class DeviceGroupManagementService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(DeviceGroupManagementService.class);

    private final DeviceGroupRepository groupRepository;
    private final DeviceRepository deviceRepository;
    private final ProjectRepository projectRepository;
    private final ContentAssignmentRepository assignmentRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public DeviceGroupManagementService(DeviceGroupRepository groupRepository,
                                          DeviceRepository deviceRepository,
                                          ProjectRepository projectRepository,
                                          ContentAssignmentRepository assignmentRepository,
                                          OperatorScopeResolver operatorScopeResolver) {
        this.groupRepository = groupRepository;
        this.deviceRepository = deviceRepository;
        this.projectRepository = projectRepository;
        this.assignmentRepository = assignmentRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /** Service-layer view: a group plus its computed device count. */
    public record DeviceGroupView(DeviceGroup group, long deviceCount) {}

    /** Detail variant: includes the active member device list. */
    public record DeviceGroupDetailView(DeviceGroup group, List<Device> devices) {}

    @Transactional(readOnly = true)
    public Page<DeviceGroupView> list(Long projectId, String name, Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        ScopedProjects scope = operatorScopeResolver.resolve();
        if (scope.isEmptyScope()) {
            return Page.empty(pageable);
        }
        String normalizedName = (name == null || name.isBlank()) ? null : name.trim();

        Page<DeviceGroup> page = groupRepository.findFiltered(projectId, normalizedName, scope.narrowingIds(), pageable);
        if (page.isEmpty()) {
            return page.map(g -> new DeviceGroupView(g, 0L));
        }
        Map<Long, Long> counts = countsFor(page.getContent().stream().map(DeviceGroup::getId).toList());
        return page.map(g -> new DeviceGroupView(g, counts.getOrDefault(g.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public DeviceGroupDetailView getDetail(Long id) {
        // findByIdAndDeletedAtIsNullWithProject (vs. plain findByIdAndDeletedAtIsNull)
        // so the controller-side DTO mapping can read project.getName() after the
        // transaction closes — see the repository method's javadoc for the
        // open-in-view: false rationale.
        var group = groupRepository.findByIdAndDeletedAtIsNullWithProject(id)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceGroup", id));
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("DeviceGroup", id);
        }
        var devices = deviceRepository.findByDeviceGroupIdAndDeletedAtIsNull(id);
        return new DeviceGroupDetailView(group, devices);
    }

    @Transactional
    public DeviceGroupDetailView create(Long projectId, String name) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        // Operator-scope guard (AUTHZ-02): a restricted operator may not create a device group in a
        // project outside their assigned set. Collapses to the same 404 as a missing Project so
        // out-of-scope ids leak nothing, and runs BEFORE the duplicate check so a 409 can't
        // reveal which names exist in another tenant's project. Mirrors SyncGroup create.
        if (operatorScopeResolver.resolve().excludes(projectId)) {
            throw new ResourceNotFoundException("Project", projectId);
        }
        if (groupRepository.existsByProjectIdAndNameAndDeletedAtIsNull(projectId, name)) {
            throw new IllegalStateException(
                    "Device group with name '" + name + "' already exists in project " + projectId);
        }
        DeviceGroup saved = groupRepository.save(new DeviceGroup(project, name, null));
        log.info("Created device group [id={} project={} name='{}']", saved.getId(), projectId, name);
        return new DeviceGroupDetailView(saved, List.of());
    }

    @Transactional
    public DeviceGroupDetailView rename(Long id, String name) {
        DeviceGroup group = groupRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceGroup", id));
        if (group.getName().equals(name)) {
            // No-op rename: skip the duplicate guard and DB write entirely. Returns the
            // same detail the caller just sent us.
            return getDetail(id);
        }
        if (groupRepository.existsDuplicateExcluding(group.getProject().getId(), name, id)) {
            throw new IllegalStateException(
                    "Device group with name '" + name + "' already exists in project "
                            + group.getProject().getId());
        }
        group.setName(name);
        log.info("Renamed device group [id={} newName='{}']", id, name);
        return getDetail(id);
    }

    /**
     * Soft-delete a device group.
     *
     * <p>Two independent guards refuse the delete with 409:
     * <ul>
     *   <li>Active devices still reference the group ({@code device.deviceGroup.id == this}
     *       and {@code device.deletedAt is null}). Deleting under that condition would
     *       orphan the FK, so the operator must reassign or remove the devices first.</li>
     *   <li>A CONFIRMED, non-soft-deleted {@code ContentAssignment} targets this group as
     *       a {@code DEVICE_GROUP}. Deleting under that condition would leave the
     *       assignment pointing at a phantom target.</li>
     * </ul>
     *
     * <p>Both checks fire even if the group has zero active devices but a CONFIRMED
     * assignment, or vice versa — neither alone is sufficient to safely remove the row.
     * The first failing guard returns; the operator addresses one issue at a time.
     */
    @Transactional
    public void softDelete(Long id) {
        DeviceGroup group = groupRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceGroup", id));

        long activeDevices = deviceRepository.countByDeviceGroupIdAndDeletedAtIsNull(id);
        if (activeDevices > 0) {
            throw new IllegalStateException(
                    "Group has " + activeDevices + " active device(s); reassign first");
        }

        long activeAssignments = assignmentRepository.countConfirmedAssignmentsForDeviceGroup(id);
        if (activeAssignments > 0) {
            throw new IllegalStateException(
                    "Group is targeted by " + activeAssignments + " assignment(s)");
        }

        group.softDelete();
        log.info("Soft-deleted device group [id={} name='{}']", id, group.getName());
    }

    /** Result of an {@link #addDevices} call. */
    public record AddDevicesResult(int addedCount, List<Long> alreadyMember,
                                     Map<Long, Long> movedFrom) {}

    /**
     * Add devices to a group. The whole batch is one transaction — if any device fails
     * validation the transaction rolls back and no membership change is observable.
     *
     * <p>A group now belongs to a project and may span multiple regions within that
     * project, so devices from <i>any</i> region of the group's project are accepted.
     *
     * <p>Validation:
     * <ul>
     *   <li>Every supplied id must resolve to a non-soft-deleted device. Missing or
     *       soft-deleted ids → 404 with the offending list named in the message.</li>
     *   <li>Every device must already live in the same project as the target group (i.e.
     *       its region's project). Cross-project membership is intentionally not
     *       auto-fixed — moving a device across projects is a separate operation that
     *       touches FKs operators may need to audit. Cross-project offenders → 400.</li>
     * </ul>
     *
     * <p>Outcome buckets per device:
     * <ul>
     *   <li><b>added</b> — was unassigned or in a different group; now in this group.
     *       Counted in {@code addedCount}.</li>
     *   <li><b>alreadyMember</b> — was already in this group; no-op for that row.</li>
     *   <li><b>movedFrom</b> — was in a different group before this call. The mapping
     *       {@code deviceId → previousGroupId} is returned so the admin UI / audit log
     *       can record the silent move.</li>
     * </ul>
     */
    @Transactional
    public AddDevicesResult addDevices(Long groupId, List<Long> deviceIds) {
        DeviceGroup group = groupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceGroup", groupId));
        // Operator-scope guard: a restricted operator may not mutate a group outside their
        // assigned projects. Mirror getDetail — out-of-scope collapses to 404, not 403.
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("DeviceGroup", groupId);
        }

        // De-duplicate the input — the caller may submit a noisy set; we only act once
        // per id and keep the response symmetric with what was actually requested.
        List<Long> distinctIds = deviceIds.stream().distinct().toList();
        List<Device> found = deviceRepository.findAllByIdInAndDeletedAtIsNull(distinctIds);
        Set<Long> foundIds = found.stream().map(Device::getId).collect(Collectors.toSet());

        List<Long> missing = distinctIds.stream()
                .filter(id -> !foundIds.contains(id))
                .toList();
        if (!missing.isEmpty()) {
            // Reusing ResourceNotFoundException with the list as "id" renders as
            // "Devices not found with id: [101, 102]" — the spec only requires the ids
            // be visible in the message, which the default formatter satisfies.
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
            DeviceGroup current = d.getDeviceGroup();
            if (current != null && current.getId().equals(groupId)) {
                alreadyMember.add(d.getId());
                continue;
            }
            if (current != null) {
                // Silent move — the spec explicitly allows POST to overwrite when the
                // device was in a different group, but we record the previous id so an
                // audit trail can reconstruct the move.
                movedFrom.put(d.getId(), current.getId());
            }
            d.setDeviceGroup(group);
            addedCount++;
        }
        log.info("Added devices to group [groupId={} added={} alreadyMember={} moved={}]",
                groupId, addedCount, alreadyMember.size(), movedFrom.size());
        return new AddDevicesResult(addedCount, alreadyMember, movedFrom);
    }

    /**
     * Remove a single device from this group. The {@code (groupId, deviceId)} pair must
     * match the device's current membership — passing the wrong group id collapses to a
     * 404, never a 400, so the API doesn't leak the device's actual group.
     *
     * <p>Removing the last device is allowed: empty groups remain valid targets for
     * assignment preview/setup.
     */
    @Transactional
    public void removeDevice(Long groupId, Long deviceId) {
        DeviceGroup group = groupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceGroup", groupId));
        // Operator-scope guard (mirrors addDevices/getDetail): out-of-scope ⇒ 404.
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("DeviceGroup", groupId);
        }

        Device device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        DeviceGroup current = device.getDeviceGroup();
        if (current == null || !current.getId().equals(groupId)) {
            throw new ResourceNotFoundException("Device", deviceId);
        }
        device.setDeviceGroup(null);
        log.info("Removed device from group [groupId={} deviceId={}]", groupId, deviceId);
    }

    /**
     * Apply a volume to the whole group: it becomes the single source of truth for every member.
     *
     * <p>Setting the group volume also <b>clears every active member's per-device override</b>
     * ({@code desiredVolume → null}) so they all inherit the new value via
     * {@link uz.orientadvertise.services.domain.model.DeviceVolumeResolver}. This is the "override
     * all" semantic: even devices an operator set manually snap back to the group volume on their
     * next heartbeat. (Without the clear, a per-device override would keep winning and the apply
     * would silently do nothing for that device.) Operator-scope guarded: a group outside the
     * caller's projects collapses to 404 (consistent with getDetail).
     */
    @Transactional
    public void setVolume(Long groupId, Integer volume) {
        DeviceGroup group = groupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceGroup", groupId));
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("DeviceGroup", groupId);
        }
        group.setVolume(volume);
        int cleared = deviceRepository.bulkClearDesiredVolumeByGroup(groupId, Instant.now());
        log.info("Set device group volume [groupId={} volume={} clearedOverrides={}]",
                groupId, volume, cleared);
    }

    /**
     * Clear the group's volume — its members fall back to their per-device override or the
     * default on the next heartbeat. Operator-scope guarded (out-of-scope ⇒ 404).
     */
    @Transactional
    public void clearVolume(Long groupId) {
        DeviceGroup group = groupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceGroup", groupId));
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("DeviceGroup", groupId);
        }
        group.setVolume(null);
        log.info("Cleared device group volume [groupId={}]", groupId);
    }

    private Map<Long, Long> countsFor(List<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        var rows = deviceRepository.countActiveDevicesPerGroup(groupIds);
        var out = new HashMap<Long, Long>();
        for (Object[] row : rows) {
            out.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }
}
