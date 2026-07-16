package uz.orientadvertise.services.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.SyncGroup;
import uz.orientadvertise.services.service.SyncGroupManagementService.SyncGroupDetailView;

/**
 * Detail projection for {@code GET /api/sync-groups/{id}} and the create / rename responses.
 * Strict superset of {@link SyncGroupSummary}: adds the active member device list as
 * {@link SyncGroupMember} entries (whose status field is named {@code status}, not
 * {@code computedStatus} — see {@link SyncGroupMember}).
 *
 * <p>{@code deviceCount} is derived from {@code devices.size()} — the detail path already
 * returns the member list, so the count stays authoritative without a separate query.
 */
public record SyncGroupDetail(
        Long id,
        Long projectId,
        String projectName,
        String name,
        long deviceCount,
        Instant createdAt,
        List<SyncGroupMember> devices
) {
    public static SyncGroupDetail from(SyncGroupDetailView view,
                                       Map<Long, Device.Status> computedStatuses) {
        SyncGroup g = view.group();
        var devices = view.devices().stream()
                .map(d -> SyncGroupMember.from(d, computedStatuses.get(d.getId())))
                .toList();
        return new SyncGroupDetail(
                g.getId(),
                g.getProject() != null ? g.getProject().getId() : null,
                g.getProject() != null ? g.getProject().getName() : null,
                g.getName(),
                devices.size(),
                g.getCreatedAt(),
                devices);
    }
}
