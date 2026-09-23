package uz.orientadvertise.services.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.PlaylistItem;

/**
 * Shared synchronized-playback slot timeline. Given an ORDERED, ALREADY-FILTERED list of the playlist
 * items to include in the loop, it computes each item's contiguous 0-based {@code index}, its EFFECTIVE
 * per-item duration (per-item override ?? the file's natural duration), and the loop-relative slot start
 * offset (prefix sum) plus slot length in ms.
 *
 * <p>The slot MATH lives here <b>once</b> so {@link DeviceSyncService#computeSyncPlan} (the device
 * {@code /sync} wire) and {@link SyncGroupPlaybackService} (the operator group-jump re-anchor) can never
 * disagree on a slot's start/length for the same item list. The CALLER owns the inclusion filter: /sync
 * ships only the files it actually built a URL for this round (a device-/storage-dependent subset),
 * while the jump/view path uses {@link DeviceSyncService#isDeliverable}. Because /sync's set can be a
 * strict subset in a storage-inconsistency edge case, the two timelines agree exactly only when every
 * deliverable file is URL-buildable — the common case (documented on {@link SyncGroupPlaybackService}).
 *
 * <p>Every included item MUST have a non-null {@link ContentFile} (the caller's filter guarantees it).
 */
public final class PlaybackSlotTimeline {

    private static final Logger log = LoggerFactory.getLogger(PlaybackSlotTimeline.class);

    /**
     * Fallback slot length for a deliverable item with no usable effective duration (an image with
     * neither an operator dwell nor a natural duration). Emitting a 0-ms slot would collapse the loop
     * and break {@code floorMod(now - anchor, loopDuration)} positioning, so such an item dwells for
     * this default instead; the fallback is logged so ops can supply a real dwell.
     */
    private static final long DEFAULT_SLOT_DURATION_SECONDS = 10L;

    private PlaybackSlotTimeline() {
    }

    /**
     * One slot of the loop. {@code index} is the contiguous 0-based ordinal over the delivered list;
     * {@code position} is the raw (possibly sparse) playlist slot; {@code effectiveSeconds} may be null
     * when it fell back to the default dwell.
     */
    public record Slot(int index, int position, Long fileId, String title,
                       Integer effectiveSeconds, long slotStartMs, long slotDurationMs) {}

    /** The full loop: ordered slots plus the total loop length (Σ slotDurationMs; 0 when empty). */
    public record Timeline(List<Slot> slots, long loopDurationMs) {}

    /**
     * Build the timeline over the ordered items to include. The caller has already applied its
     * inclusion filter; each item's content file must be non-null.
     */
    public static Timeline of(List<PlaylistItem> includedInOrder) {
        return ofInputs(includedInOrder.stream().map(it -> {
            ContentFile f = it.getContentFile();
            return new Input(it.getPosition(), f.getId(), f.getName(),
                    it.getDurationSeconds() != null ? it.getDurationSeconds() : f.getDurationSeconds());
        }).toList());
    }

    /**
     * One item's timeline inputs, free of JPA. {@code effectiveSeconds} is the per-item dwell if set,
     * else the file's own length, else null (the default slot applies).
     */
    public record Input(int position, Long fileId, String title, Integer effectiveSeconds) {}

    /**
     * Same math over detached data. {@code /sync} builds its plan OUTSIDE any transaction since
     * VG-07, so nothing there may touch a lazy association — it reads the playlist into
     * {@link Input}s while the read transaction is open and lays out the loop afterwards.
     */
    public static Timeline ofInputs(List<Input> includedInOrder) {
        List<Slot> slots = new ArrayList<>(includedInOrder.size());
        int index = 0;
        long slotStartMs = 0L;
        for (Input it : includedInOrder) {
            long slotDurationMs = slotDurationMs(it.effectiveSeconds(), it.fileId());
            slots.add(new Slot(index++, it.position(), it.fileId(), it.title(),
                    it.effectiveSeconds(), slotStartMs, slotDurationMs));
            slotStartMs += slotDurationMs;
        }
        return new Timeline(List.copyOf(slots), slotStartMs);
    }

    private static long slotDurationMs(Integer effectiveSeconds, Long fileId) {
        if (effectiveSeconds != null && effectiveSeconds > 0) {
            return effectiveSeconds * 1000L;
        }
        log.warn("Deliverable file {} has no positive effective duration ({}) — using default {}s dwell "
                + "for its sync slot; supply a dwell to fix the loop timing",
                fileId, effectiveSeconds, DEFAULT_SLOT_DURATION_SECONDS);
        return DEFAULT_SLOT_DURATION_SECONDS * 1000L;
    }
}
