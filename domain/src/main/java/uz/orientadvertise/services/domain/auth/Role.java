package uz.orientadvertise.services.domain.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Application role. Embedded in JWT access tokens as the {@code roles} claim and
 * checked at endpoints via {@code @PreAuthorize("hasRole(...)")}. The set is closed —
 * adding a new role is a deliberate API-contract change.
 */
@Schema(description = """
        Caller role. Drives @PreAuthorize checks across the API.
        - **ADMIN** — full access incl. user/key lifecycle
        - **OPERATOR** — device + content operations, no admin lifecycle
        - **VIEWER** — read-only dashboards, reports
        - **ADVERTISER** — scoped to their granted content files only
        """,
        enumAsRef = true,
        example = "OPERATOR")
public enum Role {

    @Schema(description = "Full administrative access including user and API-key lifecycle")
    ADMIN,

    @Schema(description = "Device + content + scheduling operations; cannot create users or API keys")
    OPERATOR,

    @Schema(description = "Read-only dashboards, reports, event search; no mutations")
    VIEWER,

    @Schema(description = "Partner role scoped to their granted content files; cannot see other tenants' data")
    ADVERTISER
}
