package uz.orientadvertise.services.service.exception;

import java.time.Instant;
import java.util.List;

import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentAssignment.TargetType;

/**
 * Thrown when a new (or confirming) assignment's {@code [startTime, endTime)} window overlaps
 * an existing CONFIRMED assignment on the same target.
 *
 * <p>Extends {@link IllegalStateException} so it retains the established 409-Conflict mapping
 * even if the dedicated handler is absent; {@code GlobalExceptionHandler} adds a
 * machine-readable {@code details.conflicts} payload so the frontend can render an actionable
 * message ("occupied until X — pick a later start") instead of parsing the free-text sentence.
 *
 * <p>Internal identifiers (the conflicting ids, the {@code targetId}) live ONLY in the
 * structured fields, never in {@link #getMessage()} — keeping the human sentence non-leaky per
 * the security guidance.
 */
public class AssignmentTimeOverlapException extends IllegalStateException {

    private final TargetType targetType;
    private final Long targetId;
    private final List<Conflict> conflicts;

    public AssignmentTimeOverlapException(TargetType targetType, Long targetId, List<Conflict> conflicts) {
        super("Time overlap with existing assignment(s) for the selected target");
        this.targetType = targetType;
        this.targetId = targetId;
        this.conflicts = List.copyOf(conflicts);
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public List<Conflict> getConflicts() {
        return conflicts;
    }

    /**
     * A single conflicting assignment: its id, the playlist it carries (id + name, so the operator
     * can decide whether to replace it), its {@code status}, its {@code [startTime, endTime)} window
     * (UTC), {@code conflictingDeviceIds} — the devices BOTH this assignment and the new one
     * actually drive (the device-set intersection that makes it a real conflict) — and
     * {@code remainingDeviceCount}, how many of ITS devices this assignment would keep driving if
     * the operator replaces. Playlist fields are null-safe. {@code conflictingDeviceIds} is never
     * null on the wire (empty list when the intersection was not computed, e.g. the conservative
     * {@code createAssignment} path).
     *
     * <p>{@code remainingDeviceCount > 0} means Replace <b>narrows</b> this assignment instead of
     * retiring it, so the FE can say "2 devices keep <i>Korzinka promo</i>" rather than implying
     * the whole booking is deleted. It is {@code 0} on the conservative path (unknown), which reads
     * the same as "nothing is left behind" — the FE treats the field as advisory copy only.
     */
    public record Conflict(Long id, Long playlistId, String playlistName, String status,
                           Instant startTime, Instant endTime, List<Long> conflictingDeviceIds,
                           int remainingDeviceCount) {
        // Normalize: the device-id list is always a non-null, immutable list (deterministic order
        // preserved by the caller) so it serializes as a JSON array the FE can read without guards.
        public Conflict {
            conflictingDeviceIds = conflictingDeviceIds == null ? List.of() : List.copyOf(conflictingDeviceIds);
        }

        /** Back-compat: device-aware conflict with no remainder information → 0. */
        public Conflict(Long id, Long playlistId, String playlistName, String status,
                        Instant startTime, Instant endTime, List<Long> conflictingDeviceIds) {
            this(id, playlistId, playlistName, status, startTime, endTime, conflictingDeviceIds, 0);
        }

        /** Back-compat / conservative path: no per-device intersection computed → empty list. */
        public Conflict(Long id, Long playlistId, String playlistName, String status,
                        Instant startTime, Instant endTime) {
            this(id, playlistId, playlistName, status, startTime, endTime, List.of(), 0);
        }

        /** Conservative conflict (no device intersection) — used by the same-target reject path. */
        public static Conflict from(ContentAssignment a) {
            return from(a, List.of(), 0);
        }

        /** Device-aware conflict carrying the intersecting device ids. */
        public static Conflict from(ContentAssignment a, List<Long> conflictingDeviceIds) {
            return from(a, conflictingDeviceIds, 0);
        }

        /** Device-aware conflict carrying the intersection AND the devices left behind on replace. */
        public static Conflict from(ContentAssignment a, List<Long> conflictingDeviceIds,
                                    int remainingDeviceCount) {
            var playlist = a.getPlaylist();
            return new Conflict(
                    a.getId(),
                    playlist != null ? playlist.getId() : null,
                    playlist != null ? playlist.getName() : null,
                    a.getStatus() != null ? a.getStatus().name() : null,
                    a.getStartTime(),
                    a.getEndTime(),
                    conflictingDeviceIds,
                    remainingDeviceCount);
        }
    }
}
