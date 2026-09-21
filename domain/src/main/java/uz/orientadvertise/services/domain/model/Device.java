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
import org.hibernate.annotations.DynamicUpdate;

/**
 * {@link DynamicUpdate}: a device row has many concurrent writers (heartbeat, /sync, operator
 * edits, the re-registration window). Writing only the columns a transaction actually changed
 * keeps one writer's full-row snapshot from silently reverting another's disjoint column — e.g.
 * a heartbeat in flight while an admin opens a re-registration window.
 */
@Entity
@Table(name = "device")
@DynamicUpdate
public class Device {

    public enum Status { ONLINE, OFFLINE, NO_CONTENT, UNREGISTERED }

    /** Column widths for the remote-capability block — mirror V43. */
    private static final int REMOTE_INPUT_MAX = 16;
    private static final int REMOTE_TRANSPORT_MAX = 24;
    /** Upper sanity bound for a reported screen dimension (8K wide); anything above is nonsense. */
    private static final int REMOTE_DIMENSION_MAX = 7680;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_group_id")
    private SyncGroup syncGroup;

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

    /** AUTH-02: until when an ADMIN allows this serial to re-register (V46). Null = refused. */
    @Column(name = "reregistration_allowed_until")
    private Instant reregistrationAllowedUntil;

    @Column(name = "desired_volume")
    private Integer desiredVolume;

    @Column(name = "reported_volume")
    private Integer reportedVolume;

    @Column(name = "volume_reported_at")
    private Instant volumeReportedAt;

    // --- Device-reported remote view/control capability (refreshed on every heartbeat).
    // NULL everywhere = never reported. Capability is REPORTED, not assumed: it lets the
    // operator UI degrade to view-only with no contract change if input injection turns out
    // not to work on a box. See recordRemoteCapability(...). ---

    @Column(name = "remote_supported")
    private Boolean remoteSupported;

    @Column(name = "remote_input", length = REMOTE_INPUT_MAX)
    private String remoteInput;

    @Column(name = "remote_transport", length = REMOTE_TRANSPORT_MAX)
    private String remoteTransport;

    @Column(name = "remote_max_width")
    private Integer remoteMaxWidth;

    @Column(name = "remote_max_height")
    private Integer remoteMaxHeight;

    @Column(name = "remote_caps_at")
    private Instant remoteCapsAt;

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

    /** The explicit {@link SyncGroup} this device was placed in (its "sales point"), or {@code null}. */
    public SyncGroup getSyncGroup() { return syncGroup; }
    public void setSyncGroup(SyncGroup syncGroup) { this.syncGroup = syncGroup; this.updatedAt = Instant.now(); }

    /**
     * The synchronized-playback group this device belongs to (§1.1): explicit sync group, else
     * facility, else device group, else region — prefixed so the axes never collide
     * ({@code "sg-9"} / {@code "fac-42"} / {@code "grp-7"} / {@code "reg-3"}). The {@code sg-}
     * tier sits at the TOP: an operator-assigned {@link SyncGroup} (the "sales point") overrides
     * the derived fallbacks; removing the device from its sync group reverts the wire label to the
     * next non-null fallback (never solo unless the device has no region). Devices sharing this id
     * are one playback group; only those that ALSO resolve the same content version end up
     * frame-aligned, so this is a coordination label, not the schedule key. The value is opaque to
     * the device (ANDROID_DEVICE_FLOW_SPEC §5.2) and re-read every heartbeat. Returns {@code null}
     * only when the device has no region at all — the device then free-runs solo. Derived, not
     * stored; must be read inside a transaction because the grouping associations are {@code LAZY}.
     */
    public String getSyncGroupId() {
        if (syncGroup != null && syncGroup.getId() != null) return "sg-" + syncGroup.getId();
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

    public Boolean getRemoteSupported() { return remoteSupported; }
    public String getRemoteInput() { return remoteInput; }
    public String getRemoteTransport() { return remoteTransport; }
    public Integer getRemoteMaxWidth() { return remoteMaxWidth; }
    public Integer getRemoteMaxHeight() { return remoteMaxHeight; }
    public Instant getRemoteCapsAt() { return remoteCapsAt; }

    /**
     * Record the device's self-reported remote view/control capability from a heartbeat.
     *
     * <p>Every argument is optional and tolerated: a {@code null} field leaves the previously
     * known value untouched, exactly like {@link #recordReportedVolume(Integer)} — a beat that
     * omits part of the block must never wipe what we already learned. Strings are trimmed,
     * upper-cased and truncated to their column width; dimensions outside
     * {@code (0, 7680]} are dropped rather than persisted as nonsense. Nothing here can
     * throw, because a malformed capability block must never fail the heartbeat (the beat is a
     * liveness signal first — same rule as {@code volume}).
     *
     * <p>{@code at} is the observation instant; {@code null} means "now".
     */
    public void recordRemoteCapability(Boolean supported, String input, String transport,
                                        Integer maxWidth, Integer maxHeight, Instant at) {
        if (supported == null && input == null && transport == null
                && maxWidth == null && maxHeight == null) {
            return;   // nothing reported — not even a timestamp bump
        }
        if (supported != null) {
            this.remoteSupported = supported;
        }
        if (input != null) {
            this.remoteInput = normalizeToken(input, REMOTE_INPUT_MAX);
        }
        if (transport != null) {
            this.remoteTransport = normalizeToken(transport, REMOTE_TRANSPORT_MAX);
        }
        if (maxWidth != null) {
            this.remoteMaxWidth = sanitizeDimension(maxWidth);
        }
        if (maxHeight != null) {
            this.remoteMaxHeight = sanitizeDimension(maxHeight);
        }
        this.remoteCapsAt = at != null ? at : Instant.now();
        this.updatedAt = Instant.now();
    }

    private static String normalizeToken(String value, int maxLength) {
        var trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        var upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        return upper.length() <= maxLength ? upper : upper.substring(0, maxLength);
    }

    private static Integer sanitizeDimension(Integer value) {
        return (value <= 0 || value > REMOTE_DIMENSION_MAX) ? null : value;
    }

    public String getLastKnownIp() { return lastKnownIp; }
    public void setLastKnownIp(String ip) {
        this.lastKnownIp = ip;
        this.updatedAt = Instant.now();
    }

    public Instant getReregistrationAllowedUntil() { return reregistrationAllowedUntil; }

    /** Opens the admin re-registration window (AUTH-02); the next registration claims it. */
    public void allowReregistrationUntil(Instant until) {
        this.reregistrationAllowedUntil = until;
        this.updatedAt = Instant.now();
    }

    /** Closes any open re-registration window — the device proved it still holds its token. */
    public void closeReregistrationWindow() {
        this.reregistrationAllowedUntil = null;
    }

    public void register(String token) {
        this.deviceToken = token;
        this.registeredAt = Instant.now();
        // Any registration consumes the re-registration window (AUTH-02).
        this.reregistrationAllowedUntil = null;
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
