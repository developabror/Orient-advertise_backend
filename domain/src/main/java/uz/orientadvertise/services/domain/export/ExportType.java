package uz.orientadvertise.services.domain.export;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        Excel export sheet selector for `GET /api/reports/export`.
        Each value produces a different sheet and column set.
        """,
        enumAsRef = true,
        example = "DEVICES")
public enum ExportType {

    @Schema(description = "Event log: ID, occurred-at, device, type, priority, payload. "
            + "Filtered by `facilityId`/`deviceId`/`from`/`to`.")
    EVENTS,

    @Schema(description = "Device roster: serial, name, status, region, facility, "
            + "heartbeat, content version, last IP. Excludes soft-deleted rows.")
    DEVICES,

    @Schema(description = "Per-content playback aggregation: content id, name, "
            + "total plays, distinct devices. Defaults to the trailing 30 days.")
    STATS
}
