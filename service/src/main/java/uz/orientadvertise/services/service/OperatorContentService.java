package uz.orientadvertise.services.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.OperatorContentAccess;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.OperatorContentAccessRepository;

/**
 * Admin-granted content access for OPERATOR users — a behavioral clone of
 * {@link AdvertiserContentService}, differing only in the enforced role
 * ({@link Role#OPERATOR}) and the backing {@code operator_content_access} table.
 */
@Service
public class OperatorContentService {

    private static final Logger log = LoggerFactory.getLogger(OperatorContentService.class);

    private final OperatorContentAccessRepository accessRepository;
    private final AppUserRepository userRepository;
    private final ContentFileRepository contentFileRepository;

    public OperatorContentService(OperatorContentAccessRepository accessRepository,
                                  AppUserRepository userRepository,
                                  ContentFileRepository contentFileRepository) {
        this.accessRepository = accessRepository;
        this.userRepository = userRepository;
        this.contentFileRepository = contentFileRepository;
    }

    /**
     * Content an operator was granted. Edge case: operator with no grants → empty list, not
     * an error. (This is the grant set only; an operator's own uploads are a separate path.)
     */
    @Transactional(readOnly = true)
    public List<ContentFile> getAccessibleContent(Long userId) {
        return accessRepository.findAccessibleContent(userId);
    }

    @Transactional
    public OperatorContentAccess grantAccess(AppUser user, ContentFile contentFile, String grantedBy) {
        if (accessRepository.existsByUserIdAndContentFileId(user.getId(), contentFile.getId())) {
            throw new IllegalStateException(
                    "User %d already has access to content %d".formatted(user.getId(), contentFile.getId()));
        }
        return accessRepository.save(new OperatorContentAccess(user, contentFile, grantedBy));
    }

    /**
     * Link a content file to an operator by ids. Only soft-deleted content is rejected
     * (treated as gone). FAILED/INVALID content may still be linked — the grant is a
     * permission relationship, not an availability claim.
     */
    @Transactional
    public OperatorContentAccess linkContent(Long userId, Long contentFileId, String grantedBy) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getRole() != Role.OPERATOR) {
            throw new IllegalStateException("Only OPERATOR users can be linked to content");
        }
        var content = contentFileRepository.findByIdAndDeletedAtIsNull(contentFileId)
                .orElseThrow(() -> new ResourceNotFoundException("ContentFile", contentFileId));
        return grantAccess(user, content, grantedBy);
    }

    /** Idempotent unlink — missing grant returns silently. */
    @Transactional
    public boolean unlinkContent(Long userId, Long contentFileId) {
        var existing = accessRepository.findByUserIdAndContentFileId(userId, contentFileId);
        if (existing.isEmpty()) {
            return false;
        }
        accessRepository.delete(existing.get());
        return true;
    }

    @Transactional
    public void revokeAccess(Long accessId) {
        var access = accessRepository.findById(accessId)
                .orElseThrow(() -> new ResourceNotFoundException("OperatorContentAccess", accessId));
        accessRepository.delete(access);
    }

    /**
     * Bulk-remove every grant for the user. Called when an operator is deleted — the FK would
     * otherwise block the delete, and orphan grants would survive a future username reuse.
     */
    @Transactional
    public int revokeAllForUser(Long userId) {
        int removed = accessRepository.deleteAllByUserId(userId);
        if (removed > 0) {
            log.info("Cascaded {} operator content access grants on operator deletion [userId={}]", removed, userId);
        }
        return removed;
    }
}
