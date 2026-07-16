package uz.orientadvertise.services.infra.migration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceStatusView;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test pinning the {@code device_status_view} active-playlist derivation (V34) to
 * {@link uz.orientadvertise.services.service.ContentAssignmentService#resolveForDevice} exactly,
 * and pinning the three in-SQL predicates (active_playlist_id, active_playlist_name,
 * computed_status) to agree on every row.
 *
 * <p>Runs on the real H2 view via Flyway (same harness as {@code DeviceStatusViewUnassignedFilterTest}).
 * Each fixture device lives in its OWN region so REGION-targeted assignments don't bleed across
 * devices; assertions key on the {@code SN-AP-*} serials so any seed rows are ignored. All devices
 * carry a fresh {@code last_heartbeat_at} so none are OFFLINE — making
 * {@code computed_status == NO_CONTENT} exactly equivalent to "no active playlist".
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class DeviceStatusViewActivePlaylistFilterTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DeviceStatusViewRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        try (Connection conn = dataSource.getConnection(); Statement s = conn.createStatement()) {
            s.execute("INSERT INTO project (id, name, created_at, updated_at) "
                    + "VALUES (300, 'APProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // One region per device — isolates REGION-targeted assignments to a single device.
            for (int r = 3001; r <= 3010; r++) {
                s.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                        + "VALUES (" + r + ", 300, 'R" + r + "', 'RC" + r + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
            s.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) "
                    + "VALUES (3007, 300 /* this is the PROJECT id */, 'G7', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // Facility 3010 (in region 3010) for the FACILITY-targeting case (D10).
            s.execute("INSERT INTO facility (id, region_id, name, created_at, updated_at) "
                    + "VALUES (3010, 3010, 'F10', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            playlist(s, 3101, "Region-D1");
            playlist(s, 3107, "Region-D7");
            playlist(s, 3108, "Group-D7");
            playlist(s, 3109, "Tie-Old");
            playlist(s, 3110, "Tie-New");
            playlist(s, 3111, "Facility-D10");
            playlist(s, 3199, "Hidden");   // referenced by non-winning assignments (FK NOT NULL)

            device(s, 3001, 3001, null, "SN-AP-1");
            device(s, 3002, 3002, null, "SN-AP-2");
            device(s, 3003, 3003, null, "SN-AP-3");
            device(s, 3004, 3004, null, "SN-AP-4");
            device(s, 3005, 3005, null, "SN-AP-5");
            device(s, 3006, 3006, null, "SN-AP-6");
            device(s, 3007, 3007, 3007L, "SN-AP-7");
            device(s, 3008, 3008, null, "SN-AP-8");
            device(s, 3009, 3009, null, "SN-AP-9");
            // D10: region-10 + facility-10, no group — exercises the FACILITY target branch.
            s.execute("INSERT INTO device (id, region_id, facility_id, serial_number, name, status, "
                    + "created_at, updated_at, last_heartbeat_at) VALUES "
                    + "(3010, 3010, 3010, 'SN-AP-10', 'SN-AP-10', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            // D1: CONFIRMED, active. start_time = now (inclusive-start boundary) → MATCHES.
            s.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, "
                    + "start_time, end_time, status, created_at, updated_at) VALUES "
                    + "(3201, 3101, 'REGION', 3001, 1, CURRENT_TIMESTAMP, "
                    + "CURRENT_TIMESTAMP + INTERVAL '1' HOUR, 'CONFIRMED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // D2: DRAFT only → not active (case a).
            assignment(s, 3202, 3199, "REGION", 3002, 1, "DRAFT", false);
            // D3: CANCELLED only → not active (case b).
            assignment(s, 3203, 3199, "REGION", 3003, 1, "CANCELLED", false);
            // D4: CONFIRMED + active but soft-deleted → not active (case c).
            assignment(s, 3204, 3199, "REGION", 3004, 1, "CONFIRMED", true);
            // D5: CONFIRMED + active, but the device is excluded → not active (case d).
            assignment(s, 3205, 3199, "REGION", 3005, 1, "CONFIRMED", false);
            s.execute("INSERT INTO content_assignment_exclusion (id, assignment_id, device_id, created_at) "
                    + "VALUES (3501, 3205, 3005, CURRENT_TIMESTAMP)");
            // D6: CONFIRMED but ended. end_time = now (exclusive-end boundary) → does NOT match (case e).
            s.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, "
                    + "start_time, end_time, status, created_at, updated_at) VALUES "
                    + "(3206, 3199, 'REGION', 3006, 1, CURRENT_TIMESTAMP - INTERVAL '1' HOUR, "
                    + "CURRENT_TIMESTAMP, 'CONFIRMED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // D7: REGION (priority 1) AND DEVICE_GROUP (priority 3) both match → group wins (case f).
            assignment(s, 3207, 3107, "REGION", 3007, 1, "CONFIRMED", false);
            assignment(s, 3208, 3108, "DEVICE_GROUP", 3007, 3, "CONFIRMED", false);
            // D8: two same-target (REGION 3008) same-priority CONFIRMED rows → id DESC wins (case g).
            assignment(s, 3209, 3109, "REGION", 3008, 1, "CONFIRMED", false);
            assignment(s, 3210, 3110, "REGION", 3008, 1, "CONFIRMED", false);
            // D10: CONFIRMED active FACILITY assignment (priority 2) → has "Facility-D10".
            assignment(s, 3211, 3111, "FACILITY", 3010, 2, "CONFIRMED", false);
            // D9: no assignment at all → no playlist.
        }
    }

    private static void playlist(Statement s, long id, String name) throws Exception {
        s.execute("INSERT INTO playlist (id, project_id, name, created_at, updated_at) "
                + "VALUES (" + id + ", 300, '" + name + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    private static void device(Statement s, long id, long regionId, Long groupId, String serial) throws Exception {
        if (groupId == null) {
            s.execute("INSERT INTO device (id, region_id, serial_number, name, status, "
                    + "created_at, updated_at, last_heartbeat_at) VALUES "
                    + "(" + id + ", " + regionId + ", '" + serial + "', '" + serial + "', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        } else {
            s.execute("INSERT INTO device (id, region_id, device_group_id, serial_number, name, status, "
                    + "created_at, updated_at, last_heartbeat_at) VALUES "
                    + "(" + id + ", " + regionId + ", " + groupId + ", '" + serial + "', '" + serial + "', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    /** Active window [now-1h, now+1h); {@code deleted} sets deleted_at = now. */
    private static void assignment(Statement s, long id, long playlistId, String targetType, long targetId,
                                   int priority, String status, boolean deleted) throws Exception {
        s.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, "
                + "start_time, end_time, status, created_at, updated_at" + (deleted ? ", deleted_at" : "") + ") VALUES "
                + "(" + id + ", " + playlistId + ", '" + targetType + "', " + targetId + ", " + priority + ", "
                + "CURRENT_TIMESTAMP - INTERVAL '1' HOUR, CURRENT_TIMESTAMP + INTERVAL '1' HOUR, '" + status + "', "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP" + (deleted ? ", CURRENT_TIMESTAMP" : "") + ")");
    }

    private Map<String, DeviceStatusView> myRows(Boolean hasActivePlaylist) {
        var page = repository.findFiltered(null, null, null, null, null, null, null, null, null,
                hasActivePlaylist, null /* syncUnassigned */, null /* projectIds */, PageRequest.of(0, 200));
        return page.getContent().stream()
                .filter(v -> v.getSerialNumber() != null && v.getSerialNumber().startsWith("SN-AP-"))
                .collect(Collectors.toMap(DeviceStatusView::getSerialNumber, Function.identity()));
    }

    @Test
    void hasActivePlaylistTrue_returnsOnlyDevicesWithResolvedPlaylist_withCorrectWinner() {
        var rows = myRows(Boolean.TRUE);

        assertEquals(java.util.Set.of("SN-AP-1", "SN-AP-7", "SN-AP-8", "SN-AP-10"), rows.keySet(),
                "TRUE bucket = devices with a resolved active playlist now");
        // id + name both populated, and name matches the winning playlist.
        rows.values().forEach(v -> {
            assertTrue(v.getActivePlaylistId() != null, "id set for " + v.getSerialNumber());
            assertTrue(v.getActivePlaylistName() != null, "name set for " + v.getSerialNumber());
        });
        assertEquals("Region-D1", rows.get("SN-AP-1").getActivePlaylistName());
        assertEquals("Group-D7", rows.get("SN-AP-7").getActivePlaylistName(),
                "DEVICE_GROUP (priority 3) wins over REGION (priority 1)");
        assertEquals("Tie-New", rows.get("SN-AP-8").getActivePlaylistName(),
                "same-target same-priority tie broken by id DESC → higher-id assignment wins");
        assertEquals("Facility-D10", rows.get("SN-AP-10").getActivePlaylistName(),
                "FACILITY-targeted assignment resolves the device's playlist");
    }

    @Test
    void hasActivePlaylistFalse_returnsTheComplement_idAndNameNull() {
        var rows = myRows(Boolean.FALSE);

        assertEquals(java.util.Set.of("SN-AP-2", "SN-AP-3", "SN-AP-4", "SN-AP-5", "SN-AP-6", "SN-AP-9"),
                rows.keySet(), "FALSE bucket = the 'needs content' devices");
        rows.values().forEach(v -> {
            assertNull(v.getActivePlaylistId(), "id null for " + v.getSerialNumber());
            assertNull(v.getActivePlaylistName(), "name null for " + v.getSerialNumber());
        });
    }

    @Test
    void hasActivePlaylistNull_returnsAll() {
        assertEquals(10, myRows(null).size(), "null filter = no constraint");
    }

    @Test
    void fidelity_eachResolveForDeviceRuleHonoured() {
        var all = myRows(null);
        // (a) DRAFT, (b) CANCELLED, (c) soft-deleted, (d) excluded, (e) ended → no playlist.
        assertNull(all.get("SN-AP-2").getActivePlaylistId(), "DRAFT match → no playlist");
        assertNull(all.get("SN-AP-3").getActivePlaylistId(), "CANCELLED → no playlist");
        assertNull(all.get("SN-AP-4").getActivePlaylistId(), "soft-deleted assignment → no playlist");
        assertNull(all.get("SN-AP-5").getActivePlaylistId(), "excluded device → no playlist despite target match");
        assertNull(all.get("SN-AP-6").getActivePlaylistId(), "ended (end_time <= now) → no playlist (half-open)");
        // inclusive start boundary + priority + tie-break + FACILITY already asserted in the TRUE test.
        assertEquals(3101L, all.get("SN-AP-1").getActivePlaylistId(), "start_time = now is inclusive → matches");
        assertEquals(3111L, all.get("SN-AP-10").getActivePlaylistId(), "FACILITY target resolves the playlist");
    }

    @Test
    void driftPin_allThreePredicatesAgree() {
        // For every fresh-heartbeat device: id and name are BOTH null or BOTH set, and
        // computed_status == NO_CONTENT exactly when there is no active playlist. (The exact
        // id→name correspondence is asserted by the named-playlist checks in the TRUE-bucket test.)
        myRows(null).values().forEach(v -> {
            boolean hasId = v.getActivePlaylistId() != null;
            assertEquals(hasId, v.getActivePlaylistName() != null,
                    "id/name must both be null or both set for " + v.getSerialNumber());
            assertEquals(hasId ? Device.Status.ONLINE : Device.Status.NO_CONTENT, v.getComputedStatus(),
                    "computed_status must agree with active-playlist presence for " + v.getSerialNumber());
        });
    }

    @Test
    void computedStatusRegression_draftCancelledExcluded_areNowNoContent() {
        var all = myRows(null);
        // Pre-V34 these were wrongly ONLINE (view ignored status='CONFIRMED' + exclusions).
        assertEquals(Device.Status.NO_CONTENT, all.get("SN-AP-2").getComputedStatus(), "DRAFT-only");
        assertEquals(Device.Status.NO_CONTENT, all.get("SN-AP-3").getComputedStatus(), "CANCELLED-only");
        assertEquals(Device.Status.NO_CONTENT, all.get("SN-AP-5").getComputedStatus(), "excluded");
    }
}
