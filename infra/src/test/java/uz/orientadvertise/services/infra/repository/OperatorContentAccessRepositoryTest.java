package uz.orientadvertise.services.infra.repository;

import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.OperatorContentAccess;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.OperatorContentAccessRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The most correctness-bearing query in operator content scoping: a granted-then-soft-deleted file
 * must NOT leak back through {@code findContentIdsByUserId} / {@code findAccessibleContent}. Runs the
 * real JPQL against H2 (PostgreSQL mode) under the full Flyway schema (incl. V35).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class OperatorContentAccessRepositoryTest {

    @Autowired private DataSource dataSource;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ContentFileRepository contentFileRepository;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private OperatorContentAccessRepository accessRepository;

    private Long userId;
    private Long activeContentId;
    private Long deletedContentId;

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        var project = projectRepository.save(new Project("OpAccessProj", null));
        var operator = appUserRepository.save(new AppUser("op-access-repo-test", "password", Role.OPERATOR));
        var active = contentFileRepository.save(
                new ContentFile(project, "active.mp4", "video/mp4", 100, "k/active", null));
        var deleted = contentFileRepository.save(
                new ContentFile(project, "deleted.mp4", "video/mp4", 100, "k/deleted", null));
        deleted.softDelete();
        contentFileRepository.save(deleted);

        // Grant BOTH files — including the soft-deleted one — so the deletedAt filter is what
        // keeps it out of the operator's visible set, not the absence of a grant.
        accessRepository.save(new OperatorContentAccess(operator, active, "admin"));
        accessRepository.save(new OperatorContentAccess(operator, deleted, "admin"));

        userId = operator.getId();
        activeContentId = active.getId();
        deletedContentId = deleted.getId();
    }

    @Test
    void findContentIdsByUserId_excludesSoftDeletedContent() {
        List<Long> ids = accessRepository.findContentIdsByUserId(userId);
        assertEquals(List.of(activeContentId), ids);
        assertFalse(ids.contains(deletedContentId), "soft-deleted granted content must not leak");
    }

    @Test
    void findAccessibleContent_excludesSoftDeletedContent() {
        List<ContentFile> content = accessRepository.findAccessibleContent(userId);
        assertEquals(1, content.size());
        assertEquals(activeContentId, content.get(0).getId());
    }

    @Test
    void existsByUserIdAndContentFileId_trueForExistingGrant() {
        assertTrue(accessRepository.existsByUserIdAndContentFileId(userId, activeContentId));
        // The grant row exists even for the soft-deleted file (it was never row-deleted)...
        assertTrue(accessRepository.existsByUserIdAndContentFileId(userId, deletedContentId));
    }

    @Test
    void uniqueConstraint_preventsDuplicateGrant() {
        // uq_op_content_access (user_id, content_file_id) — a duplicate save violates it.
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            var op = appUserRepository.findById(userId).orElseThrow();
            var active = contentFileRepository.findById(activeContentId).orElseThrow();
            accessRepository.saveAndFlush(new OperatorContentAccess(op, active, "admin"));
        });
    }
}
