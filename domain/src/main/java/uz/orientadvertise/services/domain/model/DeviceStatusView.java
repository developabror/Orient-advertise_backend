package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import java.time.Instant;

@Entity
@Immutable
@Table(name = "device_status_view")
public class DeviceStatusView {

    @Id
    private Long id;

    @Column(name = "region_id")
    private Long regionId;

    @Column(name = "facility_id")
    private Long facilityId;

    @Column(name = "facility_name")
    private String facilityName;

    @Column(name = "device_group_id")
    private Long deviceGroupId;

    @Column(name = "sync_group_id")
    private Long syncGroupId;

    @Column(name = "serial_number")
    private String serialNumber;

    private String name;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "computed_status")
    private Device.Status computedStatus;

    // Currently-resolved active playlist (derived in the view, never stored). Null when the
    // device has no active playlist now. activePlaylistName is never null when the id is set
    // (content_assignment.playlist_id is FK NOT NULL). Projection-only — not sortable.
    @Column(name = "active_playlist_id")
    private Long activePlaylistId;

    @Column(name = "active_playlist_name")
    private String activePlaylistName;

    protected DeviceStatusView() {
    }

    public Long getId() { return id; }
    public Long getRegionId() { return regionId; }
    public Long getFacilityId() { return facilityId; }
    public String getFacilityName() { return facilityName; }
    public Long getDeviceGroupId() { return deviceGroupId; }
    public Long getSyncGroupId() { return syncGroupId; }
    public String getSerialNumber() { return serialNumber; }
    public String getName() { return name; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Device.Status getComputedStatus() { return computedStatus; }
    public Long getActivePlaylistId() { return activePlaylistId; }
    public String getActivePlaylistName() { return activePlaylistName; }
}
