package uz.orientadvertise.services.infra.repository;

import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression guard for the playlist reorder endpoint. {@code PlaylistItem.contentFile} is a
 * {@code LAZY @ManyToOne}, and {@code PlaylistController.reorderItems} maps the service result to
 * {@code PlaylistItemDto} <i>after</i> the {@code @Transactional} service method has returned and
 * the Hibernate session is closed — so the returned items must already have {@code contentFile}
 * initialized, or {@code PlaylistItemDto.from} (which reads {@code contentFile.getName()}) throws
 * {@link LazyInitializationException}.
 *
 * <p>The two repository methods differ exactly here: the plain {@code findByPlaylistIdOrderByPositionAsc}
 * leaves {@code contentFile} as an uninitialized proxy, while {@code findByPlaylistIdWithContentFile}
 * {@code JOIN FETCH}es it. Each repository call below runs in its own transaction and returns
 * detached entities, faithfully reproducing the controller's "session already closed" condition
 * without needing an explicit {@code EntityManager.clear()}.
 *
 * <p>Runs the real JPQL against H2 (PostgreSQL mode) under the full Flyway schema, mirroring
 * {@link ContentAssignmentOverlapBoundaryTest}.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class PlaylistItemContentFileFetchTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlaylistItemRepository itemRepository;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private ContentFileRepository contentFileRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private Long playlistId;

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        var project = projectRepository.save(new Project("ReorderProj", null));
        var playlist = playlistRepository.save(new Playlist(project, "ReorderPL", null));
        var first = contentFileRepository.save(
                new ContentFile(project, "first.mp4", "video/mp4", 1024, "uploads/first.mp4", null));
        var second = contentFileRepository.save(
                new ContentFile(project, "second.mp4", "video/mp4", 512, "uploads/second.mp4", null));
        itemRepository.save(new PlaylistItem(playlist, first, 0, null));
        itemRepository.save(new PlaylistItem(playlist, second, 1, null));
        playlistId = playlist.getId();
    }

    @Test
    void fetchJoinQuery_contentFileReadableAfterSessionCloses() {
        // This is the query reorderAll() returns. Reading contentFile fields on the detached
        // result must NOT throw — exactly what PlaylistItemDto.from does in the controller.
        List<PlaylistItem> items = itemRepository.findByPlaylistIdWithContentFile(playlistId);

        assertEquals(2, items.size());
        assertDoesNotThrow(() -> items.forEach(it -> {
            it.getContentFile().getId();
            it.getContentFile().getName();
            it.getContentFile().getDurationSeconds();
        }), "JOIN FETCH must initialize contentFile so DTO mapping survives a closed session");
        assertEquals("first.mp4", items.get(0).getContentFile().getName());
        assertEquals("second.mp4", items.get(1).getContentFile().getName());
    }

    @Test
    void plainQuery_contentFileIsLazyProxy_throwsAfterSessionCloses() {
        // Documents the original bug: the plain query leaves contentFile uninitialized, so the
        // very same DTO mapping blows up once the transaction (and session) has ended.
        List<PlaylistItem> items = itemRepository.findByPlaylistIdOrderByPositionAsc(playlistId);

        assertEquals(2, items.size());
        assertThrows(LazyInitializationException.class,
                () -> items.get(0).getContentFile().getName(),
                "plain query must leave contentFile lazy — proving the JOIN FETCH in reorderAll is required");
    }
}
