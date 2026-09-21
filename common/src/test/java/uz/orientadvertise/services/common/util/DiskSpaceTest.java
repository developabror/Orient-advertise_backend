package uz.orientadvertise.services.common.util;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskSpaceTest {

    @Test
    void freePercent_isFlooredWholePercent() {
        assertEquals(4, new DiskSpace("/", 100, 4).freePercent());
        assertEquals(0, new DiskSpace("/", 100, 0).freePercent());
        assertEquals(100, new DiskSpace("/", 100, 100).freePercent());
        // 386 MB free of 8.1 G — the reading that started this whole piece of work.
        assertEquals(4, new DiskSpace("/", 8_100_000_000L, 386_000_000L).freePercent());
    }

    @Test
    void freePercent_zeroTotal_doesNotDivideByZero() {
        // Some virtual filesystems report a zero total. A crash in a health probe is worse than a
        // meaningless number, and this is called from an unauthenticated endpoint.
        assertEquals(0, new DiskSpace("/proc", 0, 0).freePercent());
    }

    @Test
    void humanBytes_formatsAtAppropriateScale() {
        assertEquals("512 B", DiskSpace.humanBytes(512));
        assertEquals("1.0 KB", DiskSpace.humanBytes(1024));
        assertEquals("1.0 MB", DiskSpace.humanBytes(1024L * 1024));
        assertEquals("1.0 GB", DiskSpace.humanBytes(1024L * 1024 * 1024));
        assertEquals("2.0 TB", DiskSpace.humanBytes(2L * 1024 * 1024 * 1024 * 1024));
    }

    @Test
    void describe_carriesEverythingAnOperatorNeeds() {
        String described = new DiskSpace("/", 8L * 1024 * 1024 * 1024, 386L * 1024 * 1024).describe();

        assertTrue(described.contains("free=386.0 MB"), described);
        assertTrue(described.contains("total=8.0 GB"), described);
        assertTrue(described.contains("4% free"), described);
        assertTrue(described.contains("path=/"), described);
    }

    @Test
    void probe_readsARealFilesystem(@TempDir Path tempDir) throws IOException {
        DiskSpace space = DiskSpace.probe(tempDir.toString());

        assertTrue(space.totalBytes() > 0, "a real filesystem reports a total");
        assertTrue(space.usableBytes() >= 0);
        assertTrue(space.freePercent() >= 0 && space.freePercent() <= 100);
        assertEquals(tempDir.toString(), space.path());
    }

    @Test
    void probe_nullOrBlankPath_fallsBackToTheWorkingDirectory() throws IOException {
        assertEquals(".", DiskSpace.probe(null).path());
        assertEquals(".", DiskSpace.probe("   ").path());
    }

    @Test
    void probe_nonexistentPath_throwsRatherThanReportingFakeSpace() {
        // Callers must surface "probe failed", not a fabricated zero that would read as a full disk
        // and trigger a false alarm.
        assertThrows(IOException.class, () -> DiskSpace.probe("/definitely/not/a/real/path/xyzzy"));
    }
}
