package uz.orientadvertise.services.api.controller;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.service.PlaybackLogService;
import uz.orientadvertise.services.service.PlaybackLogService.BatchRecordResult;
import uz.orientadvertise.services.service.PlaybackLogService.PlaybackEntry;

/**
 * Device-side playback reporting. Accepts either a single object or an array of
 * objects, both with the shape {@code {"contentFileId":..., "playedAt":..., "durationSeconds":...}}.
 * The service tallies created / duplicate / rejected per entry and returns 200 with the
 * summary — partial success is normal and not an error.
 *
 * <p>Edge cases enforced server-side:
 * <ul>
 *   <li>{@code playedAt} in the future (beyond clock-skew tolerance) → entry rejected with reason</li>
 *   <li>{@code playedAt} older than 90 days → entry rejected (matches retention window)</li>
 *   <li>Duplicate {@code (deviceId, contentFileId, playedAt)} → silently ignored (idempotent),
 *       counted in {@code duplicate}. Dedup happens in the INSERT itself
 *       ({@code ON CONFLICT DO NOTHING}), so a duplicate can never abort the batch transaction
 *       or discard the entries around it — this endpoint does not 500 on a repeated entry.</li>
 *   <li>Batch size &gt; 500 → 400 (whole request rejected; client must chunk)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/devices")
public class PlaybackController {

    private final PlaybackLogService playbackLogService;
    private final ObjectMapper objectMapper;

    public PlaybackController(PlaybackLogService playbackLogService, ObjectMapper objectMapper) {
        this.playbackLogService = playbackLogService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{id}/playback")
    @PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")
    public ResponseEntity<BatchResponse> record(@PathVariable Long id,
                                                 @RequestBody JsonNode body) {
        List<PlaybackEntry> entries = parseEntries(body);
        BatchRecordResult result = playbackLogService.recordBatch(id, entries);
        return ResponseEntity.ok(BatchResponse.from(result, entries.size()));
    }

    /**
     * Accept either a single object or a JSON array. Manual parsing keeps a single
     * endpoint URL serving both shapes — clients pick whichever fits.
     */
    private List<PlaybackEntry> parseEntries(JsonNode body) {
        if (body == null || body.isNull()) {
            throw new IllegalArgumentException("Request body is required");
        }
        var entries = new ArrayList<PlaybackEntry>();
        if (body.isArray()) {
            for (Iterator<JsonNode> it = body.elements(); it.hasNext(); ) {
                entries.add(parseSingle(it.next()));
            }
        } else if (body.isObject()) {
            entries.add(parseSingle(body));
        } else {
            throw new IllegalArgumentException("Body must be a JSON object or array");
        }
        return entries;
    }

    private PlaybackEntry parseSingle(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, PlaybackEntry.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed playback entry: " + e.getMessage());
        }
    }

    public record BatchResponse(int total, int created, int duplicate, int rejected,
                                 List<RejectionDto> rejections) {
        public static BatchResponse from(BatchRecordResult r, int total) {
            return new BatchResponse(total, r.created(), r.duplicate(), r.rejected(),
                    r.rejections().stream()
                            .map(rej -> new RejectionDto(rej.index(), rej.reason()))
                            .toList());
        }
    }

    public record RejectionDto(int index, String reason) {}
}
