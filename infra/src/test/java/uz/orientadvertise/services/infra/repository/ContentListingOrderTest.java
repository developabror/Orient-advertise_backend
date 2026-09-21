package uz.orientadvertise.services.infra.repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ordering contract of {@code GET /api/content}, exercised against the real query.
 *
 * <p>{@link ContentFileRepository#findFiltered} carries no {@code ORDER BY} of its own, so an
 * unsorted request paginates in unspecified DB order — and that order <b>shifts as the transcode
 * pipeline UPDATEs rows</b>, which is enough to move a row between pages while an operator is
 * paging, or to drop it from the listing entirely. {@code ContentListService} fixes that by putting
 * {@link ContentFileRepository#DEFAULT_LISTING_SORT} on every request; this test proves the sort
 * actually produces a <i>total</i> order once it reaches SQL.
 *
 * <p>The fixture is the case a {@code createdAt}-only sort cannot decide: two rows created in the
 * same instant. A unit test on the {@code Pageable} cannot see this — only a real database can.
 *
 * <p>Lives in the infra module because that is the only place wired with {@code @SpringBootTest} +
 * Flyway + H2 in PostgreSQL mode.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class ContentListingOrderTest {

    /** Two rows share this instant; the third is one minute newer. */
    private static final String SHARED_INSTANT = "TIMESTAMP '2026-01-01 10:00:00'";
    private static final String LATER_INSTANT = "TIMESTAMP '2026-01-01 10:01:00'";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ContentFileRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            // Orphan content (project_id NULL, allowed since V31) keeps the fixture to one table.
            // Ids are inserted OUT of creation order so a query that accidentally orders by id or
            // by insertion order cannot pass by luck.
            insert(stmt, 3002, "b.mp4", SHARED_INSTANT);
            insert(stmt, 3003, "c.mp4", LATER_INSTANT);
            insert(stmt, 3001, "a.mp4", SHARED_INSTANT);
        }
    }

    private static void insert(Statement stmt, long id, String name, String createdAt) throws Exception {
        stmt.execute(("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, "
                + "storage_key, status, created_at, updated_at) "
                + "VALUES (%d, NULL, '%s', 'video/mp4', 100, 'raw/%d.mp4', 'UPLOADED', %s, %s)")
                .formatted(id, name, id, createdAt, createdAt));
    }

    private List<Long> idsOnPage(int page, int size) {
        return repository.findFiltered(null, null, null,
                        PageRequest.of(page, size, ContentFileRepository.DEFAULT_LISTING_SORT))
                .map(ContentFile::getId)
                .getContent();
    }

    @Test
    void listing_returnsNewestFirst_withIdBreakingTheTieOnEqualTimestamps() {
        // 3003 is the newest. 3001 and 3002 share createdAt to the microsecond, so only the id
        // tiebreaker can decide between them — descending, so the newer row of the pair leads.
        assertEquals(List.of(3003L, 3002L, 3001L), idsOnPage(0, 10));
    }

    @Test
    void listing_isStableAcrossIdenticalRequests() {
        // The defect this pins: with no ORDER BY the DB may return either of the tied rows first,
        // and it may answer differently on the next call.
        assertEquals(idsOnPage(0, 10), idsOnPage(0, 10));
    }

    @Test
    void paging_neitherRepeatsNorSkipsARowAcrossPageBoundaries() {
        // The operator-visible symptom of a non-total order: page 1 re-showing a row from page 0,
        // and the row it displaced never appearing at all.
        List<Long> first = idsOnPage(0, 2);
        List<Long> second = idsOnPage(1, 2);

        assertEquals(List.of(3003L, 3002L), first);
        assertEquals(List.of(3001L), second);
        assertTrue(first.stream().noneMatch(second::contains), "pages must not overlap");
    }
}
