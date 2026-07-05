package uz.orientadvertise.services.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AdvertiserContentAccess;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.AdvertiserContentAccessRepository;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

@Service
public class AdvertiserContentService {

    private static final Logger log = LoggerFactory.getLogger(AdvertiserContentService.class);

    private final AdvertiserContentAccessRepository accessRepository;
    private final AppUserRepository userRepository;
    private final ContentFileRepository contentFileRepository;

    public AdvertiserContentService(AdvertiserContentAccessRepository accessRepository,
                                     AppUserRepository userRepository,
                                     ContentFileRepository contentFileRepository) {
        this.accessRepository = accessRepository;
        this.userRepository = userRepository;
        this.contentFileRepository = contentFileRepository;
    }

    /**
     * Get content accessible to an advertiser.
     * Edge case: advertiser with no linked content → returns empty list, not error.
     */
    @Transactional(readOnly = true)
    public List<ContentFile> getAccessibleContent(Long userId) {
        return accessRepository.findAccessibleContent(userId);
    }

    @Transactional
    public AdvertiserContentAccess grantAccess(AppUser user, ContentFile contentFile, String grantedBy) {
        if (accessRepository.existsByUserIdAndContentFileId(user.getId(), contentFile.getId())) {
            throw new IllegalStateException(
                    "User %d already has access to content %d".formatted(user.getId(), contentFile.getId()));
        }
        return accessRepository.save(new AdvertiserContentAccess(user, contentFile, grantedBy));
    }

    /**
     * Link a content file to an advertiser by IDs.
     *
     * <p>Edge case: linking FAILED or INVALID content is intentionally allowed — the
     * grant is a permission relationship, not an availability claim. Operators may pre-link
     * content while it's still being processed, or keep historical access for audits after
     * a file is marked invalid. Only soft-deleted content is rejected (treated as "gone").
     */
    @Transactional
    public AdvertiserContentAccess linkContent(Long userId, Long contentFileId, String grantedBy) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getRole() != Role.ADVERTISER) {
            throw new IllegalStateException("Only ADVERTISER users can be linked to content");
        }
        var content = contentFileRepository.findByIdAndDeletedAtIsNull(contentFileId)
                .orElseThrow(() -> new ResourceNotFoundException("ContentFile", contentFileId));
        return grantAccess(user, content, grantedBy);
    }

    /**
     * Idempotent unlink — missing grant returns silently. Avoids 404 churn from retries.
     */
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
                .orElseThrow(() -> new ResourceNotFoundException("AdvertiserContentAccess", accessId));
        accessRepository.delete(access);
    }

    /**
     * Bulk-remove every access grant for the user. Called when an advertiser is deleted —
     * the FK constraint would otherwise block the user delete, and orphan grants would
     * survive a future username reuse.
     */
    @Transactional
    public int revokeAllForUser(Long userId) {
        int removed = accessRepository.deleteAllByUserId(userId);
        if (removed > 0) {
            log.info("Cascaded {} content access grants on advertiser deletion [userId={}]", removed, userId);
        }
        return removed;
    }
}
