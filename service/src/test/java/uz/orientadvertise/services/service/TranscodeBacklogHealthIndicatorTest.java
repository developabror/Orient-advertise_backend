package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The alarm whose absence let two production uploads sit unnoticed until a user complained:
 * nothing threw, so {@code GlobalExceptionHandler}'s 500-forwarding was never going to fire.
 */
class TranscodeBacklogHealthIndicatorTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private ContentFileRepository repository;
    private TranscodeBacklogHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        repository = mock(ContentFileRepository.class);
        indicator = new TranscodeBacklogHealthIndicator(repository, STALE_AFTER);
    }

    @Test
    void noBacklog_isUp() {
        when(repository.countStaleUploaded(any(Instant.class))).thenReturn(0L);

        var status = indicator.check();

        assertEquals("transcode-backlog", status.component());
        assertTrue(status.isUp());
    }

    @Test
    void stuckFiles_reportDownWithTheCount() {
        when(repository.countStaleUploaded(any(Instant.class))).thenReturn(2L);

        var status = indicator.check();

        assertInstanceOf(HealthStatus.Status.Down.class, status.status());
        if (status.status() instanceof HealthStatus.Status.Down(var reason)) {
            assertTrue(reason.contains("2 content file(s)"), reason);
            assertTrue(reason.contains("UPLOADED"), reason);
        }
    }

    @Test
    void cutoffIsTheConfiguredStaleWindowBeforeNow() {
        // The same property drives the sweeper's WARN, so the health surface and the Telegram alert
        // can never disagree about what "stuck" means.
        when(repository.countStaleUploaded(any(Instant.class))).thenReturn(0L);
        Instant before = Instant.now().minus(STALE_AFTER);

        indicator.check();

        var cutoff = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(repository).countStaleUploaded(cutoff.capture());
        assertTrue(!cutoff.getValue().isBefore(before.minusSeconds(5))
                        && !cutoff.getValue().isAfter(Instant.now().minus(STALE_AFTER).plusSeconds(5)),
                "cutoff should be ~now - " + STALE_AFTER + " but was " + cutoff.getValue());
    }

    @Test
    void probeFailure_isReportedDownWithoutLeakingTheCause() {
        // /api/health is unauthenticated: it must never 500 and must never surface a JDBC error.
        when(repository.countStaleUploaded(any(Instant.class)))
                .thenThrow(new RuntimeException("jdbc:postgresql://db:5432/appdb password=hunter2"));

        var status = indicator.check();

        assertInstanceOf(HealthStatus.Status.Down.class, status.status());
        if (status.status() instanceof HealthStatus.Status.Down(var reason)) {
            assertEquals("backlog query failed", reason);
            assertTrue(!reason.contains("jdbc"), "must not echo connection detail to an anonymous caller");
        }
    }
}
