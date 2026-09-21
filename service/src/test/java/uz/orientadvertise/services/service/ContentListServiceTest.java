package uz.orientadvertise.services.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import uz.orientadvertise.services.common.exception.AccessForbiddenException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.AdvertiserContentAccessRepository;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.OperatorContentAccessRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for the thumbnail-decoration logic added to
 * {@link ContentListService#list} and {@link ContentListService#getDetail}. Controller
 * tests mock this service, so the actual READY+key-set → presigned URL gate only gets
 * exercised here.
 *
 * <p>Also pins the listing's <b>order normalisation</b> (v1.0.134). The two listing queries carry no
 * {@code ORDER BY}, so the {@code Sort} this service puts on the {@code Pageable} <i>is</i> the
 * ordering contract — assert on the captured {@code Pageable}, because nothing downstream of it
 * would notice the sort going missing.
 */
class ContentListServiceTest {

    private ContentFileRepository contentFileRepository;
    private AdvertiserContentAccessRepository accessRepository;
    private OperatorContentAccessRepository operatorAccessRepository;
    private AppUserRepository userRepository;
    private FileStorageService fileStorageService;
    private ContentListService service;

    @BeforeEach
    void setUp() {
        contentFileRepository = mock(ContentFileRepository.class);
        accessRepository = mock(AdvertiserContentAccessRepository.class);
        operatorAccessRepository = mock(OperatorContentAccessRepository.class);
        userRepository = mock(AppUserRepository.class);
        fileStorageService = mock(FileStorageService.class);
        service = new ContentListService(contentFileRepository, accessRepository,
                operatorAccessRepository, userRepository, fileStorageService, 60);
    }

    @Test
    void list_readyRowWithThumbnailKey_populatesUrlAndExpiry() {
        var ready = mock(ContentFile.class);
        when(ready.getStatus()).thenReturn(ContentFile.Status.READY);
        when(ready.getThumbnailStorageKey()).thenReturn("thumbnails/abc.jpg");
        var pageable = PageRequest.of(0, 20);
        when(contentFileRepository.findFiltered(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ready), pageable, 1));
        when(fileStorageService.presignedThumbnailUrl(eq("thumbnails/abc.jpg"), eq(15)))
                .thenReturn("http://signed/abc?ttl=15m");

        var page = service.list(null, null, null, null, null, pageable);

        assertEquals(1, page.getContent().size());
        var view = page.getContent().get(0);
        assertEquals("http://signed/abc?ttl=15m", view.thumbnailUrl());
        assertNotNull(view.thumbnailExpiresAt(),
                "expiresAt must be populated alongside the URL");
    }

    @Test
    void list_readyRowWithoutThumbnailKey_leavesUrlAndExpiryNull() {
        var ready = mock(ContentFile.class);
        when(ready.getStatus()).thenReturn(ContentFile.Status.READY);
        when(ready.getThumbnailStorageKey()).thenReturn(null); // poster step failed
        var pageable = PageRequest.of(0, 20);
        when(contentFileRepository.findFiltered(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ready), pageable, 1));

        var page = service.list(null, null, null, null, null, pageable);

        assertEquals(1, page.getContent().size());
        var view = page.getContent().get(0);
        assertNull(view.thumbnailUrl(),
                "Missing thumbnail key must NOT trigger a 5xx — both URL fields stay null");
        assertNull(view.thumbnailExpiresAt());
        verify(fileStorageService, never()).presignedThumbnailUrl(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void list_transcodingRow_neverGeneratesUrlEvenIfKeyIsSet() {
        var inFlight = mock(ContentFile.class);
        when(inFlight.getStatus()).thenReturn(ContentFile.Status.TRANSCODING);
        // A stale thumbnail key from a prior run shouldn't leak through — status gate wins.
        when(inFlight.getThumbnailStorageKey()).thenReturn("thumbnails/stale.jpg");
        var pageable = PageRequest.of(0, 20);
        when(contentFileRepository.findFiltered(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inFlight), pageable, 1));

        var page = service.list(null, null, null, null, null, pageable);

        var view = page.getContent().get(0);
        assertNull(view.thumbnailUrl());
        assertNull(view.thumbnailExpiresAt());
        verify(fileStorageService, never()).presignedThumbnailUrl(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void list_failedRow_leavesUrlNull() {
        var failed = mock(ContentFile.class);
        when(failed.getStatus()).thenReturn(ContentFile.Status.FAILED);
        var pageable = PageRequest.of(0, 20);
        when(contentFileRepository.findFiltered(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(failed), pageable, 1));

        var page = service.list(null, null, null, null, null, pageable);

        assertNull(page.getContent().get(0).thumbnailUrl());
        verify(fileStorageService, never()).presignedThumbnailUrl(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void list_mixedPage_onlyReadyWithKeyGetsUrl() {
        // Spec'd batch behavior: a single page may contain a mix; the URL gate fires
        // exactly once per qualifying row, never on the others.
        var readyWith = mock(ContentFile.class);
        when(readyWith.getStatus()).thenReturn(ContentFile.Status.READY);
        when(readyWith.getThumbnailStorageKey()).thenReturn("thumbnails/yes.jpg");
        var readyWithout = mock(ContentFile.class);
        when(readyWithout.getStatus()).thenReturn(ContentFile.Status.READY);
        when(readyWithout.getThumbnailStorageKey()).thenReturn(null);
        var transcoding = mock(ContentFile.class);
        when(transcoding.getStatus()).thenReturn(ContentFile.Status.TRANSCODING);
        var pageable = PageRequest.of(0, 20);
        when(contentFileRepository.findFiltered(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(readyWith, readyWithout, transcoding),
                        pageable, 3));
        when(fileStorageService.presignedThumbnailUrl(eq("thumbnails/yes.jpg"), eq(15)))
                .thenReturn("http://signed/yes");

        var page = service.list(null, null, null, null, null, pageable);

        assertEquals("http://signed/yes", page.getContent().get(0).thumbnailUrl());
        assertNull(page.getContent().get(1).thumbnailUrl());
        assertNull(page.getContent().get(2).thumbnailUrl());
        verify(fileStorageService).presignedThumbnailUrl(eq("thumbnails/yes.jpg"), eq(15));
    }

    @Test
    void getDetail_readyRowWithThumbnail_populatesUrl() {
        var ready = mock(ContentFile.class);
        when(ready.getStatus()).thenReturn(ContentFile.Status.READY);
        when(ready.getThumbnailStorageKey()).thenReturn("thumbnails/d.jpg");
        when(ready.getDeletedAt()).thenReturn(null);
        when(contentFileRepository.findById(7L)).thenReturn(Optional.of(ready));
        when(fileStorageService.presignedThumbnailUrl(eq("thumbnails/d.jpg"), eq(15)))
                .thenReturn("http://signed/d");

        var view = service.getDetail(7L, null, false, false);

        assertEquals("http://signed/d", view.thumbnailUrl());
        assertNotNull(view.thumbnailExpiresAt());
    }

    // --- projectId sentinel normalization (the "+ Add item" empty-picker fix) ---------------
    // The FE sends projectId=-1 for a playlist bound to the seeded "Unassigned" project, but
    // unassigned content is stored with project_id=NULL. The service must coerce <=0 to null so
    // the query stops filtering by project, matching ContentController's write-path normalization.

    @Test
    void list_negativeSentinelProjectId_normalizedToNoFilter() {
        var pageable = PageRequest.of(0, 20);
        when(contentFileRepository.findFiltered(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.list(-1L, ContentFile.Status.READY, null, null, null, pageable);

        ArgumentCaptor<Long> projectId = ArgumentCaptor.forClass(Long.class);
        verify(contentFileRepository).findFiltered(
                projectId.capture(), eq(ContentFile.Status.READY), any(), any(Pageable.class));
        assertNull(projectId.getValue(),
                "-1 (Unassigned sentinel) must be normalized to null so it returns all READY content");
    }

    @Test
    void list_zeroProjectId_normalizedToNoFilter() {
        var pageable = PageRequest.of(0, 20);
        when(contentFileRepository.findFiltered(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.list(0L, ContentFile.Status.READY, null, null, null, pageable);

        ArgumentCaptor<Long> projectId = ArgumentCaptor.forClass(Long.class);
        verify(contentFileRepository).findFiltered(
                projectId.capture(), eq(ContentFile.Status.READY), any(), any(Pageable.class));
        assertNull(projectId.getValue(), "0 must normalize identically to -1/null");
    }

    @Test
    void list_realProjectId_passedThroughUnchanged() {
        var pageable = PageRequest.of(0, 20);
        when(contentFileRepository.findFiltered(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.list(5L, ContentFile.Status.READY, null, null, null, pageable);

        ArgumentCaptor<Long> projectId = ArgumentCaptor.forClass(Long.class);
        verify(contentFileRepository).findFiltered(
                projectId.capture(), eq(ContentFile.Status.READY), any(), any(Pageable.class));
        assertEquals(5L, projectId.getValue(),
                "a real positive project id must still filter to that project (no regression)");
    }

    @Test
    void list_advertiserPath_negativeSentinelNormalizedToNoFilter() {
        var pageable = PageRequest.of(0, 20);
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(42L);
        when(userRepository.findByUsername("adv")).thenReturn(Optional.of(user));
        when(accessRepository.findContentIdsByUserId(42L)).thenReturn(List.of(7L, 8L));
        when(contentFileRepository.findFilteredScoped(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.list(-1L, ContentFile.Status.READY, null, "adv", null, pageable);

        ArgumentCaptor<Long> projectId = ArgumentCaptor.forClass(Long.class);
        verify(contentFileRepository).findFilteredScoped(
                projectId.capture(), eq(ContentFile.Status.READY), any(), any(), any(Pageable.class));
        assertNull(projectId.getValue(),
                "advertiser path must normalize -1 the same way, returning scoped READY content not empty");
    }

    // ---------- deterministic ordering (v1.0.134) ----------

    /** The Pageable actually handed to the unscoped listing query. */
    private Pageable capturedPageable(Pageable requested) {
        when(contentFileRepository.findFiltered(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), requested, 0));
        service.list(null, null, null, null, null, requested);
        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(contentFileRepository).findFiltered(any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void list_unsortedRequest_paginatesNewestFirstWithAnIdTiebreaker() {
        // Without this, page membership is unspecified DB order AND it shifts as the transcode
        // pipeline UPDATEs rows — a row can move between pages while an operator is paging.
        var sort = capturedPageable(PageRequest.of(0, 20)).getSort();

        assertEquals(ContentFileRepository.DEFAULT_LISTING_SORT, sort);
        assertEquals(Sort.Direction.DESC, sort.getOrderFor("createdAt").getDirection());
        assertNotNull(sort.getOrderFor("id"), "id is the tiebreaker that totally orders the page");
    }

    @Test
    void list_callerSort_survives_andStillGetsTheIdTiebreaker() {
        // The frontend sends sort=createdAt,desc itself but structurally cannot send a second key
        // (it binds one `sort` parameter per request), so the tiebreaker has to be added here.
        var sort = capturedPageable(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "name"))).getSort();

        assertEquals(List.of(
                        Sort.Order.asc("name"),
                        Sort.Order.desc("id")),
                sort.toList(),
                "the caller's key stays first and authoritative: " + sort);
    }

    @Test
    void list_callerAlreadySortsById_isNotGivenASecondIdKey() {
        var sort = capturedPageable(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "id"))).getSort();

        assertEquals(List.of(Sort.Order.asc("id")), sort.toList(), sort.toString());
    }

    @Test
    void list_pageNumberAndSize_areCarriedThroughUnchanged() {
        // Negative: normalising the sort must not quietly re-page the request.
        var page = capturedPageable(PageRequest.of(3, 7));

        assertEquals(3, page.getPageNumber());
        assertEquals(7, page.getPageSize());
    }

    @Test
    void list_scopedBranch_isOrderedToo() {
        // The advertiser/operator branch runs a different query; an ordering fix that covers only
        // the unscoped one leaves half the callers non-deterministic.
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(5L);
        when(userRepository.findByUsername("adv")).thenReturn(Optional.of(user));
        when(accessRepository.findContentIdsByUserId(5L)).thenReturn(List.of(1L, 2L));
        var requested = PageRequest.of(0, 20);
        when(contentFileRepository.findFilteredScoped(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), requested, 0));

        service.list(null, null, null, "adv", null, requested);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(contentFileRepository).findFilteredScoped(any(), any(), any(), any(), captor.capture());
        assertEquals(ContentFileRepository.DEFAULT_LISTING_SORT, captor.getValue().getSort());
    }

    // ---- operator row guards (AUTHZ-01) ----------------------------------------------------

    /** Content 5 uploaded by {@code owner}; operator {@code op} (user id 3) holds a grant iff {@code granted}. */
    private void contentOwnedBy(String owner, boolean granted) {
        var file = mock(ContentFile.class);
        when(file.getUploadedBy()).thenReturn(owner);
        when(contentFileRepository.findById(5L)).thenReturn(Optional.of(file));
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(3L);
        when(userRepository.findByUsername("op")).thenReturn(Optional.of(user));
        when(operatorAccessRepository.existsByUserIdAndContentFileId(3L, 5L)).thenReturn(granted);
    }

    @Test
    void assertOperatorCanManage_owned_passes_granted_is403_neither_is404() {
        contentOwnedBy("op", false);
        assertDoesNotThrow(() -> service.assertOperatorCanManage(5L, "op", true));

        contentOwnedBy("someone-else", true);
        assertThrows(AccessForbiddenException.class, () -> service.assertOperatorCanManage(5L, "op", true));

        contentOwnedBy("someone-else", false);
        assertThrows(ResourceNotFoundException.class, () -> service.assertOperatorCanManage(5L, "op", true));
    }

    @Test
    void assertOperatorCanAccess_ownedOrGranted_passes_neither_is404() {
        contentOwnedBy("op", false);
        assertDoesNotThrow(() -> service.assertOperatorCanAccess(5L, "op", true));

        contentOwnedBy("someone-else", true);
        assertDoesNotThrow(() -> service.assertOperatorCanAccess(5L, "op", true));

        contentOwnedBy("someone-else", false);
        assertThrows(ResourceNotFoundException.class, () -> service.assertOperatorCanAccess(5L, "op", true));
    }

    @Test
    void operatorGuards_areNoOpsForNonOperatorCallers() {
        // Admin/hybrid callers are unrestricted — the guards must not even load the row.
        service.assertOperatorCanManage(5L, "admin", false);
        service.assertOperatorCanAccess(5L, "admin", false);
        verify(contentFileRepository, never()).findById(any());
    }
}
