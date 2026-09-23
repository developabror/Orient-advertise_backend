package uz.orientadvertise.services.api.dto;

import java.util.List;

import uz.orientadvertise.services.service.PlaylistControlService.ActivePlaylistView;

/**
 * Response for {@code GET /api/devices/{id}/active-playlist}: what the device is playing, as the
 * operator panel lists it.
 *
 * <p>This is the operator view, not the device's own {@code GET /api/devices/{id}/playlist}: no
 * presigned URLs and no storage keys, so it is safe for VIEWER. {@code items} is the deliverable
 * subset in play order; {@code index} is exactly what {@code POST /playlist/control} takes as a
 * JUMP {@code position}. An unassigned device returns 200 with a null {@code playlistId} and no
 * items, because "nothing assigned" is a normal state, not an error.
 *
 * <p>{@code scheduled} = the device is in synchronised (group) playback, where it refuses
 * per-device transport commands; the UI disables them and offers the sync-group jump instead.
 */
public record ActivePlaylistResponse(Long deviceId, Long playlistId, String playlistName,
                                     long totalDurationSeconds, boolean scheduled,
                                     List<Item> items) {

    /** {@code durationSeconds} is the slot the device actually plays, so it is never null. */
    public record Item(int index, int position, Long fileId, String name, long durationSeconds) {}

    public static ActivePlaylistResponse from(ActivePlaylistView v) {
        var items = v.items().stream()
                .map(i -> new Item(i.index(), i.position(), i.fileId(), i.title(), i.durationSeconds()))
                .toList();
        return new ActivePlaylistResponse(v.deviceId(), v.playlistId(), v.playlistName(),
                Math.round(v.loopDurationMs() / 1000.0), v.scheduled(), items);
    }
}
