package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "device")
public class Device {

    public enum Status { ONLINE, OFFLINE, NO_CONTENT, UNREGISTERED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "facility_id")
    private Facility facility;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_group_id")
    private DeviceGroup deviceGroup;

    @Column(nullable = false, length = 100)
    private String serialNumber;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant deletedAt;

    @Column(length = 200)
    private String deviceToken;

    @Column
    private Instant registeredAt;

    @Column
    private Instant lastHeartbeatAt;

    @Column(name = "current_content_version", length = 64)
    private String currentContentVersion;

    @Column(name = "sync_pending_version", length = 64)
    private String syncPendingVersion;

    @Column(name = "sync_pending_since")
    private Instant syncPendingSince;

    @Column(name = "content_mismatch_since")
    private Instant contentMismatchSince;

    @Column(name = "last_known_ip", length = 45)
    private String lastKnownIp;

    @Column(name = "desired_volume")
    private Integer desiredVolume;

    @Column(name = "reported_volume")
    private Integer reportedVolume;

    @Column(name = "volume_reported_at")
    private Instant volumeReportedAt;

    protected Device() {
    }

    public Device(Region region, Facility facility, String serialNumber, String name) {
        this.region = region;
        this.facility = facility;
        this.serialNumber = serialNumber;
        this.name = name;
        this.status = Status.UNREGISTERED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Region getRegion() { return region; }
    public void setRegion(Region region) { this.region = region; this.updatedAt = Instant.now(); }
    public Facility getFacility() { return facility; }
    public void setFacility(Facility facility) { this.facility = facility; this.updatedAt = Instant.now(); }
    public DeviceGroup getDeviceGroup() { return deviceGroup; }
    public void setDeviceGroup(DeviceGroup deviceGroup) { this.deviceGroup = deviceGroup; this.updatedAt = Instant.now(); }

    /**
     * The synchronized-playback group this device belongs to (§1.1): facility, else device group,
     * else region — prefixed so the axes never collide ({@code "fac-42"} / {@code "grp-7"} /
     * {@code "reg-3"}). Devices sharing this id are one playback group; only those that ALSO resolve
     * the same content version end up frame-aligned, so this is a coordination label, not the schedule
     * key. Returns {@code null} only when the device has no region at all — the device then free-runs
     * solo (today's behavior). Derived, not stored; must be read inside a transaction because the
     * grouping associations are {@code LAZY}. Operators relocate devices, so callers re-read it live.
     */
    public String getSyncGroupId() {
        if (facility != null && facility.getId() != null) return "fac-" + facility.getId();
        if (deviceGroup != null && deviceGroup.getId() != null) return "grp-" + deviceGroup.getId();
        if (region != null && region.getId() != null) return "reg-" + region.getId();
        return null;
    }
    public String getSerialNumber() { return serialNumber; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; this.updatedAt = Instant.now(); }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }

    public String getDeviceToken() { return deviceToken; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public boolean isRegistered() { return registeredAt != null && deviceToken != null; }

    public String getCurrentContentVersion() { return currentContentVersion; }
    public void setCurrentContentVersion(String version) {
        this.currentContentVersion = version;
        this.updatedAt = Instant.now();
    }

    public String getSyncPendingVersion() { return syncPendingVersion; }
    public Instant getSyncPendingSince() { return syncPendingSince; }

    /**
     * Mark a sync as in-flight. If a sync is already pending, only the target version is
     * updated — {@code syncPendingSince} is preserved so the 30-minute timeout is measured
     * from the FIRST sync plan, not refreshed on every re-sync call.
     */
    public void markSyncPending(String expectedVersion) {
        this.syncPendingVersion = expectedVersion;
        if (this.syncPendingSince == null) {
            this.syncPendingSince = Instant.now();
        }
        this.updatedAt = Instant.now();
    }

    public void clearSyncPending() {
        this.syncPendingVersion = null;
        this.syncPendingSince = null;
        this.updatedAt = Instant.now();
    }

    public Instant getContentMismatchSince() { return contentMismatchSince; }

    /**
     * Record whether the device's current content version matches the expected version.
     * When mismatched, anchors the start time on the first observation; subsequent
     * mismatched heartbeats leave the anchor untouched. When matched, clears the anchor.
     */
    public void recordContentMismatch(boolean mismatch) {
        if (mismatch) {
            if (this.contentMismatchSince == null) {
                this.contentMismatchSince = Instant.now();
            }
        } else {
            this.contentMismatchSince = null;
        }
        this.updatedAt = Instant.now();
    }

    public void recordHeartbeat() {
        this.lastHeartbeatAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Integer getDesiredVolume() { return desiredVolume; }

    /**
     * Set the per-device volume override (exposed by the API as {@code volumeOverride}).
     * {@code null} clears the override so the device inherits its group's volume — or the
     * default when the group has none. See {@link DeviceVolumeResolver}.
     */
    public void setDesiredVolume(Integer desiredVolume) {
        this.desiredVolume = desiredVolume;
        this.updatedAt = Instant.now();
    }

    public Integer getReportedVolume() { return reportedVolume; }
    public Instant getVolumeReportedAt() { return volumeReportedAt; }

    /**
     * Record the device's self-reported current volume from a heartbeat. No-op on {@code null}
     * — a beat that omits volume must not wipe the last known value. Mirrors
     * {@link #recordHeartbeat()} / {@link #recordContentMismatch(boolean)}.
     */
    public void recordReportedVolume(Integer volume) {
        if (volume == null) {
            return;
        }
        this.reportedVolume = volume;
        this.volumeReportedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getLastKnownIp() { return lastKnownIp; }
    public void setLastKnownIp(String ip) {
        this.lastKnownIp = ip;
        this.updatedAt = Instant.now();
    }

    public void register(String token) {
        this.deviceToken = token;
        this.registeredAt = Instant.now();
        // A (re-)registration means the device is starting fresh: on reinstall / data-clear
        // / factory-reset it keeps its serialNumber but loses its local content store. Drop
        // our stale record of its confirmed version (and any in-flight sync marker) so the WS
        // connect-replay staleness check (current != expected) fires and diagnostics reflect
        // reality; the next /sync/confirm repopulates currentContentVersion. For a brand-new
        // or never-synced device these are already null — a harmless no-op.
        this.currentContentVersion = null;
        this.syncPendingVersion = null;
        this.syncPendingSince = null;
        // Do NOT persist ONLINE here: registration never sets lastHeartbeatAt, so a
        // registered-but-never-heartbeated device would read as a phantom ONLINE. The
        // raw status column is non-authoritative — every read surface derives status
        // from the heartbeat (device_status_view / DeviceStatusEvaluator). The first
        // real heartbeat flips the column via DeviceHeartbeatService.deriveStatus.
        this.updatedAt = Instant.now();
    }

    public boolean isDeleted() { return deletedAt != null; }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
