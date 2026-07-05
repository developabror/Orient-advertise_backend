package uz.orientadvertise.services.service;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaybackSyncSchedule;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceSyncServiceTest {

    private DeviceRepository deviceRepository;
    private ContentAssignmentService assignmentService;
    private ContentVersionService contentVersionService;
    private PlaylistItemRepository playlistItemRepository;
    private FileStorageService fileStorageService;
    private PlaybackScheduleService playbackScheduleService;
    private DeviceSyncService syncService;

    @BeforeEach
    void setUp() throws Exception {
        deviceRepository = mock(DeviceRepository.class);
        assignmentService = mock(ContentAssignmentService.class);
        contentVersionService = mock(ContentVersionService.class);
        playlistItemRepository = mock(PlaylistItemRepository.class);
        fileStorageService = mock(FileStorageService.class);
        playbackScheduleService = mock(PlaybackScheduleService.class);
        syncService = new DeviceSyncService(deviceRepository, assignmentService,
                contentVersionService, playlistItemRepository, fileStorageService, playbackScheduleService);

        Field f = DeviceSyncService.class.getDeclaredField("presignedUrlExpiryMinutes");
        f.setAccessible(true);
        f.setInt(syncService, 120);

        // By default, all objects exist in MinIO. Individual tests override to simulate
        // storage inconsistency.
        when(fileStorageService.processedObjectExists(anyString())).thenReturn(true);
    }

    @Test
    void unknownDevice_throws404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> syncService.computeSyncPlan(999L, null, Set.of()));
    }

    @Test
    void nullVersion_returnsFullSync_withAllFilesToAdd() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(100L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile f1 = readyFile(10L, "key/10.mp4");
        ContentFile f2 = readyFile(11L, "key/11.mp4");
        PlaylistItem i1 = item(0, f1, 30);
        PlaylistItem i2 = item(1, f2, 20);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(100L))
                .thenReturn(List.of(i1, i2));
        when(fileStorageService.presignedProcessedUrl(eq("key/10.mp4"), anyInt())).thenReturn("https://minio/url10");
        when(fileStorageService.presignedProcessedUrl(eq("key/11.mp4"), anyInt())).thenReturn("https://minio/url11");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-1");

        // Even though device "claims" file 10 in currentFileIds, null version means fullSync —
        // we ignore the held set and return everything as files-to-add.
        var plan = syncService.computeSyncPlan(1L, null, Set.of(10L));

        assertTrue(plan.fullSync());
        assertEquals(2, plan.filesToAdd().size());
        assertEquals(0, plan.filesToDelete().size());
        assertEquals("v-1", plan.expectedContentVersion());
        assertEquals(120, plan.presignedUrlExpiryMinutes());
        assertEquals("https://minio/url10", plan.filesToAdd().get(0).presignedUrl());
        // /sync must serve the device the size + integrity checksum of the processed object so it
        // can do its free-space check and SHA-256 verification (regression guard for the fields
        // the transcoder/reconciler now populate).
        assertEquals(1024L, plan.filesToAdd().get(0).sizeBytes());
        assertEquals("sha-10", plan.filesToAdd().get(0).checksum());
        assertEquals(2, plan.playlistOrder().size());
        assertEquals(0, plan.playlistOrder().get(0).position());
        assertEquals(10L, plan.playlistOrder().get(0).fileId());
        // §2: a fully-READY-and-present playlist → order fileIds == delivered (filesToAdd) set.
        assertEquals(plan.filesToAdd().stream().map(DeviceSyncService.SyncFileToAdd::fileId).toList(),
                plan.playlistOrder().stream().map(DeviceSyncService.PlaylistEntry::fileId).toList());
    }

    /**
     * GUARDRAIL: an OFFLINE device with a stale heartbeat that polls /sync after
     * reconnect must receive its assigned content. The resolution path is
     * status-free by design (see {@code ContentAssignmentService.resolveForDevice})
     * — a future refactor that adds an online-only filter on either the resolver
     * or the sync planner would silently break the offline-rollout contract.
     * This test pins the behaviour at the sync-plan layer; the lower-level
     * guardrail lives in {@code ContentAssignmentServiceTest}.
     */
    @Test
    void offlineDevice_freshSync_returnsFilesToAdd_forReadyFiles() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(99L);
        when(device.getStatus()).thenReturn(Device.Status.OFFLINE);
        when(device.getLastHeartbeatAt()).thenReturn(java.time.Instant.now().minusSeconds(60 * 60 * 24));
        when(deviceRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(990L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile f1 = readyFile(101L, "key/101.mp4");
        ContentFile f2 = readyFile(102L, "key/102.mp4");
        // Mocking the items must happen BEFORE the surrounding when(...).thenReturn(...)
        // — nesting them inside the argument list triggers UnfinishedStubbingException.
        PlaylistItem i1 = item(0, f1, 10);
        PlaylistItem i2 = item(1, f2, 15);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(990L))
                .thenReturn(List.of(i1, i2));
        when(fileStorageService.presignedProcessedUrl(eq("key/101.mp4"), anyInt())).thenReturn("https://minio/101");
        when(fileStorageService.presignedProcessedUrl(eq("key/102.mp4"), anyInt())).thenReturn("https://minio/102");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-99");

        var plan = syncService.computeSyncPlan(99L, null, Set.of());

        assertTrue(plan.fullSync());
        assertEquals(2, plan.filesToAdd().size(),
                "OFFLINE device on first sync after reconnect must still receive its assigned READY files");
        assertEquals("v-99", plan.expectedContentVersion());
    }

    @Test
    void withVersion_andHeldFiles_returnsDiffOnly() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(2L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(200L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        // Server expects {20, 21}; device claims {19, 20}. Diff: add 21, delete 19.
        ContentFile fNew = readyFile(21L, "key/21.mp4");
        ContentFile fKept = readyFile(20L, "key/20.mp4");
        PlaylistItem keptItem = item(0, fKept, 10);
        PlaylistItem newItem = item(1, fNew, 12);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(200L))
                .thenReturn(List.of(keptItem, newItem));
        when(fileStorageService.presignedProcessedUrl(eq("key/21.mp4"), anyInt())).thenReturn("https://minio/url21");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-2");

        var plan = syncService.computeSyncPlan(2L, "v-old", Set.of(19L, 20L));

        assertFalse(plan.fullSync());
        assertEquals(1, plan.filesToAdd().size());
        assertEquals(21L, plan.filesToAdd().get(0).fileId());
        assertEquals(List.of(19L), plan.filesToDelete());
        assertEquals(2, plan.playlistOrder().size());
    }

    @Test
    void noActiveAssignment_tellsDeviceToDeleteAllHeld() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(3L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(device));
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(null);

        var plan = syncService.computeSyncPlan(3L, "v-stale", Set.of(50L, 51L));

        assertFalse(plan.fullSync());
        assertNull(plan.expectedContentVersion());
        assertEquals(0, plan.filesToAdd().size());
        assertEquals(List.of(50L, 51L), plan.filesToDelete());
        assertEquals(0, plan.playlistOrder().size());
    }

    @Test
    void notReadyFiles_areExcludedFromAddAndOrder() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(4L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(400L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile ready = readyFile(40L, "key/40.mp4");
        ContentFile transcoding = mock(ContentFile.class);
        when(transcoding.getId()).thenReturn(41L);
        when(transcoding.getStatus()).thenReturn(ContentFile.Status.TRANSCODING);
        when(transcoding.getProcessedStorageKey()).thenReturn(null);

        PlaylistItem readyItem = item(0, ready, 10);
        PlaylistItem transcodingItem = item(1, transcoding, 0);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(400L))
                .thenReturn(List.of(readyItem, transcodingItem));
        when(fileStorageService.presignedProcessedUrl(eq("key/40.mp4"), anyInt())).thenReturn("https://minio/url40");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-4");

        var plan = syncService.computeSyncPlan(4L, null, Set.of());

        assertEquals(1, plan.filesToAdd().size());
        assertEquals(40L, plan.filesToAdd().get(0).fileId());
        assertEquals(1, plan.playlistOrder().size());
    }

    @Test
    void confirmSync_matchingVersion_clearsPendingState() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(7L);
        when(device.getSyncPendingVersion()).thenReturn("v-7");
        when(deviceRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(device));

        var result = syncService.confirmSync(7L, "v-7");

        assertEquals(DeviceSyncService.ConfirmStatus.CONFIRMED, result.status());
        assertFalse(result.syncRequired());
        org.mockito.Mockito.verify(device).setCurrentContentVersion("v-7");
        org.mockito.Mockito.verify(device).clearSyncPending();
    }

    @Test
    void confirmSync_mismatchingVersion_keepsPendingAndRequestsResync() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(8L);
        when(device.getSyncPendingVersion()).thenReturn("v-expected");
        when(deviceRepository.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(device));

        var result = syncService.confirmSync(8L, "v-wrong");

        assertEquals(DeviceSyncService.ConfirmStatus.MISMATCH, result.status());
        assertTrue(result.syncRequired());
        assertEquals("v-expected", result.expectedVersion());
        assertEquals("v-wrong", result.reportedVersion());
        org.mockito.Mockito.verify(device).setCurrentContentVersion("v-wrong");
        // Edge case requirement: the 30-min timer must keep running on mismatch.
        org.mockito.Mockito.verify(device, org.mockito.Mockito.never()).clearSyncPending();
    }

    @Test
    void confirmSync_noPendingState_recomputesExpectedAndConfirms() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(9L);
        when(device.getSyncPendingVersion()).thenReturn(null);
        when(deviceRepository.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-9");

        var result = syncService.confirmSync(9L, "v-9");

        assertEquals(DeviceSyncService.ConfirmStatus.CONFIRMED, result.status());
        org.mockito.Mockito.verify(device).clearSyncPending();
    }

    @Test
    void confirmSync_unknownDevice_throws404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> syncService.confirmSync(404L, "v-anything"));
    }

    @Test
    void computeSyncPlan_withWorkToDo_marksPendingOnDevice() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(11L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(11L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(1100L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile f = readyFile(110L, "key/110.mp4");
        PlaylistItem only = item(0, f, 10);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(1100L))
                .thenReturn(List.of(only));
        when(fileStorageService.presignedProcessedUrl(eq("key/110.mp4"), anyInt())).thenReturn("https://minio/url110");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-11");

        syncService.computeSyncPlan(11L, null, Set.of());

        org.mockito.Mockito.verify(device).markSyncPending("v-11");
    }

    @Test
    void computeSyncPlan_noWork_doesNotMarkPending() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(12L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(12L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(1200L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        // Server expects {120}; device already holds {120} → diff is empty → no pending.
        ContentFile f = readyFile(120L, "key/120.mp4");
        PlaylistItem only = item(0, f, 10);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(1200L))
                .thenReturn(List.of(only));
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-12");

        syncService.computeSyncPlan(12L, "v-12", Set.of(120L));

        org.mockito.Mockito.verify(device, org.mockito.Mockito.never()).markSyncPending(any());
    }

    @Test
    void computeSyncPlan_pureReorder_marksPending() {
        // FIX 4: same file set but the version changed (a reorder) is still "work" → arm pending,
        // so SyncTimeoutMonitor can escalate a dropped reorder push and confirmSync validates
        // against a stored expected version (no back-to-back reorder race).
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(14L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(14L)).thenReturn(Optional.of(device));
        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(1400L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);
        ContentFile f = readyFile(140L, "key/140.mp4");
        PlaylistItem i0 = item(0, f, 10);   // build mocks first — nesting a stubbing helper inside thenReturn(...) breaks Mockito
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(1400L)).thenReturn(List.of(i0));
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-new");

        // Device holds {140} (no add/delete) but its reported version is stale → pure reorder.
        syncService.computeSyncPlan(14L, "v-old", Set.of(140L));

        org.mockito.Mockito.verify(device).markSyncPending("v-new");
    }

    @Test
    void computeSyncPlan_heldFileNoOverride_orderCarriesNaturalDuration() {
        // FIX 1: a held file with no per-item override still carries the file's NATURAL duration
        // in playlistOrder (parity with /playlist), since filesToAdd excludes held files.
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(15L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(15L)).thenReturn(Optional.of(device));
        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(1500L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);
        ContentFile f = readyFile(150L, "key/150.mp4");   // natural duration 30
        PlaylistItem noOverride = mock(PlaylistItem.class);
        when(noOverride.getPosition()).thenReturn(0);
        when(noOverride.getContentFile()).thenReturn(f);
        when(noOverride.getDurationSeconds()).thenReturn(null);   // no per-item override
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(1500L)).thenReturn(List.of(noOverride));
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-15");

        var plan = syncService.computeSyncPlan(15L, "v-15", Set.of(150L));   // held, same version

        assertEquals(1, plan.playlistOrder().size());
        assertEquals(30, plan.playlistOrder().get(0).durationSeconds(),
                "held file, no override → effective duration = file natural duration (30)");
    }

    @Test
    void computeSyncPlan_playlistOrder_contiguousIndexOverSparsePositions() {
        // FIX 3: positions [0,1,2] with the middle item non-READY → delivered [pos0, pos2] with a
        // CONTIGUOUS index [0,1] (gap-free) while raw positions stay sparse [0,2].
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(16L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(16L)).thenReturn(Optional.of(device));
        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(1600L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);
        ContentFile f0 = readyFile(160L, "key/160.mp4");
        ContentFile mid = mock(ContentFile.class);
        when(mid.getId()).thenReturn(161L);
        when(mid.getStatus()).thenReturn(ContentFile.Status.TRANSCODING);
        when(mid.getProcessedStorageKey()).thenReturn(null);
        ContentFile f2 = readyFile(162L, "key/162.mp4");
        PlaylistItem i0 = item(0, f0, 10);
        PlaylistItem i1 = item(1, mid, 0);
        PlaylistItem i2 = item(2, f2, 10);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(1600L))
                .thenReturn(List.of(i0, i1, i2));
        when(fileStorageService.presignedProcessedUrl(eq("key/160.mp4"), anyInt())).thenReturn("u0");
        when(fileStorageService.presignedProcessedUrl(eq("key/162.mp4"), anyInt())).thenReturn("u2");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-16");

        var plan = syncService.computeSyncPlan(16L, null, Set.of());   // fresh → all new

        assertEquals(List.of(0, 1),
                plan.playlistOrder().stream().map(DeviceSyncService.PlaylistEntry::index).toList(),
                "index is contiguous 0-based");
        assertEquals(List.of(0, 2),
                plan.playlistOrder().stream().map(DeviceSyncService.PlaylistEntry::position).toList(),
                "raw positions stay sparse");
        assertEquals(List.of(160L, 162L),
                plan.playlistOrder().stream().map(DeviceSyncService.PlaylistEntry::fileId).toList());
    }

    @Test
    void syncAndPlaylist_agreeOnOrderAndIndex_whenInteriorPresignFails() {
        // FIX 5: the shared isDeliverable predicate → both endpoints drop a (non-held) interior
        // file whose presign fails, keeping the SAME order + contiguous index.
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(17L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(17L)).thenReturn(Optional.of(device));
        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(1700L);
        when(playlist.getName()).thenReturn("PL");
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);
        ContentFile a = readyFile(170L, "key/a.mp4");
        ContentFile b = readyFile(171L, "key/b.mp4");   // interior — presign will fail
        ContentFile c = readyFile(172L, "key/c.mp4");
        PlaylistItem ia = item(0, a, 10);
        PlaylistItem ib = item(1, b, 10);
        PlaylistItem ic = item(2, c, 10);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(1700L))
                .thenReturn(List.of(ia, ib, ic));
        when(fileStorageService.presignedProcessedUrl(eq("key/a.mp4"), anyInt())).thenReturn("ua");
        when(fileStorageService.presignedProcessedUrl(eq("key/b.mp4"), anyInt()))
                .thenThrow(new RuntimeException("presign down"));
        when(fileStorageService.presignedProcessedUrl(eq("key/c.mp4"), anyInt())).thenReturn("uc");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-17");

        var plan = syncService.computeSyncPlan(17L, null, Set.of());   // fresh: all "new"
        var view = syncService.getPlaylistView(17L);

        var syncFileIds = plan.playlistOrder().stream().map(DeviceSyncService.PlaylistEntry::fileId).toList();
        var viewFileIds = view.items().stream().map(DeviceSyncService.PlaylistViewItem::fileId).toList();
        assertEquals(List.of(170L, 172L), syncFileIds, "/sync drops the presign-failed interior file");
        assertEquals(syncFileIds, viewFileIds, "/sync and /playlist agree on deliverable order");

        var syncIdx = plan.playlistOrder().stream().map(DeviceSyncService.PlaylistEntry::index).toList();
        var viewIdx = view.items().stream().map(DeviceSyncService.PlaylistViewItem::index).toList();
        assertEquals(List.of(0, 1), syncIdx, "contiguous index after the gap");
        assertEquals(syncIdx, viewIdx, "same index in both endpoints");
    }

    @Test
    void computeSyncPlan_heldFileWithOverride_orderCarriesOverrideDuration() {
        // FIX 1 complement: a held file WITH a per-item override → order carries the OVERRIDE,
        // not the file's natural duration (guards against a flipped override/natural ternary).
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(18L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(18L)).thenReturn(Optional.of(device));
        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(1800L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);
        ContentFile f = readyFile(180L, "key/180.mp4");   // natural duration 30
        PlaylistItem withOverride = item(0, f, 7);          // per-item override = 7
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(1800L)).thenReturn(List.of(withOverride));
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-18");

        var plan = syncService.computeSyncPlan(18L, "v-18", Set.of(180L));   // held, same version

        assertEquals(7, plan.playlistOrder().get(0).durationSeconds(),
                "held file WITH override → effective duration = override (7), not natural (30)");
    }

    @Test
    void computeSyncPlan_heldExpectedFile_staysInOrder_andIsNeverRePresigned() {
        // FIX 5 policy: /sync trusts held bytes — a held+expected file stays in playlistOrder and
        // is NEVER re-presigned (the device already has it). Presign is stubbed to throw to prove
        // it isn't called for a held file; a regression that filtered held files by presign success
        // would either throw or drop the file and fail here.
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(19L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(19L)).thenReturn(Optional.of(device));
        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(1900L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);
        ContentFile held = readyFile(190L, "key/190.mp4");
        PlaylistItem heldItem = item(0, held, 10);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(1900L)).thenReturn(List.of(heldItem));
        when(fileStorageService.presignedProcessedUrl(eq("key/190.mp4"), anyInt()))
                .thenThrow(new RuntimeException("/sync must not presign a held file"));
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-19");

        var plan = syncService.computeSyncPlan(19L, "v-19", Set.of(190L));   // device HOLDS 190

        assertTrue(plan.filesToAdd().isEmpty(), "held file is not re-added");
        assertEquals(List.of(190L),
                plan.playlistOrder().stream().map(DeviceSyncService.PlaylistEntry::fileId).toList(),
                "held+expected file stays in order regardless of current presignability");
    }

    @Test
    void presignedUrl_alwaysFreshlyGenerated_perCall() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(5L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(500L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile f = readyFile(50L, "key/50.mp4");
        PlaylistItem only = item(0, f, 10);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(500L))
                .thenReturn(List.of(only));
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-5");

        // Each invocation returns a different URL (new signature each time).
        when(fileStorageService.presignedProcessedUrl(eq("key/50.mp4"), anyInt()))
                .thenReturn("https://minio/url50?sig=AAA")
                .thenReturn("https://minio/url50?sig=BBB");

        var first = syncService.computeSyncPlan(5L, null, Set.of());
        var second = syncService.computeSyncPlan(5L, null, Set.of());

        assertEquals("https://minio/url50?sig=AAA", first.filesToAdd().get(0).presignedUrl());
        assertEquals("https://minio/url50?sig=BBB", second.filesToAdd().get(0).presignedUrl());
    }

    @Test
    void fileMissingFromMinIO_isExcludedFromSync_othersStillServed() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(60L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(60L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(600L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile present = readyFile(601L, "key/present.mp4");
        ContentFile orphaned = readyFile(602L, "key/orphaned.mp4");
        PlaylistItem i1 = item(0, present, 10);
        PlaylistItem i2 = item(1, orphaned, 12);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(600L))
                .thenReturn(List.of(i1, i2));
        when(fileStorageService.processedObjectExists("key/present.mp4")).thenReturn(true);
        when(fileStorageService.processedObjectExists("key/orphaned.mp4")).thenReturn(false);
        when(fileStorageService.presignedProcessedUrl(eq("key/present.mp4"), anyInt()))
                .thenReturn("https://minio/present");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-60");

        var plan = syncService.computeSyncPlan(60L, null, Set.of());

        // Only the present file is in filesToAdd; orphaned is silently dropped.
        assertEquals(1, plan.filesToAdd().size());
        assertEquals(601L, plan.filesToAdd().get(0).fileId());
        assertEquals("https://minio/present", plan.filesToAdd().get(0).presignedUrl());

        // §2: the orphaned READY-but-missing file must ALSO be absent from playlistOrder —
        // a device is never told to play a file it has no download URL for. (Pre-fix this
        // was 2: the order was re-filtered by READY status, independent of deliverability.)
        assertEquals(1, plan.playlistOrder().size(), "playlistOrder must exclude the undeliverable file");
        assertEquals(601L, plan.playlistOrder().get(0).fileId());
    }

    /**
     * §2 invariant — playlistOrder ⊆ deliverable set, with the held-file branch exercised:
     * a file the device ALREADY HOLDS stays in the order even if its MinIO object is now
     * missing (the device has the bytes); a FRESH READY file whose object is missing is
     * dropped (the device has no URL for it).
     */
    @Test
    void playlistOrder_keepsHeldFile_dropsFreshUndeliverableFile() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(65L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(65L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(650L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile held = readyFile(651L, "key/held.mp4");   // already on device; object now gone
        ContentFile fresh = readyFile(652L, "key/fresh.mp4"); // new, builds fine
        ContentFile orphan = readyFile(653L, "key/orphan.mp4"); // new, object missing
        PlaylistItem heldItem = item(0, held, 10);
        PlaylistItem freshItem = item(1, fresh, 12);
        PlaylistItem orphanItem = item(2, orphan, 15);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(650L))
                .thenReturn(List.of(heldItem, freshItem, orphanItem));
        // held file's object is missing too — irrelevant: held files bypass the storage check
        // because the device already downloaded the bytes.
        when(fileStorageService.processedObjectExists("key/held.mp4")).thenReturn(false);
        when(fileStorageService.processedObjectExists("key/fresh.mp4")).thenReturn(true);
        when(fileStorageService.processedObjectExists("key/orphan.mp4")).thenReturn(false);
        when(fileStorageService.presignedProcessedUrl(eq("key/fresh.mp4"), anyInt()))
                .thenReturn("https://minio/fresh");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-65");

        var plan = syncService.computeSyncPlan(65L, "v-old", Set.of(651L));

        // Only the fresh, deliverable file is added; orphan dropped; held not re-added.
        assertEquals(1, plan.filesToAdd().size());
        assertEquals(652L, plan.filesToAdd().get(0).fileId());
        // Held file is still expected, so nothing to delete.
        assertEquals(0, plan.filesToDelete().size());
        // Order = held (pos0) + fresh (pos1); orphan (pos2) excluded.
        assertEquals(List.of(651L, 652L),
                plan.playlistOrder().stream().map(DeviceSyncService.PlaylistEntry::fileId).toList());
    }

    @Test
    void presignedUrlGenerationThrows_doesNotBlockOtherFiles() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(70L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(70L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(700L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile good1 = readyFile(701L, "key/good1.mp4");
        ContentFile bad = readyFile(702L, "key/bad.mp4");
        ContentFile good2 = readyFile(703L, "key/good2.mp4");
        PlaylistItem g1Item = item(0, good1, 10);
        PlaylistItem badItem = item(1, bad, 10);
        PlaylistItem g2Item = item(2, good2, 10);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(700L))
                .thenReturn(List.of(g1Item, badItem, g2Item));
        when(fileStorageService.presignedProcessedUrl(eq("key/good1.mp4"), anyInt()))
                .thenReturn("https://minio/good1");
        when(fileStorageService.presignedProcessedUrl(eq("key/bad.mp4"), anyInt()))
                .thenThrow(new RuntimeException("MinIO signing temporarily unavailable"));
        when(fileStorageService.presignedProcessedUrl(eq("key/good2.mp4"), anyInt()))
                .thenReturn("https://minio/good2");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-70");

        var plan = syncService.computeSyncPlan(70L, null, Set.of());

        assertEquals(2, plan.filesToAdd().size(), "bad file dropped, good1 + good2 still ship");
        assertEquals(701L, plan.filesToAdd().get(0).fileId());
        assertEquals(703L, plan.filesToAdd().get(1).fileId());
    }

    @Test
    void playlistView_unknownDevice_throws404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> syncService.getPlaylistView(404L));
    }

    @Test
    void playlistView_noAssignment_returnsEmptyArray_not404() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(90L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(90L)).thenReturn(Optional.of(device));
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(null);

        var view = syncService.getPlaylistView(90L);

        assertEquals(90L, view.deviceId());
        assertEquals(0, view.items().size());
        assertEquals(0, view.totalDurationSeconds());
        // Critical: device-level "no playlist" must be 200/empty, NOT 404.
        // 404 is reserved for unknown device IDs.
    }

    @Test
    void playlistView_returnsOrderedItemsWithUrlsAndTotalDuration() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(91L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(91L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(910L);
        when(playlist.getName()).thenReturn("Mall Loop");
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile a = readyFile(910L, "key/a.mp4");
        ContentFile b = readyFile(911L, "key/b.mp4");
        PlaylistItem aItem = item(0, a, 30);
        PlaylistItem bItem = item(1, b, 45);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(910L))
                .thenReturn(List.of(aItem, bItem));
        when(fileStorageService.presignedProcessedUrl(eq("key/a.mp4"), anyInt())).thenReturn("https://minio/a");
        when(fileStorageService.presignedProcessedUrl(eq("key/b.mp4"), anyInt())).thenReturn("https://minio/b");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-91");

        var view = syncService.getPlaylistView(91L);

        assertEquals(910L, view.playlistId());
        assertEquals("Mall Loop", view.playlistName());
        assertEquals("v-91", view.contentVersion());
        assertEquals(75, view.totalDurationSeconds(), "30 + 45 = 75");
        assertEquals(2, view.items().size());
        assertEquals(0, view.items().get(0).position());
        assertEquals(910L, view.items().get(0).fileId());
        assertEquals("https://minio/a", view.items().get(0).presignedUrl());
        assertEquals(1, view.items().get(1).position());
    }

    @Test
    void playlistView_missingFile_excludedFromItems() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(92L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(92L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(920L);
        when(playlist.getName()).thenReturn("Test");
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile present = readyFile(920L, "key/present.mp4");
        ContentFile orphaned = readyFile(921L, "key/orphan.mp4");
        PlaylistItem p1 = item(0, present, 20);
        PlaylistItem p2 = item(1, orphaned, 25);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(920L))
                .thenReturn(List.of(p1, p2));
        when(fileStorageService.processedObjectExists("key/present.mp4")).thenReturn(true);
        when(fileStorageService.processedObjectExists("key/orphan.mp4")).thenReturn(false);
        when(fileStorageService.presignedProcessedUrl(eq("key/present.mp4"), anyInt()))
                .thenReturn("https://minio/present");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-92");

        var view = syncService.getPlaylistView(92L);

        assertEquals(1, view.items().size());
        assertEquals(920L, view.items().get(0).fileId());
        assertEquals(20, view.totalDurationSeconds(), "missing item's duration excluded");
    }

    @Test
    void playlistView_perItemDurationOverride_winsOverFileDuration() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(93L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(93L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(930L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile f = readyFile(930L, "key/930.mp4");
        // file's intrinsic duration is 30s (from readyFile helper)
        // playlist item override is 5s — playlist override should win
        PlaylistItem withOverride = item(0, f, 5);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(930L))
                .thenReturn(List.of(withOverride));
        when(fileStorageService.presignedProcessedUrl(eq("key/930.mp4"), anyInt()))
                .thenReturn("https://minio/930");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-93");

        var view = syncService.getPlaylistView(93L);

        assertEquals(5, view.totalDurationSeconds());
        assertEquals(5, view.items().get(0).durationSeconds());
    }

    /**
     * Companion to {@code playlistView_perItemDurationOverride_winsOverFileDuration}: when
     * the playlist item's override is null (i.e. the operator just cleared it via
     * {@code PUT /api/playlists/{id}/items/{itemId}/duration}), the device-side response
     * falls back to {@code content_file.duration_seconds} — the encoder probe value
     * captured at upload. This is the contract the duration-clear endpoint promises.
     */
    @Test
    void playlistView_nullItemOverride_fallsBackToContentFileDuration() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(94L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(94L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(940L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile f = readyFile(940L, "key/940.mp4");
        // file's intrinsic duration is 30s (from readyFile helper).
        // The playlist item has its override CLEARED — getDurationSeconds() returns null.
        // The expected response duration is 30s, sourced from the file.
        PlaylistItem cleared = mock(PlaylistItem.class);
        when(cleared.getPosition()).thenReturn(0);
        when(cleared.getContentFile()).thenReturn(f);
        when(cleared.getDurationSeconds()).thenReturn(null);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(940L))
                .thenReturn(List.of(cleared));
        when(fileStorageService.presignedProcessedUrl(eq("key/940.mp4"), anyInt()))
                .thenReturn("https://minio/940");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-94");

        var view = syncService.getPlaylistView(94L);

        // Fallback applied: 30s comes from f.getDurationSeconds(), NOT from the cleared override.
        assertEquals(30, view.items().get(0).durationSeconds());
        assertEquals(30, view.totalDurationSeconds());
    }

    @Test
    void presignedUrls_useTwoHourTtl_byDefault() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(80L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(80L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(800L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile f = readyFile(800L, "key/800.mp4");
        PlaylistItem only = item(0, f, 10);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(800L))
                .thenReturn(List.of(only));
        when(fileStorageService.presignedProcessedUrl(eq("key/800.mp4"), eq(120)))
                .thenReturn("https://minio/url800");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-80");

        var plan = syncService.computeSyncPlan(80L, null, Set.of());

        assertEquals(120, plan.presignedUrlExpiryMinutes());
        org.mockito.Mockito.verify(fileStorageService).presignedProcessedUrl("key/800.mp4", 120);
    }

    // ---------------------------------------------------------------------------------------
    // Synchronized-playback schedule block (§1.3)
    // ---------------------------------------------------------------------------------------

    @Test
    void computeSyncPlan_schedule_slotTimelineIsPrefixSum_loopIsSum_anchorEchoed() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(30L);
        when(device.getSyncGroupId()).thenReturn("fac-7");
        when(deviceRepository.findByIdAndDeletedAtIsNull(30L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(300L);
        when(assignment.getVersionNumber()).thenReturn(1);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(3000L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile f1 = readyFile(31L, "key/31.mp4");
        ContentFile f2 = readyFile(32L, "key/32.mp4");
        ContentFile f3 = readyFile(33L, "key/33.mp4");
        // Effective durations 39s / 15s / 41s → slots [0,39000),[39000,54000),[54000,95000); loop 95000.
        PlaylistItem i1 = item(0, f1, 39);
        PlaylistItem i2 = item(1, f2, 15);
        PlaylistItem i3 = item(2, f3, 41);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(3000L)).thenReturn(List.of(i1, i2, i3));
        when(fileStorageService.presignedProcessedUrl(anyString(), anyInt())).thenReturn("https://minio/x");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-30");

        java.time.Instant activateAt = java.time.Instant.ofEpochMilli(1_719_830_400_000L);
        when(playbackScheduleService.getOrCreate(eq(300L), eq(1), eq("v-30")))
                .thenReturn(new PlaybackSyncSchedule(300L, 1, "v-30", activateAt));

        var plan = syncService.computeSyncPlan(30L, null, Set.of());
        var order = plan.playlistOrder();

        assertEquals(List.of(0L, 39_000L, 54_000L),
                order.stream().map(DeviceSyncService.PlaylistEntry::slotStartMs).toList(),
                "slotStartMs is the running prefix sum of slotDurationMs");
        assertEquals(List.of(39_000L, 15_000L, 41_000L),
                order.stream().map(DeviceSyncService.PlaylistEntry::slotDurationMs).toList());
        assertEquals(95_000L, plan.loopDurationMs(), "loopDurationMs == Σ slotDurationMs");
        // Slot set is BY CONSTRUCTION identical to playlistOrder (same fileIds, same contiguous index).
        assertEquals(List.of(31L, 32L, 33L),
                order.stream().map(DeviceSyncService.PlaylistEntry::fileId).toList());
        assertEquals(List.of(0, 1, 2),
                order.stream().map(DeviceSyncService.PlaylistEntry::index).toList());
        // Anchor == activateAt (loop T0), echoed as epoch ms; group present.
        assertEquals("fac-7", plan.syncGroupId());
        assertEquals(1_719_830_400_000L, plan.anchorEpochMs());
        assertEquals(1_719_830_400_000L, plan.activateAt());
    }

    @Test
    void computeSyncPlan_ungroupedDevice_nullSyncGroup_noAnchor_butLoopStillComputed() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(34L);
        when(device.getSyncGroupId()).thenReturn(null);   // no region/facility/group → free-run solo
        when(deviceRepository.findByIdAndDeletedAtIsNull(34L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(340L);
        when(assignment.getVersionNumber()).thenReturn(1);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(3400L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile f = readyFile(341L, "key/341.mp4");
        PlaylistItem only = item(0, f, 20);   // build the mock before thenReturn(...) (Mockito nesting trap)
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(3400L)).thenReturn(List.of(only));
        when(fileStorageService.presignedProcessedUrl(anyString(), anyInt())).thenReturn("u");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-34");
        // Not yet anchored for this device's cycle (no schedule row) — getOrCreate yields nothing.
        when(playbackScheduleService.getOrCreate(eq(340L), eq(1), eq("v-34"))).thenReturn(null);

        var plan = syncService.computeSyncPlan(34L, null, Set.of());

        assertNull(plan.syncGroupId(), "ungrouped device → null syncGroupId (free-run solo, today's behavior)");
        assertNull(plan.anchorEpochMs(), "no schedule row → no anchor");
        assertNull(plan.activateAt(), "no schedule row → no cut-over instant");
        // The slot timeline is still emitted (it is playlist-derived); the device just can't position yet.
        assertEquals(20_000L, plan.loopDurationMs());
    }

    @Test
    void computeSyncPlan_emptyDeliverableSet_loopDurationZero_notAnchored() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(35L);
        when(device.getSyncGroupId()).thenReturn("reg-1");
        when(deviceRepository.findByIdAndDeletedAtIsNull(35L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(350L);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(3500L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        // The only item is still transcoding → not deliverable → empty order.
        ContentFile transcoding = mock(ContentFile.class);
        when(transcoding.getId()).thenReturn(351L);
        when(transcoding.getStatus()).thenReturn(ContentFile.Status.TRANSCODING);
        when(transcoding.getProcessedStorageKey()).thenReturn(null);
        PlaylistItem notReady = item(0, transcoding, 10);   // build before thenReturn (Mockito nesting trap)
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(3500L))
                .thenReturn(List.of(notReady));
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-35");

        var plan = syncService.computeSyncPlan(35L, null, Set.of());

        assertEquals(0, plan.playlistOrder().size());
        assertEquals(0L, plan.loopDurationMs(), "no deliverable items → loopDurationMs == 0 (no divide-by-zero)");
        assertNull(plan.anchorEpochMs(), "an empty loop is never anchored");
        org.mockito.Mockito.verify(playbackScheduleService, org.mockito.Mockito.never())
                .getOrCreate(any(), anyInt(), anyString());
    }

    @Test
    void computeSyncPlan_nullDurationImage_usesDefaultDwell_neverZeroSlot() {
        // An image with neither an operator dwell nor a natural duration must NOT collapse the loop.
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(36L);
        when(device.getSyncGroupId()).thenReturn("fac-1");
        when(deviceRepository.findByIdAndDeletedAtIsNull(36L)).thenReturn(Optional.of(device));

        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(360L);
        when(assignment.getVersionNumber()).thenReturn(1);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(3600L);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(eq(device), any())).thenReturn(assignment);

        ContentFile image = mock(ContentFile.class);
        when(image.getId()).thenReturn(361L);
        when(image.getStatus()).thenReturn(ContentFile.Status.READY);
        when(image.getProcessedStorageKey()).thenReturn("key/361.jpg");
        when(image.getDurationSeconds()).thenReturn(null);          // no natural duration
        PlaylistItem noDwell = mock(PlaylistItem.class);
        when(noDwell.getPosition()).thenReturn(0);
        when(noDwell.getContentFile()).thenReturn(image);
        when(noDwell.getDurationSeconds()).thenReturn(null);        // no operator override either
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(3600L)).thenReturn(List.of(noDwell));
        when(fileStorageService.presignedProcessedUrl(eq("key/361.jpg"), anyInt())).thenReturn("https://minio/361");
        when(contentVersionService.computeForAssignment(assignment)).thenReturn("v-36");

        var plan = syncService.computeSyncPlan(36L, null, Set.of());
        var slot = plan.playlistOrder().get(0);

        assertNull(slot.durationSeconds(), "the legacy effective-duration field stays null");
        assertTrue(slot.slotDurationMs() > 0, "a null-duration image must NEVER produce a 0-ms slot");
        assertEquals(10_000L, slot.slotDurationMs(), "falls back to the defined 10s default dwell");
        assertEquals(10_000L, plan.loopDurationMs());
    }

    private static ContentFile readyFile(Long id, String processedKey) {
        ContentFile f = mock(ContentFile.class);
        when(f.getId()).thenReturn(id);
        when(f.getStatus()).thenReturn(ContentFile.Status.READY);
        when(f.getProcessedStorageKey()).thenReturn(processedKey);
        when(f.getName()).thenReturn("file-" + id + ".mp4");
        when(f.getContentType()).thenReturn("video/mp4");
        when(f.getSizeBytes()).thenReturn(1024L);
        when(f.getDurationSeconds()).thenReturn(30);
        when(f.getChecksum()).thenReturn("sha-" + id);
        return f;
    }

    private static PlaylistItem item(int position, ContentFile file, int duration) {
        PlaylistItem item = mock(PlaylistItem.class);
        when(item.getPosition()).thenReturn(position);
        when(item.getContentFile()).thenReturn(file);
        when(item.getDurationSeconds()).thenReturn(duration);
        return item;
    }
}
