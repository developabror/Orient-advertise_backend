package uz.orientadvertise.services.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Operator-issued device action types accepted by {@code POST /service/devices/{id}/actions}.
 *
 * <p>Stored as a string in {@code remote_action.action_type} alongside other system-issued
 * types (e.g. {@code PLAYLIST_CONTROL} from the transport endpoint). Keeping the column
 * a free-form VARCHAR avoids a coupled migration each time a new action is added; the
 * enum here is the authoritative list at the API boundary.
 */
@Schema(description = """
        Remote action a device executes on next pickup. Devices poll
        `GET /api/devices/{id}/actions/pending` or receive a WebSocket push.
        """,
        enumAsRef = true,
        example = "REBOOT")
public enum DeviceActionType {

    @Schema(description = "Soft-reboot the device. No payload.")
    REBOOT,

    @Schema(description = "Re-fetch content sync diff. Used after an out-of-band content change.")
    SYNC_CONTENT,

    @Schema(description = "Set audio output volume. Requires `volume` 0–100; rejected with 400 otherwise.")
    VOLUME_SET,

    @Schema(description = "Pause current playlist playback. Resumed via PLAYBACK_RESUME.")
    PLAYBACK_PAUSE,

    @Schema(description = "Resume playback after a PLAYBACK_PAUSE.")
    PLAYBACK_RESUME,

    @Schema(description = "Request a diagnostics dump (logs, network, current content). "
            + "Result returned to the device-confirm endpoint.")
    GET_DIAGNOSTICS
}
