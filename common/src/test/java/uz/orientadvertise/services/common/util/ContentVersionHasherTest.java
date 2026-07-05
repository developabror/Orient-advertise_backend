package uz.orientadvertise.services.common.util;

import java.util.List;

import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.util.ContentVersionHasher.FileRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentVersionHasherTest {

    @Test
    void hash_isDeterministic_sameInputsSameHash() {
        var files = List.of(new FileRef(10L, "k1", 30), new FileRef(20L, "k2", 15));
        var h1 = ContentVersionHasher.hash(1L, 1, 5L, files);
        var h2 = ContentVersionHasher.hash(1L, 1, 5L, files);
        assertEquals(h1, h2);
    }

    @Test
    void hash_isHex64Chars() {
        var h = ContentVersionHasher.hash(1L, 1, 5L, List.of());
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
    }

    @Test
    void hash_changesWhenFileOrderChanges() {
        var ordered = List.of(new FileRef(10L, "k1", 30), new FileRef(20L, "k2", 15));
        var reversed = List.of(new FileRef(20L, "k2", 15), new FileRef(10L, "k1", 30));
        assertNotEquals(
                ContentVersionHasher.hash(1L, 1, 5L, ordered),
                ContentVersionHasher.hash(1L, 1, 5L, reversed));
    }

    @Test
    void hash_changesWhenFileReplaced() {
        var v1 = List.of(new FileRef(10L, "k1", 30));
        var v2 = List.of(new FileRef(11L, "k2", 30));
        assertNotEquals(
                ContentVersionHasher.hash(1L, 1, 5L, v1),
                ContentVersionHasher.hash(1L, 1, 5L, v2));
    }

    @Test
    void hash_changesWhenVersionNumberBumps_evenIfContentIdentical() {
        // The rollback case: identical playlist content but different version number
        // → different hash. Devices can tell rollback apart from no-change.
        var files = List.of(new FileRef(10L, "k1", 30));
        var beforeRollback = ContentVersionHasher.hash(1L, 1, 5L, files);
        var afterRollback = ContentVersionHasher.hash(1L, 2, 5L, files);
        assertNotEquals(beforeRollback, afterRollback);
    }

    @Test
    void hash_differsAcrossAssignments_evenWithSamePlaylist() {
        var files = List.of(new FileRef(10L, "k1", 30));
        assertNotEquals(
                ContentVersionHasher.hash(1L, 1, 5L, files),
                ContentVersionHasher.hash(2L, 1, 5L, files));
    }

    @Test
    void hash_handlesNullProcessedKey() {
        var files = List.of(new FileRef(10L, null, 30));
        var h = ContentVersionHasher.hash(1L, 1, 5L, files);
        assertEquals(64, h.length());
    }

    @Test
    void hash_changesWhenEffectiveDurationEdited_andRestoringReturnsOriginal() {
        // The synchronized-playback invariant: a pure dwell-time edit must yield a new
        // contentVersion so offline/reconnecting devices re-anchor to the new slot timings.
        var original = List.of(new FileRef(10L, "k1", 30), new FileRef(20L, "k2", 15));
        var edited = List.of(new FileRef(10L, "k1", 45), new FileRef(20L, "k2", 15));
        var restored = List.of(new FileRef(10L, "k1", 30), new FileRef(20L, "k2", 15));

        var hOriginal = ContentVersionHasher.hash(1L, 1, 5L, original);
        var hEdited = ContentVersionHasher.hash(1L, 1, 5L, edited);
        var hRestored = ContentVersionHasher.hash(1L, 1, 5L, restored);

        assertNotEquals(hOriginal, hEdited, "a dwell-time edit must change the hash");
        assertEquals(hOriginal, hRestored, "restoring the duration must return the original hash");
    }

    @Test
    void hash_handlesNullEffectiveDuration() {
        // A file with no natural duration and no override (e.g. an image awaiting dwell config)
        // must still hash to a valid digest, distinct from a defined-duration variant.
        var nullDuration = List.of(new FileRef(10L, "k1", null));
        var withDuration = List.of(new FileRef(10L, "k1", 15));

        var h = ContentVersionHasher.hash(1L, 1, 5L, nullDuration);
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
        assertNotEquals(h, ContentVersionHasher.hash(1L, 1, 5L, withDuration));
    }
}
