# Orient-advertise-backend

Multi-module Spring Boot application with strict architectural layering enforced via Gradle module boundaries and ArchUnit tests.

## Version

`1.0.153`

## Architecture

```
┌─────────────────────────────────────────────┐
│                   api                        │
│  (controllers, DTOs, application entry)      │
├──────────┬──────────────┬───────────────────┤
│          │   service    │                    │
│          │ (business    │                    │
│          │  logic)      │                    │
│          ├──────┬───────┤                    │
│          │ infra│       │                    │
│          │(ext. │domain │                    │
│          │integ)│(model)│                    │
├──────────┴──────┴───────┴───────────────────┤
│                 common                       │
│           (shared utilities)                 │
└─────────────────────────────────────────────┘
```

### Module Dependencies (strict, enforced)

| Module  | Depends on               | Cannot depend on       |
|---------|--------------------------|------------------------|
| api     | service, domain, common  | infra (runtimeOnly)    |
| service | domain, infra, common    | api                    |
| infra   | domain, common           | api, service           |
| domain  | common                   | api, service, infra    |
| common  | (none)                   | all internal modules   |

### Enforcement

1. **Compile-time**: Gradle `implementation` vs `runtimeOnly` prevents importing forbidden modules
2. **Test-time**: ArchUnit `LayerDependencyTest` fails CI if any layer boundary is violated

## Tech Stack

- Java 21
- Spring Boot 3.5.16
- Gradle 8.11 (multi-module)
- PostgreSQL 17 (production)
- Redis 7 (session/token cache + pub/sub)
- MinIO (S3-compatible object storage)
- H2 with PostgreSQL mode (dev profile and tests only — never in the production jar)
- Flyway (versioned schema migrations)
- HikariCP (connection pooling)
- Lettuce (Redis client with auto-reconnect)
- JJWT (JWT token creation/validation)
- Spring Security (stateless JWT authentication)
- Springdoc OpenAPI 2.9 + Swagger UI (API documentation)
- ArchUnit (architecture tests)

## Authentication

JWT-based authentication with access/refresh token rotation and **refresh token reuse attack detection**.

### Endpoints

| Method | Path           | Description                                                   | Auth Required |
|--------|----------------|---------------------------------------------------------------|---------------|
| POST   | /auth/login    | Authenticate, get access token + refresh cookie               | No            |
| POST   | /auth/refresh  | Rotate refresh cookie, mint new access token                  | No            |
| POST   | /auth/logout   | Invalidate token family, clear refresh cookie                 | No            |

### Token Details

| Token   | Transport                                                         | Storage         | TTL      | Format            |
|---------|-------------------------------------------------------------------|-----------------|----------|-------------------|
| Access  | JSON body (login/refresh) → FE sends `Authorization: Bearer <jwt>` | Client memory   | 15 min   | JWT (HMAC-SHA256) |
| Refresh | `refresh_token` cookie: `HttpOnly; Secure; SameSite=Strict; Path=/auth` | Redis           | 7 days   | UUID (opaque)     |

The refresh token is **never exposed to JavaScript** — `HttpOnly` prevents an XSS payload from exfiltrating it, `SameSite=Strict` blocks cross-site CSRF, and `Path=/auth` ensures the browser only attaches it to the three auth endpoints. FE callers must send `credentials: 'include'` on `POST /auth/login`, `POST /auth/refresh`, and `POST /auth/logout`; on bootstrap, calling `POST /auth/refresh` will silently restore the session if the cookie is still valid.

### Refresh Token Reuse Attack Detection

Each login creates a **token family** (UUID). On refresh, the old token is deleted and a new one is issued in the same family. If a previously-used (rotated) refresh token is presented again:
- It won't be found in Redis (already deleted)
- This is detected as a **reuse attack**
- Returns HTTP 401

On logout, the **entire token family** is invalidated — all refresh tokens in the family are deleted.

### Security Guarantees

- Expired access token → **401** (never 403)
- Missing token → **401** (never 403)
- Invalid token → **401**
- Refresh token reuse → **401** + family invalidation
- Insufficient role → **403** (authenticated but not authorized)

## Role-Based Access Control (RBAC)

### 4 Roles

| Role       | Capabilities                              |
|------------|-------------------------------------------|
| ADMIN      | Full access — upload, delete, status, all  |
| OPERATOR   | Upload, delete, download files             |
| VIEWER     | Download files, generate presigned URLs    |
| ADVERTISER | Download files, generate presigned URLs    |

### Method-Level Authorization (`@PreAuthorize`)

| Endpoint             | Required Role            |
|----------------------|--------------------------|
| POST /api/files      | ADMIN, OPERATOR          |
| DELETE /api/files/*   | ADMIN, OPERATOR          |
| GET /api/files/status | ADMIN only               |
| GET /api/files/*      | Any authenticated user   |
| GET /api/files/*/presigned-url | Any authenticated user |

### Edge Cases

- **User with no role** → rejected at **login time** (401), not at API level
- **Role change takes effect on next token refresh** — roles are baked into the JWT access token; when `POST /auth/refresh` is called, the user's **current** role is fetched from the repository and embedded in the new access token
- **Role removed after login** → next refresh invalidates the token family

### Default Users — dev only (`APP_SEED_ENABLED=true`)

V12/V33 seed these accounts (plus the owner account `developabror@gmail.com`, ADMIN) in every
database, but since v1.0.136 **V45 deactivates them** and they
are only usable where seed data is enabled (`dev` profile, or `APP_SEED_ENABLED=true` in `.env`),
where `DefaultLoginPolicy` re-activates them at startup:

| Username    | Password | Role       | Active (seed on) |
|-------------|----------|------------|------------------|
| admin       | password | ADMIN      | yes    |
| operator    | password | OPERATOR   | yes    |
| viewer      | password | VIEWER     | yes    |
| advertiser  | password | ADVERTISER | yes    |
| deactivated | password | VIEWER     | **no** |

**With seed data off, the app refuses to start** while any active account still has the default
password. The first ADMIN of such an environment comes from `APP_BOOTSTRAP_ADMIN_USERNAME` /
`APP_BOOTSTRAP_ADMIN_PASSWORD` (see v1.0.136 below).

### User Deactivation

Deactivated users (`is_active = false`) are rejected **even with a valid JWT token**:
- **At login**: `AuthService` checks `isActive()` → 401 "Account is deactivated"
- **At refresh**: `AuthService` checks `isActive()` → 401, invalidates token family
- **Per-request**: `JwtAuthenticationFilter` calls `UserActiveChecker.isActive(username)` → clears security context → 401

### Advertiser Content Access (V12)

`advertiser_content_access` — join table linking advertisers to content files they can view.

- Advertiser with no linked content → `getAccessibleContent()` returns **empty list, not error**
- `UNIQUE(user_id, content_file_id)` — no duplicate grants
- Access granted/revoked by admin via `AdvertiserContentService`

### Device Registration (V13)

`POST /api/devices/register` — TV-Box calls this on first boot with its serial number. No authentication required. An already-registered serial gets **409** unless an ADMIN opened a re-registration window for it (v1.0.137).

```json
// Request
{"serialNumber": "SN-ABC-123", "deviceName": "Lobby TV"}

// Response (201 Created)
{"deviceId": 42, "deviceToken": "dtk_a1b2c3...", "serialNumber": "SN-ABC-123", "status": "registered"}
```

**Edge Cases:**

| Scenario | Behavior |
|----------|----------|
| New serial number | Creates device in "Unassigned" region, returns 201 |
| Existing unregistered device | Completes registration, returns 201 |
| Already registered device | **Upsert**: refreshes device token, returns 200 |
| Unregistered device calling other endpoints | 401 (no JWT) |

The `deviceToken` (`dtk_*` prefix) is the device's credential for subsequent API calls. Devices are initially placed in a default "Unassigned" region (id=-1) and can be reassigned by operators.

### Device Heartbeat

`POST /api/devices/{id}/heartbeat` — TV-Box calls this every 2-3 minutes. No authentication required (the device ID in the URL identifies the caller).

```json
// Response (200 OK) — always includes pendingActions, even if empty
{
  "deviceId": 42,
  "status": "ONLINE",
  "serverTime": "2025-06-15T10:30:00Z",
  "pendingActions": [
    {
      "actionId": 7,
      "actionType": "REBOOT",
      "payload": "{\"reason\":\"scheduled\"}",
      "issuedAt": "2025-06-15T10:25:00Z",
      "expiresAt": "2025-06-15T10:30:00Z"
    }
  ]
}
```

**Edge Cases:**

| Scenario | Behavior |
|----------|----------|
| Unknown device ID | **404** with uniform error response |
| Device with no pending actions | 200 with `"pendingActions": []` (never null/missing) |
| Heartbeat from any device status | Marks device `ONLINE`, updates `updatedAt` |

The heartbeat is the device's primary mechanism for receiving remote actions. Operators issue actions (REBOOT, UPDATE_CONTENT, etc.) via separate endpoints; the device picks them up on its next heartbeat.

### Content Version Sync (V22)

Each device caches a SHA-256 **content version hash** for whatever it's currently playing, computed from:

```
hash(assignmentId | versionNumber | playlistId | [contentFileId:processedKey, ...])
```

Heartbeat now accepts an optional body `{contentVersion: "..."}`. The server computes the expected version using the same `ContentVersionHasher`. If they differ, the response includes `syncRequired: true` and the new `expectedContentVersion`, telling the device to refetch.

**Edge case: rollback.** A rollback to an earlier playlist would normally produce the same hash (same files, same order). To distinguish "rolled back to last week's content" from "no change", `ContentAssignment` carries a monotonic `version_number` that bumps on every meaningful edit. The hasher includes it, so rollback always yields a NEW hash.

**Edge case: bulk WebSocket flood.** Confirming an assignment that affects 1000 devices would, naively, fan out 1000 simultaneous WebSocket sends. `BatchedSyncDispatcher` instead:

- Splits devices into 50-per-batch chunks
- Schedules each batch with a configurable stagger (`app.sync.batch-stagger-ms`, default 100 ms)
- Returns immediately to the caller (single-thread `ScheduledExecutorService`)
- 1000 devices → 20 batches → ~2 seconds of paced fan-out instead of one tick

Devices that aren't connected to WebSocket are recorded as `skipped` — they pick up the same sync signal on their next heartbeat poll because `syncRequired=true` will be returned by `processHeartbeat`. Both paths converge on the same end state.

### Derived Device Status (V14)

Each heartbeat updates `last_heartbeat_at` and **recomputes the device's status** via `DeviceStatusEvaluator` (pure logic, no I/O):

```
if (now - lastHeartbeatAt) > 15min + 60s grace  → OFFLINE
else if no resolvable content assignment        → NO_CONTENT
else                                              → ONLINE
```

**60-second clock skew grace** absorbs minor time drift between server and devices. A device whose heartbeat was 15min 30s ago is still considered ONLINE, but at 16min 1s it transitions to OFFLINE.

**Status changes always emit an event** via the resilient `EventPublisher` (Redis pub/sub):

```
device.status.changed deviceId=42 from=OFFLINE to=ONLINE at=2025-06-15T10:30:00Z
```

If Redis is unavailable, the publish failure is logged but the heartbeat itself succeeds — pub/sub is non-critical.

### Device CRUD (V15 — `device_status_view`)

| Method | Path                | Auth Roles              | Description |
|--------|---------------------|-------------------------|-------------|
| GET    | `/api/devices`               | ADMIN, OPERATOR, VIEWER | Paginated, filterable list |
| GET    | `/api/devices/{id}`          | ADMIN, OPERATOR, VIEWER | Get by ID (incl. soft-deleted) |
| PUT    | `/api/devices/{id}`          | ADMIN, OPERATOR         | Update name |
| PUT    | `/api/devices/{id}/location` | ADMIN, OPERATOR         | Move device to a new region (and optional facility) |
| DELETE | `/api/devices/{id}`          | ADMIN                   | Soft delete only |
| POST   | `/api/devices/{id}/reregistration-window` | ADMIN      | Allow this serial to re-register once within the window (v1.0.137) |

**`PUT /api/devices/{id}/location`** — body `{"regionId": <long>, "facilityId": <long|null>}`. Used to move a device out of the default "Unassigned" region and into a real region/facility, or to transfer it later. `facilityId` is nullable — `null` keeps the device under the region directly.

Validation:
- **400** if `facilityId` is non-null but the facility belongs to a different region (`Facility {fid} is not in region {rid}`). The org tree forbids cross-region facilities.
- **409** if the device currently belongs to a `DeviceGroup` whose **project** differs from the new region's project (`Device {id} is in device group {gid} from a different project; remove from the group before relocating`). Device groups are project-scoped and may span regions within their project, so a same-project cross-region move **succeeds**; only a move into a different project's region is blocked. The operator must clear the membership first via `DELETE /api/device-groups/{gid}/devices/{id}` before such a move.
- **404** if the device, region, or (non-null) facility is missing/soft-deleted.

**List supports filters via query params** (all optional, all combinable except where noted):
- `status=ONLINE|OFFLINE|NO_CONTENT|UNREGISTERED` — filter by computed status
- `projectId`, `regionId`, `facilityId`, `deviceGroupId` — scope to a tree node by exact ID (`projectId` narrows to all devices whose region belongs to that project — powers the project-scoped device picker)
- `unassigned=true` — only devices with `device_group_id IS NULL` (the "needs grouping" bucket). **Mutually exclusive with `deviceGroupId`** — combining the two returns 400. `unassigned=false` or unset behaves identically (no constraint).
- `serial=foo` — case-insensitive substring match on serial number
- `name=lobby` — case-insensitive substring match on device name (V16)
- `facilityName=tower` — case-insensitive substring match on facility name (V16)
- Standard Spring `Pageable`: `page`, `size`, `sort=name,asc`

**`device_status_view`** (V15, extended in V16) — DB view that computes `computed_status` from `last_heartbeat_at` plus a `NOT EXISTS` lookup against `content_assignment` matching the device's region/facility/group. V16 adds a `LEFT JOIN facility` so `facility_name` is available for search without an extra query per row. Soft-deleted devices are excluded from the view.

**Zero matches → empty `content` array, never 404.** Spring Data's `Page<T>` returns `{"content":[],"totalElements":0,...}` natively — the controller does not throw on empty results.

**Edge Cases:**

| Case | Behavior |
|------|----------|
| Filter by status | Backed by `device_status_view.computed_status` — fast, indexed |
| Soft-deleted device in list | Excluded (view filters `deleted_at IS NULL`) |
| Soft-deleted device by ID | **Returned** for audit purposes (`deleted: true` in response) |
| DELETE | Sets `deleted_at` only — never physical row removal |
| DELETE on already-deleted | 404 (the active-only finder doesn't see it) |

### Bulk Device Group Actions

`POST /api/device-groups/{id}/actions` — fan out a single command to every device in the group.

**Roles**: `ADMIN`, `OPERATOR`

**Request:**
```json
{
  "actionType": "REBOOT",   // SYNC_CONTENT | REBOOT | ASSIGN_CONTENT
  "payload": "{\"reason\":\"scheduled\"}"
}
```

**Response (always 200, even with partial failures):**
```json
{
  "deviceGroupId": 1,
  "actionType": "REBOOT",
  "totalDevices": 200,
  "succeededCount": 197,
  "skippedCount": 2,
  "failedCount": 1,
  "succeededActionIds": [101, 102, ...],
  "skipped": [{"deviceId": 50, "reason": "Device 50 already has a PENDING REBOOT (action id=99)"}],
  "failed":  [{"deviceId": 73, "reason": "DB write failed"}]
}
```

**Edge Cases:**

| Edge Case | Implementation |
|-----------|---------------|
| 200+ devices | Devices iterated in batches of 50; Hibernate `jdbc.batch_size=50` flushes inserts in groups |
| Per-device duplicate PENDING | Listed under `skipped`, not `failed` — semantic difference for UI |
| Per-device unexpected error | Listed under `failed`; **does NOT roll back successful peers** |
| Partial failure | HTTP 200 with summary — caller decides UI/retry behaviour |

Each device's `RemoteAction` insert runs in its own `Propagation.REQUIRES_NEW` transaction so a failure on one device doesn't poison the persistence context for the next.

#### `ASSIGN_CONTENT` payload validation

Unlike `REBOOT` and `SYNC_CONTENT` (which pass `payload` through opaquely — the device interprets it), `ASSIGN_CONTENT` carries a structured payload that the server validates **before** any device is touched. The whole bulk request rejects with **400** on any of:

1. `payload` is `null`. `ASSIGN_CONTENT` requires a body — can't assign nothing.
2. `payload` is not parseable JSON, or doesn't contain a numeric `playlistId` field. Schema mismatch.
3. `playlistId` resolves to a non-existent or soft-deleted playlist. Looked up via `PlaylistRepository.findByIdAndDeletedAtIsNull` — soft-deleted is treated as gone, matching the rest of the playlist API.

```json
// Valid request body
{
  "actionType": "ASSIGN_CONTENT",
  "payload": "{\"playlistId\":7}"
}
```

**Error message contract.** The 400 body's `message` field uses one of:

- `ASSIGN_CONTENT requires payload {"playlistId":<id>}` — schema failure (null payload, missing field, malformed JSON).
- `ASSIGN_CONTENT requires payload {"playlistId":<id>}; playlist 7 not found` — lookup failure (named id).

**Why fail-fast.** Without pre-iteration validation, an unknown playlist id would still write `RemoteAction` rows for every device in the group before the lookup miss surfaces — and the partial-failure summary architecture means those bad rows would land as `succeeded` (they were created OK). The operator's only recovery would be to manually clean up dozens of dangling actions. Validating once, up front, before the device loop avoids that mess.

**Per-device payload propagation.** When validation passes, each device's `RemoteAction.payload` is the **verbatim FE-supplied JSON** — no re-serialization, no key reordering. The TV-Box parses the same string the operator wrote.

## Audit Logging

All mutating requests (POST, PUT, DELETE, PATCH) are logged asynchronously.

### What is Captured

| Field        | Description                                |
|--------------|--------------------------------------------|
| principal    | Authenticated user (or "anonymous")        |
| httpMethod   | POST, PUT, DELETE, PATCH                    |
| path         | Request URI                                |
| requestBody  | Masked request payload                     |
| responseBody | Masked response payload                    |
| responseStatus | HTTP status code                         |
| timestamp    | When the request completed                 |

### Edge Cases

- **Audit write failure never fails the original request** — `AsyncAuditWriter.record()` catches all exceptions, logs warning, continues
- **Sensitive fields never appear in audit logs** (v1.0.139) — bodies are parsed as JSON and every key that
  `SensitiveFieldMasker.isSensitiveKey` matches is replaced, at any depth and of any type: keys containing
  `password`/`secret`/`token`/`ticket`/`authorization`, keys ending in `key` (`rawKey`, `apiKey`, …), and
  `pin`/`ssn`/`cvv`/`creditCard`. Non-JSON bodies are stored as `[omitted: non-JSON body]`, never raw.
- **Credential endpoints keep no bodies at all** — POST `/api/me/password`, `/api/auth/reset-password`,
  `/api/auth/refresh`, `/api/admin/api-keys` store `[omitted: credential endpoint]` (method/path/status/principal
  are still recorded). Validation 400s never echo a credential field's `rejectedValue`.
- **Successful device-agent traffic is not audited** (v1.0.143) — `POST /api/devices/*/heartbeat`,
  `*/sync/confirm`, `*/actions/*/confirm`, `*/playback` and `*/remote/*/ack` skip the caching
  wrappers entirely, so they cost nothing. They were 98.6% of the table on the test server, and
  nothing reads `audit_log`. **A FAILED one (status ≥ 400) still records a bodyless entry**
  (principal, method, path, status, time — bodies are `[omitted: unaudited device-agent body]`,
  because nothing was buffered): a device using its token against another device's id is a
  `@PreAuthorize` **403** raised inside the controller invocation, so it unwinds back through the
  filter and keeps its trail. An authentication **401** is emitted by the security filter chain,
  which runs before this filter — it never reached `audit_log`, before or after v1.0.143.
  **Every other write under `/api/devices/**` is an operator/admin action and is still audited
  in full** — `register`, `{id}/actions`, `playlist/control`, remote start/stop,
  `reregistration-window`, edit, delete, location and volume.
- **Async execution** — dedicated `auditExecutor` thread pool (2-5 threads, 500 queue). If queue full, entries are silently dropped
- **Body size limit** — masked first, then cut to 10,000 characters (`...[truncated]`). Request caching stops just
  above 256 KiB, and bodies (request or response) over 256 KiB are not parsed (`[omitted: … bytes]`). UTF-8.

### Storage

Persisted to `audit_log` table via Flyway migration V3. Indexed on `timestamp` and `(principal, timestamp)`.

## Error Handling

All errors use a **uniform response format** with a `correlationId` for tracing:

```json
{
  "status": 400,
  "error": "Validation Failed",
  "message": "Request validation failed — see fieldErrors for details",
  "correlationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "timestamp": "2024-01-15T10:30:00Z",
  "fieldErrors": [
    {"field": "username", "message": "Username is required", "rejectedValue": ""},
    {"field": "password", "message": "Password must be at least 4 characters", "rejectedValue": "ab"}
  ]
}
```

### HTTP Status Mapping

| Status | Trigger                               |
|--------|---------------------------------------|
| 400    | Validation errors (lists ALL fields)  |
| 401    | Expired/missing/invalid token         |
| 403    | Insufficient role (@PreAuthorize)     |
| 404    | Resource not found / unknown endpoint |
| 503    | Storage unavailable                   |
| 500    | Unexpected error (catch-all)          |

### Edge Cases

- **Validation lists all failed fields** — not just the first one (via `MethodArgumentNotValidException.getBindingResult().getFieldErrors()`)
- **Never exposes stack traces in production** — `app.error.include-stacktrace: false` (default). In dev, set to `true` for debugging.
- **500 errors in production** show only: `"An unexpected error occurred. Reference: <correlationId>"`

## Database & Migrations

Schema is managed exclusively by Flyway versioned migrations. **No `ddl-auto: update` in production** — only `validate` (dev) or `none` (prod).

- Migrations: `infra/src/main/resources/db/migration/V*.sql` (plus Java migrations in `infra/src/main/java/db/migration/`) — **head is `V52__content_file_transcode_queued_at.sql`**
- Rollbacks: `infra/src/test/resources/db/rollback/U*.sql`
- Migration failure **halts application startup** (Flyway default + Spring Boot propagation)
- **Every new migration must bump `EXPECTED_MIGRATIONS` in `FlywayMigrationTest`** — it pins both
  the applied count and a contiguous `1..N` version run, so an out-of-order or skipped version fails
  in CI rather than on a prod deploy. The count includes the **Java** migration `V36` (there is no
  `V36__*.sql` on disk). It is **48** as of v1.0.142.
- **DDL must be valid on both H2 (PostgreSQL mode, used by tests) and PostgreSQL (production).**
  Notably: **no partial unique indexes** — H2 does not support them, so rules like "one live remote
  session per device" live in the service layer instead.

### Domain Schema (V4)

```
project 1──* region 1──* facility
                    1──* device
```

| Table        | Key Constraints                                              |
|--------------|--------------------------------------------------------------|
| project      | PK: id                                                       |
| region       | FK → project, UNIQUE(project_id, code)                       |
| facility     | FK → region, UNIQUE(region_id, name) — name unique per region |
| device       | FK → region (ON DELETE RESTRICT), FK → facility, FK → device_group |
| device_group | FK → project, UNIQUE(project_id, name)                       |

### Device Status Enum

`ONLINE` | `OFFLINE` | `NO_CONTENT` | `UNREGISTERED`

### Soft Delete (V5)

Devices and device groups use **soft delete only** — records are never physically removed. A `deleted_at` timestamp is set instead.

- `Device.softDelete()` / `DeviceGroup.softDelete()` sets `deleted_at`
- All repository queries filter by `deletedAtIsNull` (only return active records)
- `serial_number` is **unique among live devices** (V50, v1.0.146). A soft-deleted device keeps its serial for history, but no longer blocks it: the same TV box can register again after an admin deletes its device

### Edge Cases (enforced at DB level)

- **Deleting region with active devices → blocked** — `ON DELETE RESTRICT` on `device.region_id` FK
- **Facility name unique within region** — `UNIQUE(region_id, name)` constraint
- **serial_number unique among live devices** — `UNIQUE` on the generated column `device.live_serial` (the serial while `deleted_at IS NULL`, NULL once deleted; V50). Same rule on Postgres and H2
- **Device group name unique within project** — `UNIQUE(project_id, name)` constraint (a group may span regions within its project, V37)
- All constraints tested in `SchemaConstraintTest` and `DeviceSchemaTest`

### Content & Playlists (V6)

```
project 1──* content_file
        1──* playlist 1──* playlist_item *──1 content_file
```

| Table         | Key Constraints                                          |
|---------------|----------------------------------------------------------|
| content_file  | FK → project, soft delete                                |
| playlist      | FK → project, UNIQUE(project_id, name), soft delete      |
| playlist_item | FK → playlist (CASCADE), FK → content_file, UNIQUE(playlist_id, position) |

### Playlist Position Ordering

Items are ordered by `position` (integer). Position is unique within a playlist (`UNIQUE(playlist_id, position)`).

**Atomic reorder** — all position mutations happen in a single `@Transactional`:

| Operation     | Strategy                                                      |
|---------------|---------------------------------------------------------------|
| Add item      | Shift existing items at insert position, insert new item      |
| Remove item   | Delete item, compact remaining positions                      |
| Move item     | Sentinel (-1) → shift affected range → place at target       |
| Bulk reorder  | All to negative sentinels → reassign from ordered ID list     |

The sentinel pattern avoids `UNIQUE` constraint violations during mid-transaction position swaps. If any step fails, the entire transaction rolls back — no partial reorder is possible.

### Content Assignments (V7)

Polymorphic assignments of playlists to targets (REGION, FACILITY, or DEVICE_GROUP) with time ranges.

```
content_assignment → playlist
    ├── target_type: REGION | FACILITY | DEVICE_GROUP
    ├── target_id: FK to target table
    ├── start_time / end_time
    └── priority: auto-set from target_type

content_assignment_exclusion → assignment + device
    └── Exclude specific devices from an assignment
```

**Resolution order (the total order, v1.0.142)**

```sql
ORDER BY priority DESC,                              -- DEVICE_GROUP 3 > FACILITY 2 > REGION 1
         COALESCE(confirmed_at, created_at) DESC,    -- most recently CONFIRMED wins a tie
         id DESC                                     -- last resort; makes the order TOTAL
```

Among the CONFIRMED, non-deleted, non-excluded assignments whose half-open window `[start_time, end_time)` covers the instant, the first row under this order is what the device plays. The most-specific target still wins outright: a group-level assignment overrides any region or facility one.

The **recency** term (`confirmed_at`, added in V48 — *not* `updated_at`, which `bumpVersion()`/`truncateEndTo()` move) is what lets two assignments overlap on purpose: a short campaign confirmed over a long-running booking wins for its own window and hands the devices back when it ends. See the v1.0.142 release note below.

**This rule exists in FOUR places and they must agree exactly:**

| # | Copy | Where |
|---|------|-------|
| 1 | `ContentAssignment.PRECEDENCE` (Java `Comparator`, null-safe) | `domain/…/model/ContentAssignment.java` |
| 2 | `ContentAssignmentRepository.findActiveAtTime` (JPQL `ORDER BY`) | `domain/…/repository/ContentAssignmentRepository.java` |
| 3 | `device_status_view` — embedded **3×** (`active_playlist_id`, `active_playlist_name`, `computed_status`) | `V48__content_assignment_confirmed_at.sql` |
| 4 | The consumers of (1): `resolveForDevice` and `previewForTarget` both `.max(PRECEDENCE)` | `service/…/ContentAssignmentService.java` |

`ContentAssignmentWindowOverrideTest` pins (2) and (3) against each other on the real schema; `DeviceStatusViewActivePlaylistFilterTest` pins the three in-view copies against one another.

**Edge Cases:**

| Edge Case | Enforcement |
|-----------|-------------|
| Time overlap for same target | Application-level: `rejectTimeOverlap()` checks existing assignments before creating |
| end_time > start_time | DB-level: `CHECK (end_time > start_time)` constraint |
| Valid target_type only | DB-level: `CHECK (target_type IN ('REGION','FACILITY','DEVICE_GROUP'))` |
| Device exclusion unique | DB-level: `UNIQUE(assignment_id, device_id)` |
| Exclusion cascade on assignment delete | DB-level: `ON DELETE CASCADE` |
| Excluded device skipped in resolution | Application-level: `resolveForDevice()` filters out excluded assignments |

### Two-Phase Assignment Workflow (V21)

Assignments now have a lifecycle status: `DRAFT → CONFIRMED` (or `CANCELLED`).

| Method | Path | Roles | Behavior |
|--------|------|-------|----------|
| POST   | `/api/assignments`            | ADMIN, OPERATOR | Create a DRAFT (returns 201) |
| POST   | `/api/assignments/{id}/confirm` | ADMIN, OPERATOR | Atomically confirm + attach exclusions |
| GET    | `/api/assignments/preview`    | ADMIN, OPERATOR, VIEWER | Preview matching devices (capped at 200) |

**Atomic confirmation:** `POST /api/assignments/{id}/confirm` accepts `excludedDeviceIds[]` and runs everything inside one `@Transactional`:

```java
@Transactional
public ContentAssignment confirmWithExclusions(Long id, Collection<Long> excludedDeviceIds, String reason) {
    // 1. Re-check time-overlap (someone could have CONFIRMED a conflict since the draft was created)
    // 2. Resolve all device IDs — fail with 404 if any missing
    // 3. Insert all ContentAssignmentExclusion rows
    // 4. Flip status DRAFT → CONFIRMED
}
```

If any step fails — overlap, missing device, exclusion insert error — **everything rolls back**. The status stays `DRAFT`, no exclusions are persisted. Verified by `confirmWithExclusions_overlapDetectedAtConfirm_throws` test.

**Edge case: overlap-check rules:**
- `findOverlapping*` queries filter by `status = 'CONFIRMED'` only — DRAFTs don't conflict with each other
- This means two operators can prepare overlapping drafts simultaneously; the second to confirm fails with `IllegalStateException`

**Edge case: 1-hour draft TTL.** `DraftAssignmentCleaner` runs every 10 minutes (via `@Scheduled` + `@EnableScheduling`):

```java
@Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT5M")
@Transactional
public void cleanup() {
    findExpiredDrafts(now() - 60min) → softDelete each
}
```

Configurable via `app.assignments.draft-ttl-minutes` (default 60). Idle drafts get auto-purged so the listing UI stays clean and we don't leak DB rows.

### Assignment Preview

`GET /api/assignments/preview?targetType=&targetId=` — what devices would receive this assignment, with their current state.

```json
{
  "devices": [
    {
      "deviceId": 42,
      "serialNumber": "SN-ABC",
      "name": "Lobby TV",
      "status": "ONLINE",
      "offline": false,
      "currentAssignmentId": 17,
      "currentPlaylistId": 99
    }
  ],
  "totalDevices": 547,
  "returnedCount": 200,
  "truncated": true
}
```

**Implementation:**
- Routes to the right repo by `targetType`: `findByRegionId*`, `findByFacilityId*`, `findByDeviceGroupId*`
- One bulk query for `findActiveAtTime` + one for all exclusions; per-device matching is in-memory
- Resolves the device's currently-assigned playlist with `ContentAssignment.PRECEDENCE` — the *same* total order as `resolveForDevice` (copy #4 above), so the preview shows exactly what the device will play, including which of two overlapping campaigns wins right now

**Edge cases:**

| Case | Behavior |
|------|----------|
| 500+ matching devices | First 200 returned, `truncated=true`, `totalDevices` shows the real count |
| Offline devices | Included in the response with `offline=true` flag (operators need full footprint) |
| Zero matches | Empty `devices` array, `totalDevices=0`, never 404 |
| Excluded assignments | Filtered out of `currentAssignmentId` so the preview reflects what the device actually plays |

### Schedules (V8)

> **Not applied to playback (v1.0.141, LOGIC-04).** Schedules are stored and the API below still
> works, but nothing that decides what a device plays reads them — an assignment plays for its whole
> start/end window. The UI no longer offers schedules and the per-minute evaluation job is off
> (`app.schedule.evaluation-enabled=false`). Real dayparting is a separate, future feature.

Schedules define when a content assignment is active. Linked to `content_assignment` with optional repeat patterns.

```
schedule → content_assignment
    ├── start_time_utc / end_time_utc (always UTC)
    ├── repeat_type: NONE | DAILY | WEEKLY | MONTHLY
    └── repeat_end_utc (required for repeating schedules)
```

**All datetimes stored in UTC.** Display conversion to UTC+5 is done at the application boundary only via `TimeZoneConverter`:
- `formatForDisplay(Instant)` → `"2025-06-15 15:30:00"` (UTC+5)
- `parseFromDisplay(String)` → `Instant` (UTC)

**DB-level constraints:**
- `CHECK (end_time_utc > start_time_utc)`
- `CHECK (repeat_type IN ('NONE','DAILY','WEEKLY','MONTHLY'))`
- `CHECK ((NONE AND repeat_end IS NULL) OR (repeating AND repeat_end > end_time))`

**Overlapping schedules raise WARNING, not hard block.** The `ScheduleService.createSchedule()` returns a `ScheduleResult` containing both the saved schedule and a list of `OverlapWarning`s. Callers decide whether to surface warnings to the user. The schedule is always persisted regardless.

Overlap detection expands recurring schedules into concrete `TimeWindow` occurrences up to a horizon and checks pairwise overlap.

### Schedule REST + Quartz Evaluator

| Method | Path | Roles | Behavior |
|--------|------|-------|----------|
| POST   | `/api/schedules`        | ADMIN, OPERATOR | Create a schedule (returns 201) |
| PUT    | `/api/schedules/{id}`    | ADMIN, OPERATOR | Replace time/repeat (soft-deletes old, creates new) |
| DELETE | `/api/schedules/{id}`    | ADMIN, OPERATOR | Soft delete |

**Edge case: window validation** (`400 Invalid Upload` otherwise; POST and PUT alike).
- `endTimeUtc` must be after `startTimeUtc` (v1.0.146)
- Repeating: the window can be at most one repeat interval long — 1 day (DAILY), 7 days (WEEKLY), 28 days (MONTHLY) — or it overlaps its own next occurrence; `repeatEndUtc`, when set, must be after `startTimeUtc` (v1.0.146)
- For `repeat_type=NONE`: `endTimeUtc` must be after `now()`
- For repeating: `repeatEndUtc` must be after `now()`

**Bounded expansion (v1.0.146).** Occurrence *n* is computed from the original start, so a schedule is
expanded from the moment that matters (now, or the new schedule's start) instead of from its first
occurrence, MONTHLY keeps its day of month (Jan 31 → Feb 28 → Mar 31), and one expansion returns at
most `Schedule.MAX_OCCURRENCES` (1,000) windows. Overlap warnings compare two sorted lists in one pass.

**Quartz job: per-minute evaluation.**
`ScheduleEvaluationJob` is registered via Spring Boot's `spring-boot-starter-quartz`:
- Cron: `"0 * * * * ?"` (every minute on the second)
- Calls `ScheduleEvaluator.evaluateNow()` which iterates non-deleted schedules and counts active ones
- Returns `EvaluationResult(totalSchedules, activeNow, errors)`
- `AutowiringJobFactory` enables `@Autowired` injection into Quartz jobs

**Edge case: missed runs on downtime.**
`MissedRunCatchUp` is an `ApplicationReadyEvent` listener that performs **one immediate evaluation** at startup, so any schedules that became active during downtime are picked up without waiting up to a minute for the next cron tick. Uses `ObjectProvider<ScheduleEvaluator>` so it's a no-op when the evaluator bean isn't available (e.g. infra-only test contexts).

### Events & Incidents (V9)

```
device 1──* event
       1──* incident ──* event (first_event, last_event)
```

**Events** — discrete occurrences with priority:

| Priority | Meaning |
|----------|---------|
| CRITICAL | Immediate attention required |
| HIGH     | Urgent issue |
| MEDIUM   | Standard issue |
| LOW      | Minor issue |
| INFO     | Informational only |

**Incidents** — lifecycle-managed issues tied to a device + event type:

| Status       | Transition |
|-------------|------------|
| OPEN        | → ACKNOWLEDGED or RESOLVED |
| ACKNOWLEDGED| → RESOLVED |
| RESOLVED    | (terminal) |

**Edge Case: One open incident per device per event type.** When a repeat event arrives for the same device and event type:
- If an open/acknowledged incident exists → **update it** (increment `occurrence_count`, update `last_event_id`, escalate priority if higher)
- If no open incident exists (none or all resolved) → **create new** incident
- No duplicate open incidents are ever created

`IncidentService.processEvent()` returns `IncidentResult(incident, created)` — callers know if the event opened a new incident or updated an existing one.

### Entity Audit Logs (V10)

Field-level change tracking with old/new JSON values. Separate from the HTTP audit log (V3).

```
entity_audit_log
├── entity_type + entity_id (polymorphic reference)
├── action: CREATE | UPDATE | DELETE
├── changed_by (principal)
├── old_value (JSON text) — null for CREATE
└── new_value (JSON text) — null for DELETE
```

Writes are async (`@Async("auditExecutor")`) and failure-safe — audit write errors never fail the original operation.

### Remote Actions (V10)

Commands sent to devices with timeout tracking.

```
remote_action → device
├── action_type (REBOOT, UPDATE_CONTENT, etc.)
├── status: PENDING → CONFIRMED | EXPIRED | FAILED
├── issued_at / expires_at (5-minute timeout)
└── payload / result (JSON text)
```

**Edge Cases:**

| Edge Case | Enforcement |
|-----------|-------------|
| 5-minute expiry | `expires_at = issued_at + 5min`. `expireStale()` marks overdue PENDING actions as EXPIRED |
| No duplicate PENDING per device+type | Application-level: `findPendingByDeviceAndType()` check before `issue()`, throws `IllegalStateException` if exists |
| Confirm after expiry | Throws `IllegalStateException`, marks action EXPIRED |
| Different action types | Allowed concurrently — dedup is per action_type |

`RemoteActionService.expireStale()` is designed to be called periodically by a scheduled task to sweep expired actions.

### Playback Logs (V11)

Records of content actually played on devices — the proof-of-play trail.

```
playback_log
├── device_id → device
├── content_file_id → content_file
├── assignment_id → content_assignment (optional)
├── played_at (when content was played)
├── duration_seconds
└── reported_at (when device reported it)
```

**DB-level deduplication:** `UNIQUE(device_id, content_file_id, played_at)` — the same content played at the exact same timestamp on the same device is silently ignored (idempotent).

**Clock skew check:** `played_at` in the future beyond a configurable tolerance (default 30 seconds) is rejected. This catches devices with drifted clocks without blocking minor discrepancies.

```yaml
app:
  playback:
    max-clock-skew-seconds: 30  # configurable
```

**Sealed result type (JDK 21):**

```java
sealed interface PlaybackLogResult {
    record Created(PlaybackLog log) implements PlaybackLogResult {}
    record Duplicate()              implements PlaybackLogResult {}
    record Rejected(String reason)  implements PlaybackLogResult {}
}
```

Callers pattern-match on the result to determine outcome — no exceptions for expected cases (duplicate, clock skew).

## Redis Cache & Pub/Sub

### Key Namespaces (TTL enforced on all keys)

| Namespace | Prefix     | TTL        | Purpose                |
|-----------|------------|------------|------------------------|
| session   | `session:` | 30 minutes | Session data cache     |
| token     | `token:`   | 60 minutes | Token/credential cache |
| (default) | —          | 15 minutes | Any other cached value |

### Resilience

- Cache errors logged + swallowed (graceful degradation)
- Pub/sub non-critical — connection loss doesn't crash app

## MinIO Object Storage

### Features

- **Auto-bucket creation on startup** — configurable bucket list
- **Presigned URL expiry** — configurable via `app.minio.presigned-url-expiry-minutes`
- **Graceful degradation** — if MinIO is unavailable, the app keeps serving and storage endpoints return HTTP 503 until it comes back

### Resilience

- `MinioHealthStatus` — thread-safe holder of `UP`/`DEGRADED` **plus the reason and since when**. Logs once per transition (WARN down, INFO up with the outage duration); re-marking the same state neither logs nor moves `since`.
- `MinioBucketInitializer` (`ApplicationRunner`) — creates the buckets, marks UP; on failure marks DEGRADED with a reason and WARNs, and the app still starts.
- **`MinioHealthProbe` (v1.0.144) — the flag is no longer a latch.** While storage is degraded it re-probes MinIO every `app.minio.recheck-interval` (default `PT30S`) with a single `bucketExists` and clears the flag on the first success. While storage is healthy it makes **no** MinIO calls. Before this, the only writer that could set `UP` ran once at startup, so any blip 503'd storage until the application was restarted by hand.
- **Failure classification (v1.0.144) — `MinioFailureClassifier`, shared by every writer of the flag.** Degrades on `java.io.IOException` anywhere in the cause chain (connect/timeout/unknown-host, however many SDK wrappers deep), `ServerException`, `InvalidResponseException`, and an `ErrorResponseException` that is a 5xx (by S3 code or HTTP status). Stays per-request: every 4xx — `NoSuchKey`, `NoSuchBucket`, `AccessDenied` — plus the quota `XMinioStorageFull` (HTTP 507, excluded by code: a full MinIO still serves reads) and the argument/parsing families. A blanket `catch (Exception) → degrade` is how one bad object became a fleet-wide outage; a type-only check is how "MinIO up but broken" became invisible.
- **Presigned URLs work while MinIO is down.** `generatePresignedUrl` is an HMAC over strings with the region preconfigured and never touches the network, so it is not gated on the health flag (and never sets it).
- All other storage operations check availability before proceeding
- **The probe has its own short-timeout client** (`minioProbeClient`, 3 s) and the `@Scheduled` pool is sized 3, so a blackholed MinIO cannot park the single scheduler thread for minio-java's 5-minute default and silence the Telegram forwarder with it
- `StorageUnavailableException` → HTTP 503 via `GlobalExceptionHandler`, with a **fixed** client-facing message (MinIO's own message names the endpoint, bucket and key, and this reaches TV boxes) and a **constant-text WARN** so the Telegram rate limiter can fold a flood of them; the correlation id goes on an INFO line
- `GET /api/files/status` — reports current storage availability; `GET /api/health` carries the `storage` component (state + reason + since); the Telegram `/health` probe writes its verdict back into the flag, so an operator check also heals it
- **Compose ordering** — `app` declares `depends_on: minio { condition: service_healthy }` (alongside postgres and redis), so the bucket initializer never races a still-starting MinIO. Since v1.0.144 losing that race is survivable anyway — the probe recovers within one tick — but the gate keeps a clean boot clean.

### Configuration

```yaml
app:
  minio:
    url: http://localhost:9000
    # The `minioadmin` fallbacks are for BARE-METAL dev only (a MinIO you started by hand with its
    # own defaults). application-prod.yml has NO fallback, and docker-compose supplies the real
    # credentials from `.env` — see .env.example. Nothing ships a working credential.
    access-key: ${MINIO_ACCESS_KEY:minioadmin}
    secret-key: ${MINIO_SECRET_KEY:minioadmin}
    buckets:
      - uploads
      - content-raw
      - content-processed
    raw-bucket: content-raw
    presigned-url-expiry-minutes: 60
    raw-expiry-days: ${MINIO_RAW_EXPIRY_DAYS:30}   # raw/ originals deleted after 30 days; 0 = never
    # How fast a MinIO outage heals. Re-probed ONLY while degraded; zero MinIO calls while healthy.
    # ISO-8601 — a bare number is milliseconds to both readers of this key, so anything under 1s
    # fails startup naming the property rather than hammering a dead MinIO.
    recheck-interval: ${APP_MINIO_RECHECK_INTERVAL:PT30S}
```

### Content Upload Pipeline (V17)

`POST /api/content/upload` — multipart upload that lands raw bytes in `content-raw` and triggers async transcoding.

**Roles**: `ADMIN`, `OPERATOR`

**Request** (multipart/form-data):
- `projectId` — owning project (**optional** — see "Orphan uploads" below)
- `file` — the binary

**Response (202 Accepted, immediate):**
```json
{
  "fileId": 42,
  "status": "UPLOADED",
  "storageKey": "raw/uuid_movie.mp4",
  "projectId": 1,
  "message": "Upload accepted; transcoding in progress"
}
```

**Lifecycle: `UPLOADED → TRANSCODING → READY`** (or `FAILED` / `INVALID`)

The upload endpoint returns 202 immediately after the bytes land in `content-raw`. The `Transcoder` interface (domain) is invoked via `@Async` so the HTTP response isn't blocked. Real implementations would call `ffmpeg` and copy the processed asset to `content-processed`; the included `StubTranscoder` simply marks the row READY after inspection.

#### Orphan uploads (project optional)

The upload endpoint **does not fail** when `projectId` is omitted or refers to a project that doesn't exist — or, for a project-restricted OPERATOR, to a project outside their scope (v1.0.145; identical response, so it reveals nothing). The bytes are accepted, a `content_file` row is persisted with `project_id = null`, async transcode kicks off, and the response carries `"projectId": null` plus a message that explicitly mentions "orphan content."

This is a deliberate FE-friendly stance: if the upload form runs before the project picker is wired up, or if the user uploads from a context where the project isn't known yet, we'd rather hold the asset and let them finish the binding later than reject the request and ask for a re-upload.

**Attaching a project later:**

```http
PATCH /api/content/{id}/project
Content-Type: application/json

{ "projectId": 5 }
```

- `204 No Content` on success.
- `404 Not Found` if the content file doesn't exist (or has been soft-deleted), or if `projectId` is set but unknown — at this point we know the operator is filling in a real binding, so a wrong id is a mistake worth surfacing (in contrast to the upload path, which defers).
- `{ "projectId": null }` clears the binding (returns the file to orphan state). Useful for reverting a wrong assignment without deleting and re-uploading.
- **Operator-only callers (v1.0.145):** the same row rule as `DELETE` — own the file, or `403` if it was only granted to them and `404` if they can't see it at all. And both ends of the move must be in their project scope: an out-of-scope target is the same `404` as an unknown one; a file currently bound to a project outside their scope is `403`.

**Caveats:**
- Orphan content is **not** subject to the `urgent=true` device fan-out — there's no project audience to push to. Re-issue an `urgent` flow after the project is bound (or use a manual sync trigger).
- The DB schema (`V31`) drops the `NOT NULL` constraint on `content_file.project_id`. The FK to `project(id)` stays in place, so non-null values remain referentially valid.

### Urgent Uploads + WebSocket Push

`POST /api/content/upload?urgent=true` skips the regular transcode queue and triggers a real-time fan-out to connected devices.

**Two layers of delivery:**

1. **WebSocket push (best-effort, immediate)** — `DeviceWebSocketHandler` broadcasts to every device with an open `/ws/devices/{id}` socket. The push is fire-and-forget; failures are recorded in the response (`webSocketPush.sent / skipped / failed`) but do not error the request.
2. **Heartbeat fallback (durable, eventual)** — devices that aren't currently connected pick up the same content on their next heartbeat poll via the existing `remote_action` queue.

**Front-of-queue transcoding** (rewritten in v1.0.132):

```
TranscodeExecutor  — width auto-planned from the host (1 on a 700 MiB/1-vCPU box, 7 on 8-core/16 GB),
                     PriorityBlockingQueue, bounded queue, rejection logged at ERROR
@Bean("auditExecutor")  — audit rows and notifications only; NO transcodes
```

`Transcoder.transcodeAsyncUrgent(id)` enqueues at `PRIORITY_URGENT`, so an urgent upload overtakes
everything already queued at any pool width. Same pipeline (FFprobe inspect → ffmpeg → output check
→ upload), just ahead in line. It cannot preempt an encode already running — nothing short of
killing a live ffmpeg could — so on a host whose planned width is 1, "urgent" means *next*, not *now*.

There is deliberately only **one** transcode pool: a second one makes the real ceiling on concurrent
encodes the sum of two widths, and since ffmpeg is a child of the JVM that RSS lands in the same
cgroup. The former `urgentTranscodeExecutor` also used `CallerRunsPolicy`, which on overflow ran the
entire multi-minute pipeline inline on the Tomcat request thread.

**Edge case: WebSocket push failure → heartbeat carries it.** The WebSocket handler returns a `PushResult(sent, skipped, failed)`:
- `skipped` = device wasn't connected
- `failed` = send threw IOException

Both categories are logged but never thrown. The same `URGENT_CONTENT` notification is durably available via heartbeat polling, so eventual consistency is guaranteed even with zero connected sockets.

### Persistent Device Channel: `/ws/devices/{id}`

Each registered device opens one long-lived WebSocket. The path segment after `/ws/devices/` is the device id — e.g. `/ws/devices/42`.

**Push message types** (`PushMessageType` in domain):

| Type            | Trigger |
|-----------------|---------|
| `SYNC_REQUIRED` | Server-computed content version differs from device-cached version |
| `ACTION_PENDING`| A `remote_action` was issued for the device |
| `URGENT_CONTENT`| `urgent=true` upload broadcast |

**Edge case: missed messages on reconnect.** When a device reconnects, `afterConnectionEstablished` runs a one-shot replay:

1. Query `remote_action` table for `PENDING` rows assigned to this device → emit one `ACTION_PENDING` per row
2. Compute expected content version, compare to `device.current_content_version` — if different, emit `SYNC_REQUIRED`

The device receives anything it missed during the disconnect immediately on reconnect, without waiting for the next heartbeat tick.

**Edge case: multiple connections from same device.** If a second WebSocket arrives for a device that already has an open session (e.g. flaky NAT, two TV-Boxes sharing serial), the handler atomically:

1. `put` the new session in the live map (winning the race)
2. `close(POLICY_VIOLATION, "superseded")` on the prior session
3. Replay pending to the new session

The device always sees a single canonical socket; the operator's view shows one connection per device.

### Device Sync API: `GET /api/devices/{id}/sync`

Returns the diff a device must apply to reach the server's current expected content state.

**Query parameters:**
- `currentVersion` (optional) — the device's currently-cached content version hash
- `currentFileIds` (optional, repeatable) — file IDs the device currently holds locally

**Response:**

```json
{
  "deviceId": 42,
  "expectedContentVersion": "sha256-...",
  "fullSync": false,
  "filesToAdd": [
    { "fileId": 21, "name": "ad.mp4", "contentType": "video/mp4",
      "sizeBytes": 5242880, "durationSeconds": 30, "checksum": "sha-...",
      "presignedUrl": "https://minio/.../21.mp4?sig=..." }
  ],
  "filesToDelete": [19],
  "playlistOrder": [
    { "position": 0, "fileId": 20, "durationSeconds": 10 },
    { "position": 1, "fileId": 21, "durationSeconds": 30 }
  ],
  "presignedUrlExpiryMinutes": 60,
  "presignedUrlsExpireAt": "2026-05-06T01:23:45Z"
}
```

**Edge case: device reports null version → full content list.** When `currentVersion` is omitted, the held set is ignored and every READY file in the resolved playlist is returned in `filesToAdd` with `fullSync: true`. Devices that wiped local storage just call `/sync` with no query params.

**Edge case: presigned URLs expire after 2hr → regenerate on re-sync.** Sync URLs use `app.device.sync-url-expiry-minutes` (default 120) — longer than the standard `app.minio.presigned-url-expiry-minutes` (60) used by the file-download API, since devices over cellular links may need more headroom for large files. The endpoint regenerates URLs on every call — devices that are mid-download and lose a URL just hit `/sync` again to receive fresh signatures. No server-side state is kept between calls.

**Edge case: file missing from MinIO → excluded from sync, logged as storage inconsistency.** Before signing, the service calls `MinioStorageClient.exists(...)` (a `statObject` HEAD-equivalent). If a file is `READY` in the database but absent from the `content-processed` bucket, it is silently dropped from `filesToAdd` and a `WARN` is logged for ops to triage. URL signing failures (auth, network, MinIO degraded) are caught per-file: the affected file is dropped, others in the same diff still ship. One file's failure never blocks the rest of the response.

**Edge case: no active assignment.** Returns empty `filesToAdd` + every held file ID in `filesToDelete` so the device clears local storage cleanly.

**Edge case: not-yet-READY files.** Files in `UPLOADED` / `TRANSCODING` / `INVALID` status are excluded from both `filesToAdd` and `playlistOrder`. The device picks them up on the next sync once the pipeline finishes.

The endpoint is `permitAll` (matches `/heartbeat` and `/register` — devices use device tokens, not user JWTs).

### Device Playlist: `GET /api/devices/{id}/playlist`

Returns the device's currently-assigned playlist as an ordered list with file URLs and durations.

**Response:**

```json
{
  "deviceId": 42,
  "playlistId": 100,
  "playlistName": "Mall Holiday Loop",
  "contentVersion": "sha256-...",
  "totalDurationSeconds": 75,
  "items": [
    { "position": 0, "fileId": 10, "name": "ad.mp4", "contentType": "video/mp4",
      "presignedUrl": "https://minio/.../ad.mp4?sig=...",
      "durationSeconds": 30, "checksum": "sha-...", "sizeBytes": 5242880 },
    { "position": 1, "fileId": 11, "name": "promo.mp4", "contentType": "video/mp4",
      "presignedUrl": "https://minio/.../promo.mp4?sig=...",
      "durationSeconds": 45, "checksum": "sha-...", "sizeBytes": 8388608 }
  ]
}
```

Per-item `durationSeconds` prefers the `playlist_item.duration_seconds` override, falling back to `content_file.duration_seconds`. URLs use the same 2-hour TTL and per-file isolation as `/sync` (missing files / signing failures dropped, others still served).

**Edge case: no assigned playlist → empty array, NOT 404.** When the device has no resolved assignment, the response is `200 OK` with `playlistId=null`, `items=[]`, `totalDurationSeconds=0`. Only an unknown device id returns `404` — "no playlist" is a valid steady state, not an error.

**Edge case: playlist update mid-playback — device picks up on next sync without interrupting current play.** The response always reflects the latest server state and includes `contentVersion`. The server takes no special action: the device compares `contentVersion` against its currently-playing version and applies the new playlist at the next item boundary, so the in-progress file finishes uninterrupted. Devices that re-poll naturally pick up changes; those that have an open WebSocket also receive a `SYNC_REQUIRED` push when the version changes.

The endpoint is `permitAll`.

### Nightly Retention Cleanup

`RetentionCleanupService` deletes `event`, `playback_log` and `audit_log` rows older than **each table's own** window. Scheduled at **02:00 in `Asia/Karachi` (UTC+5)** via Spring `@Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Karachi")`.

**Per-table retention (`app.retention.*`, all overridable from the environment):**

| Key | Env var | Default | Why |
|-----|---------|---------|-----|
| `audit` | `APP_RETENTION_AUDIT` | `P14D` | `audit_log` rows carry a whole HTTP request **and** response body, and **nothing reads the table** — no API, no UI, no report. Since v1.0.143 successful device-agent traffic isn't audited at all (a failure still leaves a bodyless row), so what is left is a short forensic tail of operator/admin writes. |
| `playback` | `APP_RETENTION_PLAYBACK` | `P90D` | Proof-of-play: advertisers are billed from it and `GET /api/stats/*` reports over it. `PlaybackLogService` refuses a report older than this **same** value, so the accept bound and the sweep can't disagree. |
| `event` | `APP_RETENTION_EVENT` | `P90D` | Incident provenance. |
| `batch-size` | `APP_RETENTION_BATCH_SIZE` | `1000` | Rows per transaction; also the "was that a full batch?" test that keeps a drain going. |
| `max-run-duration` | `APP_RETENTION_MAX_RUN_DURATION` | `PT15M` | Wall-clock budget for the **whole run**, shared by the three tables. |
| `max-batches-per-run` | `APP_RETENTION_MAX_BATCHES_PER_RUN` | `5000` | Safety stop per table — not a work cap. |
| `guard-window` | `APP_RETENTION_GUARD_WINDOW` | `true` | Refuse to run outside 01:00–04:00 Asia/Karachi. |

**Units and fail-fast validation.** A bare number means **days** for the three windows and
**minutes** for `max-run-duration` (`@DurationUnit`) — Spring Boot's own default is *milliseconds*,
so `APP_RETENTION_PLAYBACK=90`, the obvious way to write "90 days", would otherwise bind as 90 ms
and the next 02:00 run would delete every proof-of-play row on the box. Explicit ISO-8601 (`P90D`)
still wins. Each window must be **≥ 1 day**, `max-run-duration` **≥ 1 minute** and both counts
**> 0**; a bad value fails binding at **startup** with the property name, because every one of these
bounds a `DELETE`.

**How it runs:**

- Every table drains against **its own** threshold (`now - audit` / `now - playback` / `now - event`) through one shared helper — the three near-identical loops are gone
- Each table is drained in `batch-size` batches, each batch its own `Propagation.REQUIRES_NEW` transaction (self-injection via `@Lazy` so the proxy boundary is crossed) — partial progress commits even if a later batch fails
- A drain stops on: a short batch (drained), an exception (WARN once, the run moves on to the next table), the run's `max-run-duration` budget, or the `max-batches-per-run` safety stop
- The budget is checked **after** each batch, so every table makes progress even under a tiny budget, and the run can't outlive its window
- A stop on budget or cap logs at **INFO** with the table and the rows deleted — a visible backlog, not an alert (WARN is forwarded to Telegram) — and the next night continues from there
- A failure on one table doesn't block the others (orchestrated independently with isolated try/catch)
- Each run logs: `Retention cleanup done: deleted events=N, playback_logs=M, audit_logs=K` (the three thresholds are logged when the run starts)

**Edge cases:**

- **Events an incident references are kept.** `EventRepository.findExpiredIdsNotReferencedByIncidents(...)` filters out any event referenced as the `firstEvent` or `lastEvent` of **any** incident, whatever its status. `incident.first_event_id`/`last_event_id` are plain foreign keys and incidents are never deleted, so such an event can never be deleted — until v1.0.146 the query skipped only *open* incidents, the first batch holding a resolved incident's event failed every night, and event retention stopped for good (DATA-03). That is two kept rows per incident; `V49` indexes both columns for the probe and the FK checks.
- **Time-of-day guard.** Even if the scheduler fires late or an operator triggers `runCleanup()` manually from a console, the method aborts with a no-op return when the current time is outside the **01:00–04:00 Asia/Karachi** window. Set `app.retention.guard-window=false` to disable in non-prod environments.
- **The per-run cap is a safety stop, not a budget.** It used to be `MAX_BATCHES_PER_RUN = 100`, i.e. 100k rows per table per night — which silently became a *ceiling*: past roughly 26 always-on devices `playback_log` gained more rows per day than a night could delete, so the table could never shrink again. The real bound is now wall-clock time; the cap only exists so a delete that never actually removes the rows it counted can't loop forever.
- **Monotonic progress.** `findIds` is `ORDER BY id ASC`, so successive runs don't repeat work.
- **`entity_audit_log` is deliberately not swept.** It is business provenance (who changed which entity) in small rows, not HTTP traffic.

### Event Search: `GET /api/events`

Filtered, paginated event lookup for the admin/operator UI.

**Query parameters:**

| Param | Type | Notes |
|-------|------|-------|
| `deviceId` | Long | At least one of `deviceId` / `facilityId` is required |
| `facilityId` | Long | Joins through `device.facility.id` |
| `from` | ISO-8601 instant | Defaults to `to - 90d` if omitted |
| `to` | ISO-8601 instant | Defaults to now if omitted |
| `priority` | enum | `CRITICAL` / `HIGH` / `MEDIUM` / `LOW` / `INFO` |
| `page`, `size`, `sort` | Spring Pageable | `size` capped at 100 |

Example: `GET /api/events?deviceId=42&priority=CRITICAL&from=2026-04-01T00:00:00Z&size=50`

**Response** is a standard Spring `Page<EventDto>` envelope sorted by `occurredAt DESC`. Roles: `ADMIN` / `OPERATOR` / `VIEWER`.

**Edge cases (all return 400 via `IllegalArgumentException → 400` in the global handler):**

- **No filters** → `400 "At least one of deviceId or facilityId is required"`. Unbounded scans are blocked because the `event` table grows fast (every status change writes a row).
- **Date range > 90 days** → `400 "Date range cannot exceed 90 days"`. When neither `from` nor `to` is given, defaults to the trailing 90-day window so the request still resolves cleanly.
- **`from` after `to`** → `400 "from must be before to"`.
- **`size` > 100** → `400 "Page size cannot exceed 100"`. Hard limit prevents accidental large payloads.

### Incident Lifecycle: Acknowledge / Resolve / Auto-Resolve

**HTTP endpoints** (`ADMIN` / `OPERATOR`):

- `POST /api/incidents/{id}/acknowledge` — moves OPEN → ACKNOWLEDGED, stamps `acknowledged_by` with the authenticated username (V25 migration). Re-acknowledging or acknowledging a RESOLVED incident returns 409.
- `POST /api/incidents/{id}/resolve` — moves OPEN or ACKNOWLEDGED → RESOLVED, stamps `resolved_by`. Resolving an already-RESOLVED incident returns 409.

Both transitions are guarded inside the `Incident` aggregate (`IllegalStateException` mapped to 409 by `GlobalExceptionHandler`). The 409 protects the manual close audit trail and prevents duplicate close events.

**Auto-resolve on device recovery.** `IncidentService.autoResolveOnRecovery(deviceId, eventType)` looks up every open incident for `(deviceId, eventType)` (so a duplicate heals too) and transitions each one to RESOLVED with `resolved_by = "system"`. Wired in at three points:

| Trigger | Auto-resolves |
|---------|---------------|
| Heartbeat whose *previous* beat is older than `DeviceHealthMonitor.HEARTBEAT_THRESHOLD` (15 min, the escalation threshold) or absent (v1.0.140; the stored status is never OFFLINE) | `DEVICE_OFFLINE` |
| Heartbeat reports a matching version after a previous mismatch | `CONTENT_VERSION_MISMATCH` |
| `DeviceHealthMonitor` recovery sweep (every health check): open incident, last heartbeat newer than the threshold | `DEVICE_OFFLINE` |

The heartbeat resolves **after its own transaction has committed**. `processHeartbeat` only reports the recovered types in `HeartbeatResult.resolveIncidentTypes` (server-internal, not on the wire). `DeviceController.heartbeat`, which has no transaction around it (`open-in-view: false`), then calls the non-transactional `DeviceHeartbeatService.resolveRecoveredIncidents`, and each `autoResolveOnRecovery` runs in its own transaction on its own connection. Resolving inside the beat instead would either join its transaction, so a failing resolve marks the beat rollback-only and loses it, or nest a second one, which holds two pooled connections per recovery beat and deadlocks the pool when a whole region recovers at once. Each resolve is wrapped in try/catch with a WARN, so it never fails the beat; the sweep retries whatever it missed. The OFFLINE→ONLINE *status event and broadcast* use a different rule: `DeviceStatusEvaluator.isOffline` (15 min + 60 s grace, the same cut-off as `device_status_view`). A 15–16 min gap therefore resolves the incident without announcing a transition that no screen showed.

**Edge case: auto-resolve must not override manually resolved incidents.** `incidentRepository.findAllOpenByDeviceAndEventType(...)` filters by `status <> RESOLVED`, so an incident closed manually *before* the lookup is never returned to the auto-resolver. A manual resolve that commits *concurrently* with an auto-resolve is **not** detected: `Incident` has no `@Version` (LOGIC-07), so the two writes are last-writer-wins, and `resolved_by` shows whichever committed second. (An `isResolved()` re-check used to sit here, but it could never fire, because every row it saw came from that `status <> RESOLVED` query.)

The audit trail distinguishes the two cases via `resolved_by`: a username (e.g. `"alice"`) for manual closes, the literal `"system"` for auto-resolve. `Incident.wasManuallyResolved()` exposes this for downstream filters.

### Critical Incident Live Feed

When `IncidentService.processEvent` creates (or escalates) an incident with `Priority.CRITICAL`, the incident is published to a dedicated Redis pub/sub channel and fanned out over WebSocket to every connected admin/operator session.

**Pipeline:**

```
IncidentService ─► CriticalIncidentBroadcaster (domain port)
                       │
                       ▼ infra impl
                RedisCriticalIncidentBroadcaster
                  (publishes to "app:incidents:critical")
                       │
                       ▼
                 Redis pub/sub channel
                       │
                       ▼ infra subscriber
                CriticalIncidentSubscriber
                  (forwards to IncidentPushChannel domain port)
                       │
                       ▼ api impl
                BatchedIncidentBroadcaster
                  (1-second flush window)
                       │
                       ▼
                AdminIncidentWebSocketHandler
                  (broadcasts to all admin sessions)
```

**WebSocket endpoint:** `/ws/admin/incidents` — `SecurityConfig` requires `ADMIN` or `OPERATOR` role; the JWT is validated by `JwtAuthenticationFilter` during the HTTP upgrade handshake (anonymous users get 401, viewer users get 403).

**Wire format (sent every 1s when buffer non-empty):**

```json
{
  "type": "CRITICAL_INCIDENTS",
  "items": [
    { "incidentId": 1, "deviceId": 42, "eventType": "DEVICE_OFFLINE",
      "priority": "CRITICAL", "description": "...", "occurrenceCount": 1,
      "openedAt": "2026-05-06T01:00:00Z", "updatedAt": "2026-05-06T01:00:00Z" }
  ]
}
```

**Edge case: user offline at incident time → sees it on next page load.** `GET /api/incidents/open` (any of `ADMIN`/`OPERATOR`/`VIEWER`) returns the current open-incident list straight from the DB, optionally filtered by `?priority=CRITICAL`. The admin UI calls this on mount to backfill, then opens the WebSocket for live updates. So an operator whose browser was closed during an incident sees it on next login.

**Edge case: multiple simultaneous incidents → one push per second.** `BatchedIncidentBroadcaster` keeps a `ConcurrentLinkedQueue` of inbound JSON payloads; a daemon `ScheduledExecutorService` flushes every 1 second by draining the queue and shipping a single envelope `{"type":"CRITICAL_INCIDENTS","items":[...]}`. A burst of 50 device-offline incidents in the same second arrives at each session as one frame instead of 50. If no admin sessions are connected, the buffer is drained anyway so it can't grow unbounded.

**Other guarantees:**
- Broadcast on first transition to CRITICAL only — repeat occurrences (occurrence count bumps) don't re-broadcast (avoids spamming admins on flapping devices)
- Pub/sub failure is logged but doesn't fail `processEvent` — the incident is still saved to the DB and surfaced via `/api/incidents/open`
- Per-session send failure isolated — one bad socket doesn't block others (`AdminIncidentWebSocketHandler.broadcast` catches and counts per-session)
- The infra subscriber uses `ObjectProvider<IncidentPushChannel>` so infra-only test contexts (no api beans) start cleanly

### Async Device State Events

`DeviceEventService` is the central emit-point for device state changes. It runs on the existing `auditExecutor` pool, so callers (heartbeat, registration, future call sites) never block on persistence or pub/sub.

**Each emission:**
1. Persists an `Event` row with priority + payload
2. Publishes a short string notification via `EventPublisher` (Redis pub/sub)
3. Both steps are best-effort — a Redis outage doesn't fail the originating request, and a DB hiccup is logged but doesn't block the pub/sub broadcast

**Currently wired call sites:**
- `DeviceHeartbeatService` — `DEVICE_STATUS_CHANGED` on every status transition. Priority maps to severity: any → `OFFLINE` is `HIGH`, `OFFLINE` → `ONLINE` is `MEDIUM`, others are `INFO`.
- `DeviceRegistrationService` — `DEVICE_REGISTERED` (first-time) or `DEVICE_REREGISTERED` (token refresh).

**Edge case: rapid online/offline cycling → 10 events/min/device cap.** A flaky network can flip a device between `ONLINE` and `OFFLINE` many times a minute. `DeviceEventService` keeps a per-device sliding-window deque of emission timestamps; the 11th emission within the trailing 60-second window is dropped with a `WARN` log. The cap is per-process (in-memory) — sufficient because heartbeats from a given device land on a single API instance.

**Edge case: event metadata capped at 10KB.** `truncate(...)` byte-clamps the payload to `MAX_PAYLOAD_BYTES = 10240` (UTF-8) and appends `...[truncated]`. The cut is walked back to the previous codepoint boundary so a multi-byte character isn't sliced through (no replacement-char `�` in stored payloads). Guards against runaway debug dumps blowing up the `event.payload TEXT` column or the Redis broadcast.

### Device Health Monitor (Quartz, every 5 min)

`DeviceHealthMonitorJob` is a Quartz job (separate from `ScheduleEvaluationJob`) that scans the device fleet on a 5-minute cadence and converts failure conditions into incidents.

**Detection rules:**

| Condition | Threshold | Event Type | Priority |
|-----------|-----------|------------|----------|
| `last_heartbeat_at` stale | > 15 min | `DEVICE_OFFLINE` | `CRITICAL` |
| `content_mismatch_since` stale | > 30 min | `CONTENT_VERSION_MISMATCH` | `MEDIUM` (warning tier) |

`content_mismatch_since` (V24 migration) is anchored on the first heartbeat that reports a version different from the server-computed expected version, and cleared on the first heartbeat that matches. The monitor uses this column to measure mismatch duration precisely.

**Edge case: `@DisallowConcurrentExecution` to prevent overlap.** The job class is annotated with Quartz's `@DisallowConcurrentExecution`. If a scan takes longer than 5 minutes (large fleet, DB pressure), Quartz skips the next firing rather than running concurrently — protecting against double-emission of the same condition.

**Edge case: re-check incident status before opening — avoid duplicates on recovery.** Each candidate device passes through `escalate()` in its own `Propagation.REQUIRES_NEW` transaction. Inside, before emitting an event:

1. **Re-read the device fresh** from the DB. If it recovered between the candidate fetch and decision, `stillMatches(...)` returns false and the emission is skipped.
2. **Check `incidentRepository.existsOpenByDeviceAndEventType(...)`**. If an open incident already exists for this `(deviceId, eventType)`, skip — without this guard, every 5-minute pass would bump the existing incident's occurrence count forever, even after the underlying condition cleared. An exists check, so duplicate open incidents (LOGIC-16) cannot make it throw.

The result of each tick is logged: `offline +N (skipped M); mismatch +N (skipped M)` so ops can distinguish "newly detected" from "still open".

**Architecture note.** The Quartz job lives in `infra/` to match the rest of the scheduling infrastructure, but infra cannot depend on service. The job autowires the domain port `uz.orientadvertise.services.domain.health.DeviceHealthChecker`; the implementation `DeviceHealthMonitor` lives in `service/` — same pattern as the existing `ScheduleEvaluator` / `ScheduleEvaluationJob` pair.


### Device-Side Action Pickup & Confirmation

Devices have two ways to find out about queued actions and one way to confirm them.

**Pickup (poll):** `GET /api/devices/{id}/actions/pending`

Returns the device's PENDING actions (oldest first). Devices that don't keep an open WebSocket poll this on a cadence — e.g. on each heartbeat. The response is the same shape as the heartbeat's `pendingActions` field. The endpoint is `permitAll` since devices use device tokens, not user JWTs.

**Pickup (push):** `/ws/devices/{id}` WebSocket — see "Persistent Device Channel". Sends one `ACTION_PENDING` frame per queued action immediately on connect (replay) and as new actions are issued.

**Confirm:** `POST /api/devices/{id}/actions/{actionId}/confirm`

Body:
```json
{ "status": "SUCCESS", "result": "..." }
```

Status is `SUCCESS` or `FAILED`. `result` is an arbitrary string (typically JSON the device returns about execution).

Response:
```json
{ "actionId": 60, "outcome": "CONFIRMED", "finalStatus": "CONFIRMED" }
```

**Edge cases (all return 200, never 4xx for "device confused" cases):**

- **Unknown action id → `outcome: UNKNOWN`.** A confused or restarted device may try to confirm an id it shouldn't know about. Returning 404 would brick devices that retry on error; instead the server logs the anomaly and returns 200 so the device can move on.
- **Wrong device for the action → `UNKNOWN`.** Defense-in-depth: even if a device guesses an id from another device, the lookup must match `device_id`. Treated as UNKNOWN — don't leak existence.
- **Late SUCCESS (after deadline) → `outcome: CONFIRMED_LATE`** (V26 migration adds the new status to `remote_action.status`). Whether the action was already flipped to EXPIRED by the cleanup job or just past its `expires_at` while still PENDING, a SUCCESS confirmation transitions it to `CONFIRMED_LATE` — the operator console can distinguish on-time vs. late delivery for SLO tracking.
- **FAILED takes precedence over timing.** A late FAILED is still FAILED, not CONFIRMED_LATE — accuracy of failure reporting matters more than the timing label.
- **Already-finalized confirms → `outcome: ALREADY_FINALIZED`.** Duplicate confirmation arriving after a previous CONFIRMED/CONFIRMED_LATE/FAILED state logs a warning and returns the existing state without mutating — protects the audit trail.

The endpoint is `permitAll` (matches `/heartbeat` — device tokens, not user JWTs).

#### Operator Action History

`GET /api/devices/{id}/actions` — paginated audit trail of every remote action issued to a device, regardless of status. Roles: `ADMIN` / `OPERATOR` / `VIEWER`. Returns `Page<RemoteActionDto>{actionId, actionType, status, payload, issuedAt, expiresAt, confirmedAt, issuedBy, result}` sorted **`issuedAt` DESC**.

This complements the device-side `GET /api/devices/{id}/actions/pending` documented above. The two endpoints solve different problems:

| Concern | `/actions/pending` (device) | `/actions` (operator) |
|---------|----------------------------|------------------------|
| Audience | The TV-Box itself | Human operator on the admin console |
| Auth | `permitAll` — device token, no user JWT | JWT, role-gated to ADMIN/OPERATOR/VIEWER |
| Status filter | Always PENDING (hard-coded) | Any status, optional filter |
| Pagination | None — small list | Page size capped at 100 |
| Sort | `issuedAt ASC` (oldest pickup first) | `issuedAt DESC` (most recent first, fixed) |
| Returned fields | Slim — what the device needs to execute | Full audit trail incl. `confirmedAt`, `issuedBy`, `result` |
| Delivery semantics | Falls off the list when CONFIRMED/EXPIRED/FAILED | Persistent — every issued action stays visible forever |

The role split is enforced because the device-side endpoint can't carry a user JWT (devices authenticate with device tokens) and the operator-side endpoint must not be reachable without authentication (the audit trail is sensitive — it names the operator who issued each action via `issuedBy`).

**Query parameters (all optional):**

- `status` — `PENDING` / `CONFIRMED` / `CONFIRMED_LATE` / `EXPIRED` / `FAILED`. The full enum from `RemoteAction.Status`. Filtering on `CONFIRMED_LATE` is the standard SLO-tracking query.
- `actionType` — exact-match string (`REBOOT`, `SYNC_CONTENT`, `VOLUME_SET`, etc.).
- `from` / `to` — ISO-8601 instants. Default: trailing 30 days ending at `now`. Hard cap: 90 days. `from > to` → 400.
- `page` / `size` — Spring Pageable. `size` ≤ 100 → 400 otherwise. Caller-supplied `sort` is **ignored**: the service forces `issuedAt DESC` to keep the operator console contract stable.

**Edge cases:**

- **Unknown deviceId → 404 even for ADMIN.** The device-existence check fires before any range/page validation. Probing `400`/`403` cannot enumerate live device ids — only authenticated, authorized callers see whether a given id resolves.
- **Date range > 90 days → 400.** Hard cap. Operators slicing audit trails do it in 90-day windows. Range exactly 90 days is allowed.
- **`from > to` → 400.** Silent swap would mask a caller bug.

### `/sync` stops holding the pool, and a playlist edit cuts over as a group (v1.0.153)

Three findings that all live in the same block of `DeviceSyncService`, fixed together because they
depend on each other.

> **VG-07 — `/sync` could deadlock the connection pool.** The whole endpoint was one
> `@Transactional` method. Inside it, every file it offered was checked against object storage
> (`statObject`) on the primary MinIO client, which carries minio-java's **5-minute** defaults — so a
> blackholed store parked the request *and* the pooled database connection it was holding. Worse, the
> anchor insert ran `REQUIRES_NEW`, so each call needed a **second** connection while still holding
> the first. Twenty devices taking a new campaign at once therefore exhausted the pool of 20 for its
> 10-second timeout: those devices played unsynced, and every other request in that window — heartbeats,
> the dashboard — failed with 500.

> **VG-06 — the coordinated cut-over never worked for an edit.** The anchor was keyed on
> `(assignment_id, version_number)`, and `ContentAssignment.bumpVersion()` has no callers, so the
> number is always 1. After any playlist edit the lookup returned the ORIGINAL row, whose `activateAt`
> was long past — so each screen flipped the moment it finished downloading. Every multi-screen site
> and every sync group showed different content until the slowest download landed.

> **VG-10 — group members raced to delete one row.** `/sync` retired a stale group-jump override with
> a Spring Data derived delete, which loads the row and removes it at commit, outside the method's
> try/catch. When members of a group synced together after an edit, all but one hit an optimistic-lock
> failure on a DELETE matching 0 rows: HTTP 500 plus a Telegram alert, for a device whose sync was fine.

**What changed**

- **`/sync` runs in three phases** (`TransactionTemplate`, not `@Transactional` — a self-invoked
  annotation gets no transaction at all): a read-only transaction that copies everything it needs into
  immutable records, **no transaction at all** for the storage checks and presigning, then a short write
  transaction opened only when there is something to write. One connection at a time, never across a
  storage call. `getPlaylistView` is split the same way.
- **A `minioMetadataClient` bean** with 3-second timeouts serves `exists()`; transfers keep the long
  defaults. A hanging store now gives the documented 503 in about three seconds.
- **The anchor is keyed on the content version** (`V53`, `uq_playback_sched_cv`), so an edit anchors
  its own future cut-over and every member of the group flips together. `version_number` is kept and
  still written. The insert is `ON CONFLICT DO NOTHING` **in the caller's transaction**, which is what
  let the `REQUIRES_NEW` writer bean (`PlaybackScheduleWriter`) go; a lost race writes 0 rows, raises
  nothing, and re-reads the winner's row.
- **The cut-over lead is sized from the download it implies:** `1.5 x newBytes /
  app.sync.activation-assumed-bytes-per-sec` (new property, default 1 MB/s), clamped to
  `[activation-min-lead, activation-max-lead-cap]` = 2–15 min. A reorder or dwell edit downloads
  nothing and gets the 2-minute floor; a fresh device's full pull is excluded, so one new box cannot
  push the group's cut-over to the cap.
- **The sync-pending marker is a targeted UPDATE** (`COALESCE(sync_pending_since, :now)`), because the
  write phase holds no `Device` entity to dirty-check.
- **A stale override is retired once, by the edit** (`SyncGroupOverrideCleaner`, AFTER_COMMIT +
  REQUIRES_NEW + `fallbackExecution`), with a bulk `DELETE … WHERE assignment_id IN`. `/sync` only
  ignores one, which it already did safely.

**Operator-visible:** a playlist edit now takes at least 2 minutes to reach the screens (longer when it
adds a large file), and they all change together. **Device-visible:** an edit's `activateAt` is in the
future, so the client must apply the §7.3 formula to every pending version — `ANDROID_DEVICE_FLOW_SPEC.md`
R28, §7.4 and G-14 are updated.

**Tests:** `DeviceSyncConnectionPoolIntegrationTest` (new; 8 concurrent syncs against the test
profile's 3-connection pool, storage stubbed to sleep 500 ms and to assert no transaction is open,
all sharing one anchor), `PlaybackScheduleServiceTest` (rewritten for the content-version key and the
lead formula), `PlaybackSyncSchedulePersistenceTest` (+4: two content versions of one assignment both
anchor, `insertIfAbsent` returns 1 then 0), `DeviceSyncServiceTest` (+3, and the marker/override
assertions rewritten), `SyncGroupOverrideCleanerTest` (new), `MinioHealthProbeTest` (+1 for the new
client's timeouts). Mutation-checked: putting the storage calls back inside a transaction fails the
pool test with "storage was called while a database transaction was open".

### Plays are credited to the campaign that was on screen when they played (v1.0.152)

> **VG-03 (review LOGIC-17).** Every entry in a playback batch was checked against the campaign
> resolved **now** (`resolveForDevice(device, Instant.now())`), not at its `playedAt`, and the row was
> written with `assignment_id = NULL`. A device flushes minutes after the fact and keeps playing the
> old loop until it has downloaded the new one, so every campaign switch discarded that trailing
> window of the old campaign's plays — and a device offline across a switch lost all of them. The
> plays were rejected as "not assigned to device", i.e. as forgery. Advertiser proof-of-play
> undercounted, which is what the invoices are built from.

**What changed:** a batch now loads the device's campaign *history* once — every CONFIRMED assignment
targeting its region/facility/group whose window overlaps the batch's span, including rows soft-deleted
inside it (`findHistoricalCandidates`) — plus the device's exclusions and each candidate playlist's
files. Each entry is then judged at its own `playedAt`: the winner among the campaigns live at that
instant decides (same `PRECEDENCE` the device resolves with), and if the winner doesn't hold that clip,
the campaign that most recently stopped applying to *this device* and did hold it is credited, within
`app.playback.assignment-grace` (default 30 min = the 15-minute activation lead cap + the 5-minute
flush + margin). Only then is the entry refused. A device that nothing ever targeted still has its
plays kept and unattributed, as before.

Re-resolving at `playedAt` would not have been enough: the live path filters `deleted_at IS NULL` and
ignores when an exclusion was written, so a cancelled, replaced or narrowed campaign would still
vanish. `ContentAssignment` gained `effectiveEnd()` and `wasLiveAt`/`wasLiveDuring` — the historical
twin of `PRECEDENCE`, in the same place, so the rule has one home. Every accepted play now carries its
`assignment_id`; the column and its FK have existed since V11, so **no migration**. Dedup is unchanged
(`uq_playback_dedup` ignores the campaign), and no report reads the column yet.

**Known limit:** playlist membership has no history (items are hard-deleted), so a play of a clip that
was removed from the playlist, or from a device that changed region, is still refused if it arrives
after the change. V53's per-version anchor rows are the natural place to fix that later.

**Tests:** `PlaybackLogBatchTest` (+6, including the switch-window repro that asserts the captured
`assignment_id`s), `ContentAssignmentHistoricalCandidatesTest` (new, H2 under the full schema: keeps a
campaign soft-deleted mid-window, drops one deleted before it, drops DRAFTs and non-overlapping
windows, targets region/facility/group, and a device with no facility or group), and
`PlaybackLogDedupInsertTest` (+1: the id persists and does not widen the dedup key). Mutation-checked:
dropping the attribution, and judging at `now` instead of `playedAt`, each fail exactly the tests
written for them.

### The device page's playlist panel works again (v1.0.151)

> **VG-02.** The operator UI's active-playlist panel called `GET /api/devices/{id}/playlist`, which is
> device-only (`hasRole('DEVICE')` and the token's id must equal the path id). Every operator, admin and
> viewer therefore got 403, the frontend swallowed it, and every device page said "No playlist assigned"
> with the Prev/Next/Jump controls permanently hidden.

**What changed:** a new operator-side endpoint `GET /api/devices/{id}/active-playlist`
(`ADMIN`/`OPERATOR`/`VIEWER`, then `assertScopeForDevice` so an out-of-scope operator gets 404, never a
hint the device exists). It returns the **deliverable** items in play order, re-indexed from 0, so
`items[].index` is exactly the `position` that `POST /playlist/control` accepts for a JUMP: the row an
operator clicks and the clip the device jumps to cannot drift apart. `PlaylistControlService` now lists
and range-checks through one helper for that reason.

The device's own `/playlist` stays device-pinned: it presigns a media URL per item and probes storage
for each one, so widening it would leak media to viewers and make a panel refresh do object-store I/O.
The new endpoint does no storage I/O at all and carries no URLs or storage keys. `durationSeconds` is
the slot the device really plays (the 10 s default when an item has no dwell and the file no length),
never null. `scheduled` reports that a playback anchor exists — the device is in synchronised group
playback and answers `PLAYLIST_CONTROL` with `FAILED "SCHEDULE_MODE"` — so the UI can disable per-device
transport and point at the sync-group jump. The anchor is only read (`PlaybackScheduleService.find`);
rendering a panel never arms a cut-over.

**Tests:** `PlaylistControlServiceTest` (+6: re-indexing across a non-deliverable item, the default-slot
duration, the listed rows matching the JUMP bound, no assignment, unknown device, anchor read but never
created), `DeviceControllerTest` (+8: 200 for admin/operator/viewer with the indexed items and no URLs,
200 with no items when nothing is assigned, 404 out of scope without touching the service, 403 for
advertiser and for a device token, 401 unauthenticated, and a regression that an operator still gets
403 from the device-only `/playlist`).

### Remote actions reach a connected device at once (v1.0.150)

> **VG-04 (review G-1).** Issuing an action (Reboot, Next, Prev, volume, playlist control, a group
> action) only saved a `remote_action` row. `ACTION_PENDING` was pushed only when a device
> (re)connected, so a connected device learned of the action at its next heartbeat — up to ~130 s —
> and with two missed beats the 5-minute action expired and never ran. The buttons looked broken.

**What changed:** every path that creates an action (`RemoteActionService.issue`, the group path
`BulkRemoteActionService.issueOneIsolated`) publishes a `RemoteActionIssuedEvent`, and
`RemoteActionPushListener` pushes the same `ACTION_PENDING` frame the connect replay sends — after the
issuing transaction commits, so a rolled-back action never reaches a device (`fallbackExecution`
covers the group path, which runs without a surrounding transaction). The frame is formatted in one
place (`DevicePushFrames.actionPending`), shared with the replay. The push is best-effort; the
heartbeat and `GET /actions/pending` stay the guarantee, and devices already deduplicate by
`actionId`. `ANDROID_DEVICE_FLOW_SPEC.md` (R2, §8.1, §10, G-1) is updated.

**Tests:** `RemoteActionPushIntegrationTest` (new, real context: committed → pushed, rolled back →
never pushed, group action with no surrounding transaction → pushed to every member),
`RemoteActionPushListenerTest` (new), `RemoteActionServiceTest` (+2), `BulkRemoteActionServiceTest`
(+1 assertion). Mutation-checked: removing the publish, firing before commit, or dropping
`fallbackExecution` each fails exactly the test written for it.

### Transcoded videos always play on TV boxes: 8-bit 4:2:0 High profile (v1.0.149)

> **VG-01 (review G-11).** ffmpeg was never told the output pixel format or profile, so libx264 kept
> the source's: a 10-bit clip (the default iPhone HDR recording) became **H.264 High 10**, a 10-bit
> 4:2:2 ProRes/DNx master became **High 4:2:2**. Both passed every check and showed READY with a
> thumbnail, but most Android TV hardware decoders cannot play them: that ad slot was black or
> skipped, and nothing warned anyone. Reproduced locally with real encodes before the fix.

**What changed:** the encode now always adds `-pix_fmt yuv420p -profile:v high`. The level is left to
x264, which derives it from the actual resolution and frame rate (3.1 for 720p30, 4.2 for 1080p60);
pinning 4.1 would mislabel 1080p60. Checked on real encodes: 10-bit HEVC, 10-bit 4:2:2 ProRes and a
1080p60 HLG clip all come out as `High, yuv420p`.

**Not done (follow-up):** HDR tone mapping. An HLG source (the iPhone default) converted to 8-bit
without tone mapping still plays and looks acceptable, because HLG is designed to degrade gracefully
on SDR screens; a PQ/HDR10 source would look flat. Files transcoded before v1.0.149 keep their old
format until someone retranscodes them.

**Tests:** `FFmpegTranscoderTest` (+2 real encodes that run where ffmpeg is installed and are skipped
elsewhere, + the argument assertions). Mutation-checked: without the two flags the real encodes come
out `High 10` and `High 4:2:2` again.

### Dependency updates: Spring Boot 3.5.16 and the libraries with known CVEs (v1.0.148)

> The 2026-09-18 review's dependency item. Spring Boot 3.4.5 carried Tomcat 10.1.40, which is
> affected by CVE-2025-48988 (multipart DoS — reachable through the 50 MB upload endpoint) and
> CVE-2025-52520, plus patch-level Spring Framework and Security fixes.

| Dependency | Was | Now | Why |
|---|---|---|---|
| Spring Boot (plugin + BOM) | 3.4.5 | **3.5.16** | Newest 3.x: Spring Framework 6.2.19, Spring Security 6.5.11, Hibernate 6.6.53, Flyway 11.7.2. 4.x is a major migration (Framework 7, Hibernate 7) and was left out on purpose |
| springdoc-openapi | 2.7.0 | 2.9.1 | Built against Boot 3.5.16 |
| swagger-annotations | 2.2.25 | 2.2.55 | Same swagger-core as springdoc 2.9.1 |
| MinIO client | 8.5.14 | 8.6.0 | |
| Apache POI | 5.3.0 | 5.5.1 | CVE-2025-31672 (read path only here, bumped anyway) |
| jjwt | 0.12.6 | 0.13.0 | 0.12.7's parser fixes; 0.13.0 adds only a public constructor |
| ArchUnit (test) | 1.3.0 | 1.5.0 | |

**Security pins on top of Boot 3.5.16.** 3.x is out of open-source support, so even its last BOM
ships libraries with advisories published since — the Tomcat ones critical (authentication and
authorization bypasses). An OSV scan of the production jar found 8 affected libraries; each has a
patch release in the same line, pinned in the root `build.gradle` with the advisory ids beside it:

| Library | Boot 3.5.16 | Pinned | |
|---|---|---|---|
| Tomcat (`tomcat.version`) | 10.1.55 | **10.1.60** | 3 critical (GHSA-9xv2-5v5q-p794, -gcx9-497g-6cp6, -h3x4-894j-xpx5); 10.1.58 was never published, 10.1.60 is the newest 10.1.x |
| Netty (`netty.version`) | 4.1.135 | 4.1.137 | 1 critical, 1 high, 1 moderate (via Lettuce) |
| BouncyCastle `bcprov-jdk18on` | 1.81 | 1.85 | 2 critical, 1 high, 1 moderate (via MinIO; not in Boot's BOM) |
| PostgreSQL JDBC | 42.7.11 | 42.7.12 | 1 high |
| Jackson (`jackson-bom.version`) | 2.21.4 | 2.21.5 | 3 moderate |
| commons-lang3 | 3.17.0 | 3.18.0 | 1 moderate |
| Log4j API (`log4j2.version`) | 2.24.3 | 2.25.5 | 1 moderate (SLF4J bridge only) |

After the pins, the same scan reports **no known vulnerabilities in any of the 235 production
artifacts**. Re-run it before each release and drop a pin once a Boot upgrade overtakes it.

**H2 is no longer in the production jar.** It was `runtimeOnly` in `infra`, so every image shipped
an embedded database engine it never uses. It is now `testRuntimeOnly` (infra, api) and
`developmentOnly` in `api`: on the `bootRun` classpath for the `dev` profile, excluded from
`bootJar`. Checked: the built jar has no `h2-*.jar`, and `./gradlew :api:bootRun
--args='--spring.profiles.active=dev'` (the README command used to say `:service:bootRun`, which
does not exist) boots, applies all 52 migrations to H2, and serves `/api/health`.

**One build change was needed:** JUnit 5.12 (via the Boot 3.5 BOM) needs a
`junit-platform-launcher` of the same version, and Gradle 8.11 otherwise supplies its own, so test
discovery failed in every library module ("OutputDirectoryProvider not available"). The root build
now declares `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'` (version from the BOM).

No application code changed. All 2,465 tests pass on the new versions (the real-Tomcat integration
tests and the opt-in real-Postgres test included), the test log shows no new deprecation warnings,
and the `dev` profile boots on Tomcat 10.1.60.

**Not changed:** `telegrambots` 6.9.7.1 (the 6.x line is abandoned; moving to 7.x+ is a rewrite of the
bot integration) and the Gradle wrapper (8.11 supports Boot 3.5).

### A transcode waiting in the queue is no longer failed as "crashed" (v1.0.147)

> **LOGIC-08.** The claim stamped the lease (`transcode_started_at`), so a job's 20-minute lease
> started ticking while it merely waited in the queue. Upload several long videos to a single-width
> pool and the later ones waited past the lease: each sweep re-claimed them (one attempt each) and
> queued a duplicate, and after three rounds they were marked FAILED — "Abandoned after 3 transcode
> attempt(s)" — without ever having been encoded, and not retried. Separately, the start guard
> only checked the status, so on a pool wider than one a duplicate queue entry could start a
> second, concurrent encode of the same file.

**What changed**

- **Queued and started are separate** (`V52`). The claim stamps the new `transcode_queued_at` and
  leaves `transcode_started_at` null; the lease is taken only when the encode actually starts.
- **The start guard admits one task.** `beginTranscode` succeeds only while `transcode_started_at`
  is null, so of any duplicate queue entries exactly one runs; the rest are logged no-ops.
- **Attempts count encodes that started**, not claims. Three real crashes still end the retry
  loop; waiting or being re-queued costs nothing.
- **The sweeper asks before it re-drives.** The transcoder tracks the files it holds (queued or
  running); a `TRANSCODING` row still held is skipped however old it is — including an encode
  running past its lease. A row no longer held (rejected by a full queue, lost in a restart, or its
  task died before starting) is re-queued after the usual 5-minute grace, through a
  compare-and-set on the sweeper's own predicate (`requeueStalledTranscode`), so a task that
  started in the meantime is never reset.
- **No duplicate queue entries.** Dispatching a file that is already queued here is a no-op. One
  whose task is still *running* is queued normally: that can only be a legitimate re-dispatch after
  the task wrote its terminal state (an operator retranscode landing before the task's cleanup).
- **Held files are excluded from the stalled-row query itself**, so a long queue cannot fill the
  sweeper's 50-row page and starve genuinely lost rows.

**Because a held file is never reclaimed, a hung encode must not be silent:**

- **ffprobe's timeout is real now.** `FFprobeVideoInspector` read stdout to the end before
  `waitFor(30s)`, so the timeout could never fire, and read stderr only afterwards, so a corrupt file
  that floods stderr deadlocked it. Both streams are now drained on virtual threads, the wait is
  bounded (`app.video.ffprobe-timeout`, default 30 s) and the process is killed on timeout. On a
  single-width pool one such upload used to stall every transcode behind it.
- **An encode still running past its lease** is an ERROR on every sweep (forwarded to Telegram) and
  turns the `transcode-backlog` health component DOWN (`countEncodesPastLease`).
- **A task failure is logged** (ERROR, through the log pipeline) instead of dying on the pool
  thread's stderr, and a file is always released from the held set — even if `submit` itself throws
  or the pool is shutting down (the executor's rejection handler now throws, so `submit` reports
  the drop).

**Deploy note (V52 backfill).** Rows already `TRANSCODING` at deploy were claimed under the old
rules (attempts counted claims; `transcode_started_at` is a claim time), so V52 resets them to
"queued, never started" with a fresh budget and the boot sweep re-queues them. `FAILED` rows are
left alone: a pre-v1.0.147 "Abandoned after 3 transcode attempt(s)" may be a queue-wait victim or a
real poison file. If uploads failed that way after a bulk upload, use **Retranscode** on them.

The lease and the boot-time recovery are otherwise unchanged; with several application instances
the start guard, not the in-process check, is what keeps an encode single.

**Tests:** `TranscodeQueueWaitIntegrationTest` (new, real schema + real sweeper: the review's
scenario — a job waiting through four lease periods is never re-claimed, abandoned or duplicated,
then starts once; a lost queue entry is re-queued without costing an attempt),
`ContentFileTranscodeLeaseTest` (claim/start semantics, the one-starter guard, the requeue CAS, the
stalled-candidate predicate with held files excluded, the past-lease count, the V52 backfill),
`TranscodeSweeperTest` (+7), `FFmpegTranscoderTest` (+6: no duplicate queue entries; released after
success, after a logged failure, when `submit` throws and on rejection; a re-dispatch during the old
task's cleanup is queued), `FFprobeVideoInspectorTest` (new: a hung ffprobe times out, a stderr flood
does not deadlock — scripted stand-ins, each time-boxed), `TranscodeExecutorTest` (+1),
`TranscodeBacklogHealthIndicatorTest` (+1), `TranscodeDispatchServiceTest` (+3). Mutation-checked:
restoring the old start guard, the old sweeper behaviour or the old ffprobe reading each fails them.

### Pre-release fixes: event retention, deleted devices, playlist shifts, schedules, exports, passwords (v1.0.146)

> Seven small fixes from the 2026-09-18 review, picked because each one is easy to hit in normal
> use. Frontend half: FE-19 (route roles; viewers no longer see incident actions they cannot use).

**DATA-03 — event retention stopped for good.** Retention skipped only the events of *open*
incidents, but `incident.first_event_id`/`last_event_id` are plain foreign keys and incidents are
never deleted, so the first batch holding a resolved incident's event failed — and, ordered by
`id ASC`, it was the first batch every night. Since v1.0.140 every offline→online cycle
auto-resolves an incident, so this would have hit about 90 days after deploy. The query
(`findExpiredIdsNotReferencedByIncidents`) now skips events that **any** incident references; `V49`
indexes both columns.

**G-6 — a deleted device could never register again.** The deleted row kept its serial and V4 made
`serial_number` globally `UNIQUE`, so the box got a 500 from `/register` on every boot. `V50` (Java,
because V4's inline constraint has an engine-generated name) replaces it with `UNIQUE` on a
generated `live_serial` column — the serial while live, NULL once deleted — so the rule is "unique
among live devices" on Postgres and H2 alike. The box registers as a new device; the deleted one
keeps its history. No code change was needed in `DeviceRegistrationService`.

**LOGIC-09 — shifting playlist items could hit a duplicate key.** Postgres checks a plain `UNIQUE`
row by row, so `UPDATE … SET position = position ± 1` collided depending on physical row order
(reorder, then remove → 409/500). `V51` re-declares `uq_playlist_position` as
`DEFERRABLE INITIALLY IMMEDIATE`, checked at the end of each statement. Postgres-only (a no-op on
H2, which already checks per statement — why tests never saw it).

**LOGIC-12 — any operator could exhaust the heap with one schedule.** Expansion walked every
occurrence from the start: a DAILY schedule from year 1 was ~740k windows on create, on every GET
(VIEWER too) and on every evaluation. Occurrences are now computed arithmetically from the moment
that matters, capped at 1,000 per expansion (for rows saved before validation), MONTHLY no longer
drifts (Jan 31 → Feb 28 → Mar 31), and overlap detection is a single merge pass over the windows
from now on (a repeating schedule that started years ago used to be compared on its history only).
New validation: end after start; a repeating window at most one interval long; repeat end after start.

**AUTHZ-03 — formula-looking export cells.** Device names, serials and event payloads come from
devices, and `/register` needs no login. POI writes them as text, so nothing runs on open — but
F2 + Enter (or a copy into CSV) turns `=HYPERLINK(…)` into a live formula. Cells starting with
`= + - @`, tab or CR now get Excel's *quote prefix* style: always text, value unchanged.

**AUTH-09 — dead default-login bean removed.** `InMemoryUserRepository` (a live `@Repository` with
`admin`/`password`, injected nowhere) and its `UserInfo` record are deleted.

**AUTH-08 — one password rule.** Creating a user accepted 6 characters; change and reset required 8.
`PasswordPolicy` — at least 8 characters, at most 72 **bytes** — now drives all three, and the three
request DTOs' `@Size`. The ceiling is bcrypt's: the encoder throws past 72 bytes, so the old 200 let
73–200 characters (or 37 Cyrillic letters) pass validation and fail inside the encoder. Login is
unchanged on purpose: it must accept whatever an account was created with. The frontend already
required 8 everywhere.

**Tests:** `EventRetentionIncidentReferenceTest` (new, real schema: resolved/open incident events
skipped, returned ids deletable, the old failure reproduced), `DeviceSchemaTest` (the test that
pinned the G-6 bug is inverted; + un-deleting a duplicate is still refused),
`DeviceReRegistrationAfterDeleteIntegrationTest` (new: register → delete → register, and the new
device is still 409-protected), `ScheduleServiceTest` (+11), `ExcelExportServiceTest` (+3),
`UserManagementServiceTest` (+4), `UserControllerTest` (+1), and `PostgresMigrationSmokeTest`
(new, opt-in, below).

**Real-Postgres check (opt-in).** H2 cannot show V51 (a no-op there) or V50's Postgres naming, so
`PostgresMigrationSmokeTest` runs the migration chain on a real, disposable Postgres. It reproduces the
LOGIC-09 collision on the pre-V51 schema, shows it gone after V51, and checks V50's constraint swap.
It is skipped unless `ORIENT_PG_TEST_URL` is set, and it only cleans a database named
`orient_migration_smoke`:

```bash
docker run -d --rm --name orient-pg-smoke -p 127.0.0.1:55432:5432 \
  -e POSTGRES_PASSWORD=smoke -e POSTGRES_DB=orient_migration_smoke postgres:17-alpine
ORIENT_PG_TEST_URL=jdbc:postgresql://127.0.0.1:55432/orient_migration_smoke \
  ./gradlew :infra:test --tests '*PostgresMigrationSmokeTest'
```

### Operator project scope enforced on content moves, retranscode, upload and every create (v1.0.145)

> **AUTHZ-01/02 — a project-restricted OPERATOR could reach into other tenants' projects.**
> Reads, renames and deletes were scoped, but five writes only ever called `findById`:
> `PATCH /api/content/{id}/project` moved **any** content file (even one the operator couldn't see)
> into any project, or cleared its project; `POST /api/content/{id}/retranscode` re-queued any file
> and reset its attempt counter, so walking the sequential ids kept ffmpeg busy indefinitely; the
> upload's `projectId` landed bytes in any project; and `create()` on Region, Facility, Playlist and
> DeviceGroup saved under whatever `projectId`/`regionId` was sent — only `SyncGroup` create had the
> guard.

**What changed**

- **Content row rules** (`ContentListService`, called from `ContentController`). Moving a file is a
  management action, so it takes the delete rule, now named `assertOperatorCanManage`: owned passes,
  granted-only is 403, anything else is 404. Retranscode uses the new `assertOperatorCanAccess`, the
  same owned-or-granted rule as `GET /api/content/{id}`, since the operator is only retrying a file
  they can see. Admins and admin+operator hybrids skip both.
- **Content project scope** (`ContentManagementService.assignProject`). A file currently bound to an
  out-of-scope project is 403: the operator already sees the file, so there is nothing to hide. An
  out-of-scope target is the same `Project not found` 404 as an unknown id.
- **Upload** (`ContentUploadService.persistUploaded`). An out-of-scope `projectId` is handled exactly
  like an unknown one: the file is saved as orphan content and the response is the same. A 404 here
  would reveal which project ids exist, because unknown ones are accepted.
- **Every `create()`** now calls `operatorScopeResolver.resolve().excludes(..)` before the duplicate
  check, the same as `SyncGroupManagementService.create`. Running it first matters: otherwise a 409
  would reveal which names exist in another tenant's project. Facility checks
  `region.getProject()` and answers `Region not found`.

**Not changed:** the frontend. Its project picker and the content grid only show in-scope projects
and visible rows, and it doesn't call `PATCH …/project` anywhere. Out-of-scope requests can only
come from hand-crafted calls.

**Tests:** `ProjectScopedCreateGuardTest` (new, 7: all four creates out of scope → 404 with the
missing-row message, no duplicate probe, no save; empty scope; in scope; admin),
`ContentManagementServiceTest` (new, 5), `ContentListServiceTest` (+3), `ContentUploadServiceTest`
(+2), `ContentControllerAssignProjectTest` (+4), `ContentControllerRetranscodeTest` (+2).

### MinIO outages self-heal; storage on `/api/health`; `/sync` stops hiding one (v1.0.144)

> **DATA-02 — "one blip and storage is down until someone restarts the app."** `MinioHealthStatus`
> was a two-state flag that starts **DEGRADED**, and the only code in the entire application that
> could ever set it back to **UP** was `MinioBucketInitializer`, which runs once, at startup. Every
> other writer could only degrade it — and every writer did, from a blanket `catch (Exception)` on
> upload, download, `exists` and `delete`. So a MinIO restart, a full disk, a five-second network
> blip or a single unreadable object latched the process: `ensureAvailable()` then threw
> `StorageUnavailableException` for **every** storage call, including `generatePresignedUrl`, which
> is pure local crypto and works perfectly while MinIO is unreachable (its own comment said so).
> Uploads 503'd, transcodes were marked FAILED — and `/sync` was worse than either, because
> `tryBuildFileToAdd` swallowed *both* the existence check and the presign failure to `null`: the
> endpoint answered **HTTP 200 with a silently incomplete plan**. A TV box cannot tell that apart
> from a correct answer. It applied it, played a shortened loop, and the fleet quietly ran the wrong
> playlist until an operator happened to restart the backend. MinIO was also **absent from
> `GET /api/health` entirely**, so the one surface an operator checks said nothing at all.

**What changed in the repo**

- **`MinioHealthStatus` carries a reason and a `since`**, modelled on `StorageLifecycleStatus`: one
  immutable snapshot behind an `AtomicReference`, so state and reason can never be read torn.
  `markDegraded(reason)` / `markUp()` log **once, on the transition** — WARN going down with the
  reason, INFO coming back with how long it was down and what took it down. Re-marking the state we
  are already in logs nothing and does **not** move `since`: every device in the fleet reaches
  `markDegraded` on every failed call, WARN is forwarded to Telegram, and "down for 4 hours" must
  not read as "down for 30 seconds" forever.
- **New `MinioHealthProbe`** — `@Scheduled(fixedDelayString = "${app.minio.recheck-interval:PT30S}")`,
  the missing second writer. While storage is degraded it issues one `bucketExists` against the raw
  bucket and clears the latch on the first successful round trip; while storage is healthy it does
  nothing at all, so a healthy deployment pays no MinIO calls for it. It never throws out of the
  scheduled method, and a failed probe stays at DEBUG — the outage was already announced once.
  A reachable MinIO that answers "no such bucket" still heals the flag: that is an S3-level fact
  about one bucket, not a connectivity failure.
- **The probe is cheap to FAIL, not just cheap to pass.** It runs on Spring's shared scheduling
  pool — the Telegram log forwarder, `SyncTimeoutMonitor` and `TranscodeSweeper` are on it too —
  and minio-java's OkHttp defaults are **5 minutes**. A *blackholed* MinIO (packets dropped rather
  than refused: a crashed host, a full conntrack table, a dropped firewall rule) would hang each
  probe for the whole timeout, parking a pool thread for the duration of the outage and stopping
  the alerting meant to report it. So the probe uses its own `minioProbeClient` with 3 s
  connect/read/write timeouts (the shared client keeps its long ones — it carries whole video
  files), and `spring.task.scheduling.pool.size` is **3**, not the default 1. `fixedDelay` already
  serialises a job against itself, so no other guard is needed.
- **`app.minio.recheck-interval` fails startup below 1 s, naming the property.** The key is read
  twice — bound onto `MinioProperties` and by the `@Scheduled` placeholder — and a bare number is
  milliseconds to both, so `APP_MINIO_RECHECK_INTERVAL=30` meaning "30 seconds" would be 33 probes
  a second against a dead MinIO, each holding a scheduler thread.
- **New `MinioFailureClassifier` — one definition of "MinIO is broken", used by every writer of
  the flag** (the storage client and the Telegram `/health` probe). Checked against minio-java
  8.5.14's real behaviour, not its exception names. Degrades on: `java.io.IOException` **anywhere in
  the cause chain** (`S3Base.throwEncapsulatedException` rethrows nine declared types and wraps
  everything else in a bare `RuntimeException`, so a type-only check loses real outages);
  `ServerException`; `InvalidResponseException` (the bytes were not an S3 response at all — a proxy
  error page, a half-started MinIO); and an `ErrorResponseException` that is a **5xx** — by S3 code
  (`InternalError`, `SlowDown`, `ServiceUnavailable`) or by HTTP status. `ServerException` alone is
  not enough: it is constructed in exactly one place in the SDK, for a 5xx whose body could not be
  parsed, so "MinIO up but broken" normally arrives as an `ErrorResponseException` instead. Stays
  per-request: every 4xx (`NoSuchKey`, `NoSuchBucket`, `AccessDenied`), the quota
  `XMinioStorageFull` — excluded by **code**, because MinIO answers it with HTTP **507** and a full
  MinIO still serves reads, so a status-only rule would degrade on every upload and heal on every
  probe, flapping a WARN+INFO into the operator chat each time — and the argument/parsing families.
  The reason handed to `markDegraded` is operation + exception **type** only
  (`"upload: ConnectException"`), never the message or the object key, because it is published on an
  unauthenticated endpoint.
- **`ensureAvailable()` removed from both `generatePresignedUrl` overloads.** Signing is an HMAC
  over strings with the region preconfigured; it never touches the network. Gating it on the health
  flag is what turned "MinIO is down" into "every device gets a short playlist". It still does not
  mark degraded on failure — a presign failure is an argument bug, not an availability signal.
- **`DeviceSyncService.tryBuildFileToAdd` tells the two failures apart.** A genuinely missing object
  (`exists == false`, `NoSuchKey`, `ResourceNotFoundException`) keeps today's WARN-and-skip: it is a
  fact about one file and the rest of the playlist must still ship. A `StorageUnavailableException`
  now **propagates**, so `/sync` returns **503 "retry later"** — already the documented device
  behaviour — instead of 200 with a truncated plan. It propagates *before* `filesToDelete` is
  computed, so an outage can never produce a delete instruction either. `/playlist` shares the
  method and fails the same way, for the same reason.
- **`GET /api/health` gains a `storage` component** (new `StorageHealthIndicator`, same shape as
  `StorageLifecycleHealthIndicator`/`DiskFreeHealthIndicator`), reporting DEGRADED as DOWN with the
  reason and since when.
- **The Telegram `/health` MinIO probe now writes back.** An operator typing `/health` performs
  exactly the round trip the scheduled probe performs; a success heals the latch and a failure
  records why — through the **same classifier**, so an `AccessDenied` from `listBuckets` (a healthy
  MinIO refusing these credentials) reports DOWN in the chat without 503-ing every upload and every
  device `/sync` in the fleet because an operator typed a slash command. Reporting "minio UP,
  4 bucket(s)" in the chat while the application goes on refusing uploads because a stale flag says
  otherwise is an hour of an incident. "No MinioClient bean" is a wiring fact, not a probe result,
  and touches neither direction.
- **The 503 body no longer echoes MinIO's message.** Storage failures reach TV boxes through
  `/sync` and `/playlist` now, and MinIO's messages name the endpoint, the bucket and the object
  key. Every storage 503 out of `MinioStorageClient` carries one fixed string; the detail stays in
  the log.
- **The storage 503 log line is constant text at WARN, not ERROR with a correlation id in it.**
  `TelegramRateLimiter` keys its 5-per-5-minutes window on a SHA-256 of the rendered message, so a
  per-request id made every occurrence a distinct hash: only the global 30/minute cap applied, and
  a fleet-wide outage would evict every other alert from the 500-entry appender buffer — including
  the ones explaining the outage. The correlation id moves to an INFO line, below the appender's
  WARN threshold, so it never reaches Telegram and stays greppable in the log.
- **New `app.minio.recheck-interval`** (`APP_MINIO_RECHECK_INTERVAL`, default `PT30S`) in
  `MinioProperties`, `application.yml`, `.env.example` and the compose `environment:` block — a var
  in `.env` alone does not reach the container. Write it as an ISO-8601 duration: the key is read
  both by the binding and by the `@Scheduled` that drives the probe, and they agree on every input
  only because neither declares a unit (a bare number is milliseconds to both).

**Operating notes**

- **A MinIO outage now recovers within ~30 s of MinIO coming back, with no restart.** Nothing needs
  to be run by hand; `docker restart minio`, a full disk that gets cleared, or a network blip all
  heal on the next probe tick. `/health` in Telegram heals it immediately.
- **`/sync` answers 503 during an outage instead of a 200 with an incomplete playlist.** Devices
  retry, and keep playing what they already hold — the previous behaviour handed them a short plan
  they applied as if it were correct. The cost is that a storage outage is now visible to devices;
  that is the point.
- **A full bucket, a denied ACL or a deleted object no longer disable storage for everyone.** They
  fail the one request that hit them, exactly as before, and the next request is unaffected. A 5xx
  from MinIO does degrade — that is MinIO saying it cannot serve — and heals on the next probe.
- **Devices and browsers get a fixed "Object storage is temporarily unavailable" on a storage 503.**
  The endpoint, bucket, key and MinIO's own message stay in the application log.
- **`/api/health` now shows storage** (`"storage"`, DOWN with e.g.
  `upload: ConnectException (since 2026-09-20T04:12:00Z)`), so "uploads are 503-ing" is visible on
  the same surface an operator already checks. A freshly-started process reports it DOWN with
  `not probed yet (startup)` until the bucket initializer runs — honest, because storage endpoints
  really do 503 in that window.

**Tests:** new `MinioHealthStatusTest` (a logback `ListAppender` on the status logger) — the DEGRADED
and UP transitions each log exactly one line at the right level, the recovery line carries both the
reason and the duration, re-marking either state logs nothing and leaves `since` identical
(`assertSame`), a full degrade→recover cycle logs exactly two lines, a null/blank reason falls back
to a placeholder so health never publishes null, and `humanize` renders seconds/minutes/hours and
clamps a backwards clock to `0s`. New `MinioHealthProbeTest` — degraded + probe ok → UP with the
reason cleared, the configured raw bucket is the one probed (`ArgumentCaptor`), a probe that throws
(checked `ConnectException` *and* an unchecked `IllegalStateException`) leaves the status degraded
with its original reason and lets nothing escape, healthy → `verifyNoInteractions(minioClient)`, a
missing bucket still heals, and two failures followed by a success recover on exactly the third
probe. `MinioStorageClientTest` — quota `XMinioStorageFull`, `AccessDenied` on upload/stat/download
and `NoSuchKey` on delete all reach the caller **without** degrading; `NoSuchBucket` on stat still
returns false; `IOException`/`ConnectException`/`SocketTimeoutException` and `ServerException` do
degrade, with the reason asserted to be `object stat: IOException` and **not** to contain the
exception message; an unrecognised `IllegalArgumentException` stays per-request; presigning succeeds
while degraded on both overloads and does not heal the flag. `DeviceSyncServiceTest` — a
`StorageUnavailableException` from the existence check propagates out of `computeSyncPlan` (and out
of `getPlaylistView`), one unverifiable file fails the whole sync rather than shipping the others,
and during an outage no plan is produced at all so a held-but-no-longer-expected file is never
scheduled for deletion and the in-flight marker is never armed (`verify(device, never())`); the
missing-object and `ResourceNotFoundException` cases are still skipped with the rest of the playlist
shipping. `HealthServiceTest` — six components in order with `storage` last, and an unreachable
MinIO surfacing its reason while the database stays UP. New `StorageHealthIndicatorTest` — UP/DOWN,
the reason and `since` on the payload, the pre-startup state, and recovery without a restart.
`HealthCommandHandlerTest` — the Telegram probe heals the latch on success, degrades it on failure
with `telegram /health probe: ConnectException` as the reason, leaves it alone when there is no
`MinioClient` bean, and — the fleet-safety case — reports DOWN for an `AccessDenied` from
`listBuckets` **without** degrading storage. New `MinioFailureClassifierTest` covers each family
against the SDK's real shapes: a buried `IOException` two wrappers deep, a 5xx
`ErrorResponseException` by status and by code, `InvalidResponseException`, an
`ErrorResponseException` with no parsed body (`Set.of(…).contains(null)` would throw), 4xx codes,
`XMinioStorageFull` at 507, the argument/parsing families, null, and a self-referential cause
chain. `MinioStorageClientTest` adds the leak guard — a MinIO message naming the endpoint, bucket,
object key and access key never appears in the 503 body, and upload/delete/short-circuit all return
the same fixed string. `MinioHealthProbeTest` asserts the probe uses the short-timeout client and
never the shared one, reads the built beans' OkHttp timeouts through reflection (3 s on the probe
client, longer on the upload client), and pins the `recheck-interval` floor. New
`StorageUnavailableLoggingTest` — two failures produce byte-identical WARN text, the text contains
no correlation id or endpoint, the id is on an INFO line, nothing is logged at ERROR, and the
response is still a 503 with a per-request id.

### Device traffic no longer audited; per-table retention (v1.0.143)

> **DATA-01 — "`audit_log` grows with the fleet, and nobody can read it."** `AuditFilter` wrote a full
> request **and** response row for every POST/PUT/PATCH/DELETE, device-agent calls included — a
> heartbeat every 120 s per box, plus a playback flush, plus sync/action/remote confirms. On the test
> server **98.6%** of `audit_log` rows were that traffic, roughly **130 MB per always-on device** over
> the retention window, on the same 8.1 G volume as the Postgres data directory, MinIO and `/swap.img`.
> **Nothing reads the table**: there is no API over it, no UI, no report — so the cost bought nothing,
> and the rows it bought were the least informative ones in it (the same beat, forever). Meanwhile
> `RetentionCleanupService` swept all three tables with one 90-day constant and at most
> `MAX_BATCHES_PER_RUN = 100` × 1000 rows per table per night. Past roughly **26 devices**
> `playback_log` gained more rows per day than a night could delete, so the cap was not a safety
> valve but a ceiling: the table could never shrink again, and nothing said so.

**What changed in the repo**

- **`AuditFilter` stops auditing SUCCESSFUL device-agent calls** — `POST /api/devices/*/heartbeat`,
  `*/sync/confirm`, `*/actions/*/confirm`, `*/playback`, `*/remote/*/ack`. The check runs **before**
  the caching wrappers are created, so a skipped call costs nothing rather than buffering a body and
  throwing it away. Matched with `AntPathMatcher` on method + the **decoded** path within the
  application (like the AUTH-06 `BODYLESS` check), so `%68eartbeat` can't slip past and `*` spans
  exactly one segment — `/api/devices/1/heartbeats` and `/api/devices/1/playback/extra` are different
  endpoints and stay audited. The scope is deliberately narrow: everything else under
  `/api/devices/**` is an operator/admin write — `register`, `{id}/actions`, `playlist/control`,
  `POST|DELETE {id}/remote…`, `reregistration-window`, `PUT|DELETE {id}`, `{id}/location`,
  `{id}/volume`, `PUT /api/devices/volume` — and still writes its row. The credential-endpoint
  body omission is untouched.
- **A FAILED agent call still leaves a row.** The skip would otherwise have deleted the evidence
  along with the noise: a device presenting its token against *another* device's id is a
  `@PreAuthorize` **403**, raised during the controller invocation and therefore unwound back
  through this filter. Any status ≥ 400 on those five paths records principal, method, path,
  status and time, with both bodies as `[omitted: unaudited device-agent body]` — nothing was
  buffered, which is the entire point. A healthy fleet produces none of these. (An authentication
  **401** is emitted earlier, by the security filter chain, and has never reached this filter.)
- **`RetentionProperties` refuses to boot on a bad bound.** `@DurationUnit` makes a bare number mean
  days (minutes for the run budget), closing the `APP_RETENTION_PLAYBACK=90` → *90 milliseconds*
  footgun, which would have put the threshold after `now` and deleted every billing row on the first
  nightly run — with `PlaybackLogService` then rejecting every new report, so it could not come
  back. `@Validated` + `@DurationMin`/`@Positive` require ≥ 1 day per window, ≥ 1 minute of budget
  and positive counts, and the failure names the property at startup. `batch-size=0` used to throw
  inside `PageRequest.of`, get caught per table and no-op the whole job behind one WARN.
- **Client-driven playback rejections log at INFO, not WARN** (the v1.0.137 rule). A box returning
  from an outage flushes up to 500 queued entries, and a skewed clock or an expired window made every
  one of them a WARN — all forwarded to Telegram.
- **New `RetentionProperties` (`app.retention.*`)** — `audit` **P14D**, `playback` **P90D**,
  `event` **P90D**, `batch-size` 1000, `max-run-duration` **PT15M**, `max-batches-per-run` 5000,
  `guard-window` true, each with an `APP_RETENTION_*` env override. Typed, no Lombok, defaults in the
  class; the inline `@Value("${app.retention.guard-window:true}")` moved onto it. Tuning retention on
  a box is now a restart, not a rebuild.
- **Each table drains against its own threshold.** The three near-identical loops in
  `RetentionCleanupService` collapsed into one helper taking (label, threshold, batch callable). The
  `@Lazy self` REQUIRES_NEW-per-batch shape, the maintenance-window guard, `CleanupResult` and the
  summary log are unchanged.
- **The per-run cap stopped being the limit.** A run now has a wall-clock budget
  (`max-run-duration`, shared by all three tables) and the batch cap is only a high safety stop
  against a delete that never removes the rows it counted. Either stop logs at **INFO** naming the
  table and the rows deleted, so a backlog is visible — INFO, not WARN, because an unfinished table
  is routine and WARN reaches Telegram. The budget is checked *after* a batch, so every table makes
  progress even under a tiny budget.
- **`PlaybackLogService` reads the same property** for its "played_at older than the retention
  window" rejection instead of its own 90-day constant, and the day count in the message follows the
  configured value — the accept bound and the sweep cannot drift apart. The default is still 90 days,
  so the Android spec's contract is unchanged.

**Operating note**

- **`audit_log` stops growing with the fleet.** Adding devices no longer adds audit rows at all; the
  table's size now tracks operator activity, which is flat.
- **Existing device rows disappear within 14 days of deploying** — the first nightly run after the
  deploy starts deleting everything older than 14 days, and it will run for several nights on a box
  with a real backlog (each night takes `max-run-duration`, logs what it deleted at INFO, and
  continues the next night). Nothing needs to be run by hand. Postgres reuses the freed pages; a
  `VACUUM FULL audit_log` during a maintenance window is what actually returns the space to the
  filesystem.
- **Proof-of-play is unchanged at 90 days.** `playback_log` keeps its window, and the device-facing
  `POST /api/devices/{id}/playback` still accepts reports up to 90 days old — a box that was offline
  for weeks still gets its queued reports in. Shortening `APP_RETENTION_PLAYBACK` shortens **both**,
  which is why they read one property.
- **The trade-off being accepted:** there is no longer an HTTP-level record of a *successful*
  heartbeat or playback flush, and a failed one is recorded without its bodies. The device's trail
  is `device.last_seen_at`, `event`, `playback_log`, the action rows and the connection log — all
  of which are read by something. If a specific agent call ever needs auditing in full again, it is
  one line out of `DEVICE_AGENT_ENDPOINTS`.

**Tests:** `AuditFilterTest` — parameterized over all five agent endpoints (no entry recorded **and**
the response still reaches the client), a 403/404/422/500 on `POST /api/devices/2/heartbeat`
recording a **bodyless** entry while a 200 records nothing (response untouched either way), a
percent-encoded agent path skipped after decoding, ten
operator/admin device writes still audited (including `POST /api/devices/register`,
`POST /api/devices/1/remote`, `DELETE /api/devices/1/remote/abc` and `PUT /api/devices/volume`), and
the look-alike paths `/api/devices/1/heartbeats` and `/api/devices/1/playback/extra` still audited;
every AUTH-06 test kept. `RetentionCleanupServiceTest` binds a real `RetentionProperties` instead of
reflecting on constants: the per-table thresholds are captured **per repository** and asserted
against their own windows (14 vs 90 days), a drain runs past 150 batches where the old cap stopped at
100, the run budget stops a repository that always reports a full batch after exactly one batch per
table (with the batch cap set low so a broken budget check fails instead of hanging), the cap stop
does the same, both stops are asserted to log at INFO with the row count, and a failing table still
leaves the other two drained. The maintenance-window guard is now driven from a fixed instant
(a package-private `runCleanup(Instant)` seam) instead of whatever time the suite runs at, so the
skip asserts that **no** repository is touched. `RetentionPropertiesTest`
(`ApplicationContextRunner`) pins every default, binds `app.retention.*` and `APP_RETENTION_*`
(including the hyphenated `max-run-duration`), proves a bare `90` is 90 **days** and a bare `20`
budget is 20 **minutes**, and asserts that eight bad values — `PT0S`, a negative duration, a
sub-day window, `batch-size=0`/`-5`, `max-batches-per-run=0` — each **fail context startup with the
property named**, with the boundary values (`P1D`, `PT1M`, `1`) accepted. `PlaybackLogServiceTest`
sets the configured playback window to 30 days and asserts a 45-day-old play is rejected — with
"30-day" in the message — while a 20-day-old one is accepted.

### Replace overrides only its own window; assignment precedence (v1.0.142)

> **LOGIC-05 — "a one-week campaign kills the booking underneath it."** `ContentAssignmentService.supersede`
> retired a CONFIRMED predecessor unconditionally on Replace — `truncateEndTo(newStart)` when it was
> running, `softDelete()` otherwise, plus permanent `ContentAssignmentExclusion` rows for a partial
> device takeover. It never looked at `newAssignment.getEndTime()`. Booking a one-week campaign over a
> "forever" (year-2100 sentinel) assignment therefore destroyed the remainder: when the campaign's
> window closed, `resolveForDevice` returned null, the screens went blank and `/sync` told the devices
> to purge their files. The operator's only recovery was to re-create the booking by hand.

**What changed in the repo**

- **The decision is precedence, not row surgery.** `V48__content_assignment_confirmed_at.sql` adds a
  nullable `confirmed_at` (backfilled to `created_at` for existing CONFIRMED rows) and recreates
  `device_status_view` so every copy of the resolution rule orders by
  `priority DESC, COALESCE(confirmed_at, created_at) DESC, id DESC`. Splitting the predecessor into
  two rows was rejected: it would fork `playback_log.assignment_id` (proof-of-play) and the
  `(assignment_id, version_number)` `playback_sync_schedule` anchors.
- **`ContentAssignment.confirmedAt` + `PRECEDENCE`.** `confirm()` stamps the instant; the comparator
  lives on the entity so the two service call sites cannot drift apart. Ordering deliberately does
  **not** use `updatedAt` — `bumpVersion()` and `truncateEndTo()` move it, so an unrelated edit would
  steal precedence. The comparator is null-safe (`confirmedAt` falls back to `createdAt`, a null id
  sorts last) and **total**, so Java and SQL cannot silently disagree. The same order is now in all
  four copies: `PRECEDENCE`, `findActiveAtTime`'s JPQL `ORDER BY`, `resolveForDevice`,
  `previewForTarget` (which had its own `max(comparingInt(priority))`), and the view's three
  embedded predicates — see the resolution-order table under *Content Assignments (V7)*.
- **`supersede` is gated on the window**, by two independent tests:
  `reachesPredecessorEnd` = `newEnd >= predEnd` (`>=`, not `>`, so forever-over-forever still retires
  the old row: both carry the same sentinel) and `coversFromNow` = `newStart <= max(predStart, now)`.
  - **Retiring** (no devices remain) needs only the end test: `coversFromNow` → exactly v1.0.135
    (soft-delete, or truncate a running predecessor at the cutover); otherwise **truncate** and keep
    the head. The old `runningNow` guard soft-deleted a *future* predecessor whose head the new
    window never covered — a scheduled booking deleted before it ever played.
  - **Narrowing** (some devices stay) needs **both**. An exclusion row is permanent and takes effect
    the instant it is written, so a takeover that only opens next week would blank the handed-over
    devices from today until then — the same failure in miniature. When the new window opens later,
    nothing is written: precedence hands those devices over at the start edge, and the unselected
    devices keep the predecessor through the new assignment's own exclusions.
  - When the new window **ends first**, the predecessor is left **completely untouched**.
- **Cancelling an assignment releases the narrowings it caused.** `softDelete` deletes the
  exclusions stamped with `partialSupersedeReason(id)` in the same transaction. An exclusion has no
  FK to the assignment that *caused* it (only to the one it narrows), so that reason string is the
  join key and has a single formatter. Without this, "replace on some devices, then cancel" left
  those devices excluded from a still-CONFIRMED, still-running predecessor — dark, with no visible
  cause. Operator-written exclusions and other assignments' narrowings are untouched.
- **A future-dated confirm no longer detaches sync-group members.** `detachReassignedSyncGroupMembers`
  compares against a snapshot taken at `now`, so for a campaign starting next week it was answering a
  question about a change that had not happened — and the detach is irreversible. It is now skipped
  when `startTime > now`; the flip happens at the start edge, and an incoherent group is reported by
  `SyncGroupPlaybackService.resolveCoherence` (recoverable) instead of silently unwired.
- **The cancel push is unchanged and still fires** for the handed-over devices — they must switch
  now — but the event no longer means "the assignment was soft-deleted". Fixed the javadoc that said
  so on `AssignmentCancelledEvent` and `AssignmentCancelledSyncPushListener`, and the stale
  "rejected or superseded" wording on `resolveConfirmOverlap` / `detachReassignedSyncGroupMembers`.
- **Frontend:** the Replace confirm dialog now prints `<playlist> resumes on <date>` for every
  conflict that outlasts the new assignment, so "Move & assign" no longer reads as a permanent
  deletion. A campaign with **no end date** outlasts everything, so no line is shown then (the
  existing year-2100 `INDEFINITE_END_TIME` guard), and an unparseable `endTime` prints nothing rather
  than a guessed date. Dates go through the shared Tashkent formatter; strings added in en/ru/uz.

**Operating note**

- A replaced assignment now **stays CONFIRMED**, so `DELETE /api/playlists/{id}`,
  `/api/device-groups/{id}` and `/api/facilities/{id}` still answer **409** while it exists, and the
  assignment list shows **two overlapping rows** for the same target. Both are intended: the booking
  is genuinely still live, it is just outranked for the campaign's window. Cancelling the campaign
  brings the booking back with no re-assignment.
- At the **end** edge the flip is **not** pushed: devices pick the booking back up on their next
  heartbeat, so expect up to one beat (**≤ 2 min**) of the campaign still playing, and a
  re-download, because the files were purged at the **start** edge when the campaign took over. An
  end-edge push (a scheduled sweep that notifies devices whose winning assignment changes at a
  window boundary) is the obvious follow-up and is deliberately not in this change.

**Tests:** `ContentAssignmentServiceTest` — shorter window leaves the predecessor untouched with no
exclusions (whole-target and partial-device), a partial takeover whose window opens later writes no
exclusion **yet** (and one that opens now still narrows), the handover push still fires,
forever-over-forever still retires, a future predecessor's head is truncated not deleted,
out-of-order ids resolve by recency, cancelling the campaign mid-window brings the predecessor back
(and releases its narrowings, keyed by the exact reason `supersede` wrote), a future-dated confirm
keeps sync-group membership, and `PRECEDENCE` is a total order. `ContentAssignmentPartialSupersedeTest`
pins `deleteByReason` against the real JPQL: it releases only the narrowing, and the handed-over
device resolves the predecessor again once the superseder is cancelled. `ContentAssignmentWindowOverrideTest` (new, real H2 + Flyway) asserts `findActiveAtTime` + the
resolver at three instants — before / during / after the campaign — and that `device_status_view`
picks the same winner, including when only the tie-break can decide. `AssignmentSchemaTest` pins the
column, its nullability and the backfill; `DeviceStatusViewActivePlaylistFilterTest` gains a device
whose ids run against its confirm order. `FlywayMigrationTest` 47 → 48; `U48` restores the V41 view
and drops the column.

**Also in this version:** `PlaybackScheduleActivationMonitor` no longer WARNs on a `0/N` cut-over
readiness. Zero-ready is not a straggler situation — it means no device holds that version, which is
the ordinary look of an anchor whose assignment is outranked for the current window, and overlapping
CONFIRMED rows are now normal. WARN reaches Telegram and the poll runs every minute, so that case
logs at INFO; a genuine partial rollout (`0 < ready < total`) still WARNs.

### Dayparting schedules hidden; per-minute evaluation job off (v1.0.141)

> **LOGIC-04 — "schedules have no effect on playback."** Operators could create DAILY/WEEKLY/MONTHLY
> windows ("09:00–12:00 daily"), but `ContentAssignmentService.resolveForDevice` — which drives
> heartbeat, `/sync`, status and the playlist view — never reads schedules, so the ad played around the
> clock for the whole assignment. Meanwhile the Quartz job loaded the whole `schedule` table every
> minute (soft-deleted rows included), expanded every repeat, and logged INFO 1,440 times a day.

**What changed (decision: hide it until real dayparting exists; keep the data)**

- **Frontend:** the content page no longer offers the *Schedules* action (`ContentPage` stops passing
  `onSchedules`; `ContentSchedulesDrawer`, `useContentSchedules` and the API client are kept, unused).
- **Backend:** `scheduleEvaluationJobDetail`/`scheduleEvaluationTrigger` (`QuartzConfig`) and
  `MissedRunCatchUp` only exist with `app.schedule.evaluation-enabled=true`
  (`APP_SCHEDULE_EVALUATION_ENABLED`, default **false**). The schedule table and REST API are unchanged.

**Tests:** `ScheduleEvaluationToggleTest` (no job/trigger/catch-up by default; all three when enabled);
frontend `ContentPage.test` (no Schedules action on a READY card).

### Incident pipeline: offline auto-resolve, monitor transactions, live alerts (v1.0.140)

> **LOGIC-01/02/03 — "offline incidents never close, SYNC_TIMEOUT fires every minute, no live
> critical alerts."** Root cause, three bugs:
> **(1)** `DeviceHeartbeatService` auto-resolved `DEVICE_OFFLINE` only when the *stored* status went
> OFFLINE→other, but nothing ever stores OFFLINE: `DeviceHealthMonitor` is derive-only, and the beat
> refreshes `lastHeartbeatAt` before it derives the status. Every offline incident stayed open
> forever, the device's next outage raised no new one (the monitor skips a device that already has
> one), and OFFLINE→ONLINE status events/broadcasts never fired.
> **(2)/(3)** Both monitors called their `@Transactional(propagation = REQUIRES_NEW)` `escalate()` on
> `this`, which bypasses the Spring proxy, so no transaction was opened. In `SyncTimeoutMonitor`,
> `clearSyncPending()` ran on a detached entity and was never written, so a stuck device
> re-escalated every minute. In `DeviceHealthMonitor`, the critical-incident broadcast and the
> dashboard offline broadcast navigated the detached device's LAZY region and threw
> `LazyInitializationException`. The exception was swallowed: the incident was saved, but no
> operator was alerted live. The Mockito tests passed throughout.

**What changed in the repo**

- **Heartbeat recovery is judged from the previous beat's age** (`DeviceHeartbeatService`), read
  *before* `recordHeartbeat()`. Two thresholds apply:
  - The `DEVICE_OFFLINE` resolve fires from `DeviceHealthMonitor.HEARTBEAT_THRESHOLD`, 15 min, the
    point where the monitor opens the incident.
  - The OFFLINE→ONLINE `DEVICE_STATUS_CHANGED` event and dashboard broadcast fire only past
    `DeviceStatusEvaluator.isOffline`, 15 min + 60 s grace, the same cut-off as `device_status_view`.
    That way no transition is announced that no screen ever showed.
- **The resolve runs after the beat has committed.** `processHeartbeat` only reports what it
  recovered from, in `HeartbeatResult.resolveIncidentTypes` (`DEVICE_OFFLINE` and/or
  `CONTENT_VERSION_MISMATCH`; server-internal, not on the wire). `DeviceController.heartbeat` then
  calls the non-transactional `DeviceHeartbeatService.resolveRecoveredIncidents`, which runs each
  `autoResolveOnRecovery` in its own transaction, best-effort. A resolve inside the beat would either
  join its transaction (a failing resolve marks it rollback-only and the beat is lost) or nest a
  second one. Nesting holds two pooled connections per recovery beat, and after a regional outage
  every device has an open incident, so 20 or more simultaneous recovery beats would deadlock the
  prod pool.
- **Duplicate open incidents (LOGIC-16) no longer throw.** Only the service layer enforces "one open
  incident per pair", so duplicates can exist. What changed:
  - A resolve closes every open incident for the pair (`findAllOpenByDeviceAndEventType`).
  - `IncidentService.processEvent` adds the occurrence to the oldest one (lowest id).
  - `DeviceHealthMonitor.escalate` uses `existsOpenByDeviceAndEventType`.
  - The single-result `findOpenByDeviceAndEventType`, which threw on duplicates, is removed.
- **Recovery sweep** in `DeviceHealthMonitor.runHealthCheck`: after escalating, a scalar query
  (`findDeviceIdsWithOpenIncidentAndHeartbeatAfter`) finds devices that have an open
  `DEVICE_OFFLINE` incident but a last beat newer than the *same* threshold instant, and resolves
  each one in its own transaction. Resolutions also evict the dashboard summary cache.
- **Both monitors call `escalate` through a `@Lazy self` proxy**, so `REQUIRES_NEW` applies.
  `DeviceHealthMonitor.escalate` now returns `EscalationOutcome(escalated, projectId)`; the project
  is read inside that transaction and is what the dashboard offline broadcast routes on.
- **`findBySyncPendingSinceLessThanAndDeletedAtIsNull`** replaces `findBySyncPendingSinceLessThan`,
  so soft-deleted devices are no longer scanned. The `isDeleted()` guard in `escalate` stays, for a
  device deleted mid-scan.
- **`/sync` clears a stale pending marker when there is no work** (`DeviceSyncService.computeSyncPlan`),
  including when no assignment is active. A device that already holds the expected content (its
  confirm was lost), or whose assignment lapsed, is no longer escalated as `SYNC_TIMEOUT`. No
  migration.

**Operating notes**

- **Incidents already stuck open before the deploy close by themselves within 5 minutes.** The first
  health-check pass (Quartz, every 5 minutes) resolves the open `DEVICE_OFFLINE` incident of every
  device that is beating again, with `resolved_by = 'system'`. Expect a one-off burst of
  `incidentUpdated` frames on the dashboard and a `recovered N` count in the health-check log line.
- **OFFLINE→ONLINE (or →NO_CONTENT) status events now fire** on every recovery after more than
  16 minutes of silence (15 min + 60 s grace), **including a device's first beat** after
  registration (`lastHeartbeatAt` is null). Expect more `DEVICE_STATUS_CHANGED` rows than before
  (MEDIUM for →ONLINE).
- **Live CRITICAL alerts for offline devices work again** (`/ws/admin/incidents` and the dashboard
  feed). The `Critical incident broadcast failed … Could not initialize proxy` WARNs, which were
  forwarded to Telegram, stop.
- **SYNC_TIMEOUT escalates once per stuck sync**, not every minute. Existing incidents with a huge
  `occurrence_count` stop growing; operators still close them by hand.
- A beat never holds more than one pooled connection at a time. Resolves take one only after the
  beat has released its own. If a resolve fails, a WARN is logged, the beat has already succeeded,
  and the sweep closes the incident on its next pass (within 5 minutes).
- An auto-resolve and a concurrent manual resolve are last-writer-wins on `resolved_by`: there is no
  `@Version` yet (LOGIC-07).

**Tests:** new `DeviceMonitorTransactionIntegrationTest` runs on real H2 with the real transaction
manager. Its heartbeat cases call both steps, exactly as the controller does. It checks that:
- a scan commits `sync_pending_since = NULL`, and a second scan adds no event;
- a health check opens the incident and broadcasts it with the device's project;
- a heartbeat after an outage resolves the incident the monitor opened, and a spy confirms that the
  resolve starts only once the beat is visible to an independent transaction;
- the sweep resolves a fresh device's open incident;
- a heartbeat resolves two duplicate incidents, and its own `last_heartbeat_at` commits;
- a resolve that throws inside its transaction leaves the beat committed.

Each case was confirmed red against the defect it guards: `this.escalate` in either monitor, the
stored-status gate, or moving the resolve back inside `processHeartbeat` (which fails with
`UnexpectedRollbackException`).

Unit tests:
- `DeviceHeartbeatServiceTest` uses a device mock that behaves like the entity (its heartbeat is
  stale until `recordHeartbeat()` runs) and never stores OFFLINE. It covers:
  - `processHeartbeat` only reports types and never touches `IncidentService`;
  - a stale beat whose stored status is unchanged still reports `DEVICE_OFFLINE`;
  - 14 min: nothing; 15.5 min: resolve but no event; 16.5 min: resolve and event;
  - a first beat;
  - `resolveRecoveredIncidents` resolves each type and swallows a failure.
- `DeviceControllerTest`: the controller resolves with the beat's own result, after it returns, and
  never after a 404.
- `DeviceStatusEvaluatorTest`: `isOffline` agrees with `evaluate` at the grace boundary.
- `DeviceHealthMonitorTest`: the sweep resolves and invalidates the cache, uses the escalation's
  threshold instant, a no-op doesn't invalidate, and failures never fail the check. The broadcast
  project comes from `escalate`, not the detached device.
- `IncidentServiceTest`: duplicates resolved, and `processEvent` picks the oldest duplicate.
- `DeviceSyncServiceTest`: a stale marker is cleared when there is no work or no assignment, left
  alone when none is set, and kept while there is work.
- `SyncTimeoutMonitorTest` and `IncidentServiceDashboardBroadcastTest` were updated.

### Credentials no longer written to audit_log (v1.0.139)

> **AUTH-06 — "plaintext passwords and API keys in `audit_log`."** Root cause: `AuditFilter` stored
> every mutating request/response body, cut to 10k **before** masking, and `SensitiveFieldMasker`
> redacted only an exact list of key names with a regex. `currentPassword`/`newPassword`/
> `confirmPassword` (password change and reset) and `rawKey` (a freshly minted API key) were not on the
> list; a secret straddling the cut, or containing an escaped quote, slipped past the regex; and a
> too-short password came straight back in the 400's `rejectedValue`.

**What changed in the repo**

- **`SensitiveFieldMasker.isSensitiveKey`** (common) replaces the exact list with a name pattern
  (contains `password|secret|token|ticket|authorization`, ends with `key`, or is
  `pin|ssn|cvv|credit_card`). `keyPrefix`/`apiKeyId` stay readable.
- **`AuditBodySanitizer`** (api): parses the body with Jackson, masks every sensitive key at any depth
  and of any type, serializes, and only then truncates (never splitting a surrogate pair). Anything
  that isn't JSON is stored as a marker, not raw.
- **`AuditFilter`**: the four credential endpoints (POST `/api/me/password`, `/api/auth/reset-password`,
  `/api/auth/refresh`, `/api/admin/api-keys`, matched on the decoded path) keep no bodies; login and
  device registration keep theirs, masked, as the audit trail. Bodies are parsed as UTF-8 (the old
  10,000-*byte* cut could split a multi-byte character); request caching is capped at 256 KiB; and the
  response is copied to the client in its own `finally`, so a failing audit can never blank it.
- **`GlobalExceptionHandler`**: `rejectedValue` is dropped when any segment of the field path is a credential
  name, and sensitive query parameters are masked in the path sent to the Telegram 500 alert (a failing
  `GET /api/auth/reset-password?token=…` used to post the live token).
- **`V47__scrub_credentials_from_audit_log.sql`** clears the bodies of existing rows for those four
  endpoints, the registration responses (device tokens before v1.0.137), and 400 responses of
  `/api/users` and `/api/auth/login` (echoed passwords), and the request bodies of all historical
  login/user-create rows — the old regex left the tail of a password with an escaped quote (with no trace
  of the damage) and never masked a numeric one. `U47` is a documented no-op.

**After upgrading:** V47 scrubs the live table only — existing database dumps still hold these
secrets. **Rotate every API key created before v1.0.139** (its plaintext was in `audit_log`), and
consider re-registering devices whose tokens predate v1.0.137. Treat old dumps as containing
credentials.

**Tests:** `SensitiveFieldMaskerTest` (which keys count), `AuditBodySanitizerTest` (password-change
fields, `rawKey`, nested/array/numeric values, escaped quotes, a secret straddling the limit, Cyrillic,
surrogate pairs, non-JSON), `AuditFilterTest` (the four endpoints keep method/path/status only, an
encoded path, login masked, UTF-8, unread body, oversized body, the response survives an audit
`Error`), `GlobalExceptionHandlerTest` (no rejected password echo), `AuditLogCredentialScrubSchemaTest`
(V46 → V47). `FlywayMigrationTest` count → 47.

### Per-IP rate limits no longer trust a client-supplied X-Forwarded-For (v1.0.138)

> **AUTH-04 — "every per-IP limit is bypassable."** Root cause: four copies of
> `xff != null ? xff.split(",")[0] : getRemoteAddr()` keyed the login, refresh, forgot/reset-password,
> device register/re-register and API-key-failure limits (and `device.last_known_ip`) on the **first**
> `X-Forwarded-For` entry — whatever the client wrote — without checking who set the header.

**What changed in the repo**

- **`server.forward-headers-strategy: native`** (`application.yml`): Spring Boot installs Tomcat's
  `RemoteIpValve`. `X-Forwarded-For` is honoured **only when the direct peer is a trusted proxy**
  (`server.tomcat.remoteip.internal-proxies`; the default covers loopback, RFC 1918, link-local,
  100.64/10 and IPv6 loopback/link-local/ULA — which includes Caddy → Docker's bridge gateway), and it is read **right to left**, so an entry a client prepends is ignored.
  A client that reaches the app without the proxy gets its socket address, whatever header it sends.
- **One helper, `api/.../security/ClientIp.of(request)`** (= `getRemoteAddr()`), replaces the four
  private copies in `AuthController`, `PasswordResetController`, `DeviceController` and
  `ApiKeyAuthFilter`.
- **Heartbeat**: a resolved IP longer than `last_known_ip`'s 45 chars is not stored (logged at INFO)
  instead of failing the beat — the valve copies a trusted proxy's header without validating it.

**Side effects to know about** (the valve also honours `X-Forwarded-Proto`/`-Host` from the proxy):
- Behind TLS the request is now seen as HTTPS, so Spring Security's default
  `Strict-Transport-Security: max-age=31536000 ; includeSubDomains` is sent on API responses.
- Swagger "Try it out" on the API host now counts as same-origin (a fix); the SPA stays cross-origin.

**Operating it**
- The proxy in front must set `X-Forwarded-For` itself. Caddy's default does (it replaces the header
  from untrusted clients); if you configure `trusted_proxies`, keep it to real upstream proxies only.
- To narrow the trusted set, set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to a regex matching only
  your compose network's gateway, in the app service's `environment:` (a compose override, single-quoted
  YAML — a `.env` entry alone doesn't reach the container; double quotes would eat the backslashes).
  **Never set it to an empty value** — that trusts no proxy, so every client shares the proxy's IP and one rate bucket
  (which is why compose deliberately does not pass it through).
- After deploying, check a device's `last_known_ip` on its diagnostics page: it should be a public
  address, not `172.x`.

**Tests:** per-call-site tests in `AuthControllerTest`, `PasswordResetControllerTest`,
`DeviceControllerTest` and `ApiKeyAuthFilterTest` (a client `X-Forwarded-For` doesn't change the key);
real-Tomcat `ForwardedClientIpIntegrationTest` (single hop honoured, a prepended fake skipped, trailing
trusted hops peeled off) and `UntrustedPeerClientIpIntegrationTest` (spoofed header from an untrusted
peer ignored), both asserting the IP `AuthController.refresh` hands to the limiter;
`DeviceHeartbeatServiceTest` (oversized IP not stored, 45 chars stored).

### Device takeover by re-registration closed; admin re-registration window (v1.0.137)

> **AUTH-02 — "anyone who knows a serial can steal the device."** Root cause: `POST
> /api/devices/register` is public (first contact has no token) and keyed only on the client-sent
> `serialNumber`; for an already-registered serial it rotated the live device's token and returned
> the new one. Serials (ANDROID_ID, hardware serial, UUID) are not secrets.

**What changed in the repo**

- **Re-registration needs an admin.** `DeviceRegistrationService.register` refuses an
  already-registered serial with `DeviceAlreadyRegisteredException` → **409**, unless an ADMIN opened
  a window with **`POST /api/devices/{id}/reregistration-window`** (ADMIN only; returns
  `{ allowedUntil }`; `APP_DEVICE_REREGISTRATION_WINDOW`, default `PT1H`). The next registration claims
  the window with a conditional `UPDATE … WHERE reregistration_allowed_until > now`
  (`DeviceRepository.claimReregistrationWindow`), so of two concurrent callers exactly one wins; the
  token is rotated as before. New serials are unchanged (they land in *Unassigned* with no content).
- **`V46__device_reregistration_window.sql`**: nullable `device.reregistration_allowed_until` (+ `U46`).
  `DeviceDetail` exposes it as `reregistrationAllowedUntil`; the admin UI shows it.
- **`@DynamicUpdate` on `Device`**: only changed columns are written, so a heartbeat holding a stale
  snapshot can no longer erase a window an admin opened meanwhile (or resurrect a used one). This also
  narrows the lost-update problem (LOGIC-07) for disjoint columns.
- **Separate rate-limit budget for re-registration attempts**: an attempt on an already-registered
  serial counts against `rate:devrereg:{ip}` (`app.device.reregister-rate-limit-per-hour`, default
  60), not the 10/hour new-device budget. A wiped box retrying every few minutes can't starve new
  boxes behind the same venue NAT, and polling a known serial to snatch an admin's window stays
  metered. Refusals log at INFO (like 429s), so a stuck box can't flood the Telegram alert budget.
- **A heartbeat closes an open window**: an authenticated beat proves the box still holds its token,
  so a window opened on a healthy device by mistake closes within one heartbeat interval.
- **`app.device.reregistration-window` must be ≥ 1 minute** or startup fails (a bare `60` would bind
  as 60 ms). Compose passes `APP_DEVICE_REREGISTRATION_WINDOW` through.
- **Input validation**: `serialNumber` ≤ 100 and `^[A-Za-z0-9][A-Za-z0-9._:-]*$`, `deviceName` ≤ 200 →
  **400** instead of 500 (spec gap G-5). The alphanumeric first character also keeps a leading
  `= + - @` out of spreadsheet exports.
- **`SensitiveFieldMasker` now masks `deviceToken`** — the registration response is persisted by
  `AuditFilter` and a device token never expires.
- **Android contract** (`ANDROID_DEVICE_FLOW_SPEC.md`, R21, §1.6/§2/§4/§13/§14/§16): on 409 from
  `/register`, keep playing cached content and retry every ~5 min with jitter.

**Operating it**: a TV box that was wiped/reinstalled shows up as stuck (its `/register` gets 409). On
its device page an ADMIN clicks *Allow re-registration*; within the hour the box's next retry gets a
new token. Don't open a window for a device that is online and healthy — whoever registers that
serial first inside the window gets the token (the UI warns, and the device's next heartbeat closes
such a window anyway).

**Upgrade note:** a device whose stored serial doesn't match the new pattern can never re-register
(it gets 400). Check with `SELECT serial_number FROM device WHERE serial_number !~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$'`.

**Tests:** `DeviceRegistrationServiceTest` (refused + token unchanged, claimed + rotated, claim uses
the current time, window opened, 404, `isRegistered`, window < 1 min fails fast),
`DeviceControllerTest` (409 on the re-registration budget, new serial on the new-device budget, 400
validation incl. a trailing newline and real-world serial formats, admin 200 / operator 403 /
device-token 403 / anonymous 401 / 404), `DeviceHeartbeatServiceTest` (a beat closes the window),
`DeviceReregistrationWindowRepositoryTest` on H2 (claimed exactly once, expired/none not claimable,
rotated token persisted after a claim, a stale concurrent edit doesn't wipe the window),
`DeviceRegistrationRateLimiterTest`, `SensitiveFieldMaskerTest`. `FlywayMigrationTest` count → 46.

### Default logins removed outside dev; bootstrap admin from env (v1.0.136)

> **AUTH-01 — "every environment starts with `admin`/`password`."** Root cause: V12 seeds six users
> with the password `password` and V33 rewrites it to a fixed, publicly committed BCrypt hash. Both
> run on **every** database, so a fresh production had a working ADMIN login out of the box. V12/V33
> are already applied everywhere (`validate-on-migrate: true`), so the fix is additive.

**What changed in the repo**

- **`V45__deactivate_default_seed_logins.sql`** deactivates every account still on the V33 hash.
  Accounts whose password was changed carry a different hash and are untouched. Rollback
  `U45` (test/dev only — it re-opens AUTH-01).
- **`DefaultLoginPolicy`** (`service/.../seed`, a `SmartInitializingSingleton`: runs after Flyway,
  before the port opens):
  - seed **on** → re-activates the five accounts V12 created active if they are inactive and still
    on the default hash (a deleted or re-passworded account is left alone), and logs a WARN;
  - seed **off** → **refuses to start** (`IllegalConfigurationException`) while any active account is
    on the default hash, naming the accounts. Known gap: only the exact V33 hash is recognised.
- **`BootstrapAdminProvisioner`** (`ApplicationRunner`): when **no active ADMIN** exists and
  `APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD` are set, creates that ADMIN — or, if
  the username exists, re-activates it, promotes it to ADMIN and sets the password — and **revokes
  every refresh-token family of that username**, as a password change does, so a session opened
  with the old password can't survive (or come back as ADMIN). Both variables or neither; password
  ≥ 12 characters with no leading/trailing whitespace; startup fails otherwise (the message never
  contains the password). With no admin and no variables it logs a WARN; with an admin and the
  variables still set it logs a WARN to remove them.
- **`docker-compose.yml`**: `APP_SEED_ENABLED` now defaults to **false** (opt in via `.env` for local
  use) and passes the two bootstrap variables through. `.env.example` documents all three.

**Upgrading an environment with seed data off**

V45 deactivates every account that still uses `password` — possibly **all** admins. Before
deploying, either change those passwords, or set `APP_BOOTSTRAP_ADMIN_USERNAME` (the existing
admin's exact **username** — matched on username only) and `APP_BOOTSTRAP_ADMIN_PASSWORD`; on first
start that account is re-activated with the new password. Log in, then remove both variables.

- A compose deployment that never set `APP_SEED_ENABLED` used to get **true** from the compose
  default; it is now **false**, so the paragraph above applies to it.
- An explicit `APP_SEED_ENABLED=true` in a deployment's `.env` keeps every default login working —
  remove it anywhere that isn't local development.

**Tests:** `DefaultSeedLoginSchemaTest` (V44 → V45 upgrade: seed accounts deactivated, changed-password
and ordinary accounts untouched), `DefaultLoginPolicyTest` (guard, dev re-activation, the constant
pinned to the V33/V45 literals), `BootstrapAdminProvisionerTest` (fail-fast config incl. the 12-char
boundary and whitespace, no-op with an active admin, create, re-activate + promote, sessions revoked).
`FlywayMigrationTest` count → 45.

### Remote view/control — session control plane (v1.0.131)

Lets an operator open a **live view of an Android TV box** (and, where the box supports it, drive its
input) from the console. **DB migration `V43__remote_session.sql`** (new `remote_session` table plus
six nullable `device` capability columns). **Ships dark** behind `app.remote.enabled=false`: with the
flag off, `POST /api/devices/{id}/remote` returns **503** and the heartbeat never emits
`desiredRemoteSession`, so the device wire is byte-identical to v1.0.130 apart from one added `null`
key. Device contract: `ANDROID_DEVICE_FLOW_SPEC.md` §4, §6.1, §7. Canonical wire contract:
`../REMOTE_CONTROL_CONTRACT.md`.

> **THE BACKEND CARRIES NO MEDIA.** Not one video byte touches this service. The device and the
> operator browser meet on a **separate relay VPS** (never `subzero` — 1 vCPU / 594 MB RAM / 294 MB
> disk free); this service only mints the rendezvous and hands each side a signed ticket. That is
> deliberate: it dodges the 8 KB Tomcat text cap and `TextWebSocketHandler`'s binary rejection
> (close 1003), keeps the app server out of the media path entirely, and gives the Android team a
> clean seam. **There is no binary-WebSocket code, no `handleBinaryMessage`, and no Tomcat buffer
> change in this release, and none should ever be added for this feature.** The relay itself is a
> separate deliverable (`../REMOTE_CONTROL_CONTRACT.md` §6) and is **not** built here.

```
  Operator browser                  Backend (Spring)                Device (Android TV box)
        │ 1. POST /api/devices/{id}/remote │                                 │
        ├─────────────────────────────────►│ 2. mint session + 2 tickets     │
        │ 3. 201 {sessionId, relayUrl,     ├────────────────────────────────►│ 4. su -c scrcpy-server
        │        viewerTicket}             │    REMOTE_SESSION_START (WS;    │
        │◄─────────────────────────────────┤    heartbeat is the fallback)   │ 5. POST …/ack READY
        │                                  │◄────────────────────────────────┤
        │ 6. wss://relay/viewer?ticket=…   │        ┌─────────┐              │ 6. wss://relay/agent?ticket=…
        ├─────────────────────────────────────────►│  RELAY  │◄──────────────┤
        │◄══════════ H.264 video ══════════════════╡  (VPS)  ╞══════════════►│
```

**Session lifecycle** — `PENDING → ACTIVE → ENDED`, with `FAILED` and `EXPIRED` as the other
terminals. Illegal transitions are rejected **in the `RemoteSession` entity**, not in the service, so
no future caller can write a state-machine violation to the database.

- **`POST /api/devices/{id}/remote`** — start. **201** with `sessionId`, `relayUrl` (the **viewer**
  URL), `viewerTicket`, `expiresAt`, `viewOnly`, `deliveredVia`, and the device's last reported
  `capability`. `403` for VIEWER/ADVERTISER · `404` unknown, soft-deleted, or out of operator scope ·
  `409` a `PENDING`/`ACTIVE` session already exists · `422` the device reported
  `capability.supported == false` · `503` the feature is disabled.
- **`DELETE /api/devices/{id}/remote/{sessionId}`** — stop. **204**, and **idempotent**: stopping an
  already-terminal session is still a 204, so a double-click never surfaces an error. Keeps working
  even when the feature flag is off, so disabling remote control cannot strand a streaming box.
- **`GET /api/devices/{id}/remote`** — the live session, **without** `viewerTicket`. Tickets are
  single-issue; a reconnecting viewer must `POST` again. `404` when there is none.
- **`POST /api/devices/{id}/remote/{sessionId}/ack`** — the **device** surface (`X-Device-Token`,
  `hasRole('DEVICE') and #id == authentication.principal`). `READY` → `ACTIVE` + `started_at` +
  dimensions; `FAILED` → `FAILED` + error; `ENDED` → `ENDED` + reason. REST rather than an inbound
  WebSocket frame because the device sends **no** outbound WS frames today
  (`ANDROID_DEVICE_FLOW_SPEC` §10.5) and adding an inbound path to `DeviceWebSocketHandler` is a far
  bigger change than one endpoint.
- **`GET /api/devices/{id}/connection`** → `{deviceId, connected}`, `ADMIN`/`OPERATOR`/`VIEWER`.
  Backed by the live socket map, **not** `device_status_view` — that view lags up to **16 minutes**
  (`OFFLINE_THRESHOLD` 15 min plus a hardcoded `INTERVAL '16' MINUTE` in V34, recreated in V41) and
  is useless behind a Connect button.

**Two tickets per session, one per role.** `ticket = base64url(payload) + "." +
base64url(HMAC_SHA256(secret, payload))` over `{"sid","role","did","exp"}`. The relay verifies
offline — no callback — so it keeps working while the backend redeploys. A leaked *viewer* ticket
must never be usable as the *agent*, so `RemoteSessionTicketService.verify` pins the expected role
and rejects a mismatch exactly as hard as a bad signature; comparison is constant-time
(`MessageDigest.isEqual`). `REMOTE_RELAY_SIGNING_SECRET` is **≥32 bytes, env-only, never logged, never
in a DTO**, and the app **fails fast at startup** if it is missing or short while the feature is on.
Relay tickets were also added to `SensitiveFieldMasker` — `AuditFilter` persists whole response
bodies and its key match is exact, so `viewerTicket` was *not* covered by the existing `token` entry.

**Session keys are random, never derived.** `"rs_" + HexFormat.formatHex(SecureRandom, 16 bytes)`.
Enumerable ids were the fatal flaw in the original repeater design.

**One live session per device**, enforced in the service layer — *not* with a partial unique index,
because **H2 (the test database) does not support them** and the DDL has to be valid on both H2 and
PostgreSQL. This mirrors `remote_action`'s one-PENDING-per-type rule exactly.

**Heartbeat: capability up, desired state down.** The request gains an optional `remote` block
(`supported`, `input`, `transport`, `maxWidth`, `maxHeight`) and the response a nullable
`desiredRemoteSession`. Both are **additive** — records deserialize missing fields as `null`, and
existing clients ignore extra keys. This is the same desired-state convergence loop as
`desiredVolume`, which is why the WS push is an *optimisation* rather than a dependency: a device
that never opens a socket still gets remote control, just with up to one beat of latency.
**A malformed or absent `remote` block never fails the beat** — unknown enum tokens and out-of-range
dimensions are dropped with a WARN and the beat carries on, exactly the rule that already governs
`volume`.

**`expiresAt` is enforced by the device, on its own clock.** `RemoteSessionExpirationJob`
(`@Scheduled(fixedDelayString = "PT1M")`) flips stale `PENDING`/`ACTIVE` rows to `EXPIRED`, but it is
a **janitor, not the enforcement mechanism**: if it stops running, rows linger in a table — no box
keeps streaming. A dead backend can never leave a device broadcasting.

**Why not reuse `remote_action`:** that table's 5-minute `DEFAULT_TIMEOUT`, one-PENDING-per-type
rule, once-and-terminal confirm and `RemoteActionExpirationJob` would all fight a long-lived session.
`remote_action` *initiates* things; `remote_session` *holds* one.

**Push types come from the enum, never a literal.** `PushMessageType.REMOTE_SESSION_START` /
`REMOTE_SESSION_STOP` are formatted via `.name()`, and `PushMessageTypeWireTest` asserts the emitted
`type` string **equals the enum constant name** — a guard against repeating the `SYNC_CONTENT` drift
described below. `grep -rn '"REMOTE_SESSION' --include=*.java` returns nothing by design.

**Configuration** (all env-overridable; see `.env.example` and `docker-compose.yml`):

```
app.remote.enabled=false                       # feature flag; ship dark          APP_REMOTE_ENABLED
app.remote.session-ttl=PT30M                   # hard ceiling                     APP_REMOTE_SESSION_TTL
app.remote.max-width=1280                      # encoder hint                     APP_REMOTE_MAX_WIDTH
app.remote.max-fps=15                          # encoder hint                     APP_REMOTE_MAX_FPS
app.remote.bit-rate=2000000                    # encoder hint                     APP_REMOTE_BIT_RATE
app.remote.relay.agent-url=wss://relay.example.uz/agent    APP_REMOTE_RELAY_AGENT_URL
app.remote.relay.viewer-url=wss://relay.example.uz/viewer  APP_REMOTE_RELAY_VIEWER_URL
app.remote.relay.signing-secret=${REMOTE_RELAY_SIGNING_SECRET}   # >=32 bytes, env only, never logged
```

`max-width` is further clamped **down** to the device's own reported `remote_max_width` when it has
reported one — capability is reported, not assumed.

#### Known pre-existing defects — filed here, deliberately **not** fixed in this change

Folding unrelated fixes into a feature branch makes both harder to review and to revert. Each of
these is real and each deserves its own change:

1. **`BatchedSyncDispatcher:73-74` emits the literal `"SYNC_CONTENT"`**, a wire type that is **not a
   member of `PushMessageType`**. The enum is decorative for that one message. Devices happen to
   handle it, so it is not user-visible — but it is exactly the drift the new
   `PushMessageTypeWireTest` exists to prevent recurring.
2. **No live `ACTION_PENDING` push on issue.** `DeviceWebSocketHandler` replays pending actions on
   *connect*, but issuing an action to an already-connected device pushes nothing — the device only
   learns about it on its next heartbeat, up to 2 minutes later.
3. **`LayerDependencyTest` defines the `Api` layer as `uz.orientadvertise.services.service..`** — the
   same package as the `Service` layer, almost certainly a copy-paste slip. The consequence is that
   `whereLayer("Api").mayNotBeAccessedByAnyLayer()` is vacuous and no rule actually analyses the
   `...api..` packages. Module boundaries are still enforced at compile time by Gradle
   (`service` has no dependency on `api`), so nothing is currently broken — but the architecture test
   is weaker than it reads.

#### Known limitation — WebSocket session map is per-replica

`DeviceWebSocketHandler`'s socket map is an in-process `ConcurrentHashMap`. On the current
single-replica deployment `GET /api/devices/{id}/connection` and `deliveredVia: "WS"` are exact.
Behind more than one replica they would answer only for the replica that served the request, and a
push would reach a device only if it happened to land on the right node. The fix is a Redis fan-out
for the session map. That is a **latent single-replica problem, not this feature's problem** — the
heartbeat fallback means nothing is lost even today, only latency. Noted here rather than solved.

### Playback batch: DB-level idempotent insert (v1.0.130)

**Playback telemetry: DB-level idempotent insert (`ON CONFLICT DO NOTHING`); a duplicate no longer
500s or discards the rest of the batch.** No migration — the fix leans on `uq_playback_dedup`, live
since `V11`.

`PlaybackLog` uses `GenerationType.IDENTITY`, so `repository.save()` issued its INSERT eagerly inside
the batch transaction. One duplicate entry therefore raised SQLSTATE 23505, which aborted the
PostgreSQL transaction (`25P02` on every later statement) **and** marked the Hibernate session
rollback-only — both *before* the `catch (DataIntegrityViolationException)` could run. The catch
returned a tidy `Duplicate` tally while the commit threw `UnexpectedRollbackException`, so **every
row in the batch was discarded**, the device got a 500, and — per `ANDROID_DEVICE_FLOW_SPEC.md`
("retry only on network/5xx") — a correct client retried the identical payload forever. One wedged
device lost ~46 h of telemetry before the fix.

- Dedup now happens **in** the INSERT: `PlaybackLogRepository.insertIgnoringDuplicate(...)` is a
  native `INSERT … ON CONFLICT DO NOTHING` returning `1` (created) or `0` (duplicate). A duplicate
  never raises, so nothing aborts and the whole batch commits with an exact tally in one plain
  `@Transactional`.
- The conflict target is deliberately **omitted** — H2 `MODE=PostgreSQL` (the test profile) rejects
  an explicit target. Exact here because `playback_log` has only the identity PK and
  `uq_playback_dedup`. Add an explicit target if a third unique constraint is ever introduced.
- `isDuplicateViolation` (driver-message string matching) is gone, as is the try/catch in `record()`.
  `PlaybackLogResult.Created` no longer carries an entity — nothing is materialized on the write path.
- Same-request duplicates are short-circuited by an in-batch seen-set; only **accepted** keys enter
  it, so a repeated *rejected* entry is still rejected twice. `ON CONFLICT` remains the correctness
  mechanism — the set only saves a round trip.
- Concurrent flushes are handled by PostgreSQL speculative insertion (the loser tallies `duplicate`),
  with no application-level retry and no TOCTOU window.
- **Wire contract unchanged.** `POST /api/devices/{id}/playback` still returns 200 with
  `{total, created, duplicate, rejected, rejections}` — it simply starts being honoured.
- **Expected after deploy:** wedged devices flush their queued backlog at once; `duplicate` counts
  spike for a few hours then fall to near zero. A device whose `duplicate` count stays high after the
  drain indicates a *client-side* timestamp bug (a separate ticket).

### Sync-group "Jump to video N" — group re-anchor over `/sync` (v1.0.129)

Lets an operator make **every device in a sync group jump to a chosen playlist index in lockstep** —
pick the 7th video and all member TVs converge on it and stay frame-aligned, wherever each was in the
loop. Ships **entirely over the existing `/sync` wire** (no new device field, no `PLAYLIST_CONTROL`
action) — a sync-honoring Android device needs **zero changes**. **DB migration
`V42__sync_group_playback_override.sql`** (new mutable `sync_group_playback_override` table, one row
per group, `ON DELETE CASCADE` with the group).

- **`GET /api/sync-groups/{id}/playback`** — the pickable deliverable timeline (`index`, `fileId`,
  `title`, `durationSeconds`, `slotStartMs`, `slotDurationMs`) + `loopDurationMs`, `memberCount`, and
  the current `activeJump`. When members are not content-coherent (different playlist/version, a
  member with no active playlist, or an empty group) → `coherent: false` + a `reason`, empty items.
  Roles: ADMIN/OPERATOR/VIEWER.
- **`POST /api/sync-groups/{id}/playback/jump`** `{ "index": 6 }` → **200**. Re-anchors the group's
  loop (`anchorEpochMs = activateAt − slotStart[index]`) so at the coordinated cut-over every member
  resolves to `index`; delivered via the existing `SYNC_CONTENT` push, so offline members converge on
  their next heartbeat. **409** empty / not content-coherent; **400** index out of range
  `[0, deliverableCount)`; **404** unknown/out-of-scope. Roles: ADMIN/OPERATOR (never `ROLE_DEVICE`).
- **Design (re-anchor-on-jump):** a MUTABLE per-group override overrides the immutable per-version
  base anchor (V40 `playback_sync_schedule`) **only while** the group stays on the same
  `(assignment, version, contentVersion)`; a content/version change makes it stale and it is ignored
  (and cleaned up) at `/sync` time — the group falls back to the base anchor. A re-jump overwrites the
  override in place. The one behavior change is in `DeviceSyncService.computeSyncPlan`, which now
  prefers a matching override; the slot timeline was extracted to a shared `PlaybackSlotTimeline`
  helper so `/sync` and the jump service compute an identical timeline.
- **Config:** `app.sync.jump-min-lead` (env `APP_SYNC_JUMP_MIN_LEAD`, default **PT5S**) — a short jump
  lead distinct from the version cut-over lead (`activation-min-lead`, PT2M): a jump needs no download,
  only push delivery + clock-offset convergence.

`ANDROID_DEVICE_FLOW_SPEC.md` (§5.1 sync-time layer, §6 `PLAYLIST_CONTROL` row) and
`frontend/docs/openapi.json` updated. **No** content-hash, assignment-resolution, or base-anchor
change.

### SyncGroup management — `/api/sync-groups` + a `sg-` sync-group tier (v1.0.128)

Adds the **SyncGroup** aggregate the admin frontend's `/settings/sync-groups` page expects — a
**project-scoped "sales point"** (one sales point = one sync group), distinct from
region/facility/device_group. Fixes the FE 404 *"No endpoint found for GET api/sync-groups"*.
**DB migration `V41__sync_groups.sql`** (new `sync_group` table + nullable `device.sync_group_id`
FK; `device_status_view` recreated to expose `sync_group_id`).

- **`/api/sync-groups` CRUD + membership** — `GET` (list, paginated, `projectId`/`name` filters),
  `GET /{id}` (detail with members), `POST` (create → 201), `PUT /{id}` (rename), `DELETE /{id}`
  (**hard** delete, ADMIN-only, `[SENSITIVE]`), `POST /{id}/devices` (set members — move-not-reject,
  reports `movedFrom`), `DELETE /{id}/devices/{deviceId}` (remove). Modeled on the device-group
  stack **minus volume, minus bulk-actions**; the single delete guard is *"has N active
  device(s)"* (a sync group can never be a `ContentAssignment` target). Reads:
  ADMIN/OPERATOR/VIEWER; writes: ADMIN/OPERATOR; delete: ADMIN. Operator scope (V35) collapses
  out-of-scope to 404.
- **Device member field is `status`** (heartbeat-derived `computed_status`), not `computedStatus`;
  no volume fields on sync-group members.
- **`syncGroupId` wire label gains an `sg-` top tier** — `Device.getSyncGroupId()` is now
  `sg-{syncGroup.id} ?? fac-{facilityId} ?? grp-{deviceGroupId} ?? reg-{regionId} ?? null`. The
  value stays **opaque** to devices and is re-read every heartbeat, so the Android app needs **zero
  changes**; removing a device from its sync group reverts the label to the derived fallback.
- **Device surface** — `GET /api/devices` gains a `syncUnassigned=true` filter (`sync_group_id IS
  NULL`, a DISTINCT axis from `unassigned`); the device list item carries the numeric `syncGroupId`,
  and device detail carries `syncGroupId` + `syncGroupName`.

`ANDROID_DEVICE_FLOW_SPEC.md` (§3/§4) and `frontend/docs/openapi.json` updated. **No** content-hash,
assignment-resolution, or `playback_sync_schedule` change — membership propagates solely via the
heartbeat `syncGroupId` echo.

### Partial-device supersede + sync-group reset on reassignment (v1.0.135)

Two operator-reported reassignment bugs. Both root causes sat in
`ContentAssignmentService`; **no migration, no controller change, no Android change.**

> **Bug 1 — "reassigning a playlist to *some* devices sets the playlist of the *unchecked* devices
> to null."** Root cause: `supersede()` retired a conflicting predecessor **in full** even when the
> new assignment only took over part of its device set. `resolveConfirmOverlap` already computed the
> exact per-candidate intersection for the 409 payload, then threw it away when deciding *how much*
> of the predecessor to retire. (This was the gap flagged as an open product decision under
> v1.0.108 — the operator report is that decision arriving from production.)
>
> **Bug 2 — "after changing a device's playlist it still remembers its old sync group."** Root
> cause: `device.sync_group_id` had exactly three production writers, all in
> `SyncGroupManagementService` / `DeviceRepository.bulkClearSyncGroup`. **No assignment path ever
> touched it**, so a device carried its sales point forever after being moved to different content.

#### 1. Partial-device supersede

For each device-intersecting predecessor `P` and the new assignment `N`:

```
Dn        = N's effective device set        (target MINUS the excludedDeviceIds param)
Dp        = P's effective device set        (target MINUS P's own exclusions)
handover  = Dp ∩ Dn      remainder = Dp \ Dn
```

| case | action |
|---|---|
| `remainder` **empty** — `N` covers `P` entirely | retire in full: **truncate** to `N.startTime` if `P` is running, else **soft-delete**. Unchanged from v1.0.107. |
| `remainder` **non-empty** | **narrow `P`**: one `ContentAssignmentExclusion(P, device, "superseded for these devices by assignment {N}")` per handed-over device. `P` stays `CONFIRMED` with its original `startTime`/`endTime` and keeps driving `remainder`. |

**Why exclusions rather than truncation or a version bump —** `ContentVersionService.computeForAssignment`
hashes `(assignmentId, versionNumber, playlistId, files+durations)` and **does not read exclusions**.
Narrowing therefore leaves every `remainder` device's expected content version **byte-identical**:
no re-download, no interruption, `DeviceSyncService` computes `hasWork=false` for them. Truncating
`P` or calling `bumpVersion()` would churn devices that are not part of the reassignment at all —
so **neither is done**. `PlaybackSyncSchedule` is keyed `(assignment_id, version_number)` and
immutable, so those devices also keep their frame-alignment anchor. The exclusion row's `createdAt`
records *when* each device left `P` — per-device history truncation cannot express.

`content_assignment_exclusion` has `UNIQUE(assignment_id, device_id)` and `handover ⊆ Dn`, where
`Dn` is the target minus `P`'s existing exclusions — so a duplicate insert is structurally
impossible and no defensive `existsBy…` probe is needed (pinned by
`confirm_replaceConflicting_predecessorWithOwnExclusions_handoverExcludesThem`).

**`AssignmentCancelledEvent` is now scoped to `handover`.** Previously `supersede` resolved the
predecessor's whole target *and ignored the predecessor's own exclusions*, pushing `SYNC_CONTENT` to
devices it never drove. It now names exactly the devices that changed hands.

**Unchanged:** the no-flag (`replaceConflicting:false`) path still 409s with the same structured
`details.conflicts`; cross-target layering is still not a conflict; there is no new request flag —
Replace simply became correctly scoped.

#### 2. Sync-group reset on reassignment

A sync group is a **sales point**: its members are meant to play the same content, frame-aligned.
`SyncGroupPlaybackService.resolveCoherence` refuses to drive a group whose members resolve different
`(assignmentId, versionNumber)` pairs — so **one silently-reassigned member disabled group control
and 409'd `POST /api/sync-groups/{id}/jump` for the whole sales point**, while `/sync` kept echoing
`sg-{id}` for a device that no longer shared the group's content.

`confirmWithExclusions` (the single production confirm path — `confirmWithIncludedDevices` delegates
to it) now clears `device.syncGroup` for exactly the devices whose **resolved playlist actually
changes**:

```
# snapshot BEFORE the overlap gate mutates anything
candidates = [d in effectiveDevices if d.syncGroup != null]      # in practice a handful
before[d]  = resolveForDevice(d, now)

# after assignment.confirm()
before == null                      -> clear    # the device gains content
before.priority > N.priority        -> KEEP     # shadowed by a more specific booking
before.playlistId != N.playlistId   -> clear    # a real reassignment
otherwise                           -> KEEP     # same playlist, nothing changed
```

**The naive rule ("clear the whole effective set") is wrong**, and that is the load-bearing detail:
a device shadowed by a higher-priority booking (a `DEVICE_GROUP` assignment while the new one
targets its `REGION`) sits inside the effective set, yet `resolveForDevice` keeps returning the
shadowing assignment — its playlist does not change, and clearing it would break a working sales
point for nothing. Pinned by `confirm_deviceShadowedByHigherPriorityAssignment_keepsSyncGroup`.

`before.priority == N.priority` implies the same target *type*, and a device belongs to exactly one
region / facility / device-group, so it implies the same target *id* — a same-target predecessor,
which the overlap gate has just rejected or superseded. `N` wins from there, so comparing playlists
decides it.

Implementation notes:

- **Never capped.** `app.sync.push-on-confirm-cap` is a *push budget*, not a correctness bound. The
  target's devices are now loaded **once, uncapped**, in `confirmWithExclusions` and threaded through
  the overlap gate, the narrowing and the detach; the cap is applied only to the
  `AssignmentConfirmedEvent` payload. Pinned by `confirm_syncGroupClear_isNotCappedByPushBudget`.
- **Mutated through the entities** (dirty-checked), not `DeviceRepository.bulkClearSyncGroup`: that
  query carries `clearAutomatically = true`, which would detach the just-confirmed assignment and the
  exclusion rows written moments earlier from the persistence context. No new repository method.
- No extra push: the reassigned devices already receive `SYNC_CONTENT` from
  `AssignmentConfirmedEvent`, and the next `/sync` naturally returns the new wire label.

**Deliberately NOT cleared (documented, not omissions):**

- **Cancelling an assignment** (`DELETE /api/assignments/{id}`) does not clear sync groups. A cancel
  is often followed by re-assigning the same playlist; keeping membership lets the group heal itself,
  and `resolveCoherence` already reports *"N member(s) have no active playlist"* honestly meanwhile.
- **`remainder` devices of a narrowed predecessor** keep their sync group — their playlist did not
  change; that is the whole point of §1.
- **Excluded devices** are never touched.
- **No `SyncGroupPlaybackOverride` cleanup.** The override is keyed by `sync_group_id` and matched on
  `(assignmentId, versionNumber, contentVersion)`; the members that stay behind still match it, and
  `sync_group_playback_override` has `ON DELETE CASCADE` on `sync_group` (V42).
- **No Android change.** `syncGroupId` is opaque to the client (`ANDROID_DEVICE_FLOW_SPEC` §5.2) and is
  re-read every heartbeat. Clearing the explicit group reverts the label to the next fallback tier —
  `fac-…` / `grp-…` / `reg-…` — never to `null` for a device that has a region.

#### 3. The 409 now names the remainder

`AssignmentTimeOverlapException.Conflict` (and `details.conflicts[]`) gained
**`remainingDeviceCount`** — how many of that conflicting assignment's own devices keep playing it if
the operator replaces. Appended **last** so the JSON stays back-compat; `0` on the conservative
same-target path (`Conflict.from(a)`), which reads the same as "nothing is left behind". With
partial supersede in place, Replace is no longer "this deletes the other booking", so the frontend
can render *"2 devices keep **Korzinka promo**"* instead of implying total deletion. The field is
advisory copy only — the FE degrades to neutral wording when it is absent.

```json
{ "id":6, "playlistId":3, "playlistName":"Korzinka promo", "status":"CONFIRMED",
  "startTime":"2026-06-03T19:37:00Z", "endTime":"2100-01-01T00:00:00Z",
  "conflictingDeviceIds":[1], "remainingDeviceCount":2 }
```

#### What changes on the wire

| surface | before | after |
|---|---|---|
| Replace, predecessor fully covered | retired | unchanged |
| Replace, predecessor partially covered | **whole predecessor retired; unselected devices go dark** | predecessor narrowed; unselected devices keep playing, **same content version, no re-download** |
| `AssignmentCancelledEvent` on supersede | predecessor's whole target, exclusions ignored | exactly the handed-over devices |
| Reassigned device's `sync_group_id` | kept forever | `NULL` |
| Reassigned device's `/sync` `syncGroupId` | `sg-9` | falls back to `fac-…` / `grp-…` / `reg-…` — opaque to Android |
| Sync-group jump after a partial reassign | 409 *"members resolve different content"* | works — the split device left the group |
| `details.conflicts[]` | — | `+ remainingDeviceCount` |
| DB schema | — | **no migration** |

Coverage: 13 new/updated cases in `ContentAssignmentServiceTest` (both branches of the supersede
split, the exclusion-blind cancel push, all four sync-group outcomes, the uncapped-clear negative)
plus `ContentAssignmentPartialSupersedeTest` in `infra` — the narrowed end state resolved through the
real `findActiveAtTime` + exclusion queries on H2 under the full Flyway schema.

### Content live-feed hardening: ordering, FAILED reasons, abandon frames, per-session routing (v1.0.134)

Four defects found while investigating an operator report — *"new content doesn't appear until
reload; it never flips to ready without another reload"*. **None of them is that bug**, which was a
frontend subscription-topology defect and shipped separately. All four are real, and all four are
independent.

- **`GET /api/content` had no `ORDER BY`.** Both listing queries end at the LIKE predicate and the
  controller takes a bare `Pageable`, so page membership was unspecified DB order — and it *shifts as
  the transcode pipeline UPDATEs rows*, which is enough to move a row between pages while an operator
  pages through it. `ContentListService` now normalises every request: no caller sort ⇒
  `ContentFileRepository.DEFAULT_LISTING_SORT` (`createdAt DESC, id DESC`), and the `id` tiebreaker is
  appended to *any* caller sort. An explicit `sort=` deliberately still wins — pinning the order
  inside the `@Query` would have silenced `sort=` for every caller, a bigger change than the bug.
  `id` is also the key the frontend structurally cannot send, since it binds one `sort` parameter per
  request. Proven against a real database (`ContentListingOrderTest`) with two rows sharing a
  `createdAt` — the case a unit test on the `Pageable` cannot see.
- **A FAILED frame carried no reason.** `markFailed` wrote the ffmpeg error to
  `transcode_last_error` and then broadcast an explicit `null`, with the text in scope one line
  above. Neither DTO exposed the column either, so **a FAILED card could not say why it failed on any
  surface**. The frame now carries the same truncated string that is persisted, and
  `ContentFileSummary` / `ContentFileDetail` expose it as `transcodeLastError`.
- **`TranscodeSweeper.abandon` wrote a terminal FAILED and told nobody.** The class injected no
  broadcaster at all. It is the one terminal transition the transcoder does not write, so an
  exhausted row went FAILED server-side while every operator's grid kept showing "Transcoding" until
  the next poll — exactly the failure the live feed exists to report. It now broadcasts, after the CAS
  returns 1 (never announce a state that was not written) and best-effort (a Redis outage must not
  stop a backlog from draining).
- **Content frames leaked across projects.** `contentStatusChanged` skipped the routing envelope every
  other event type uses, and the handler sends an unwrapped frame byte-identically to every open
  session — so every operator received every content id, status and ffmpeg diagnostic regardless of
  project or ownership. Frames are now enveloped like everything else, and the envelope gained an
  `_owner` alongside `_projectId`: content visibility is **owned ∪ granted**, never project-gated, and
  orphan content has no project, so project-only routing would have silently dropped an operator's own
  transcode updates. See "Dashboard Live Feed" for the routing table.

Also: the upload response's documented `status` example was `"PROCESSING"`, a value
`ContentFile.Status` cannot produce. It is `"UPLOADED"`.

**No frontend change is required by any of this**, and no schema migration ships with it.

### Production exposure hardening: closed ports, rotated credentials, bounded disk (v1.0.133)

Tracing an unrelated `"Error parsing HTTP request header"` log storm turned up internet scanners
sending TLS ClientHellos at the **plaintext** `0.0.0.0:8080` (Palo Alto Cortex Xpanse, and a Google
Cloud host probing 12 path variants). `ss -lntp` showed **six** ports listening on `0.0.0.0` with no
upstream firewall — Postgres 5432, Redis 6379, the API 8080, the SPA 3000, MinIO 9000 and the MinIO
console 9001 — all completing a handshake from off-host on the credentials committed in this repo.

**Redis was the sharp end.** It is not just a cache: `RefreshTokenRepository` stores `refresh:` /
`family:` / `userfam:` keys there, so write access is **token forgery**, and one `FLUSHALL` drops
every session *and* clears every brute-force counter at the same moment. `requirepass` was empty.
Postgres answered `SSLRequest` with `N` (queries in cleartext over the internet) on `apppass`, and
MinIO was `minioadmin`/`minioadmin`.

**What changed in the repo** (the host half is a runbook — see below):

- **Loopback by default.** Every publish in `docker-compose.yml` is now
  `"${BIND_ADDR:-127.0.0.1}:<host>:<container>"`. Local tooling still works; off-host handshakes are
  refused. `BIND_ADDR=0.0.0.0` is an explicit, documented opt-in.
  ⚠️ **MinIO's 9000 publish is rebound, never deleted** — Caddy runs as a *host systemd process*,
  cannot resolve compose service names, and proxies presigned media through `localhost:9000`.
  Deleting it breaks playback on every TV box.
- **No credential left in git.** `POSTGRES_PASSWORD`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` and
  `REDIS_PASSWORD` are `${VAR:?message}` — `docker compose up` aborts rather than starting on a
  well-known password, the discipline `JWT_SECRET` already used. The app's S3 keys reference the same
  variables, so they cannot drift.
- **Redis authentication.** `--requirepass "${REDIS_PASSWORD:-}"` on the redis service; Spring
  already bound `spring.data.redis.password` from the same variable. An empty value is accepted by
  `redis-server` and means "no password", so a bare dev box is unaffected.
- **`audit_log` is finally pruned.** `RetentionCleanupService` covered only `event` and
  `playback_log`. `audit_log` — the fattest table on the box, every row carrying the full HTTP
  request *and* response body — was pruned by nothing at all. Now drained at the same 90-day
  `RETENTION`, in the same batched own-transaction shape. *(Superseded in **v1.0.143**: the shared
  `RETENTION` constant is gone — each table now has its own window from `app.retention.*`, and
  `audit_log` is swept at 14 days.)* `entity_audit_log` is deliberately left
  alone: it is the business provenance trail in small rows, not HTTP traffic.
- **Raw originals expire** — see the storage section above. This is what stops `content-raw` growing
  forever on a volume that was 386 MB from failing.
- **Two new health components.** `disk` reports free space on the shared volume (Down below
  `app.health.disk-warn-free-percent`, default **15%**, so it fires at 85% used rather than at 99%
  when there is no room left to manoeuvre) and `storage-lifecycle` reports whether cleanup is
  actually installed. Both share `app.health.data-volume-path` and `DiskSpace`'s arithmetic with the
  Telegram `/health` command, so the two surfaces cannot disagree.

**Two rotation mechanics were verified locally before writing the runbook**, and one of them is a
trap that would have caused an outage:

- **Postgres:** changing `POSTGRES_PASSWORD` and recreating the container against an *existing* data
  volume does **nothing** — the variable is only read when the data directory is first initialised.
  The old password keeps working and the new one fails. Edit `.env` first and the app presents a
  password the server does not have. `ALTER USER` must come **first**, then `.env`.
- **MinIO:** restarting with new root credentials against an existing volume rotates cleanly — new
  credentials work, every object survives, old credentials are rejected. No special procedure.

**`DEPLOY-subzero.md` (new)** carries the host half, which is not something the image can do:
credential rotation in the correct order, the port rebinds **one service at a time** with a presigned
MinIO download verified after each, `ufw` default-deny, off-host handshake verification, disk
reclamation, and the one `curl` that settles whether Caddy's `encode gzip` is compressing `video/mp4`
— which would strip `Content-Length` and `Accept-Ranges` and break ExoPlayer seek on every TV box.

**Not a regression:** `DeviceTokenAuthFilter` logging device requests with no `X-Device-Token`, and
`POST /api/devices/{id}/remote` returning 503, are the known-unbuilt remote-control relay and Android
halves. The feature ships dark on purpose.

### Transcode pipeline: lost dispatch, missing recovery, and host-adaptive capacity (v1.0.132)

Two MP4s uploaded to production sat at `status = UPLOADED` with `processed_storage_key`,
`duration_seconds`, `checksum` and `thumbnail_storage_key` all NULL. **ffmpeg had never executed once
in that deployment.** The entire trace was two lines:
`WARN FFmpegTranscoder : Transcode requested for missing content file [id=1|2]`.

**Root cause — a commit-ordering race, not a determinism.** `ContentUploadService.upload(...)` was
`@Transactional` and called `transcoder.transcodeAsync(saved.getId())` ten lines after `save()`,
**still inside that transaction**. `ContentFile` is `GenerationType.IDENTITY`, so Hibernate had
issued the INSERT to obtain the id — but the row was uncommitted, and under READ COMMITTED a plain
SELECT on another connection takes no lock against a foreign uncommitted INSERT. Whichever finished
first — *(executor handoff + connection acquire + SELECT)* or *(tx unwind + COMMIT + WAL fsync)* —
decided the outcome. It passed in dev and CI every time and lost 2-of-2 on the production box.

**Why it became permanent.** `OrphanedTranscodeRecoverer` queried only `status='TRANSCODING'`, and
`TRANSCODING` was never committed: `runPipeline` wrapped its whole body in one transaction and every
exit path overwrote the status with a terminal value first, so the DB went
`UPLOADED → READY|FAILED|INVALID` in a single commit. Its predicate was unsatisfiable, no `@Scheduled`
job touched `content_file`, no endpoint could retry, and a restart recovered nothing.

**Seven fixes, deliberately shipped together** — fixing the dispatch alone would have turned a silent
stuck row into an OOM-killed backend on the very next upload:

- **Dispatch after commit.** `ContentUploadService` publishes `ContentUploadedEvent`;
  `ContentUploadedTranscodeListener` consumes it at `@TransactionalEventListener(AFTER_COMMIT)`. A
  rolled-back upload now dispatches nothing. The MinIO PUT also moved **out** of the transaction — a
  multi-MB transfer must not hold a Hikari connection.
- **`TRANSCODING` is a committed, observable state.** `@Transactional` is gone from both async entry
  points; the pipeline is three short self-committing statements (lease → work → terminal) with
  **no connection held across ffmpeg**. That also silences the false "Apparent connection leak
  detected" traces that `leak-detection-threshold: 30000` fired on *every* real transcode, and makes
  the "Transcode complete" INFO after-commit by construction rather than before a possible rollback.
  ⚠️ These statements are `REQUIRES_NEW`, not `REQUIRED`: inside an `afterCommit` callback the
  completed transaction's resources are still bound to the thread, so a `REQUIRED` statement joins a
  transaction that has already committed and Hibernate throws *"no transaction is in progress"* —
  which the synchronization machinery then swallows, silently losing the dispatch again.
- **`V44__content_file_transcode_lease.sql`** adds `transcode_started_at`, `transcode_attempts`,
  `transcode_last_error` and a `(status, transcode_started_at)` index, plus the claiming
  `TranscodeSweeper` described above. **The two stuck rows self-heal on the first sweep.**
- **A dedicated, host-planned transcode pool.** Transcodes ran on the **audit** pool, whose
  documented rejection policy is *silently drop* — a rejected transcode reproduced this incident with
  zero diagnostic trail. `TranscodeExecutor` is now the only place ffmpeg concurrency is decided: one
  bounded pool, width auto-planned from CPU + container memory (`TranscodeCapacityPlanner`), a
  `PriorityBlockingQueue` so urgent uploads still jump the queue at any width, and a **loud** ERROR
  on rejection. The old `urgentTranscodeExecutor` is deleted: a second pool made the real ceiling the
  sum of two widths, and its `CallerRunsPolicy` ran the whole pipeline inline on the Tomcat request
  thread on overflow. `auditExecutor`'s dead `maxPoolSize` (unreachable below ~505 queued tasks) is
  fixed to `core == max` with `allowCoreThreadTimeOut`, and it now drains on shutdown.
- **The encode fits the cgroup, on any host.** `-preset` and the resolution cap are configurable
  (`veryfast` default: measured 218 MiB peak / 14.3 s versus `medium`'s 438 MiB / 44.9 s on the same
  13 s 1080p25 input — the allocation is x264 lookahead, i.e. per-frame, so a 3 MB and a 50 MB clip
  cost the same). `JAVA_TOOL_OPTIONS` in compose defaults to `-XX:MaxRAMPercentage=45` and is
  overridable per environment; **no GC is pinned** — ergonomics already picks SerialGC at ≤1 CPU and
  G1 on a larger host. `-threads`/`-filter_threads` are deliberately *not* set: measured at 136 kB of
  431 MB on 1 vCPU, where x264 already auto-selects them.
- **The dashboard WebSocket feed was double JSON-encoded.** `RedisDashboardEventBroadcaster`
  hand-builds its frame as a `String` and published it through `RedisTemplate<String,Object>`, whose
  value serializer is `GenericJackson2JsonRedisSerializer` — so `convertAndSend` JSON-quoted and
  escaped it a second time. The browser's `JSON.parse` produced a *string* and the FE guard dropped
  it: `CONTENT_STATUS_CHANGE`, `DEVICE_STATUS_CHANGE`, `INCIDENT_CRITICAL` and `INCIDENT_UPDATED`
  were **all dead in production**; only `SNAPSHOT` survived, because the WebSocket handler serialises
  that one itself. Fixed at the publisher by injecting the already-existing `StringRedisTemplate`.
  *(The frontend has a second, latent blocker behind this one — it must ship together with this
  release or realtime is still dead.)*
- **A way out, and an alarm.** `POST /api/content/{id}/retranscode` (ADMIN/OPERATOR) claims with the
  same conditional UPDATE and re-queues; it resets `transcode_attempts` first, because a human asking
  again is not an automatic retry. 404 on unknown/soft-deleted, 409 (with `message`) when the status
  is not `UPLOADED`/`FAILED`, the raw object is missing, or another worker won the claim. A new
  `transcode-backlog` component on `GET /api/health` reports files stuck in `UPLOADED` past
  `stale-alert-after` (10 min), flipping `overallStatus` to `DEGRADED`; the sweeper logs the same
  backlog at WARN, which `TelegramAppender` already forwards. **That single metric would have
  surfaced this incident in minutes instead of waiting for a user complaint.**

**Why the tests did not catch it.** `ContentUploadServiceTest` asserted
`verify(transcoder).transcodeAsync(42L)` and passed on every run with the bug live in production —
mock verification proves a call happened, never *when* it happened relative to commit. The new
`ContentUploadCommitOrderingIntegrationTest` boots the real transaction manager and uses a
`Transcoder` double that records **whether the row was visible in a fresh `REQUIRES_NEW` transaction
at dispatch time**; revert the fix and it fails. Likewise the old WS test asserted the
pre-serialisation `String`, so `DashboardFrameWireFormatTest` now captures the bytes at the Redis
connection and asserts they parse to a JSON **object**.

### Synchronized multi-device playback — time layer (v1.0.127)

Backend surface for **Variant A** (server-anchored deterministic schedule) from
`SYNCHRONIZED_PLAYBACK_FLOW.md`: every TV box at a sales point plays the same ad at the same moment,
and a device that drops out (reboot, Wi-Fi loss, app relaunch) rejoins the group **mid-loop, never
at item 0**. This is a **time layer on top of** the existing register→sync→play pipeline — the
staging/promote flow, download+verify gates, and the volume/audio path are untouched.
**DB migration `V40__playback_sync_schedule.sql`** (one immutable row per assignment content-version).

- **`GET /api/devices/{id}/time` → `{ "serverUnixMs": <long> }`** — a deliberately cheap clock
  endpoint (no DB write, no status recompute — does **not** route through `DeviceHeartbeatService`)
  the device pings a few times to estimate its clock offset. `hasRole('DEVICE')` + path-id binding.
- **`syncGroupId`** on heartbeat / sync / register — `sync_group ?? facility_id ?? device_group_id ??
  region_id`, prefixed (`"sg-9"` / `"fac-42"` / `"grp-7"` / `"reg-3"`). The `sg-` tier (an explicit
  operator-assigned SyncGroup / sales point) sits at the TOP as of v1.0.128; the rest are derived
  server-side. Echoed every beat so a relocated or re-grouped device re-groups promptly. `null` ⇒ the
  device free-runs solo (today's behavior).
- **Schedule block on `/sync`** — per item `slotStartMs`/`slotDurationMs` (a prefix-sum slot timeline,
  `slotDurationMs = effectiveSeconds × 1000`), plus loop-level `anchorEpochMs`, `loopDurationMs`
  (`= Σ slotDurationMs`), and `activateAt`. **Epoch-millisecond `long`s** (deliberately not ISO-8601);
  the slot set is by construction identical to `playlistOrder`. A deliverable item with no effective
  duration falls back to a **10 s default dwell** — a null/0-ms slot would collapse `floorMod`
  positioning. The device computes `positionInLoop()` from these + its clock offset, with **zero
  device-to-device traffic**.
- **Coordinated cut-over** — the shared `activateAt` (`== anchorEpochMs`, the loop's T0) is persisted
  **once** per `(assignmentId, versionNumber)` and **never moves**, so the whole group agrees on one
  T0. It is created lazily on first activation (`PlaybackScheduleService.getOrCreate`, isolated in a
  `REQUIRES_NEW` writer) with a `now + minLead` lead, and rides the **existing** `SYNC_CONTENT` push →
  `/sync` path (that `/sync` now carries `activateAt`) — no new push type. Devices download during the
  lead and flip together; a straggler joins at the **live position**. A `@Scheduled` readiness monitor
  (`DeviceRepository.countReadyForVersion`) reports per-cut-over readiness for ops.
  Config: `app.sync.activation-min-lead` (`PT2M`), `activation-max-lead-cap` (`PT15M`).
- **Content-version hash now folds in effective per-item durations** — a pure dwell-time edit
  previously yielded an *identical* `contentVersion`, so offline/reconnecting devices never picked up
  new slot timings. Folding the effective duration into `ContentVersionHasher` makes a dwell edit
  produce a **new version** → online push **and** offline reconnect re-sync and re-anchor. (One-time
  global re-sync of every assignment on deploy — expected.)

`ANDROID_DEVICE_FLOW_SPEC.md` (§2/§3/§4/§5.1) and `frontend/docs/openapi.json` updated. Audio/volume
stays out-of-band via the existing heartbeat `desiredVolume` + `VOLUME_SET` (unchanged).

### Per-device playback report (v1.0.126)

`GET /api/stats/device/{deviceId}` — playback aggregated **by content** for one device over a
window: per content file, how many times it played and the total play duration (seconds).
Device-scoped inverse of the existing `GET /api/stats/content/{contentFileId}`; lives in the same
`StatsController`. **Read-only — no DB migration, no `SecurityConfig` change.**

- **Auth:** `hasAnyRole('ADMIN','OPERATOR','VIEWER')` — VIEWER allowed, **ADVERTISER → 403**.
  Operators are scoped to their projects; an out-of-scope or soft-deleted device → **404** (no
  existence oracle), via `findByIdAndDeletedAtIsNull` **before** the deleted-agnostic
  `existsByIdAndProjectIdIn` guard.
- **Window:** `from`/`to` ISO-8601 (UTC), default last 7 days; `from > to` or range > 90 days → 400.
- **Response:** `scope` is an **object** `{ type, id, name }` (`type` currently always `"DEVICE"`),
  plus `from`, `to`, `totalPlayCount`, `totalDurationSeconds` (integer **seconds** — the FE formats
  `H:MM:SS`), `durationComplete`, and a bare `perContent[]` of
  `{ contentFileId, contentFileName, playCount, totalDurationSeconds, durationComplete }`, ordered
  `playCount` DESC then `contentFileName` ASC.
- **Duration rule:** `COALESCE(playback_log.duration_seconds, content_file.duration_seconds, 0)`; a
  play is "missing" only when **both** sources are null, which drives per-row and top-level
  `durationComplete` (and makes `totalDurationSeconds` a lower bound).
- **Flexible by design:** the service is modeled around `ReportScopeType { DEVICE, REGION,
  DEVICE_GROUP, FACILITY, PROJECT }` (only `DEVICE` wired). Adding region/group later = one
  aggregation query + one thin route reusing the same scope-agnostic response envelope.

### Self-service password management + Gmail email (v1.0.125)

Users can now manage their own password — change it while logged in, or recover it by email if they
forgot it. Adds `app_user.email` (**DB migration `V39`**) and a feature-flagged Gmail-SMTP mail
subsystem (off by default, exactly like the Telegram bot).

**Endpoints:**

| Method / Path | Auth | Request | Success | Notes |
|---|---|---|---|---|
| `POST /api/me/password` | JWT (any role) | `{ currentPassword, newPassword, confirmPassword }` | **204** | Revokes ALL sessions; 400 wrong-current/mismatch/policy/same-as-current |
| `PUT /api/me/email` | JWT (any role) | `{ email }` (blank clears) | **204** | 400 invalid, 409 already in use |
| `GET /api/me` | JWT | — | `{ …, email }` | now includes recovery email |
| `POST /api/auth/forgot-password` | public | `{ email }` | **202** (always) | No enumeration: identical response whether or not the email exists; rate-limited |
| `POST /api/auth/reset-password` | public | `{ token, newPassword, confirmPassword }` | **204** | Single-use token; 400 bad/expired/used; revokes ALL sessions |
| `GET /api/auth/reset-password?token=…` | public | query `token` | `{ valid }` | non-consuming; rate-limited |
| `POST /api/users` | ADMIN | `{ …, email? }` | `{ …, email }` | create-user now persists the optional email |

**Security posture.** Forgot-password **always returns 202** (mirrors the generic-401 login discipline —
no account enumeration), and the reset email is dispatched **off the request thread** so the known-email
path isn't measurably slower (no timing oracle). Reset tokens are **single-use, SHA-256-hashed in Redis,
and expire** (default 30 min); only the raw token travels in the emailed link, and consuming it is atomic
(`GETDEL`). Any successful change/reset **revokes every refresh-token family** for the user (new
`RefreshTokenRepository.invalidateAllForUser` + a `userfam:{userId}` index). New-password policy: min 8,
max 200, confirm must match, must differ from current. New-password min is 8 for self-service; the
admin-create path keeps its existing min-6.

**Mail env vars** (all default empty/false — no SMTP socket opens unless enabled):

| Env var | Default | Purpose |
|---|---|---|
| `APP_MAIL_ENABLED` | `false` | Master switch — when `false`, a No-Op sender is wired |
| `MAIL_USERNAME` | _(empty)_ | Gmail address (SMTP auth user) |
| `MAIL_PASSWORD` | _(empty)_ | 16-char Gmail **App Password** (NOT the account password) |
| `APP_MAIL_FROM` | _(empty)_ | From address (usually identical to `MAIL_USERNAME`); **required when enabled** (fail-fast) |
| `APP_MAIL_FROM_NAME` | `Orient Advertise` | Display name |
| `APP_MAIL_RESET_TTL_MINUTES` | `30` | Reset-token lifetime |
| `APP_MAIL_LOG_RESET_LINK` | `false` | Dev only — No-Op sender logs the reset URL at INFO (**never enable in prod**) |
| `APP_FRONTEND_BASE_URL` | `http://localhost:5173` | SPA origin the emailed reset link points at |
| `APP_FORGOT_MAX_PER_WINDOW` / `APP_FORGOT_MAX_PER_EMAIL` | `5` / `3` | Forgot-password rate caps (per 15-min window) |

With mail disabled, the No-Op sender logs the reset link at INFO (dev profile) so the flow is testable
locally without SMTP. Migration `V39__app_user_email.sql` adds a nullable, unique `email` column — no
backfill (existing users keep `NULL`; multiple `NULL`s don't collide).

### Group volume change silently discarded — `@Modifying` flush/clear fix (v1.0.124)

`PUT /api/device-groups/{id}/volume` was a no-op: the group volume "stuck" at its old value and members never picked up the new level. **No DB migration** (one-line repository fix + regression test).

- **Root cause.** `DeviceGroupManagementService.setVolume` mutates the managed `DeviceGroup.volume` (dirty, not yet flushed) and then, in the **same transaction**, calls `DeviceRepository.bulkClearDesiredVolumeByGroup` — a `@Modifying(clearAutomatically = true)` bulk JPQL `UPDATE`. With `flushAutomatically` defaulting to `false`, the bulk update (which touches only the `device` table) does **not** auto-flush the dirty `device_group` row, and then `clearAutomatically = true` evicts it from the persistence context — **discarding the new group volume on commit**. The members' overrides *are* cleared (direct SQL), so every device then inherits the **stale** group volume, which is exactly the "set it, nothing changes, it's stuck" symptom.
- **Fix.** Add `flushAutomatically = true` to the `@Modifying` annotation on `bulkClearDesiredVolumeByGroup`, so the dirty group volume is flushed **before** the persistence context is cleared. `setVolume` is the method's only caller and holds no other dirty entities, so the flush is safe.
- **Regression guard.** The pure-mock `DeviceGroupManagementServiceTest` could never catch this — it verifies the two calls happen but never exercises the real persistence-context flush/clear. Added `DeviceGroupVolumePersistenceTest` (infra, H2 + full Flyway schema) that mirrors the exact `setVolume` transaction and asserts the new group volume survives the bulk-clear. It fails (`expected: <86> but was: <49>`) on the old annotation and passes with the fix.

### Telegram bot crashed the app on first enable — constructor injection fix (v1.0.119)

Setting `TELEGRAM_BOT_ENABLED=true` (the bot ships disabled by default) put the app into a **startup crash-loop**: `BeanCreationException … TelegramBotInitializer … No default constructor found` / `NoSuchMethodException: <init>()`. **No DB migration** (code + test).

- **Root cause.** `TelegramBotInitializer` is component-scanned and declares **three constructors** (one production + two package-private test helpers). With none annotated `@Autowired`, Spring cannot choose an injection constructor and falls back to a non-existent no-arg one, failing bean creation. The bean is `@ConditionalOnProperty(telegram.bot.enabled=true)`, so it is never created while the bot is disabled — which is why every prior run and the entire test suite were green: nothing ever instantiated this `@Component` on the enabled path.
- **Fix.** Annotate the production constructor with `@Autowired` (one line + import). No behavioural change otherwise.
- **Regression guard.** `TelegramBotConfigConditionalsTest` exercised the enabled path only through `TelegramBotConfig`'s `@Bean` methods, never the component-scanned initializer — exactly the blind spot. Added a case that registers `TelegramBotInitializer` with the bot enabled and asserts the context starts and the bean exists; it fails (context start failure) without the `@Autowired`.

### Deleted devices no longer produce incident reports (v1.0.118)

Deleting a device left it generating and surfacing incidents. Three independent defects, one symptom — **no DB migration** (code + tests):

- **FIX 1 — rogue producer guarded.** `SyncTimeoutMonitor.escalate` loaded the device via `findById` with **no `isDeleted()` check**, and `Device.softDelete()` never clears `syncPendingSince`, so the 1-minute stuck-sync scan minted a brand-new `SYNC_TIMEOUT` incident *for an already-deleted device*. It now bails on `device.isDeleted()`, matching the guard `DeviceHealthMonitor.escalate` already had — `SyncTimeoutMonitor` was the asymmetric outlier among incident producers.
- **FIX 2 — incidents reconciled at the deletion boundary.** `DeviceManagementService.softDelete` now auto-resolves the device's still-open incidents with the system resolver (reusing the unfiltered per-device audit query + `Incident.resolve`), instead of leaving ghost `OPEN` incidents an operator can never act on. Already-resolved incidents stay sealed.
- **FIX 3 — read layer honours `deletedAt IS NULL`.** None of the operator-facing incident queries excluded soft-deleted devices (unlike every `DeviceRepository` listing query). Added the filter to `findByStatus` (→ `GET /api/incidents/open` + dashboard WebSocket snapshot), `countOpenByPriority` (→ dashboard summary + Telegram `/health`), and the report-module `countOpenedInRange` / `avgResolutionSeconds`. This covers the **historical backlog** (devices deleted before FIX 2 shipped) and the delete-vs-scan race that FIX 2 alone cannot.

> The dedup queries `findOpenByDeviceAndEventType` / `countOpenByDeviceAndEventType` are deliberately **left unfiltered** — they power escalation de-duplication, and the producer guards already prevent them being consulted for deleted devices. `findByDeviceIdOrderByUpdatedAtDesc` is also left unfiltered: it is the per-device audit view (soft-deleted devices stay reachable by id for audit), and FIX 2 reuses it to find the incidents to close.

### Reinstalled/wiped device never syncs — `syncRequired` null-version fix (v1.0.117)

A device that was reinstalled / data-cleared / factory-reset keeps its `serialNumber` (re-registers via the 200 path) but loses its local content store, so it heartbeats with `contentVersion: null`. The server replied `syncRequired: false` and the device sat on an empty screen forever — no `/sync` was ever triggered. **No DB migration** (code + tests). Reported by the Android team against spec §4.

- **FIX 1 — heartbeat honors the device-reported version.** `DeviceHeartbeatService` previously computed `mismatch = reportedVersion != null && expectedVersion != null && !reportedVersion.equals(expectedVersion)` — the leading `reportedVersion != null` short-circuited a `null` (wiped) device to "no mismatch". Now `mismatch = expectedVersion != null && !expectedVersion.equals(reportedVersion)`, so `null`/blank ≠ `expected` is correctly a mismatch and `syncRequired = true` whenever content is assigned (spec §4). Blank `contentVersion` is normalized to `null`.
- **FIX 2 — mismatch incident anchors for null-reporting devices.** The `CONTENT_VERSION_MISMATCH` incident timer is now gated on `expectedVersion != null` (was `reportedVersion != null`), so a wiped device that stays stuck still escalates to operators and auto-resolves on recovery.
- **FIX 3 — re-registration clears stale server-side version state.** `Device.register()` now nulls `currentContentVersion` / `syncPendingVersion` / `syncPendingSince`. This fixes the **WebSocket connect-replay** path, which compares the server-stored `currentContentVersion` against `expected`: after a local-only wipe both were the stale `bd0c39`, so `SYNC_REQUIRED` was never pushed either. Clearing on re-register makes `current != expected` fire. The next `/sync/confirm` repopulates it.

> **Note on the report's "either fix is sufficient":** FIX 1 (heartbeat) is the primary, reliable fix — heartbeat is the always-on poll. FIX 3 (re-registration) is **complementary, not a substitute**: it repairs the independent WS-connect blind spot and keeps server state/diagnostics honest. The report's stated mechanism for the re-registration fix ("the next heartbeat naturally reports a mismatch") does not hold — the heartbeat mismatch is derived from the *device-reported* version, not the server's stored record — so both paths are fixed explicitly.

### Remote device volume control (v1.0.123)

Operators set device audio **volume** remotely — for one device, a whole group, or every device in scope. The model is **persistent desired state, reconciled on every heartbeat** (mirroring content-version sync): each beat reports the device's current volume and the response tells it the target, so offline/reset devices self-heal on their next beat. **DB migration `V38`** (`V38__device_volume_control.sql`).

- **Inheritance / resolution.** `effectiveVolume = device.volumeOverride ?? group.volume ?? 100` (`DeviceVolumeResolver`, a pure resolver like `DeviceStatusEvaluator`). `device.desired_volume` (nullable) is the per-device override; `device_group.volume` (nullable) is the group default; both `NULL` ⇒ inherit/none. New nullable columns `device.desired_volume`/`reported_volume`/`volume_reported_at` + `device_group.volume`, each with a `CHECK (… BETWEEN 0 AND 100)`.
- **Heartbeat channel.** `POST /api/devices/{id}/heartbeat` request gains `volume` (the device's current 0–100, stored clamped — an out-of-range or missing value never fails the beat); the response gains `desiredVolume` (the resolved target, always present). This is the delivery mechanism — no WebSocket push. The one-off `VOLUME_SET` remote action is unchanged for ad-hoc pokes.
- **Operator endpoints** (all `ADMIN`/`OPERATOR`, out-of-range → 400, out-of-scope → 404): `PUT /api/devices/{id}/volume {volume}` sets a device override (204); `DELETE /api/devices/{id}/volume` clears it → inherit (204); `PUT /api/devices/volume {volume}` applies an override to **every device in the caller's operator scope** (an ADMIN with no restriction hits every device) and returns `{affected: <int>}` (200, single bulk update); `PUT/DELETE /api/device-groups/{id}/volume` sets/clears the group volume (204) — members with no override pick it up on their next heartbeat (no fan-out).
- **Surfaces.** `DeviceDetail` / `DeviceSummary` gain `reportedVolume`, `volumeOverride`, `effectiveVolume`; `DeviceGroupDetail` gains `volume`. `effectiveVolume` is resolved inside the read transaction (group volume is lazy under `open-in-view: false`). The device LIST query and `device_status_view` are intentionally untouched.

### Device groups rebind from region to project (v1.0.122)

A **device group now belongs to a Project** instead of a Region, so one group may contain devices from **any region within that project** (it spans regions). A Device is unchanged — it still has `region_id NOT NULL` (→ project via the region) and an optional `device_group_id`. **DB migration `V37`** (`V37__device_group_project_binding.sql`).

- **Schema (`V37`).** `device_group` drops `region_id` and gains `project_id BIGINT NOT NULL` (FK `fk_device_group_project` → `project`). The unique key moves from `uq_device_group_name_per_region (region_id,name)` to `uq_device_group_name_per_project (project_id,name)` (+ index `idx_device_group_project`). The migration backfills `project_id` from each group's region's project, then resolves name collisions (two same-named groups in different regions of one project) by suffixing all but the lowest-id row with ` #<id>` before adding the unique constraint. `device_status_view` is **untouched** (it joins assignments by device-level `device_group_id`, never `device_group.region_id`).
- **API contract.** `POST /api/device-groups` body is now `{projectId, name}`; the list filter is `?projectId=` (was `?regionId=`); `DeviceGroupSummary`/`DeviceGroupDetail` carry `projectId`/`projectName` (was `regionId`/`regionName`). Create/rename duplicate → **409** `…already exists in project <id>`; add-devices cross-scope → **400** `Devices belong to a different project (move cross-project first): […]`.
- **`GET /api/projects/{id}` gains `deviceGroups[]`** (`{id,name}`); **`GET /api/regions/{id}` loses `deviceGroups[]`** — device groups are no longer region-scoped. The region-delete guard that counted device groups is removed (two guards now: active devices, then facilities).
- **`GET /api/devices` gains a `projectId` filter** powering the project-scoped device picker (the view exposes only `regionId`, so it narrows via a `Region` subselect, reusing the operator-scope idiom).
- **`PUT /api/devices/{id}/location`** now blocks only **cross-project** moves of a grouped device (409 `…from a different project; …`); a same-project cross-region move **succeeds**.
- **Operator scope guard on group-membership mutation.** `addDevices` / `removeDevice` now 404 when a restricted operator targets a group outside their assigned projects (consistent with group detail).

### Playlist ordering contract fixes for device sync (v1.0.116)

Five ordering-consistency fixes to `GET /api/devices/{id}/sync` + `/playlist` + `/playlist/control`. **No DB migration** (code + DTO + `ANDROID_DEVICE_FLOW_SPEC.md`). The spec is the client contract — **coordinate with the Android team** (the JUMP/index semantics and the `playlistOrder.durationSeconds` meaning change).

- **FIX 1 — effective per-item duration in `/sync`.** `playlistOrder[].durationSeconds` is now the **effective** duration (per-item override ?? the file's natural duration), matching `/playlist`. Previously it was override-only, so a held file with no override carried no usable duration (items advanced instantly / froze).
- **FIX 2 — `JUMP` addresses the delivered list.** `PlaylistControlService` validates `JUMP position` against the **deliverable** count (via the shared `DeviceSyncService.isDeliverable` predicate), not the raw DB item count — so a jump lands on the clip the device actually shows. `position` is now a 0-based index into the device's delivered playlist.
- **FIX 3 — contiguous `index`.** `/sync` `playlistOrder[]` and `/playlist` `items[]` now carry a contiguous 0-based `index` (raw `position` is retained for reference but may be sparse after undeliverable items are filtered out). The device must address positionally by `index`, never by `position`.
- **FIX 4 — pure reorder arms the sync-timeout.** A reorder changes the version hash but no files; `computeSyncPlan` now treats a version delta as work (`markSyncPending`), so `SyncTimeoutMonitor` can escalate a dropped reorder push and `confirmSync` validates against a stored expected version (kills a back-to-back reorder race). **Clients must POST `/sync/confirm` even when `filesToAdd`/`filesToDelete` are empty.**
- **FIX 5 — single deliverable predicate.** `/sync`, `/playlist`, and the `JUMP` range all derive deliverability from one shared `isDeliverable` (READY + processed key), so order/index can't drift. Documented policy: `/sync` is the authoritative, device-stateful delta (trusts held bytes); `/playlist` is a stateless freshness snapshot (re-presigns every item).

> **Follow-up (separate PR):** `ContentVersionService.computeForAssignment` still hashes *all* playlist items (including non-deliverable ones, e.g. a still-TRANSCODING file), whereas `/sync`/`/playlist`/JUMP use the deliverable-only `isDeliverable` predicate. If an item changes deliverability in the `/sync`→`/sync/confirm` window the recomputed hash can differ → a one-shot MISMATCH/re-sync (self-healing, no data loss). Aligning the hash to `isDeliverable` for full consistency is deferred because it changes *every* assignment's version hash (a one-time global re-sync) and touches the heartbeat/WS paths — it deserves its own change.

### Device list: "has active playlist" filter + indicator, and a `computed_status` correctness fix (v1.0.115)

Operators can now see and filter the device list by whether each device currently has an **active playlist** (derived, never stored — the single source of truth is `ContentAssignmentService.resolveForDevice`).

- **New `GET /api/devices` param `hasActivePlaylist`** (tri-state `Boolean`): omitted → all; `true` → only devices with a resolved active playlist now; `false` → the "needs content" bucket. It is **orthogonal to `unassigned`** (`device_group_id IS NULL`) — the two combine freely with **no** mutual-exclusion guard (a group-less device can still get a playlist via a REGION assignment). A non-boolean value → 400.
- **New `DeviceListItem` fields** `activePlaylistId` (null when none) and `activePlaylistName` (never null when the id is set).
- **Migration `V34`** recreates `device_status_view` (DROP+CREATE — column-changing REPLACE isn't H2/PG-portable) with two derived columns whose subselect mirrors `resolveForDevice` exactly: `deleted_at IS NULL` + `status='CONFIRMED'` + half-open window (`start <= now`, `end > now`) + region/facility/group target match + not in `content_assignment_exclusion`; winner = highest `priority` (REGION<FACILITY<DEVICE_GROUP), tie-broken `id DESC`.
- **`computed_status` correctness fix (behavioral change):** the pre-V34 view (V15/V16) flagged `NO_CONTENT` using window+target only — it ignored `status='CONFIRMED'` and exclusions, so a device whose only assignment was DRAFT/CANCELLED/expired/excluded was wrongly reported `ONLINE`. V34 makes `computed_status` CONFIRMED- and exclusion-aware, so such devices now correctly report `NO_CONTENT`. `computed_status = NO_CONTENT` is now exactly equivalent to "no active playlist" (for a device with a fresh heartbeat). Consumer **count tests are mock-based** (they stub the repository/service), so no golden numbers needed changing; the new H2 view fidelity test (`DeviceStatusViewActivePlaylistFilterTest`) pins all three in-SQL predicates to agree.
- New columns are **projection-only** (not sortable). No `U34` rollback (the rollback chain lapsed after U28; `FlywayRollbackTest` only exercises U1/U2). Follow-ups (separate PRs): (1) `GET /api/devices` still lacks the `size<=100` page cap its sibling endpoints enforce; (2) the view's display tie-break (`id DESC` on a same-priority same-target tie) is deterministic, whereas `resolveForDevice`'s `Stream.max(priority)` leaves same-priority ties unspecified — so on such a tie the *displayed* playlist name could differ from the one actually served. This is unreachable in practice (the confirm-time overlap guard rejects two CONFIRMED assignments overlapping on the same target+devices), but a deterministic `id DESC` tie-break should be added to `resolveForDevice` for strict parity. **(2) resolved in v1.0.142** — both sides now share one total order, `ContentAssignment.PRECEDENCE`, and same-target overlap is deliberate rather than unreachable.

### Content `checksum` (SHA-256) + legacy `sizeBytes`/`checksum` backfill (v1.0.114)

Completes the device-download metadata fix. v1.0.112 corrected `sizeBytes` for newly-transcoded files, but **the device was still blocked** because (a) `checksum` was always null and (b) files transcoded *before* v1.0.112 still carried the original (wrong) `sizeBytes`. The Android team has now confirmed the expected algorithm — **SHA-256 hex** — so both are addressed.

- **Transcoder now records the checksum.** `FFmpegTranscoder` computes the SHA-256 (hex) of the processed MP4 and stores it on `ContentFile.checksum` in the same `READY` transition that sets `sizeBytes`. New `common` util `Sha256.of(stream)` returns `(hex, bytes)` in a single bounded-buffer pass (O(1) memory — never buffers the whole object). `/sync` and the playlist view already pass `checksum` through, so devices can now verify integrity cryptographically. The `checksum` column is `VARCHAR(64)` — exactly a SHA-256 hex — so no migration.
- **Startup backfill for legacy files.** New `ContentMetadataReconciler` (mirrors `OrphanedTranscodeRecoverer`: runs on `ApplicationReadyEvent`, fault-tolerant per file, idempotent) finds READY files with a null checksum and a processed object, **streams that stored object once** to derive its true size + SHA-256, and updates the row — **without re-transcoding**, so the object/key/presigned URLs/content-version hash are unchanged and devices needn't re-download. Self-completing (null-checksum files drop out once fixed) and gated by `app.content.reconcile-on-startup` (`APP_CONTENT_RECONCILE_ON_STARTUP`, default `true`).

> Root cause of the repeat report: `fileId=1` was transcoded before v1.0.112, so its DB `sizeBytes` was never reconciled — the forward fix only ever corrects files transcoded after deploy. The reconciler closes that historical gap.

### Harden the 409 / conflict error contract (defensive) (v1.0.113)

Guardrails so the (already-sound) 409 contract stays sound by construction, not by per-throw-site discipline. **No change to the error envelope shape.**

- **Null/blank 409 message fallback.** `handleIllegalState` now substitutes `"The request conflicts with the current state of the resource."` when an `IllegalStateException` carries a null/blank message, so a future no-arg throw can't render an empty modal/inline message on the frontend.
- **Infra/config faults are 500, not 409.** Two `IllegalStateException`s that are *not* operator conflicts now throw a new `IllegalConfigurationException extends RuntimeException` (common module), which the catch-all maps to 500 (and forwards to the ops alert channel): `ApiKeyAuthenticationService` "SHA-256 unavailable" (JVM-impossible), and `DeviceRegistrationService` "Default region not found" / "Failed to provision sentinel default region". Note the sentinel region (`-1`) still self-heals via re-seed; the 500 path only fires for a misconfigured custom `app.device.default-region-id` or a failed re-seed.
- **Handler-ordering invariant locked by test.** A new `GlobalExceptionHandlerTest` case asserts an `AssignmentTimeOverlapException` produces a 409 whose body still carries `details.code == "ASSIGNMENT_TIME_OVERLAP"` and a non-empty `conflicts[]` — so the most-specific-handler dispatch (which keeps the structured payload) can't regress unnoticed.

> **H3 (id→human-label rewording) intentionally skipped:** 3 of the flagged `IllegalStateException` sites have only numeric IDs in scope and would need extra DB fetches on the error path purely to swap an id for a name — a poor trade. The IDs are not secrets and the messages are already operator-readable. The operator-facing "some pages don't surface the message" fix lives in the frontend repo.

### Content `sizeBytes` now reflects the transcoded object devices download (v1.0.112)

**The bug (reported by the Android team, proven against MinIO `Content-Length`):** the device sync/playlist response served `sizeBytes` = the **original upload** size while the presigned download URL pointed at the **transcoded** MP4 in `content-processed` (a different, smaller object). The device downloaded the full processed object (HTTP 200, bytes == MinIO `Content-Length`) but its `bytesOnDisk == sizeBytes` integrity check then failed because the reported size never matched — rejecting every file.

**Root cause:** `FFmpegTranscoder` computed the processed file's size (`Files.size(processedTemp)`), used it for the MinIO upload, and only **logged** it — it was never written back to `ContentFile.sizeBytes`, which stayed at the ingest-time original size (`ContentFile` had no size setter).

**The fix:** `ContentFile` gains a `setSizeBytes`, and the transcoder records the processed object's size in its single `READY` transition (alongside `processedStorageKey`). This covers both real-ffmpeg and disabled/passthrough modes (both flow through the same block). `sizeBytes` now equals the bytes a device actually downloads. No migration — the `size_bytes` column already exists.

> **Not fixed here (deliberate):** `checksum` is still `null`. The Android client only verifies integrity when `checksum` is non-null; serving a digest in an algorithm/format the client doesn't expect would flip its check from *skipped* to *failing* and re-break downloads. Computing + serving a checksum is a separate change to be coordinated with the Android team on the exact algorithm (the column fits a 64-char SHA-256 hex). **Existing already-`READY` files also still carry the old (original) `sizeBytes`** — a one-time backfill / re-process is needed to correct historical rows; this fix only corrects files transcoded from now on.

### Heartbeat sync signalling — guard no-op sync requests + honour pending SYNC_CONTENT (v1.0.111)

Two fixes to the device heartbeat / content-sync contract:

- **`SYNC_CONTENT` against an unassigned device is now rejected (409).** `POST /api/devices/{id}/actions` with `type=SYNC_CONTENT` previously queued a remote action even when the device had no resolved playlist — a no-op that lingered in every heartbeat's `pendingActions` until it expired. `DeviceActionService` now guards it via `ContentVersionService.computeExpectedVersion` (the same "has content" definition the heartbeat uses): null ⇒ 409 *"Device N is not assigned to any playlist — assign a playlist before requesting a content sync."* The guard is scoped to `SYNC_CONTENT`; `REBOOT`/`VOLUME_SET`/etc. are unaffected.
- **`syncRequired` now reflects a pending sync request.** Previously `syncRequired` was wired *only* to content-version mismatch, so an operator-forced `SYNC_CONTENT` left it `false`. It's now `versionMismatch || <a SYNC_CONTENT action is pending>`. The two triggers stay distinct internally: only a true version mismatch anchors the `CONTENT_VERSION_MISMATCH` incident timer — a forced re-sync must not. (Field `HeartbeatResult.versionMismatch` renamed to `syncRequired` accordingly; WebSocket path unchanged — it already replays pending actions as `ACTION_PENDING`.)

### Device-aware assignment overlap — stop blocking disjoint device subsets (v1.0.108)

**The bug:** assigning content to *some* devices in a region, then different content to *other* devices in the *same* region, returned 409 even though no device was double-booked. Root cause: overlap was matched only on `(targetType, targetId)` + time window, **blind to per-assignment device exclusions** (`ContentAssignmentExclusion`). Two same-region assignments with disjoint effective device sets falsely collided.

**The fix — overlap is now device-aware.** Conflict iff two assignments' **effective device sets intersect** *and* their time windows overlap. Effective set = `listDeviceIdsForTarget(target)` **minus** that assignment's exclusions.

- **Draft no longer pre-rejects.** `createDraft` dropped its overlap check — drafts don't resolve (both resolution and overlap filter `status='CONFIRMED'`) and auto-expire (1h), and the draft request carries no device list yet, so a region-level time overlap can't be disambiguated at draft time. `confirm` is the single authoritative gate.
- **Confirm is device-aware.** `resolveConfirmOverlap` computes the new assignment's effective set (`listDeviceIdsForTarget` minus the `excludedDeviceIds` **param** — exclusions aren't persisted until later in the same tx, so the DB would be wrong here), fetches same-target time-overlapping CONFIRMED candidates, and flags a candidate only if its effective set (from the DB) **intersects**. Disjoint subsets pass.
- **Conflict names the devices.** `AssignmentTimeOverlapException.Conflict` (and `details.conflicts[]`) gained `conflictingDeviceIds` — the intersecting devices — so the FE can say *"3 of your 5 selected devices already run 'Korzinka toshkent'."* It's always a JSON array (empty for the conservative path), appended last for back-compat.
- **Replace is scoped.** With `replaceConflicting:true`, only the **device-intersecting** candidates are superseded; time-overlapping-but-disjoint assignments are left untouched.

**Perf:** the target device set is fetched once per check (all same-target candidates share it); a new projection query `ContentAssignmentExclusionRepository.findDeviceIdsByAssignmentId` fetches excluded ids without initializing lazy `Device` proxies.

> **§4 cross-target — deliberately NOT a conflict (design decision, not an omission).** A device covered by both a REGION assignment and a DEVICE_GROUP/FACILITY assignment is *not* a double-book: those have distinct target-type priorities (REGION<FACILITY<DEVICE_GROUP) and `resolveForDevice` deterministically picks the most specific one — the intentional priority-layering feature (pinned by `resolveForDevice_groupTakesPriorityOverRegion`/`facilityOverRegion`). A genuine same-priority double-book requires the same target type, which (since a device has exactly one region/facility/group) means the same target id — already covered by the same-target check. Making cross-target a hard 409 would forbid a valid, common workflow and make the priority resolver dead code. If product wants visibility, that should be a non-blocking preview/warning, never a reject.

> **Partial-intersection replace — resolved in v1.0.135 (product call made, implemented).** When `replaceConflicting:true` meets a predecessor that drives *more* devices than the new assignment (predecessor drives [1,2,3], new drives [3]), the predecessor is now **narrowed, not retired**: one `content_assignment_exclusion` row per handed-over device, predecessor stays CONFIRMED on its original window, devices [1,2] keep playing it at an unchanged content version. Only a predecessor the new assignment covers *entirely* is still retired in full. See [Partial-device supersede + sync-group reset on reassignment (v1.0.135)](#partial-device-supersede--sync-group-reset-on-reassignment-v10135).

> **⚠️ Pre-existing follow-ups surfaced by review (NOT introduced here):**
> - **Confirm-time overlap is a read-then-write TOCTOU.** Two near-simultaneous `POST /…/confirm` on the same target/devices/window can both pass the gate and commit two CONFIRMED rows (no DB unique/EXCLUDE constraint, no row lock, `versionNumber` is not a JPA `@Version`). This race predates this change (the old `rejectTimeOverlap` path had it too) and is unaffected by the device-aware refactor. Proper fix is a serialized critical section per target — e.g. `pg_advisory_xact_lock(targetType,targetId)` at the top of the confirm gate (preserves the device-disjoint-allowed semantics, unlike a target-level `EXCLUDE` constraint). Tracked as a separate concurrency-hardening task.
> - **`frontend/docs/openapi.json` is stale** (already deferred since v1.0.104): it predates `details`/`conflicts` (v1.0.106) and now `conflictingDeviceIds`. Regenerate from a running build when the OpenAPI refresh is picked up; it's a generated artifact, not hand-edited here.

### Replace/supersede an overlapping assignment + name the conflict (v1.0.107)

Builds on the v1.0.106 structured 409. Two additions so operators can act on an overlap instead of hand-deleting the blocker:

**1. The conflict now names itself.** `AssignmentTimeOverlapException.Conflict` (and `Conflict.from`, null-safe) gained `playlistId`, `playlistName`, and `status`, flowing through `GlobalExceptionHandler` into `details.conflicts[]`:
```json
{ "id":6, "playlistId":3, "playlistName":"Korzinka promo", "status":"CONFIRMED",
  "startTime":"2026-06-03T19:37:00Z", "endTime":"2100-01-01T00:00:00Z" }
```
So the FE renders "Replace **Korzinka promo** (until …)?" instead of a bare id.

**2. First-class atomic REPLACE at confirm.** `ConfirmRequest` gained `replaceConflicting` (default **false**), threaded into `confirmWithExclusions`/`confirmWithIncludedDevices` (new 4-arg overloads; the 3-arg ones remain, defaulting false). At confirm, when the overlap re-check finds conflicts:
- `false` → throws the structured 409 (unchanged).
- `true` → **in the same transaction**, each overlapping CONFIRMED predecessor is retired, then the new one goes CONFIRMED — so the target is never left empty. A wholly-future/forever predecessor (the common case, e.g. the year-2100 #6) is **soft-deleted**; a predecessor already running is **truncated** to end at the new assignment's start (`ContentAssignment.truncateEndTo`) so history shows it ran until the cutover. Each retired predecessor emits the same after-commit `AssignmentCancelledEvent` the `DELETE` path uses, so every device it drove — including any the new assignment excludes — re-resolves within ~1s.

The destructive replace is deliberately at **confirm** (commit), not draft creation — soft-deleting at draft time would leave the target empty if the draft expires unconfirmed (1h TTL, drafts don't resolve). `createDraft` is unchanged. No success-path response shape change. The FE's "Replace existing & assign" action re-drives confirm with `replaceConflicting:true` (see `Orient-advertise-frontend/PROMPT-assignment-replace-ux.md`).

Tests: service (replace soft-deletes a future predecessor + confirms new; no-flag overlap throws & leaves predecessor untouched; running predecessor is truncated not deleted; `Conflict.from` carries playlist id/name/status and is null-safe) and web-layer (confirm `replaceConflicting:true` → 200 flag threaded; no-flag overlap → 409 with enriched `details`).

> **⚠️ Flagged, not implemented — retire the year-2100 sentinel.** "Indefinite" as the magic `2100-01-01` constant is the root smell: it makes "forever" bookings that guarantee future conflicts and forces sentinel math. Cleaner is a **nullable `end_time`** = "until superseded", with `findOverlapping` treating `NULL` as +∞. That's a migration + query + DTO change across FE/BE — separate, product-led. This task stays on the sentinel.

### Actionable assignment time-overlap 409 — structured `details.conflicts` (v1.0.106)

The overlap guard on `POST /api/assignments` (and `/{id}/confirm`) was working as designed but its 409 was developer-facing: it dumped internal ids as free text (`"Time overlap with existing assignment(s) [3, 4] for REGION:1"`), giving the frontend nothing structured to render. **The guard is unchanged** — only the error contract improved.

`rejectTimeOverlap` now throws a purpose-built `AssignmentTimeOverlapException` (in `service/.../exception`) carrying the target plus each conflicting assignment's **id and `[startTime, endTime)` window** (previously only ids were captured). It extends `IllegalStateException`, so the established 409 mapping holds even without the dedicated handler. `GlobalExceptionHandler` adds a handler that resolves ahead of the generic `IllegalStateException` one and populates a new optional `details` field on the error envelope:

```json
{ "status": 409, "error": "Conflict",
  "message": "Time overlap with existing assignment(s) for the selected target",
  "correlationId": "…", "timestamp": "…", "fieldErrors": null,
  "details": {
    "code": "ASSIGNMENT_TIME_OVERLAP", "targetType": "REGION", "targetId": 1,
    "conflicts": [
      { "id": 3, "startTime": "2026-06-02T06:00:00Z", "endTime": "2026-06-02T18:00:00Z" },
      { "id": 4, "startTime": "2026-06-02T18:00:00Z", "endTime": "2026-06-03T00:00:00Z" }
    ] } }
```

Times are UTC ISO-8601 instants (the FE localizes). `details` is `@JsonInclude(NON_NULL)` — it's omitted from every other error response, so no existing envelope shape changed. Per the security guidance, the free-text `message` no longer embeds the raw id list or `TYPE:id`; those internal identifiers live only in `details`. The frontend consumes `details.conflicts` to render "already booked until X — pick a later start" (see `Orient-advertise-frontend/PROMPT-assignment-schedule-ux.md`).

Tests: service-layer (overlap → structured exception with ids **and** windows, non-leaky message), web-layer (`AssignmentControllerTest` → 409 `details.conflicts` JSON shape; non-overlap 201 and 404 both omit `details`), and an infra integration test (`ContentAssignmentOverlapBoundaryTest`) pinning the half-open boundary against the real `findOverlapping` query: `newStart == existingEnd` is **not** a conflict.

> **⚠️ Product decision to confirm (not implemented here):** is **hard rejection** the intended policy when a target is already booked, or should the product support **supersede/replace** on confirm, or **priority layering** (the `priority` column exists but the overlap check ignores it)? This PR only improves the *error contract* — changing the policy is a separate, product-led feature. The guard is untouched.

### Content picker fix — `projectId=-1` "Unassigned" sentinel normalized on the list path (v1.0.105)

Fixes the empty content picker when adding items to a playlist bound to the seeded **"Unassigned"** project. The FE sends `GET /api/content?projectId=-1`, but two incompatible representations of "unassigned" existed: projects/regions use a real sentinel **row** with `id=-1` (`V13__device_registration`), while content stores unassigned as `project_id=NULL` (the upload/re-bind path coerced the sentinel away). The list query asked for `cf.project.id = -1`, which never matches any row → always empty.

`ContentListService.list(...)` now normalizes `projectId <= 0` to `null` (no project filter) **before** both the unscoped (`findFiltered`) and advertiser-scoped (`findFilteredScoped`) repository calls — so `projectId=-1`/`0` returns all READY content (advertiser-scoped where applicable), exactly like omitting the param. A real positive id still filters to that project. The sentinel rule, previously a private method duplicated in `ContentController`, is now a single shared helper `common.util.ProjectIds.normalize(Long)` used by upload, re-bind, and list. No OpenAPI contract change (`projectId` stays optional). New `ContentListServiceTest` cases cover `-1`/`0`/real-id and the advertiser path.

> **Product note:** `projectId=-1` resolves to "return all content", matching the upload philosophy — *not* "only `project_id IS NULL` content". Showing only unassigned content in that picker would be a separate, explicitly-specified feature. The deeper data-model smell (sentinel-row vs `NULL` for "unassigned") is left as a follow-up; this change only reconciles the read path.

### P0 security hardening — device/WS/file auth, BCrypt, secret fail-fast, info-leak fixes (v1.0.104)

Closes the P0 deploy-blockers from the security review that need no new DB schema or destructive ops.

**S1 — device-agent API authenticated.** New `DeviceTokenAuthFilter` validates the `X-Device-Token` (`dtk_…`) header against the persisted device and authenticates as `ROLE_DEVICE` with the device id as principal. The 7 device endpoints (heartbeat, sync, sync/confirm, playlist, actions/pending, actions/{id}/confirm, playback) lost their `permitAll` and now require `@PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")` — a token for device A cannot touch device B (403); no/invalid token → 401. `POST /api/devices/register` stays open but is per-IP rate-limited (`DeviceRegistrationRateLimiter`). `PlaybackLogService.recordBatch` rejects entries for content not in the device's assigned playlist (analytics-forgery defense). *(The filter is a conditional `@Bean` (`DeviceSecurityConfig`), not a `@Component`, so `@WebMvcTest` slices don't auto-load it.)*

**S2 — device WebSocket authenticated.** `DeviceTokenHandshakeInterceptor` validates the token (header or `?device_token=`) and binds it to the path device id (401/403) on `/ws/devices/*`. All three WS handlers replaced `setAllowedOriginPatterns("*")` with the configured `app.cors.allowed-origins`.

**S3 — file access locked down.** `GET /api/files/{key}` + `/presigned-url` now require `ADMIN`/`OPERATOR` (and a `SecurityConfig` matcher denies `ROLE_API_CLIENT` on `/api/files/**`); object keys are sanitized (path-traversal → 400) and `Content-Disposition` is RFC-6266 encoded. `MinioStorageClient.download` distinguishes `NoSuchKey`/`NoSuchBucket` → **404** without `markDegraded()` (prevents a missing object from self-DoSing storage). *(Per-object tenancy deferred — no object→owner model exists.)*

**S4 — auth hardening.** Passwords are now **BCrypt**-hashed and verified with `PasswordEncoder.matches()` (constant-time); migration **V33** converts the known `'password'` seeds to their BCrypt hash. Login/refresh are throttled per-IP with per-account lockout (`LoginRateLimiter` → 429). The JWT secret has no inline default and **fails startup** if blank/<32 bytes (`JwtTokenProvider`). The login 401 is generic (no account enumeration).

**S5 — secrets & info leaks.** Refresh-token reuse logs a SHA-256 prefix, never the raw token. The committed Telegram token/chat-id defaults are removed (empty). `/api/health` no longer leaks SQL exception detail (generic reason; logged server-side). `/actuator/**` is `ADMIN`-only (health probe stays open). `ContentFileDetail` no longer exposes `storageKey`/`processedStorageKey`/`thumbnailStorageKey`/`checksum`.

**S6 — external API.** The `ApiKeyAuthFilter` 401 (failed-auth) path is per-IP rate-limited (`ApiKeyFailureRateLimiter`) so the key space can't be brute-forced; the partner event history no longer forwards raw `Event.payload`.

#### ⚠️ Required operational follow-up (cannot be done in code)
- **Rotate the leaked JWT secret** and the **Telegram bot token** (via BotFather), and **purge both from git history** (`git filter-repo`). This PR only removed the committed defaults + fails fast.
- **Production password reset:** V33 only converts the `'password'` dev seeds; any real account is left as-is and must be reset (pre-existing rows were plaintext).

#### Deferred to follow-up PRs (need a schema / product decision)
Per-object file tenancy & per-key API-key tenancy (ext-dev-5); ADVERTISER export scoping (RS-1/RS-2); DASH-1/DASH-2 incident-bucket contract; sched-2/3/4; INC-1/INC-2, ASG-2, global pageable cap, 413-on-oversized-upload, bad-sort→400, ext-dev-2, and the full `openapi.json` regeneration (`DELETE /api/assignments/{id}` path + `fieldErrors` schema).

### Heartbeat-derived device status everywhere + content-transcoding WebSocket feed (v1.0.103)

Two changes. **Part A** makes the heartbeat-derived status the single source of truth across every operator/partner-facing read; **Part B** broadcasts transcoding progress to operators over `/ws/dashboard`.

**Part A — phantom ONLINE eliminated.** The persisted `Device.status` column was stale and structurally never OFFLINE (`Device.register()` set ONLINE but never `lastHeartbeatAt`; the heartbeat path refreshed the timestamp before deriving; `DeviceHealthMonitor` only emitted incidents). The device list already served the correct value from `device_status_view`; now **every** read surface does:

- `GET /api/devices/{id}` (and `PUT /{id}`, `PUT /{id}/location`), `GET /api/external/devices/{serial}/status`, device-group **and** facility member lists, and `GET /api/devices/{id}/diagnostics` all expose the heartbeat-derived value. Detail / external / group / facility member DTOs now expose the field as **`computedStatus`** (unified with the list contract); diagnostics keeps `status` with the computed value. Lookups are centralized in `DeviceManagementService.computedStatus(id)` / `computedStatuses(ids)` (backed by `DeviceStatusViewRepository`).
- Dashboard counts (`DashboardService.getSummary`) — top-level ON/OFFLINE/NO_CONTENT buckets **and** per-region online counts now aggregate over the view's `computed_status` (new `countByComputedStatusGrouped` / `countByRegionAndComputedStatus`), so OFFLINE is finally reachable and a silent device is counted. The Telegram `/health` command's device-status counts (`HealthCommandHandler.checkDevices`) read the same view, so it agrees with the dashboard.
- **Assignment preview** derives both `status` and the `offline` flag via `DeviceStatusEvaluator.evaluate(lastHeartbeatAt, hasContent, now)` (exclusion-aware — it reuses the already-resolved assignment).
- `Device.register()` no longer persists a phantom ONLINE (the raw column stays at its `UNREGISTERED` default until the first heartbeat).
- **Derive-only live feed:** `DeviceHealthMonitor` now broadcasts a `deviceStatusChanged(OFFLINE)` over `/ws/dashboard` when a device first crosses offline, so the dashboard reflects silent devices without waiting for a heartbeat. `device.status` is never written by the scan (no third source of truth).
- **`computedStatus` is 3-valued** (`OFFLINE` / `NO_CONTENT` / `ONLINE`). A never-registered device reads `OFFLINE`. `UNREGISTERED` remains only as the raw-column default and is never surfaced as a computed value — FE unions should treat `computedStatus` as 3-valued.

**Part B — content-transcoding status over `/ws/dashboard`.** New `DashboardEventBroadcaster.contentStatusChanged(ContentStatusPayload)` + a `CONTENT_STATUS_CHANGE` frame in `RedisDashboardEventBroadcaster`. `FFmpegTranscoder` emits it at each transition: **TRANSCODING** fires immediately (live "started" hint); **READY / FAILED / INVALID** fire after the transaction commits (a rolled-back transcode never broadcasts a false terminal). Best-effort — a broadcast failure never fails the transcode. This is separate from the device-facing `URGENT_CONTENT` push; the upload response's `webSocketPush` schema now documents that distinction. The FE no longer needs to 5-second-poll `GET /api/content/{id}`.

Positive + negative tests across `DeviceManagementServiceTest`, `DashboardServiceTest`, `DeviceDiagnosticsServiceTest`, `DeviceHealthMonitorTest`, `ContentAssignmentServiceTest`, `FFmpegTranscoderTest`, `RedisDashboardEventBroadcasterTest`, and the affected controller `@WebMvcTest`s. No Lombok; `openapi.json` updated (`status` → `computedStatus` on the affected schemas).

### Assignment-delivery hardening: empty-playlist guard, deliverable playlistOrder, cancel endpoint (v1.0.102)

Four delivery-correctness gaps in the assignment/sync path are closed; one out-of-window behavior is documented as an explicit product decision.

**1. (P1) An empty playlist can't be confirmed — or drafted.** `ContentAssignmentService.confirmWithExclusions` (and the inclusion-list variant that delegates to it) and `createDraft` now reject a playlist with zero items via a shared `requireNonEmptyPlaylist` guard → **409** (`IllegalStateException`) with `"Cannot assign playlist {id} — it has no items"`. Previously a confirmed assignment whose playlist had no items resolved on the device to a non-null playlist with an empty `playlistOrder` — nothing played. Drives a new `PlaylistItemRepository.countByPlaylistId` (counts in SQL, no entity load). 409 documented on both `POST /api/assignments` and `POST /api/assignments/{id}/confirm`.

**2. (P1) `playlistOrder` is now a subset of the deliverable file set.** `DeviceSyncService.computeSyncPlan` built `filesToAdd` from successfully-presigned files but built `playlistOrder` from an *independent* READY-only re-filter, so a READY-but-undeliverable file (MinIO object missing, or presign failed → dropped from `filesToAdd`) still appeared in the order — the device was told to play a file it had no download URL for. The order is now restricted to the deliverable set = files newly built into `filesToAdd` ∪ files the device already holds that are still expected. Held files stay even if their object is now missing (the device already has the bytes); fresh undeliverable files are dropped. `getPlaylistView` already enforced the same invariant per item — unchanged.

**3. (P2) Cancel/replace endpoint.** `DELETE /api/assignments/{id}` (`@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")`, **204**; 404 for unknown or already-cancelled id, mirroring `ContentController.softDelete`) exposes the previously-unwired `ContentAssignmentService.softDelete`. Soft-delete frees the target's overlap window and stops resolution (both queries filter `deletedAt IS NULL`), so operators can retarget a far-future "forever" assignment without DB access — the prerequisite for the FE's year-2100 sentinel. Cancelling a **CONFIRMED** row publishes a new `AssignmentCancelledEvent`; `AssignmentCancelledSyncPushListener` (AFTER_COMMIT, sibling of `AssignmentConfirmedSyncPushListener`) pushes a SYNC so the formerly-targeted online devices re-resolve within ~1s. Cancelling a DRAFT pushes nothing — it never drove a device.

**4. (P3) Out-of-window = purge (documented).** When a device has no active assignment, `computeSyncPlan` continues to purge everything it holds (blank screen). This is now a documented product decision: assignments carry a single start/end window (not a recurring schedule), so a between-windows gap is intentionally indistinguishable from "cancelled". A campaign that must survive a device going offline past its window uses a far-future `endTime`; accidental purges are prevented by that sentinel, not by retaining stale content. No behavior change.

**5. (P3) A soft-deleted DRAFT can't be confirmed.** `confirmWithExclusions` loads via `findById` (no `deletedAt` filter); a draft auto-cleaned by `DraftAssignmentCleaner` could otherwise be confirmed into a CONFIRMED-but-`deletedAt` row invisible to resolution. Confirming a soft-deleted assignment now returns **404** (logically gone).

Positive + negative tests across `ContentAssignmentServiceTest`, `DeviceSyncServiceTest`, `AssignmentControllerTest`, and the new `AssignmentCancelledSyncPushListenerTest`. No Lombok; `openapi.json` updated.

### Instant push on playlist mutations + version-hash regression test (v1.0.101)

Sibling to the v1.0.100 assignment-confirm push — the same event/listener/dispatcher chain now also fires on **playlist edits**. Today the only production caller of `SyncDispatcher.dispatchSyncToDevices` is `AssignmentConfirmedSyncPushListener`; this round adds `PlaylistReorderedSyncPushListener` so an operator reordering a playlist that currently drives 1,000 mall TVs sees those TVs pull within ~1 s instead of waiting up to one heartbeat (~1 min).

**1. New event + publish points.** `PlaylistReorderedEvent(playlistId)` is published at the end of every mutating method on `PlaylistItemService` — `addItem`, `removeItem`, `moveItem`, `reorderAll`, and `setDuration`. The `setDuration` publish is intentional: durations don't change the version hash (`ContentVersionHasher` inputs are file ids + processed keys only), but the device's `/sync` response carries the per-item durations in `playlistOrder`, so a push triggers a re-fetch and the operator sees the new duration applied instantly. Publish sits after the existing `playlist.markUpdated()` call; if a validation throw inside the method rolls back the transaction, the listener never fires (Spring `@TransactionalEventListener(phase = AFTER_COMMIT)`).

**2. New repo query + service helper.** `ContentAssignmentRepository.findActiveByPlaylistId(playlistId, now)` mirrors the existing `findActiveAtTime` + `countActiveAssignmentsByPlaylistId` for the list shape we need. `ContentAssignmentService.resolveDeviceIdsForActivePlaylist(playlistId, now)` unions the device id sets across all active assignments referencing the playlist, reusing the existing `listDeviceIdsForTarget` and the same soft cap policy (`app.sync.push-on-confirm-cap`, default 5000) as `resolveTargetDeviceIds`. The un-pushed remainder reconciles on the next heartbeat — same fallback offline devices use.

**3. New listener.** `api/.../ws/PlaylistReorderedSyncPushListener.java` is a copy of `AssignmentConfirmedSyncPushListener` shape: `@TransactionalEventListener(phase = AFTER_COMMIT)` → resolve device ids → `dispatchSyncToDevices(ids, "playlist-reordered")`. Empty result short-circuits at DEBUG; non-empty logs INFO. Offline devices remain unaffected.

**4. Version-hash regression test.** New `ContentVersionServiceTest` proves that reordering playlist items changes the hash that `computeForAssignment` returns and that restoring the original order returns the original hash. Complements the lower-level `ContentVersionHasherTest.hash_changesWhenFileOrderChanges` by exercising the full integration with `playlistItemRepository.findByPlaylistIdOrderByPositionAsc` — the load-bearing link that decides whether a device's `/sync` confirm reports MISMATCH and re-syncs.

**5. OpenAPI nullability drift.** `PlaylistItemDto.durationOverride` and `SetDurationRequest.durationSeconds` are now annotated `@Schema(nullable = true, …)` so the generated `frontend/docs/openapi.json` reflects what the Java code already does (null clears the override). Docs only — no behaviour change.

### Instant push on assignment confirm + status-free guardrail tests + scheduling note (v1.0.100)

Three changes around the content-assignment surface, plus one piece of operator documentation.

**1. Instant SYNC push when an assignment is confirmed.** `POST /api/assignments/{id}/confirm` previously persisted the confirmation but pushed nothing — online devices waited up to one heartbeat (~1 min) to pick up the change. The WebSocket plumbing already existed (`BatchedSyncDispatcher`, batches of 50 with 100ms stagger) but had no production caller. `ContentAssignmentService.confirmWithExclusions` now publishes an `AssignmentConfirmedEvent` carrying the in-scope, non-excluded device ids; a new `AssignmentConfirmedSyncPushListener` on the api side subscribes with `@TransactionalEventListener(phase = AFTER_COMMIT)` and forwards to `SyncDispatcher.dispatchSyncToDevices(ids, "assignment-confirmed")`. The after-commit phase means a rolled-back confirm (e.g. the 409 overlap re-check) never pushes — offline devices are unaffected and continue to pull on reconnect via `DeviceSyncService.computeSyncPlan`. New env knob `APP_SYNC_PUSH_ON_CONFIRM_CAP` (default 5000) caps the dispatched id set on very large targets; the un-pushed remainder shares the same heartbeat-poll fallback as offline devices.

**2. `includedDeviceIds` for cherry-picking subsets of large scopes.** `ConfirmRequest` gains an `includedDeviceIds` field. When set, the backend enumerates the target's full device id set and derives the exclusion complement, then delegates to the existing confirm-with-exclusions path. Useful when an operator wants to apply an assignment to 10 specific devices in a 500-device region — the FE preview cap is 200 so it can't even enumerate the full list client-side. The two list fields are mutually exclusive (400 if both set); ids outside the target scope also return 400.

**3. Status-free resolution guardrail tests.** `ContentAssignmentService.resolveForDevice` and `ContentAssignmentRepository.findActiveAtTime` are deliberately status-free — that's the contract that lets an offline TV-Box still pull assigned content the moment it reconnects. New regression tests in `ContentAssignmentServiceTest` and `DeviceSyncServiceTest` exercise an OFFLINE device with a stale `lastHeartbeatAt` and assert that resolution + sync planning still produce the expected `filesToAdd`. Any future refactor that adds an online-only filter on the resolver will fail these tests loudly.

**4. Scheduling note (docs only).** Drift in operator mental models: "assign forever until I cancel" was sometimes attempted with a short `endTime`. `findActiveAtTime` requires `startTime ≤ now AND endTime > now`; if a window elapses while a device is offline, the device resolves to no active assignment on reconnect and is told to *delete* the content. The convention is now documented at `AssignmentController.createDraft` — use a far-future `endTime` (year 2100 or the real campaign cut-off) for "apply whenever the device next reconnects". No code change, just the javadoc.

### Swagger UI reachable through proxies that forward the `/api/` prefix (v1.0.99)

Production deploys that put a reverse proxy in front of the backend and forward `/api/*` to the container verbatim (instead of stripping the prefix) were getting `401 "Authentication required"` on the swagger UI even with `APP_OPENAPI_ADMIN_ONLY=false`. The 401 came from the catch-all `.anyRequest().authenticated()` — none of the swagger matchers covered the prefixed paths.

Two layered defects:

1. The existing matcher was malformed: `/api/swagger-ui.html/**` (with a dot) only matches `/api/swagger-ui.html/<anything>` — **not** `/api/swagger-ui/index.html`, which is the actual entry point springdoc serves.
2. The `/api/v3/api-docs/**` path was never permitted at all, so the swagger UI's first XHR (to fetch the spec) failed too.

Fix: the conditional access matcher now lists both topologies:

```java
.requestMatchers(
        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
        "/api/v3/api-docs/**", "/api/swagger-ui/**", "/api/swagger-ui.html"
).access(...);
```

Same `app.openapi.admin-only` predicate gates both — flip the env var to lock down without redeploying. The malformed `/api/swagger-ui.html/**` permitAll above was deleted (it was a no-op for the real entry point and was misleading the reader).

`SwaggerAccessTest` and `SwaggerLockedDownTest` now cover both the bare paths and the `/api/`-prefixed paths in their positive and negative modes respectively.

### Cookie-based refresh-token flow — Path corrected + CORS hardened (v1.0.98)

Two mechanical defects in the in-flight cookie-based auth contract are fixed; a third is hardened against an entire class of FE breakage; one cleanup and one doc regen.

**1. Cookie `Path` now matches the controller mapping.** `RefreshTokenCookie` was emitting `Set-Cookie: ...; Path=/auth` while the controller is mapped at `@RequestMapping("/api/auth")`. Browsers never attach a `Path=/auth` cookie to `/api/auth/refresh` or `/api/auth/logout` (the path is not a prefix), so refresh and logout silently received no cookie even when the FE sent `credentials: 'include'`. Fixed to `Path=/api/auth` — still narrow enough to cover only the three auth endpoints, but actually attached.

**2. `SameSite` is now configurable via `app.jwt.refresh-cookie-samesite` (`REFRESH_COOKIE_SAMESITE`).** Default `Strict` (correct for same-site deployments where FE and API share a registrable domain). Operators serving FE + API on different domains over TLS set `REFRESH_COOKIE_SAMESITE=None` — the browser additionally requires `Secure=true` (always on here) and HTTPS end-to-end. `Lax` is configurable too but rarely the right answer for an auth cookie. The `Secure` flag remains hardcoded `true`; making it operator-overridable would be a footgun that defeats the rest of the cookie's hardening.

**3. Prod CORS no longer defaults to a wildcard origin.** `application-prod.yml` previously had `allowed-origins: ${APP_CORS_ALLOWED_ORIGINS:*}` with a comment claiming the wildcard was legal because credentials were off. The comment was wrong — `SecurityConfig.corsConfigurationSource` sets `allowCredentials(true)` (required for the refresh cookie to ride along), and the CORS spec forbids combining `*` with credentials. The browser rejects the response and Spring's `CorsConfiguration` refuses to echo `*` on a credentialed request. The default is now empty: the deploy MUST supply `APP_CORS_ALLOWED_ORIGINS=https://<your-spa-host>` (comma-separated allow-list) or every credentialed call from the FE will hit a CORS rejection loudly at startup, not silently at runtime.

**4. `SecurityConfig` cleanup.** The `/api/auth/**` permitAll matcher was registered twice (no-op, but misleading). The inline comment claiming "AuthController is mapped at `/auth`. No `/api/auth` controller exists" was the original bug's footprint — both removed. Cookie-attribute comments updated to reflect the new path and the SameSite-configurable contract.

**5. OpenAPI docs regenerated from springdoc.** `frontend/docs/openapi.json` now lists path keys as `/api/auth/login|refresh|logout` (matching the controller mapping), and the 401 responses reference the real `ErrorResponse` envelope (`{ status, error, message, correlationId, timestamp, fieldErrors }`) instead of reusing `AccessTokenResponse`. Regeneration runs via a new opt-in test: `./gradlew :api:test --tests "*OpenApiExporter*" -Dexport.openapi=true` writes `frontend/docs/openapi.json` from the live `/v3/api-docs`. The test is gated by the `export.openapi` system property so CI does not re-run it on every commit.

**Tests.** `AuthControllerTest` and `GlobalExceptionHandlerTest` were posting to `/auth/...` (no `/api` prefix) — that mismatch is why their 27 cases previously failed with 404. Paths retargeted; cookie assertions check `Path=/api/auth`. New `RefreshTokenCookieSameSiteTest` slice proves the configurable SameSite override propagates to both `issue(...)` and `clear()`. `CorsConfigTest` was already correct (concrete origin, exact echo, credentials true) — kept untouched.

**Definition of done curl checks (after deploying with `APP_CORS_ALLOWED_ORIGINS=<spa-origin>` and TLS):**

```bash
# Login: 200 + access token + scoped cookie
curl -i -c jar -X POST https://api/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"…"}'
# → Set-Cookie: refresh_token=…; Path=/api/auth; Secure; HttpOnly; SameSite=Strict; Max-Age=604800

# Refresh: 200 + rotated cookie
curl -i -b jar -X POST https://api/api/auth/refresh
# Missing cookie → 401

# Logout: 204 + cleared cookie
curl -i -b jar -X POST https://api/api/auth/logout

# Credentialed preflight: exact origin echo (never *), credentials true
curl -i -X OPTIONS https://api/api/auth/login \
  -H 'Origin: https://spa.example.com' \
  -H 'Access-Control-Request-Method: POST'
```

### Telegram Outcome Metrics + Failure-Rate Alarm + 403 Auto-Disable (v1.0.69)

End-to-end observability and self-protection for the Telegram subsystem. Three concerns delivered together because they share the same per-send hook:

**1. Metrics under `telegram.*` (`/actuator/metrics`)**

- `telegram.sent` — counter of successful sends (lifetime).
- `telegram.failed` — counter of failed sends (lifetime).
- `telegram.failed.by_reason{reason=FORBIDDEN_403|TIMEOUT|NETWORK|OTHER}` — per-category breakdown for dashboards ("of last hour's failures, 80% were 403s").
- `telegram.failure_rate` — gauge, lifetime failure rate (0..1). 0 when no traffic. The five-minute window that drives the alarm lives separately on `TelegramFailureRateMonitor`; this gauge is the long-term view.
- `telegram.last_failure_timestamp` — gauge, epoch millis of the most recent failure (0 if none). The textual reason is on `TelegramMetrics.lastFailureReason()` (Micrometer can't carry strings — accessor instead of meter tag).

**2. Failure-rate alarm**

`TelegramFailureRateMonitor` keeps a 5-minute sliding window of (timestamp, success) samples. When the failure rate exceeds 50% over the window with at least 10 samples, it logs a critical line at `ERROR` level:

```
CRITICAL: Telegram failure rate 67% over last 5 minute(s) (8 of 12 samples failed).
Threshold 50%. Investigate Telegram connectivity and bot authorization.
```

**Loop-back avoidance** (the spec called this out): the monitor's logger lives in the `uz.orientadvertise.services.infra.telegram.*` package, which `TelegramAppender.RECURSIVE_LOGGER_PREFIXES` already excludes from the Telegram-forwarding pipeline. So the warning lands in the local file appender (and only there) — never in a Telegram message. Belt-and-braces: the appender additionally has a thread-local `SENDING` guard that suppresses re-entry on the forwarder thread.

**Throttling**: once tripped, the warning is suppressed for the next 5 minutes — a sustained outage doesn't fill the log with one near-identical line per send. Atomic CAS on the last-warn timestamp ensures two threads racing past the threshold can't both emit.

**Min-sample guard**: 10 samples required so a single "1-of-2 failure = 50%" doesn't page on tiny traffic.

**3. 403 auto-disable + DB-backed registry**

`TelegramApiException` is classified at the bot's `send` choke point:

- `TelegramApiRequestException` with `getErrorCode() == 403` → `FORBIDDEN_403` → the chat id is registered with `DisabledChatRegistry`. Future sends to that chat short-circuit to `false` without attempting the network call.
- `TelegramApiRequestException` with any other code → `OTHER`. NOT auto-disabled — only 403 (the kicked/blocked signal) gets the disable rule. Disabling on transient 5xx/400 would orphan healthy chats.
- Cause `TimeoutException` or message containing "timeout" → `TIMEOUT`.
- Cause `IOException` or message containing "connection"/"unable to execute" → `NETWORK`.
- Anything else → `OTHER`.

`DisabledChatRegistry` is a cache + DB-backed allow-list. The new `telegram_disabled_chat` table (V29 migration) is the source of truth (`chat_id BIGINT PK, reason VARCHAR(500), disabled_at TIMESTAMP`); a `volatile Set<Long>` cache fronts it for hot-path `isDisabled` checks. The cache reloads from DB every 5 minutes, so a manual re-enable (`DELETE FROM telegram_disabled_chat WHERE chat_id = ?`) takes effect within that window without an app restart.

**Persistence is non-negotiable** (the spec called this out): without it, a JVM bounce would re-attempt every previously-kicked chat, get 403'd again, and re-disable them — operationally equivalent but burns API quota and shows up as a spike of failures every restart. The DB makes the disable durable across reboots.

**DB failure during reload keeps the existing cache** rather than blanking it — a "DB hiccup → bot starts retrying every disabled chat at once" failure mode would be far worse than a stale cache. Same posture for `disable()` itself: if DB save throws, the in-memory cache is NOT updated (so we don't end up with "disabled in memory but not durable"); the next 403 will retry the disable.

**Idempotent disable**: a chat already in the cache is a no-op (no duplicate INSERT, no duplicate log). The `DataIntegrityViolationException` from a unique-PK collision (concurrent 403s, or a row already in the DB from a prior boot) is caught and treated as success — cache updated, no retry loop.

**Queue-level skip**: `TelegramOutboundQueue.processMessage` filters disabled chats out of `remainingChatIds` BEFORE attempting the network call. Disabled chats neither consume an attempt, nor enter the retry path, nor land in the fallback file. The bot itself also short-circuits, but doing it at the queue keeps the retry path clean.

**Wiring & test-slice safety**: every new bean is `@ConditionalOnBean` on its hard dependency. `TelegramMetrics` is created only when a `MeterRegistry` is on the context (Spring Boot Actuator); `DisabledChatRegistry` only when `DisabledTelegramChatRepository` is present (a `DataSource`-backed test). Slim test slices that don't load actuator or JPA still bring up the bot — the optional collaborators are `null` and recording becomes a no-op. `OrientTelegramBot` and `TelegramOutboundQueue` use setter injection for the optional collaborators, mirroring the dispatcher pattern from v1.0.66.

**Test coverage (47 new):**

- `DisabledChatRegistryTest` (12) — initial DB load populates cache; disable persists + updates cache; idempotent on second call; null chat-id ignored; reason truncated to 500 chars; blank reason → "unknown"; persistence-failure leaves cache untouched (next attempt retries); `DataIntegrityViolationException` treated as success (cache updated); reload replaces cache with current DB state (admin re-enable picked up); reload-on-DB-failure keeps existing cache (does NOT blank it); `isDisabled(null)` is false; `snapshot` is immutable.
- `TelegramMetricsTest` (11) — every meter under `telegram.*` registered; success/failure increments counters; per-reason tagged counter (FORBIDDEN_403, TIMEOUT, etc.); lifetime failure rate computed correctly; zero-samples → 0 (not NaN); last-failure-timestamp updates within the recording window; last-failure-reason captures category + detail; long detail truncated; blank detail substitutes category.
- `TelegramFailureRateMonitorTest` (11) — below MIN_SAMPLES no warning; ≥10 all-failing fires; 50% boundary doesn't fire (strictly above); 58% > 50% fires; throttle suppresses second trigger within 5 min; throttle elapsed → re-fires; eviction window drops old samples; rate reflects in-window state; concurrent stress (8 writers × 200 events) stays consistent; **logger name verified inside telegram package** so appender's recursion guard suppresses Telegram forwarding (the loop-back guarantee).
- `OrientTelegramBotMetricsTest` (13) — successful send increments metrics + monitor; 403 → FORBIDDEN_403 + chat disabled; 400 recorded as failure but chat NOT disabled; generic exception → OTHER; TimeoutException cause → TIMEOUT; IOException cause → NETWORK; disabled-chat short-circuits without network call AND without metric change (skip ≠ failure); `classifyFailure` unit cases for 403/400/timeout/network; bot works without optional collaborators wired (test-slice safety).

### Telegram `/logs` Command — Recent ERROR Events From the In-Memory Ring Buffer (v1.0.68)

`/logs` returns the most recent ERROR-level log events from the static ring buffer that `TelegramAppender` already mirrors WARN/ERROR events into (`RetainedErrorBuffer`, capacity 100, introduced in v1.0.67 for `/health`'s "recent errors" section). Operators can pull the last few errors over Telegram without touching the log files.

**Response shape (with entries):**

```
🔴 ERROR — Recent error logs (10)
2026-05-07 14:32:11 FooService: connection refused [SQLException]
2026-05-07 14:30:45 RetryHandler: timeout retrying upload after 3 attempts
2026-05-07 14:28:02 IngestPipeline: rejected payload — schema mismatch [ValidationException]
...
```

**Response shape (empty buffer):**

```
🟢 INFO — Recent error logs
no recent errors
```

The empty-buffer reply is **explicitly non-silent** per spec — silence after `/logs` is confusing (operator can't tell if the bot is alive). INFO + green tells them the absence of errors is the success case.

**Argument parsing.** `/logs` alone returns the default 10. `/logs N` returns up to N, clamped to `[1, 30]`. Bad input doesn't fail silently:

- Non-numeric (`/logs verbose`) → use default 10, response footer `argument 'verbose' is not a number — using default 10`
- Zero or negative (`/logs 0`, `/logs -3`) → use default 10, response footer with the warning
- Above 30 (`/logs 500`) → clamped to 30, response footer `argument 500 > 30 — clamped to 30`

The 30 cap is below the buffer's 100-entry capacity because Telegram's per-message limit is 4096 chars; with ~200-char message previews + log context per row, ~30 entries comfortably fits while leaving room for the header.

**Format per row.** `yyyy-MM-dd HH:mm:ss ShortLogger: message [ExceptionClass]` — exactly the four fields the spec calls out. Logger name is the FQCN's last segment (FooService rather than `uz.orientadvertise.services.api.FooService`). Message is hard-truncated at 200 chars with a trailing ellipsis. Exception class (when present) is rendered in brackets, simple-name only (no package prefix). All rows go inside a Markdown code block so log content (paths, stack fragments, special characters) renders literally without Markdown-escaping concerns.

**Source filtering.** `RetainedErrorBuffer` retains both WARN and ERROR. `/logs` filters to ERROR-only via the new `recentByLevel(String, int)` accessor — single descending pass, capped at the requested limit. WARN events are still in the buffer for `/health`'s "recent errors" section but are excluded from `/logs`.

**Lifecycle.** The buffer is in-process static memory only — restart wipes it. No persistence layer, no Redis flush — deliberately. The retention horizon is "since this JVM started"; deeper history goes to the regular log files. `RetainedErrorBuffer.resetForTests()` exercises the same observable behaviour for unit tests.

**Newest-first ordering.** Entries are listed with the most recent at the top so an operator scrolling on mobile sees the freshest error without scrolling past stale ones.

**Bypass path.** Like `/state` and `/health`, the response goes via `bot.send` directly — bypassing the outbound queue. Fourth documented bypass alongside `TelegramShutdownNotifier` and `TelegramSuppressedAggregator`.

**Test coverage (24 new):** name = `/logs`; happy path sends Markdown to requesting chat; empty buffer → "no recent errors" with INFO/green; only-WARN buffer → still empty-case (level filter works); default 10 entries with newest-first slicing using prefix-free tokens; `/logs N` honored; `parseLimit` for valid number / non-numeric / zero / negative / above-max with the right warning each time; format contract: timestamp + short logger + 200-char message + exception class in brackets; 200-char truncation with ellipsis; null message → `(no message)`; no exception → no brackets; `shortLoggerName` correctness (FQCN, already-short, null, empty); `exceptionClass` extraction from stored `FQCN` and `FQCN: msg` formats; capacity 100 enforced at the buffer level; restart simulation via `resetForTests`; never-throws envelope (markdown send throws → plain-text fallback); newest-first ordering verified by inspecting body text positions.

### Telegram `/health` Command — Comprehensive System Status With Parallel Checks (v1.0.67)

`/health` returns a single Telegram message covering 9 sub-components — Postgres, Redis, MinIO, FFmpeg worker queue, device-status counts, open-incident priorities, JVM memory, disk space on the data volume, and the last 5 errors from the past hour. Operators get one button to press when something feels off.

**Response shape:**

```
🟡 WARN — System health
postgres  : UP       ping 6ms
redis     : UP       ping 2ms
minio     : UP       3 bucket(s)
ffmpeg    : DEGRADED urgent=17/4a audit=0/0a (queue near capacity)
devices   : UP       online=42, offline=3, no_content=1, unregistered=0
incidents : DEGRADED critical=1, high=4, medium=2, low=0, info=0 (CRITICAL open)
jvm-mem   : UP       used=412.0 MB / max=1.0 GB (40%)
disk      : UP       free=18.4 GB / total=50.0 GB (36% free, path=/data)
errors    : UP       2 in last 1h

Recent errors:
2026-05-07 14:31:02 ERROR FooService: connection refused
2026-05-07 14:30:45 WARN  RetryHandler: timeout retrying upload
```

**Parallel execution.** All 9 checks fire simultaneously on a dedicated 10-thread daemon pool (`telegram-health`). They're independent — no Postgres state needed for the Redis check, no MinIO state for incident counts. Sequential execution would mean a single hung component (e.g. a Redis blocking on a 2-second TCP connect to an unreachable host) stretches the response past the 10-second budget; parallel collapses the worst case to 2 seconds (one per-check timeout window) regardless of how many components are unhealthy.

**Per-check 2-second timeout.** Each individual check is bounded by `CompletableFuture.orTimeout(2, SECONDS)`. After 2 seconds, the future resolves exceptionally and the corresponding row is marked `DOWN — timeout`. The underlying task may continue to run (Java can't truly kill a thread mid-I/O) but its result is discarded — defensive, so a hung JDBC call doesn't leak the worker thread back into the next `/health` invocation either.

**Overall 10-second budget.** `CompletableFuture.allOf(...).get(10, SECONDS)` caps the orchestration. Since each individual check resolves within 2s and they run in parallel, a typical `/health` response completes in 100–500ms. The 10s figure is a safety net — defensive against `orTimeout` jitter, scheduler stalls, or one of the inner Callables throwing during result extraction.

**Each row succeeds independently.** A failing dependency (DB unreachable, MinIO 503, missing repository bean) collapses to a `DOWN — reason` row; the other 8 still complete. Same posture as `/state` — the spec demands the response always succeed, so one bad component must not collapse eight others. Three layers of isolation:

- **Per-check `try / catch (Throwable)`** in `submit()` — any inner exception becomes a `DOWN` result, never propagates.
- **Per-check `orTimeout(2, SECONDS)`** — hung checks resolve as `TimeoutException` after 2s, not whenever the network gives up.
- **Outer `try / catch (Throwable)`** in `handle()` — even if the orchestration itself blows up, the user gets an `⚠️ /health failed unexpectedly` plain-text reply.

**Severity classification.** The overall message header reflects the worst-row severity: any `DOWN` → 🔴 ERROR, any `DEGRADED` (and no DOWNs) → 🟡 WARN, all `UP` → 🟢 INFO. Specific rules:

- **Incidents** with any open CRITICAL → DEGRADED, with HIGH → DEGRADED. Surfaces emergencies in a sea of UP rows.
- **FFmpeg queues** at ≥ 80% of capacity → DEGRADED. Urgent transcode pool capacity is 20; saturation means the next upload uses the CallerRunsPolicy fallback (back-pressure on the request thread).
- **JVM memory** at ≥ 90% of `max` heap → DEGRADED. Textbook GC-thrashing zone.
- **Disk** below 10% free → DEGRADED, below 1% → DOWN.

**RetainedErrorBuffer.** New `uz.orientadvertise.services.infra.diagnostic.RetainedErrorBuffer` — a static, bounded ring buffer (capacity 100) that retains WARN/ERROR events with timestamps. `TelegramAppender` now mirrors every event it formats to this buffer (in addition to the existing forward queue). The two buffers are independent — the forward queue gets *drained* on send while RetainedErrorBuffer *retains* for diagnostics. `/health` reads from it via `RetainedErrorBuffer.recent(Duration.ofHours(1), 5)`.

**Optional dependencies.** Every backing service is resolved via `ObjectProvider` so a missing bean — uncommon in production, common in slim test slices — collapses to `DOWN — no … bean` rather than a startup failure. The two transcode executor providers are `@Qualifier`-narrowed because the application has multiple `ThreadPoolTaskExecutor` beans and we only want the urgent + audit pools.

**Bypass path.** Like `/state`, the response goes via `bot.send` directly — bypassing the outbound queue. Documented as the third bypass, alongside `TelegramShutdownNotifier` (3s shutdown budget) and `TelegramSuppressedAggregator` (rate-limit summary).

**Test coverage (31 new tests):**

- `HealthCommandHandlerTest` (22) — happy path with all 9 rows in expected order; payload contains title + every row name; per-component failure isolated (postgres/redis/minio collapses don't break others); per-check 2s timeout enforced via timing assertion (hung Postgres → ≤ 3.5s elapsed even though stub sleeps 8s); 10s overall budget enforced with 6 simultaneous hangs (→ ≤ 4s elapsed); `handle()` never throws even when bot.send throws (plain-text fallback path); CRITICAL incident → DEGRADED classification; FFmpeg near-capacity → DEGRADED; recent errors section appears when buffer has entries, capped at 5, filtered by 1h window; disk under TempDir is UP; severity classification (any DOWN → ERROR, any DEGRADED → WARN, all UP → INFO); `humanBytes` formatting at every scale; logger truncation in error rendering; **parallel execution proven**: two 800ms checks complete in ≤ 1.5s (serial would be ≥ 1.6s).
- `RetainedErrorBufferTest` (9) — newest-first ordering, window filtering, limit cap, capacity eviction, defensive null normalization, concurrent stress (8 writers × 200 events + concurrent reads → no corruption, capacity respected), empty/nonsense inputs return empty list.

### Telegram `/state` Command — Liveness Probe From an Authorized Chat (v1.0.66)

The bot now answers `/state` from any authorized chat with a self-report of host, uptime, version, and current UTC+5 timestamp. An operator can confirm a JVM is alive and identify it (which host, which version) without leaving Telegram.

**Response shape:**

```
🟢 INFO — Server is alive
host      : api-prod-7
uptime    : 3d 14h 22m
version   : 1.0.66
timestamp : 2026-05-07 14:32:11 +0500
```

**Sub-1-second budget.** Every metric is gathered on the polling thread from in-process sources (`RuntimeMXBean`, `BuildProperties`, `InetAddress.getLocalHost`, the clock) — sub-millisecond gather. The Telegram round-trip is the only network call and typically completes in 100–300ms.

**Bypasses the outbound queue.** Interactive command responses can't tolerate the queue's 5-second per-send timeout floor. Sends go directly to `bot.send`, joining `TelegramShutdownNotifier` (3s shutdown budget) and `TelegramSuppressedAggregator` (rate-limit summary) as the third documented bypass path.

**Dispatcher topology.** A new `TelegramCommandDispatcher` parses the first whitespace-delimited token of inbound text (stripping any `@botusername` group-chat suffix) and routes to a registered `TelegramCommandHandler`. The dispatcher is set on `OrientTelegramBot` post-construction via `setCommandDispatcher` so the bot's constructor stays dependency-free. Adding a new command in the future is a one-class change: implement `TelegramCommandHandler`, mark it as a Spring bean — `TelegramBotConfig` collects every handler bean into `List<TelegramCommandHandler>` and the dispatcher's constructor builds the lookup map.

**Always-succeeds guarantee.** The spec demands the response never throws even if a metric collector blows up. Two layers of defense:

- **Per-metric `safeGet`.** Each row (host, uptime, version, timestamp) is wrapped in its own try-catch. A failure collapses to `—` and the rest of the report still goes out.
- **Outer try-catch in `handle()`.** A bug deeper than the metric supplier (formatter, builder, anything unchecked) lands in the outer catch and triggers a fallback plain-text `🟢 Server is alive (state details unavailable)` reply. Silence is worse than a degraded reply — operators rely on `/state` as their liveness probe.

**Authorization.** Inherited from the bot's existing inbound gate: `OrientTelegramBot.onUpdateReceived` rejects unauthorized chat ids before invoking the dispatcher. An attacker who probes the bot with `/state` from a non-allowlisted chat gets the same silent drop as any other unauthorized message — the bot does not advertise its existence.

**Unknown commands.** A command word that no handler claims (typo, foreign bot's slash, probing) is silently ignored — no reply, no log above debug. Same posture as the unauthorized drop.

**Test coverage (35 new tests):**

- `StateCommandHandlerTest` (18) — payload shape, every metric row present, BuildProperties absent → "unknown", per-metric failure → `—`, formatter failure → plain-text fallback, every-metric-failing still produces a coherent report, UTC+5 timestamp regex match, sub-1-second handle latency assertion, four uptime-format variants (days / hours / minutes / sub-minute).
- `TelegramCommandDispatcherTest` (10) — routes to matching handler with parsed args, unknown command silent-ignored, strips `@botname` group suffix, blank/null/non-command text ignored, null chatId ignored, handler exception absorbed, duplicate handler names fail at construction, multi-handler routing, `Map.copyOf` immutability.
- `OrientTelegramBotAuthorizationTest` (3 new) — authorized command dispatched, unauthorized command NOT dispatched (security-critical), no-dispatcher-wired bot still no-ops cleanly.
- `TelegramBotConfigConditionalsTest` (1 new) — when `telegram.bot.enabled=true`, the dispatcher and `StateCommandHandler` beans are present and `/state` is in the dispatcher's handler map.

### Telegram Outbound Queue — Bounded, Single-Consumer, With Retry (v1.0.65)

All Telegram broadcasts now flow through a bounded `LinkedBlockingDeque(1000)` consumed by a single dedicated thread (`telegram-sender`). Producers — log appender, exception handler, anywhere a `notifier.broadcastMarkdown` lands — submit and return immediately; the network round-trip happens off the request path.

**Topology:**

```
[N producer threads]                [1 consumer thread]            [Telegram API]
       │                                    │                              │
       │  enqueue(msg)                      │                              │
       ├──────────────►  LinkedBlockingDeque(1000) ────────► poll ─────────┤
       │  (non-blocking)                    │              5s timeout      │
       │                                    │                              │
       │                                    │   on failure: schedule retry │
       │                                    │   on retryScheduler with     │
       │                                    │   exponential backoff (1/2/4s)
       │                                    │                              │
       │                                    │   after 3 attempts →         │
       │                                    │   write to fallback file     │
```

**Capacity 1000.** When the deque is full, the producer drops the **oldest non-critical** message (severity != `FATAL`) to make room. Producers never block. If every entry in the deque is `FATAL` (rare — would require ≥1000 unread fatal incidents queued), the incoming message is dropped instead and routed to the fallback file. Verified by `enqueue_overflow_dropsOldestNonCritical` (asserts producer returns in < 200ms even at full capacity) and `enqueue_overflowAllFatal_routesIncomingToFallback`.

**5-second send timeout.** Each `bot.send` runs on a tiny worker pool with the consumer thread awaiting its `Future.get(5, SECONDS)`. On timeout the future is cancelled and the message moves to the retry path same as any other failure. The consumer thread is bounded to a 5s-per-message latency floor regardless of network state. Locked by `sendWithTimeout_blocksFiveSecondsThenFails`.

**Retry policy.** Up to **3 attempts** total (initial + 2 retries) with exponential backoff: **1s → 2s → 4s** (verified via `backoffSchedule_oneTwoFourSeconds`). Per-chat partial success is preserved — the retry only re-targets the chats that failed on the previous attempt. After 3 attempts, remaining chats are written to the fallback file.

**Fallback file.** Default `logs/telegram-undelivered.log`, override with `telegram.bot.fallback-file`. One JSON line per undelivered message:

```
{"timestamp":"2026-05-06T17:42:18.512Z","reason":"max retry attempts (3) exceeded",
 "severity":"ERROR","attempts":3,"chatIds":[100],"preview":"🔴 *ERROR* — *HTTP 500…"}
```

Hand-rolled JSON keeps the queue free of an `ObjectMapper` dependency. The `preview` is truncated to 500 chars so the file stays grep-friendly even after a long outage.

**Per-message tracking.** `OutboundMessage` is a record with `text`, `parseMode`, `severity`, `remainingChatIds`, and `attempt` — immutable, with `withAttempt(n)` and `withRemaining(set)` for the retry path. The consumer iterates `remainingChatIds`, tracks the per-chat success set, and shrinks `remainingChatIds` to just the failures before scheduling the retry. Verified by `processMessage_partialFailure_retriesOnlyFailedChats` (chat 100 succeeds first attempt, chat 200 fails first then succeeds — assertions: `bot.send("100", …)` called once, `bot.send("200", …)` called twice).

**FATAL protection.** The drop-oldest scan skips FATAL entries; only non-FATAL messages are eligible for eviction. Locked by `enqueue_overflow_protectsFatalEntriesFromEviction`.

**Bypass paths.** Two bots-direct paths intentionally skip the queue:

1. **`TelegramShutdownNotifier`** — JVM-exit budget is 3 seconds total; the queue's 5s-per-send latency would breach it. Shutdown writes the clean-shutdown Redis key first (so gap analysis still works), then races a parallel `bot.send` per chat with a `CompletableFuture.allOf().get(3s)` budget. Documented exception.
2. **`TelegramSuppressedAggregator`** — the rate-limit summary itself; if it went through the queue and the queue was throttled, the summary about throttling would itself be throttled. Bypasses to `bot.send` directly.

Everything else — `TelegramLogForwarder` (per WARN/ERROR), `GlobalExceptionHandler` (HTTP 500), `TelegramStartupNotifier` (boot message), `TelegramSuppressedAggregator`'s upstream rate-limited messages — flows through the queue.

**Wiring change.** `EnabledTelegramNotifier`'s constructor changed: `(props, rateLimiter, outboundQueue)` instead of `(bot, props, rateLimiter)`. The notifier no longer holds a bot reference at all — its job is to gate (auth + rate limit), wrap into `OutboundMessage`, and enqueue. The `TelegramOutboundQueue` owns the bot and is the single network exit point.

**Bean lifecycle.** Registered in `TelegramBotConfig` as `@Bean(initMethod = "start", destroyMethod = "stop")` so threads spin up at context-ready and shut down cleanly on context close. Daemon threads (`telegram-sender`, `telegram-send-timeout`, `telegram-retry`) — won't keep the JVM alive on shutdown.

**Tunables.**

| Property | Default | Purpose |
|----------|---------|---------|
| `telegram.bot.fallback-file` | `logs/telegram-undelivered.log` | Where the consumer dumps after-3-retries failures |

Constants in code: `CAPACITY=1000`, `MAX_ATTEMPTS=3`, `SEND_TIMEOUT=5s`. Backoff: `2^(attempt-1)` seconds, capped at 60s.

**Tests** (12 new):

- `TelegramOutboundQueueTest` — 12: enqueue below capacity accepted; **overflow drops oldest non-critical, producer non-blocking** (latency assertion); overflow with all FATAL routes incoming to fallback file with correct JSON line; FATAL entries protected from eviction; successful end-to-end send via consumer (no retry); partial failure retries only failed chats (asserts 100 sent once, 200 sent twice); 3 failures → fallback file written with `"max retry attempts"` reason; **5s timeout enforced** when bot hangs (timing assertion 4.9–6.5s); backoff schedule 1/2/4s; fallback line format contains all required JSON fields; null/empty chatIds skipped silently

Existing tests (`EnabledTelegramNotifierTest`, `TelegramLogForwarderTest`, `TelegramStartupNotifierTest`, etc.) refactored to verify `outboundQueue.enqueue(...)` instead of `bot.send(...)` — the notifier is now a producer, not a sender.

### Telegram Rate Limiter + Suppression Summary (v1.0.64)

Sliding-window rate limiter wired in front of the Telegram broadcast path so a logspam burst can't flood the channel. Two windows enforced together:

| Window | Limit | Backing |
|--------|-------|---------|
| Global | **30 messages / 1 minute** across the whole bot | Redis sorted set `telegram:rate:global` |
| Per-message-hash | **5 identical messages / 5 minutes** | Redis sorted set `telegram:rate:msg:{hash}` (16-char SHA-256 prefix) |

Each acquire prunes scores older than the window via `ZREMRANGEBYSCORE`, counts via `ZCARD`, then appends a new score-keyed entry — that's a true sliding window across replicas (not a fixed bucket). Member format is `{epochMillis}-{uuid}` so concurrent calls in the same millisecond don't collide and lose entries.

**Severity bypass.** `Severity.FATAL` short-circuits both windows and never touches Redis — these are too important to silently drop. Every other severity (and the no-severity overload of `broadcastMarkdown`) counts toward the limits.

**Redis-first, in-memory fallback.** When the Redis call throws (network blip, connection refused, server gone), the limiter degrades to a per-instance `InMemoryFallback` — same window semantics via `ArrayDeque<Long>` + a `ReentrantLock`, but state isn't shared across replicas and resets on JVM restart. Better than failing closed (no messages flow) or failing open (flooding allowed). Verified by `redisException_fallsBackToInMemoryLimiter` and `inMemoryFallback_perHashLimit_alsoEnforced` — both stub Redis to throw and confirm the in-memory path enforces the same caps.

**Suppression summary.** When a request is denied, the limiter increments two Redis hashes:
- `telegram:suppressed:counts` — `{messageHash → count}`
- `telegram:suppressed:samples` — `{messageHash → first-line label}` (only the first sample per hash is kept, so the summary surfaces a stable identifier instead of churning per-occurrence)

`TelegramSuppressedAggregator` runs `@Scheduled(fixedDelay=5min)`, reads both hashes, builds a Markdown summary, sends it directly via `bot.send` (bypassing the rate limiter to avoid recursion), and clears the keys. Format:

```
🟡 *WARN* — *Suppressed messages summary*
47 messages suppressed in last 5 minutes
Top: NullPointerException in DeviceController (32 occurrences)
```

The "top error" label is extracted from the message's first non-empty line via `sampleFrom()`, which strips the severity-emoji prefix (`🔴 *ERROR* — `) so the summary surfaces a clean title — already labeled `WARN` itself, redundant severity tags would clutter the output.

**Wiring.**

- `broadcastMarkdown(String)` and `broadcastMarkdown(String, Severity)` added to the `TelegramNotifier` domain port. The single-arg overload defaults to `Severity.INFO` (rate-limited, not bypass).
- `EnabledTelegramNotifier.broadcastMarkdown` consults the limiter before per-chat dispatch — one limiter check per logical broadcast, regardless of how many chats receive it.
- `GlobalExceptionHandler` (HTTP 500 forward) passes `Severity.ERROR` — bursty 500s during incidents get aggregated into the 5-minute summary instead of flooding.
- `TelegramLogForwarder` now routes through `notifier.broadcastMarkdown(chunk, severity)` instead of looping `bot.send` per chat — the rate limiter sees one decision per log event, the per-chat fan-out happens inside the notifier.
- The startup / shutdown notifiers and the suppression-summary itself bypass the limiter (use `bot.send` directly) — these are bounded and must always go through.

**Configuration.**

| Property | Default | Purpose |
|----------|---------|---------|
| `telegram.bot.suppressed-flush-ms` | `300000` (5min) | Aggregator drain cadence |
| `telegram.bot.suppressed-flush-initial-ms` | `300000` | First-flush delay after boot |

Constants in code: `GLOBAL_LIMIT=30`, `GLOBAL_WINDOW=1m`, `PER_HASH_LIMIT=5`, `PER_HASH_WINDOW=5m`. Adjust there if your operational tolerance differs — these are the spec-mandated values.

**Tests** (12 + 7 = 19 new):

- `TelegramRateLimiterTest` — 12: FATAL bypasses (never touches Redis); under both limits returns ALLOWED + records prune→count→add ordering; global limit reached → SUPPRESSED + counter increment + sample stored; per-hash limit (global under) → SUPPRESSED; distinct messages under per-hash cap → all ALLOWED; **Redis exception → in-memory fallback enforces the global cap** (31st request suppressed); in-memory per-hash cap enforced too (6th identical → suppressed); `sampleFrom` strips severity prefix and Markdown markers; SHA-256 hashing is stable + 16 hex chars; recordSuppressed Redis exception swallowed; null `zCard` treated as empty
- `TelegramSuppressedAggregatorTest` — 7: empty entries → no send; not-registered → no send; empty allow-list → drains keys but no send; builds summary with total + top offender + WARN emoji; missing sample for top hash → "(unknown)"; corrupt count value → skipped, summary built from rest; Redis exception swallowed silently

**Layering note.** `TelegramRateLimiter` is no longer `@Component`-annotated — it's wired as a `@Bean` in `TelegramBotConfig`, gated by `@ConditionalOnProperty(enabled=true)`, with `StringRedisTemplate` injected via `ObjectProvider` so test slices that don't load Redis still work. The limiter's constructor accepts a possibly-null Redis template and uses the in-memory path when null — keeps the API/test surface clean.

### TelegramMessageBuilder — Consistent Formatting Utility (v1.0.63)

A fluent builder that consolidates Telegram-Markdown formatting for the bot subsystem. Lives in `uz.orientadvertise.services.common.telegram` (NOT `infra.telegram`) so the api-side `GlobalExceptionHandler` can use it directly — strict layering keeps `api` out of `infra` at compile time, and the builder is pure utility code with no Spring/Telegram-library dependencies.

**Severity emojis** (locked against the spec):

| Severity | Emoji | Label |
|----------|-------|-------|
| `INFO` | 🟢 | bold `*INFO*` |
| `WARN` | 🟡 | bold `*WARN*` |
| `ERROR` | 🔴 | bold `*ERROR*` |
| `FATAL` | ⚫ | bold `*FATAL*` |

Header format: `🔴 *ERROR* — *HTTP 500 — Unhandled exception*` (severity emoji + bold label + em-dash + bold title).

**Fluent API:**

```java
List<String> chunks = TelegramMessageBuilder.builder()
    .severity(Severity.ERROR)
    .title("HTTP 500 — Unhandled exception")
    .kvBlock(Map.of(                       // aligned key:value table inside ``` fence
        "method", "POST",
        "path",   "/service/x",
        "user",   "alice"))
    .codeBlock("stack", stackTraceText)    // bold header + ``` fence, content NOT escaped
    .text("free-form prose")               // markdown-escaped paragraph
    .section("Header", "body")             // bold header + escaped body
    .buildChunks();                        // returns List<String>, each ≤ 4096 chars
```

**Per-field truncation.** Every input string is hard-capped at **500 characters** with a trailing `…` ellipsis before any other processing — bounds the size of a single section even when the caller passes in a multi-MB stack trace. The KV table aligns keys to the longest key's width.

**Markdown escaping.** Plain-text fields (title, prose, key-value labels, section headers/bodies) are escaped for Telegram's legacy `Markdown` mode — backslash-prefix on `_ * ` `[`. Code-block contents are NOT escaped (Telegram renders them literally), but a triple-backtick that would close the fence prematurely is split with a space.

**4096-char chunking.** `buildChunks()` packs sections greedily. The header (severity + title) goes on the first chunk only; subsequent chunks open with `(continued …)` so the operator can tell they're a follow-up. Defensive: a single section that itself exceeds 4096 (after the per-field 500 truncation, this shouldn't happen) is hard-clipped at the chunk boundary with the standard ellipsis.

**Migrated call sites** — all four notifiers now go through the builder, replacing four ad-hoc `String.format` patterns:

| Site | Severity | Sections |
|------|----------|----------|
| `TelegramStartupNotifier` | `INFO` | kvBlock(host, version, environment, duration, previous, timestamp) |
| `TelegramShutdownNotifier` | `INFO` | kvBlock(host, uptime, reason, timestamp) |
| `TelegramLogForwarder` (per WARN/ERROR event) | mapped from level | text(message) + section("exception", …) + codeBlock("stack", …) |
| `GlobalExceptionHandler` (HTTP 500) | `ERROR` | kvBlock(method, path, user, correlation, exception, message) + codeBlock("stack", …) |

The log forwarder and the 500 handler now call `buildChunks()` and loop the broadcast, so a long stack trace (or a flood of metadata) splits into back-to-back messages instead of being silently truncated by Telegram.

**Tests** (16 new):

- `TelegramMessageBuilderTest` — 16: severity emoji mapping; header rendering; markdown escape rules across `_ * ` `[`; `null` safety; 500-char truncation produces exactly 500-char output with ellipsis; title truncation; text escaping preserves line breaks; KV block alignment + key padding; KV code-block content NOT escaped; codeBlock with bold header; triple-backtick neutralized inside content; small payload produces 1 chunk; large payload produces 2+ chunks each ≤ 4096; empty builder returns empty string/list; section null-body renders header only; section escapes both header and body; integration test against a realistic 500-style payload

Existing tests in `TelegramStartupNotifierTest`, `TelegramShutdownNotifierTest`, and `TelegramLogForwarderTest` updated for the new header format (severity emoji + bold label + em-dash + bold title) and the new column-width semantics (longest key in the KV table drives padding for the rest).

### GlobalExceptionHandler → Telegram for Unhandled 500s (v1.0.62)

The `Exception.class` catch-all in `GlobalExceptionHandler` now forwards genuinely unhandled exceptions to Telegram with the full request context. Every other handler (validation 400, not-found 404, forbidden 403, conflict 409, rate-limit 429, etc.) deliberately does NOT broadcast — those are expected client outcomes, and Telegraming them would drown operators in noise.

**Payload shape** (Telegram Markdown):

```
🔥 *HTTP 500 — Unhandled exception*
\`\`\`
method      : POST
path        : /api/devices/42/actions
user        : alice
correlation : 8b313ff3-d27d-4f18-98ab-66c8eb4bf016
exception   : java.lang.RuntimeException
message     : simulated db connection lost
stack:
  at uz.orientadvertise.services.service.DeviceActionService.issueAction(DeviceActionService.java:67)
  at uz.orientadvertise.services.api.controller.DeviceController.issueAction(DeviceController.java:206)
  at jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
  at java.base/java.lang.reflect.Method.invoke(Method.java:580)
  at org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:258)
  ... (47 more)
\`\`\`
```

| Field | Source |
|-------|--------|
| `method` / `path` | `HttpServletRequest` (Spring auto-injects into the `@ExceptionHandler` method) |
| `user` | `SecurityContextHolder` — username for JWT principals, 8-char prefix for `X-API-Key` clients, `anonymous` when unauth, `?` if the lookup itself throws |
| `correlation` | The same `UUID` returned in the HTTP response body — operators paste it into log search to find the full trace |
| `exception` / `message` | `e.getClass().getName()` and `e.getMessage()` (truncated at 500 chars to leave room within Telegram's 4096-char per-message ceiling) |
| `stack` | First **5 frames** + `… (N more)` tail — same convention as the `TelegramAppender` |

**Selective forwarding** — only the catch-all 500 handler invokes Telegram. The full 4xx ladder bypasses it:

| Status | Handler method | Telegram? |
|--------|---------------|-----------|
| 400 | `handleValidation`, `handleConstraintViolation`, `handleMethodValidation`, `handleMissingParam`, `handleUnreadable`, `handleIllegalArgument`, `handleTypeMismatch`, `handleInvalidUpload` | **No** |
| 401 | `handleAuthentication` | **No** |
| 403 | `handleAccessForbidden`, `handleAccessDenied` | **No** |
| 404 | `handleNotFound`, `handleNoResourceFound` | **No** |
| 405 | `handleMethodNotSupported` | **No** |
| 409 | `handleIllegalState` | **No** |
| 429 | `handleRateLimit` | **No** |
| 503 | `handleStorageUnavailable` | **No** |
| **500** | **`handleUnexpected` (catch-all)** | **Yes** |

**Async dispatch.** The broadcast runs on `auditExecutor` via `CompletableFuture.runAsync` so a slow Telegram API doesn't add latency to the (already failing) 500 response. Even if Telegram is completely unreachable, the dispatch returns to the request thread immediately; the worker silently logs at debug if it can't deliver. `auditExecutor` is configured to drop on overflow, so a logspam burst can't queue up unbounded.

**Fail-safe wiring.** Both deps are injected via `ObjectProvider`:

- `ObjectProvider<TelegramNotifier>` — falls back to no-op when no notifier bean exists
- `@Qualifier("auditExecutor") ObjectProvider<Executor>` — falls back to no-op when no auditExecutor bean exists

This keeps the existing `@WebMvcTest` slices working — they don't load the infra module, so neither bean is present in their context, and the handler simply skips the broadcast. Production has both beans wired and works as designed.

**Defensive layers** (any one suffices):

1. Telegram disabled (`telegram.bot.enabled=false`) → `NoOpTelegramNotifier.broadcastMarkdown` is a logged no-op
2. Notifier bean absent in test slices → `ObjectProvider.getIfAvailable()` returns null → `forwardToTelegram` returns early
3. Empty `authorizedChatIds` → `EnabledTelegramNotifier.broadcastMarkdown` skips at the impl level
4. `bot.send` throws for one chat → per-chat try/catch in the impl moves to the next chat
5. Telegram broadcast throws back at the handler → outer `try/catch` in `forwardToTelegram` swallows; the HTTP 500 still flows normally

**Operational invariant** locked by `payload_correlationId_matchesResponseCorrelationId`: the correlation ID in the Telegram payload **equals** the one in the JSON response body. Operators copy it from Telegram and paste it directly into log search to find the full trace and bound context.

**Tests** (7 new + 5 broadcast-impl tests = 12):

- `GlobalExceptionHandlerTest` — 6: 500 forwards with all required fields; authenticated user shows in payload (`alice`); 400/404/401 do NOT forward; Telegram failure does not break the HTTP response (response still has correlationId, status still 500); correlation ID matches between response body and Telegram payload
- `EnabledTelegramNotifierTest` — 4 new: broadcastMarkdown sends to all authorized chats; not-registered drops silently; empty allow-list no-op; per-chat failure doesn't stop others
- `NoOpTelegramNotifierTest` — 1 new: broadcastMarkdown silently drops any input

**`SyncExecutorConfig`** test helper (in `GlobalExceptionHandlerTest`) supplies a synchronous `auditExecutor` bean via `@TestConfiguration` so assertions fire after the broadcast completes — avoids `Mockito.timeout()` polling and the wall-clock noise it would add to the suite.

### Telegram Bot — Logback Appender for WARN/ERROR (v1.0.61)

WARN and ERROR log events are forwarded to Telegram via a custom Logback `AppenderBase` so operators get the same visibility through Telegram that they would have on a tail.

**Wiring** — `api/src/main/resources/logback-spring.xml`:

```xml

<appender name="TELEGRAM" class="uz.orientadvertise.services.infra.telegram.TelegramAppender">
    <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
        <level>WARN</level>
    </filter>
</appender>
<root level="INFO">
<appender-ref ref="CONSOLE"/>
<appender-ref ref="TELEGRAM"/>
</root>
```

**Format** (Markdown):

```
🔴 *ERROR* — `uz.orientadvertise.services.service.IncidentService`
Failed to publish critical incident [id=42]: connection refused

java.net.ConnectException: connection refused
\`\`\`
  at java.base/sun.nio.ch.Net.pollConnect(Native Method)
  at java.base/sun.nio.ch.Net.pollConnectNow(Net.java:682)
  at java.base/sun.nio.ch.SocketChannelImpl.finishConnect(SocketChannelImpl.java:946)
  at io.lettuce.core.protocol.ConnectionWatchdog.run(ConnectionWatchdog.java:99)
  at io.netty.util.concurrent.AbstractEventExecutor.runTask(AbstractEventExecutor.java:174)
  ... (12 more)
\`\`\`
```

- Emoji: `🔴` for ERROR, `⚠️` for WARN, `ℹ️` fallback for anything else (shouldn't reach the appender thanks to the threshold filter)
- Bold level + backtick-quoted logger name in the header
- Exception class line when present
- Stack trace truncated to **first 5 frames**, with a "... (N more)" tail

**Static buffer + Spring forwarder.** Logback initializes long before the Spring context — events emitted during DB connect, Flyway migrations, etc. would otherwise be lost. The appender writes into a `ConcurrentLinkedDeque<TelegramLogEvent>` field on the class itself (bounded at **500** entries; oldest dropped on overflow). A separate `TelegramLogForwarder` Spring bean polls the buffer once the bot is registered:

| Trigger | Behavior |
|---------|----------|
| `notifier.isRegistered() == false` | Buffer accumulates; nothing sent. Pre-init events stay queued for later replay |
| Empty `authorizedChatIds` | Buffer is drained and discarded each tick (no recipients, no growth) |
| Registered + chats present | Up to **20 events per tick** dispatched in parallel to all chats; remainder waits for next tick |
| Buffer at 500 capacity | Oldest dropped, `droppedCount` counter incremented (visible via static getter for an actuator if needed) |

`@Scheduled(fixedDelayString = "${telegram.bot.log-forward-delay-ms:2000}", initialDelayString = "${telegram.bot.log-forward-initial-delay-ms:5000}")` — 2s drain cadence, 5s initial delay so startup-storm logs settle before the first dispatch attempt.

**Recursion prevention** — three layers, each catching what the previous misses:

1. **Logger name prefix filter** — events from `org.telegram.*` and `uz.orientadvertise.services.infra.telegram.*` are dropped at the appender. The Telegram client itself logging at WARN won't trigger another send.
2. **Logback level floor** in `logback-spring.xml` — `<logger name="org.telegram" level="ERROR"/>` and `<logger name="uz.orientadvertise.services.infra.telegram" level="WARN"/>` raise the threshold for those packages so noisy-but-harmless events never reach the appender at all. Defense-in-depth if a maintainer ever shrinks the prefix list above.
3. **Thread-local `SENDING` flag** — the forwarder sets it true around `bot.send` calls. If anything (OkHttp, Lettuce inside a Telegram timeout, Spring's HTTP client) logs an error from the same thread mid-dispatch, the appender short-circuits regardless of logger name.

Verified by `TelegramAppenderTest.recursiveLoggerName_*` and `threadLocalSendingFlag_dropsRecursiveLogs`, plus the integration scenario in `TelegramLogForwarderTest.sendingFlag_setDuringDispatch_clearedAfter` which stubs `bot.send` to enqueue a recursive event mid-flight and asserts the buffer is empty afterward.

**"Never crash the logger."** Every call into the appender's `append()` is wrapped in `try { ... } catch (Throwable t) { addError(...) }`. Format failures, queue overflow, ThreadLocal anomalies — all swallowed locally. A bug in the Telegram subsystem cannot poison the application's primary logging path.

**Tests** (10 + 9 = 19 new):

- `TelegramAppenderTest` — 10: WARN buffered with `⚠️`; ERROR with `🔴`; INFO filtered out (defensive level check); recursive logger name from `org.telegram.*` dropped; recursive logger from our own telegram package dropped; thread-local `SENDING` flag drops re-entry; exception captured + stack truncated to 5 lines with "... N more" suffix; no exception → null fields; buffer overflow drops oldest + counts; null logger name doesn't throw
- `TelegramLogForwarderTest` — 9: not-registered keeps events buffered; registered drains & sends to each chat; empty authorized set discards (prevents unbounded growth); per-tick cap of 20 events × 2 chats = 40 sends with remainder kept for next tick; sending flag cleared after dispatch; Markdown rendering includes header / message / exception / fenced stack; no-exception payload omits the code fence; null message → `(no message)` placeholder

**Tunables** (in `application.yml`):

| Property | Default | Effect |
|----------|---------|--------|
| `telegram.bot.log-forward-delay-ms` | `2000` | Drain cadence — how often the forwarder checks the buffer |
| `telegram.bot.log-forward-initial-delay-ms` | `5000` | Delay before the first drain attempt — gives bootstrap noise time to settle |

### Telegram Bot — Shutdown Notification + Hard-Kill Detection (v1.0.60)

Final piece of the bot lifecycle: a graceful-shutdown message and a way to detect the kills that don't get a chance to send one.

**`TelegramShutdownNotifier`** runs on `@PreDestroy`:

```
*Application shutting down*
\`\`\`
host        : tv-prod-01
uptime      : 4h 17m 3s
reason      : graceful
timestamp   : 2026-05-06 18:59:21 +05
\`\`\`
```

- `reason` is `"graceful"` by default. A best-effort JVM shutdown hook (registered in `@PostConstruct`) flips it to `"graceful (SIGTERM/SIGINT)"` when the shutdown was externally signalled (e.g. `docker stop`); a programmatic `context.close()` keeps the plain `"graceful"` label. The hook may race with Spring's own shutdown hook — if it loses the race, the default label still ships, which is accurate either way.
- `uptime` is from `RuntimeMXBean.getUptime()`, formatted as `Xh Ym Zs`.

**Hard 3-second budget.** Telegram's API can be slow or unreachable during shutdown — but the JVM is leaving, and we cannot block context close indefinitely.

- Every authorized chat is dispatched in parallel via `CompletableFuture.supplyAsync` on a small daemon executor
- The whole batch is awaited with `allOf(...).get(3, SECONDS)`
- On `TimeoutException`: in-flight futures cancel, partial-delivery count is logged, the executor is `shutdownNow()`'d, and the JVM proceeds with exit
- Verified by `TelegramShutdownNotifierTest.shutdown_slowSend_terminatesWithin3sBudget` — stubs `bot.send` to sleep 10s and asserts the whole `shutdown()` call returns under 5s

**Hard-kill detection (the `SIGKILL` / OOM / crash case).** `@PreDestroy` doesn't fire on those; the next startup detects them via gap analysis between two Redis keys:

| Key | Writer | Cadence |
|-----|--------|---------|
| `telegram:last-heartbeat` | `TelegramHeartbeat` | every 30s while running, 5-min TTL |
| `telegram:last-clean-shutdown` | `TelegramShutdownNotifier.@PreDestroy` | once on graceful exit, 7-day TTL |

On the next startup, `TelegramStartupNotifier` reads both and adds a `previous` line to the boot message:

| Comparison | Label |
|-----------|-------|
| Both keys missing | `previous    : first run` |
| `last-clean-shutdown ≥ last-heartbeat` | `previous    : clean (Ns ago)` |
| `last-heartbeat > last-clean-shutdown` (or shutdown key missing) | `previous    : UNCLEAN (last seen Ns ago — likely SIGKILL/OOM)` |

The 30-second heartbeat cadence bounds the detection precision — a kill within the last 30s of the previous run reads as "last seen ~30s ago." The 5-minute heartbeat TTL prevents a stale heartbeat from a long-dead JVM corrupting the analysis after a multi-minute outage.

**Ordering invariants:**

1. The clean-shutdown timestamp is written **first** in `@PreDestroy`, before any Telegram network call. Even if the Telegram send hangs and times out, the next start sees the right value.
2. If Redis is already torn down when `@PreDestroy` runs (rare, depends on bean order), the write fails silently. The next boot then sees no clean-shutdown record and reports "unclean" — a minor false-positive, never a missed real one.
3. Empty authorized chat list still writes the clean-shutdown key (so gap analysis on the next boot is correct) but skips the Telegram send (nothing to send to).
4. Bot-not-registered (init failed) skips the Telegram send for the same reason but writes the key — failed init shouldn't lose the clean-shutdown signal.

**`telegram.bot.heartbeat-rate-ms`** (default `30000`) overrides the heartbeat cadence in tests / dev. Spring `@Scheduled(fixedRateString = ...)` reads it at bean creation.

**Tests** (13 new):

- `TelegramShutdownNotifierTest` — 7: clean-shutdown key written + chats notified; empty allow-list still writes key, no send; not-registered still writes key, no send; Redis failure on key write doesn't block send; **slow send terminates within 3s budget**; payload contains host/uptime/reason/timestamp; signal-detected reason overrides default
- `TelegramHeartbeatTest` — 2: writes key with TTL; Redis failure swallowed silently
- `TelegramStartupNotifierTest` — 4 new: gap analysis classifies first-run / clean / unclean (heartbeat > shutdown) / unclean (shutdown missing)

### Telegram Bot — Authorization, Async Init, Startup Notification (v1.0.59)

**`telegram.bot.authorized-chat-ids`** — comma-separated list bound to a `Set<Long>` via Spring's `StringToCollectionConverter`. The same allow-list governs both **outbound** (only these chats receive notifications) and **inbound** (only these chats can send commands). Whitespace around commas is trimmed (`"100, 200, -300"` → `{100, 200, -300}`).

| Edge case | Behavior |
|-----------|----------|
| Empty list | Bot still boots — warning logged once at construction; every send is a no-op; every inbound is silently dropped. **No crash.** |
| Unauthorized inbound | Silent ignore — no response, no error log, only a `TRACE` line so probing chat ids don't leak into shared logs. Confirms to the spec: "no response to avoid revealing bot existence." |
| Outbound to unauthorized chat | Dropped at the notifier layer with a debug log — defense-in-depth against a service-layer typo |
| Non-numeric chat id passed to `sendMessage` | Dropped before reaching Telegram |

**Async bot initialization with retry.** Registration with `api.telegram.org` is deferred to `ApplicationReadyEvent` and runs on a one-shot daemon thread (`telegram-init`). The application is fully up before the first registration attempt — a Telegram outage at boot does NOT block startup.

- Up to **5 attempts** with exponential backoff `2s → 4s → 8s → 16s → 32s`
- On success: `EnabledTelegramNotifier.markRegistered()` is called and the startup notification fires
- On terminal failure: `log.error("CRITICAL: Telegram bot registration failed after 5 attempts...")` is emitted with the exception, and `notifyStartupLocally(...)` writes the intended startup payload to the application log so ops still gets the metadata. The application **continues running** — `EnabledTelegramNotifier.isRegistered()` reports false and subsequent sends are dropped at debug level.
- Pre-registration sends (e.g. from a `@PostConstruct` hook firing before init completes) are also dropped at debug; no race condition, no exception.

**Startup notification (`TelegramStartupNotifier`).** On a successful registration, sends one Markdown message to every authorized chat:

```
*Application started*
\`\`\`
host        : tv-prod-01
version     : 1.0.59
environment : prod
duration    : 12s
timestamp   : 2026-05-06 14:42:18 +05
\`\`\`
```

Source of each field:
- `host` — `$HOSTNAME` env, falling back to `InetAddress.getLocalHost().getHostName()`, then `"unknown"`
- `version` — Spring Boot's `BuildProperties` (generated by `springBoot { buildInfo() }` in `api/build.gradle`); falls back to `"unknown"` in raw IDE runs that haven't executed the build task yet
- `environment` — first active Spring profile, then default profile, then `"default"`
- `duration` — `ApplicationReadyEvent.getTimeTaken().toSeconds()`
- `timestamp` — `ZonedDateTime.now(ZoneId.of("Asia/Karachi"))` (UTC+5 per the spec)

**Crash-loop rate limit.** Naive notification on every restart would flood the chat during a crash loop. Implemented as a Redis `SET ... NX EX 60` on key `telegram:startup-cooldown`:

- First successful register within a 60s window → key set, message sent
- Subsequent restarts within the same window → SETNX returns false, message skipped, INFO log says "Startup notification suppressed — within 60s cooldown (likely a restart loop)"
- Redis-backed so the cooldown survives the restart itself (an in-memory counter would reset every boot and defeat the protection)
- Redis exception (transient hiccup) → conservative skip, never the wrong direction (flooding)

**Wiring (changed in this release):**

- `TelegramBotConfig.telegramBot()` constructs `OrientTelegramBot` but **no longer registers it** — the bean factory does no network I/O at all
- New `TelegramBotInitializer` (component, `@ConditionalOnProperty(enabled=true)`) listens for `ApplicationReadyEvent` and runs the registration asynchronously with a configurable `Sleeper` (test-friendly)
- New `TelegramStartupNotifier` (component, same conditional) is the lone consumer of `BuildProperties` (via `ObjectProvider` so it tolerates the bean being absent)
- `EnabledTelegramNotifier` gained a two-state lifecycle: `isRegistered()` flips true only after successful registration; pre-registration sends are dropped at debug

**Tests** (all green):

- `EnabledTelegramNotifierTest` — 8: registered/unregistered drop semantics; authorized vs unauthorized filter; blank/non-numeric chat id; empty allow-list no-throw; Markdown parse-mode passthrough
- `TelegramBotConfigConditionalsTest` — 7: disabled by default omits `TelegramBotsApi`/`OrientTelegramBot`; explicit `enabled=false`; blank token fails fast; blank username fails fast; valid config constructs bot but does NOT register; **empty authorized list does not crash**; whitespace trimming; `isAuthorized` helper
- `OrientTelegramBotAuthorizationTest` — 4: unauthorized inbound silent-dropped; authorized inbound runs cleanly; empty allow-list drops everything; null/missing-message updates handled
- `TelegramBotInitializerTest` — 4: first attempt success marks registered + fires startup notification; transient failure → retry → success; all 5 retries fail → CRITICAL log + `notifyStartupLocally`, app continues; missing startup-notifier bean does not break init
- `TelegramStartupNotifierTest` — 7: cooldown acquired → sends to each chat; cooldown held → skip (crash-loop protection); Redis failure → skip (fail closed); empty allow-list → skip without consuming cooldown; payload format contains all metadata fields; `BuildProperties` absent → `"unknown"` version; `notifyStartupLocally` logs but does not send

### Telegram Bot Integration

Outbound Telegram notifier wired as an infra component, off by default. Service-layer code injects the domain port `TelegramNotifier` and gets either a real bot or a no-op based on a single feature flag — no conditional injection or `if (enabled) ...` boilerplate at the call sites.

**Properties** (bound via `@ConfigurationProperties("telegram.bot")`):

| Key | Env var | Default | Notes |
|-----|---------|---------|-------|
| `telegram.bot.enabled` | `TELEGRAM_BOT_ENABLED` | `false` | Master switch — drives `@ConditionalOnProperty` |
| `telegram.bot.token` | `TELEGRAM_BOT_TOKEN` | *(empty)* | **Env-only.** `application.yml` defaults to the empty string; never commit a real value |
| `telegram.bot.username` | `TELEGRAM_BOT_USERNAME` | *(empty)* | Bot username from BotFather |

**Module layout** — added to the existing `infra` module (no new top-level Gradle module):

```
domain/notification/TelegramNotifier.java          ← port, no infra dependency
infra/telegram/TelegramBotProperties.java          ← @ConfigurationProperties
infra/telegram/OrientTelegramBot.java              ← extends TelegramLongPollingBot
infra/telegram/EnabledTelegramNotifier.java        ← real impl
infra/telegram/NoOpTelegramNotifier.java           ← fallback
infra/telegram/TelegramBotConfig.java              ← @ConditionalOnProperty wiring
```

Dependency `org.telegram:telegrambots:6.9.7.1` is added to `infra/build.gradle` only — `domain`, `service`, and `api` stay free of it.

**Disabled-mode invariant (the security/safety guarantee).**

When `telegram.bot.enabled=false` (or the property is missing):

- `TelegramBotsApi` is **not** constructed → no Telegram HTTP connection opens
- `OrientTelegramBot` is **not** constructed → no token is read, no long-polling thread starts
- `EnabledTelegramNotifier` is **not** wired
- `NoOpTelegramNotifier` is published as the active `TelegramNotifier` via `@ConditionalOnMissingBean` so injection sites still resolve

This is enforced by `@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")` on every Telegram-side bean and verified by `TelegramBotConfigConditionalsTest.disabled_byDefault_wiresNoOpAndOmitsBot` against `ApplicationContext.doesNotHaveBean(TelegramBotsApi.class)`.

**Fail-fast on enabled-but-blank-token.**

If `telegram.bot.enabled=true` but `TELEGRAM_BOT_TOKEN` is missing or blank, the bean factory throws `IllegalStateException` at startup with a clear message:

```
telegram.bot.enabled=true but TELEGRAM_BOT_TOKEN is empty.
Provide the token via env var, or set telegram.bot.enabled=false.
```

A blank `username` triggers a parallel error. This prevents the silent "we thought we'd enabled the bot but it never registered" failure mode where startup succeeds but no Telegram messages ever arrive.

**Token policy.**

- `application.yml` uses `${TELEGRAM_BOT_TOKEN:}` — the property defaults to the empty string, never a real value
- `.env` is gitignored (the `.gitignore` was tightened to match a bare `.env`, since `*.env` does not — only `.env.example` is tracked)
- `docker-compose.yml` passes the value through as `${TELEGRAM_BOT_TOKEN:-}` so the env var hops cleanly from your shell / `.env` file → compose → container, but is empty by default
- The token never appears in `application.yml`, the compose file, the README, or any test fixture

**Usage from service code:**

```java
@Service
public class IncidentService {
    private final TelegramNotifier telegram;

    public IncidentService(TelegramNotifier telegram) { this.telegram = telegram; }

    void notify(Incident i) {
        if (!telegram.isEnabled()) return;        // skip expensive payload formatting
        telegram.sendMessage(adminChatId, format(i));
    }
}
```

The `isEnabled()` check is optional — the no-op also accepts and drops everything — but lets you skip building expensive payloads when the feature is off.

**Tests** (all green):

- `TelegramBotConfigConditionalsTest` — 4: disabled-by-default wires NoOp and omits `TelegramBotsApi`/`OrientTelegramBot`; explicit `enabled=false` matches; `enabled=true` + blank token fails fast at startup; `enabled=true` + blank username fails fast
- `NoOpTelegramNotifierTest` — 2: `isEnabled()=false`; `sendMessage` accepts null/blank/long input without throwing
- `EnabledTelegramNotifierTest` — 3: `isEnabled()=true`; `sendMessage` delegates to bot; blank chatId is dropped before reaching the bot

### Dashboard Live Feed: `WS /ws/dashboard`

Live broadcast channel backing **FE-05**, **FE-14**, and **FE-29**. Distinct from the existing `/ws/devices/{id}` (device-side) and `/ws/admin/incidents` (legacy CRITICAL feed) endpoints — this one carries three event types tailored to the operator dashboard.

**Connect-time snapshot.** On every successful handshake, the handler sends a one-time `SNAPSHOT` frame **before** registering the session for live broadcasts:

```json
{
  "type": "SNAPSHOT",
  "serverTime": "2026-05-08T12:00:00Z",
  "openIncidents": [
    { "id": 42, "deviceId": 7, "eventType": "DEVICE_OFFLINE",
      "status": "OPEN", "priority": "CRITICAL",
      "description": "...", "occurrenceCount": 3,
      "openedAt": "...", "updatedAt": "...",
      "acknowledgedAt": null, "acknowledgedBy": null,
      "resolvedAt": null, "resolvedBy": null }
  ]
}
```

`openIncidents[]` is identical in shape to `GET /api/incidents/open` — same fields, same order. The FE routes both responses through one "set the open-incident list" reducer.

**Ordering guarantee.** Snapshot strictly precedes every live event on the same session. Because the snapshot is sent *before* `sessions.put(...)`, no `INCIDENT_CRITICAL` / `INCIDENT_UPDATED` / `DEVICE_STATUS_CHANGE` broadcast can reach this session before the snapshot does. Verified by `DashboardWebSocketHandlerSnapshotTest.snapshot_isFirstFrameSentToSession`.

**Why this closes the open-list backfill race.** Pre-1.0.85, the FE called `GET /api/incidents/open` on connect to backfill incidents that fired while its tab was closed, then opened the WS for live updates. A delta arriving during the HTTP fetch could be missed (delivered before the WS subscribed) or duplicated (delivered before AND inside the HTTP response). The snapshot frame closes that race: connect-time state and live deltas now flow through the same channel in a strict order, and the FE de-dupes by `incidentId`. See "Frontend Integration Contract → WebSocket endpoints" for the broader auth/URL contract this lives under.

**Best-effort, non-fatal on failure.** If `IncidentService.getOpen()` throws (e.g. transient DB hiccup), or the snapshot send itself fails, the handler logs a warn and continues — the session still registers, live deltas still flow, and the FE backfills via `GET /api/incidents/open` as a fallback. Closing the socket on a snapshot failure would lose live updates over a stale read; that trade-off is intentional. Verified by `DashboardWebSocketHandlerSnapshotTest.snapshot_incidentServiceFailure_doesNotBreakConnection`.

**Three live event payloads** (each tagged with a `type` discriminator the frontend dispatches on, alongside the connect-time `SNAPSHOT`):

```json
{
  "type": "INCIDENT_CRITICAL",
  "incidentId": 42, "deviceId": 7, "eventType": "DEVICE_OFFLINE",
  "status": "OPEN", "priority": "CRITICAL",
  "description": "Auto-created from event: DEVICE_OFFLINE",
  "openedAt": "2026-05-06T10:30:00Z", "updatedAt": "2026-05-06T10:30:00Z",
  "actor": null
}
```

```json
{
  "type": "INCIDENT_UPDATED",
  "incidentId": 42, "deviceId": 7, "eventType": "DEVICE_OFFLINE",
  "status": "ACKNOWLEDGED", "priority": "CRITICAL",
  "description": "...", "openedAt": "...", "updatedAt": "...",
  "actor": "alice"
}
```

```json
{
  "type": "DEVICE_STATUS_CHANGE",
  "deviceId": 7, "oldStatus": "OFFLINE", "newStatus": "ONLINE",
  "changedAt": "2026-05-06T10:31:00Z"
}
```

```json
{
  "type": "CONTENT_STATUS_CHANGE",
  "contentId": 42, "status": "FAILED",
  "invalidReason": "RuntimeException: minio down",
  "at": "2026-05-06T10:31:00Z"
}
```

`status` is a `ContentFile.Status` — `TRANSCODING`, `READY`, `FAILED` or `INVALID`. `invalidReason`
carries the rejection reason on `INVALID` **and the transcode error on `FAILED`** (v1.0.134 — it used
to be an unconditional `null` on FAILED, so a failed card could not say why); it is `null` for
`TRANSCODING`/`READY`. The same text is persisted, and the listing/detail DTOs expose it as
`transcodeLastError`.

**Per-session routing (server-internal).** Every frame is published to Redis wrapped in a routing
envelope the handler strips before it reaches the socket, so the on-the-wire shapes above are exactly
what a browser sees:

```json
{ "_projectId": 7, "_owner": "alice", "payload": { "type": "CONTENT_STATUS_CHANGE", "…": "…" } }
```

- **ADMIN** captures no project scope at handshake and receives every frame.
- **OPERATOR** receives a frame whose `_projectId` is in their assigned set, **or** whose `_owner` is
  their own username.

The `_owner` term exists because content visibility is **owned ∪ granted**, never project-gated, and
an orphan upload has no project at all — routing content on `_projectId` alone would stop an operator
from seeing their own upload transcode. Before v1.0.134 content frames were published *unwrapped*,
which the handler broadcasts byte-identically to every open session: every operator received every
content id, status and ffmpeg diagnostic. The residual gap is deliberate and small: an operator
holding an admin **grant** on someone else's content outside their project set learns of a status
change on the next listing refetch rather than live.

**Authentication at the handshake.**

`DashboardHandshakeInterceptor` extracts the JWT from the `Authorization: Bearer ...` header (or the `?access_token=...` query param fallback for browser clients that can't set headers). It validates via the existing `TokenValidator` and returns:

- `401` — missing or invalid token
- `403` — valid token but role is not `ADMIN` or `OPERATOR` (VIEWER and ADVERTISER are both rejected at handshake, never reach the handler)

The 401-vs-403 distinction is the reason role enforcement lives at the interceptor rather than via Spring Security's path matchers — the matcher mechanism conflates the two into a generic 401. The `/ws/dashboard` path is `permitAll` in `SecurityConfig` so the interceptor is the single source of auth for this endpoint.

**Single responsibility — broadcast only.**

`DashboardWebSocketHandler` overrides `handleTextMessage` to log-and-discard inbound frames. Operator commands (acknowledge/resolve, device actions, etc.) all go through normal HTTP endpoints; the WebSocket is a one-way fan-out. This is verified by `DashboardWebSocketHandlerTest.handleTextMessage_droppedSilently_noStateChange`.

**Bus + fan-out architecture:**

```
service-layer transition          infra Redis pub/sub          api WebSocket
─────────────────────────         ───────────────────         ──────────────
IncidentService.processEvent ─┐
IncidentService.acknowledge  ─┤    DashboardEventBroadcaster
IncidentService.resolve      ─┼──► (RedisDashboardEventBroadcaster)
IncidentService.autoResolve  ─┤    publishes to                 DashboardEventSubscriber
DeviceHeartbeatService       ─┘    app:dashboard:events    ──► (subscribes, forwards JSON)
                                                                       │
                                                                       ▼
                                                               DashboardPushChannel
                                                               (DashboardWebSocketHandler
                                                                fans out to all sessions)
```

- New domain port `DashboardEventBroadcaster` with three explicit methods (`incidentCritical`, `incidentUpdated`, `deviceStatusChanged`) — type-checked at every call site rather than a single generic `broadcast(Object)`.
- New domain port `DashboardPushChannel` for the api-side consumer (parallel to the existing `IncidentPushChannel` for the legacy feed).
- New Redis topic `app:dashboard:events` registered in `RedisPubSubConfig`.
- `RedisDashboardEventBroadcaster` (infra) hand-rolls JSON with a `type` discriminator. Hand-rolling keeps the infra slim-test slice free of an `ObjectMapper` dependency, matching the existing `RedisCriticalIncidentBroadcaster` pattern.
- `DashboardEventSubscriber` (infra) listens and forwards via `ObjectProvider<DashboardPushChannel>` — when no api is on the classpath (infra-only test slice) the subscriber is a tolerant no-op.

**Service hooks:**

| Transition | Method | Event |
|------------|--------|-------|
| New CRITICAL incident | `IncidentService.processEvent` (priority transition) | `INCIDENT_CRITICAL` |
| Acknowledge incident | `IncidentService.acknowledge` | `INCIDENT_UPDATED` (status=ACKNOWLEDGED, actor=username) |
| Manual resolve | `IncidentService.resolve` | `INCIDENT_UPDATED` (status=RESOLVED, actor=username) |
| Auto-resolve on recovery | `IncidentService.autoResolveOnRecovery` | `INCIDENT_UPDATED` (status=RESOLVED, actor="system") |
| Device status flip | `DeviceHeartbeatService.emitStatusChangeEvent` | `DEVICE_STATUS_CHANGE` |

Every broadcast call is wrapped in a try/catch — pub/sub failures are logged and swallowed so a Redis hiccup never poisons the operational path. Operators see the same data on next page load via `/api/dashboard/summary` and `/api/incidents/open`.

**Coexistence with legacy `/ws/admin/incidents`.** The existing endpoint (1-second-batched CRITICAL feed) keeps its semantics; both fire on a CRITICAL transition. The dashboard endpoint is richer (three event types) and unbatched. Either feed can be retired independently when the frontend cuts over.

**Edge cases (locked by tests):**

- Handshake without token → 401
- Invalid/expired token → 401
- VIEWER role → 403
- ADVERTISER role → 403
- ADMIN/OPERATOR → accepted, username + roles attached to session attributes
- Browser without header support → `?access_token=...` query param accepted as fallback
- Inbound text frame on the WebSocket → silently dropped (broadcast-only)
- Per-session send failure → logged, other sessions still receive the broadcast
- Closed/dead sessions → skipped on every broadcast
- Concurrent broadcasts → per-session synchronization on `sendMessage` (Spring's raw WebSocketSession is not thread-safe on send)

**Tests** (all green):

- `DashboardHandshakeInterceptorTest` — 7: no token 401, invalid 401, VIEWER 403, ADVERTISER 403, ADMIN allowed, OPERATOR allowed, query-param fallback
- `DashboardWebSocketHandlerTest` — 7: connect with username, no-principal closes, disconnect removes, push to all, skip closed, one failing doesn't block others, inbound dropped
- `IncidentServiceDashboardBroadcastTest` — 5: new CRITICAL fires `incidentCritical`, acknowledge fires `incidentUpdated` (status=ACKNOWLEDGED, actor=username), manual resolve fires `incidentUpdated` (RESOLVED), auto-resolve fires with actor=`system`, broadcast failure does not break the call

### Dashboard Summary: `GET /api/dashboard/summary`

Single-shot aggregation backing **FE-11** and **FE-12**. Roles: `ADMIN` / `OPERATOR` / `VIEWER`. Advertisers are 403'd — global counts would leak tenant state.

**Response shape (always populated, never null):**

```json
{
  "totalDevices": 12,
  "onlineCount": 8,
  "offlineCount": 3,
  "noContentCount": 1,
  "openIncidents": { "critical": 2, "warning": 5 },
  "regionSummary": [
    { "regionId": 1, "regionName": "Karachi", "onlineCount": 5, "totalCount": 7 },
    { "regionId": 2, "regionName": "Lahore",  "onlineCount": 3, "totalCount": 5 },
    { "regionId": 3, "regionName": "Quetta",  "onlineCount": 0, "totalCount": 0 }
  ]
}
```

**Edge case contracts (locked by tests):**

- **Region with 0 devices appears in `regionSummary` with both counts = 0.** The repository uses a `LEFT JOIN` from `Region` to `Device` so empty regions still produce a row. The service additionally coerces `null` count columns (which a LEFT JOIN can yield when no rows match) to `0` before serialization — so the response carries integers, not nulls.
- **All count fields default to 0, never null.** Status buckets are pre-populated for every `Device.Status` value before the GROUP BY rows are merged, so missing buckets become `0` automatically. Same pattern for `OpenIncidentCounts.{critical, warning}`.
- **WARNING maps to `Event.Priority.HIGH`.** The dashboard surfaces only CRITICAL and WARNING; lower priorities (MEDIUM/LOW/INFO) are aggregated by the query but ignored when building the response. Documented in `DashboardServiceTest.openIncidents_splitByCriticalAndWarning` against an explicit MEDIUM row that must NOT bleed into either field.
- **Region ordering preserved from the repository** (`ORDER BY name ASC`). The service does not re-sort, so the frontend can rely on alphabetical order.

**Caching (Redis, 30s TTL):**

`@Cacheable("dashboard", key="'summary'")` with the cache configured in `RedisConfig` (`prefixCacheNameWith("dashboard:")`, `entryTtl=30s`). Single-key cache (no per-user variation — the dashboard is global) so a single Redis entry serves the whole ops team.

**Pre-compute via the existing health-check job.** The `DeviceHealthMonitor` Quartz job (5-minute cadence) calls `dashboardService.invalidate()` whenever it emits any new offline or content-mismatch events. This is the "pre-compute via the existing incident detection job" path from the spec — the cache catches up to a status flip immediately after a scan, instead of serving a stale snapshot for up to 30 more seconds. When the scan emits nothing (nothing changed), the eviction is skipped to keep the cache warm.

**Query cost:**

Three lightweight aggregation queries — `O(distinct statuses)`, `O(regions)`, `O(distinct priorities)`. Total complexity bounded by the product cardinalities, not the device count, so the endpoint stays cheap even at a 100k-device scale. The 30s TTL absorbs the 10-user ops team's natural refresh cadence without hammering the DB.

**Tests** (all green):

- `DashboardServiceTest` — 8: everything-empty zero-fill; status counts populated; partial status buckets zero-fill rest; CRITICAL/WARNING split; only CRITICAL present; zero-device region appears with null SUM coerced to 0; ordering preserved; `invalidate()` no-op when called outside a Spring proxy
- `DashboardControllerTest` — 5: full response shape with zero-device region; ADMIN/OPERATOR/VIEWER allowed; ADVERTISER 403; unauthenticated 401

### OpenAPI / Swagger UI

Springdoc auto-generates the OpenAPI 3.1 spec from controller code. The Swagger UI is mounted at `/swagger-ui.html`; the raw spec at `/v3/api-docs`.

**Per-module groups.** The 100+-endpoint surface is split into module groups (visible as a selector in the Swagger UI top-bar):

| Group | URL | Path patterns |
|-------|-----|---------------|
| `auth` | `/v3/api-docs/auth` | `/auth/**` |
| `devices` | `/v3/api-docs/devices` | `/api/devices/**`, `/api/device-groups/**` |
| `content` | `/v3/api-docs/content` | `/api/content/**`, `/api/files/**`, `/api/assignments/**`, `/api/schedules/**` |
| `reports` | `/v3/api-docs/reports` | `/api/reports/**`, `/api/stats/**`, `/api/events/**`, `/api/incidents/**` |
| `admin` | `/v3/api-docs/admin` | `/api/admin/**`, `/api/users/**` |
| `external` | `/v3/api-docs/external` | `/api/external/**` (X-API-Key) |

**Two security schemes.** The top-level OpenAPI declares both `bearerAuth` (HTTP/JWT) and `apiKeyAuth` (`X-API-Key` header). Operations inherit the global `bearerAuth` requirement; `ExternalDeviceController` overrides this with `@SecurityRequirement(name = "apiKeyAuth")`. The Swagger UI Authorize dialog therefore shows the right input depending on which group is active.

**ADMIN-only access in production.** `app.openapi.admin-only` (default `true`) gates both `/v3/api-docs/**` and `/swagger-ui/**` behind `ROLE_ADMIN`. The dev/test profiles override it to `false` so docs are browseable without auth during development. The check is implemented as a Spring Security `.access(...)` lambda — when the switch is on, anonymous requests get `401` from the global authentication entry point; non-admin authenticated users get `403`. Two tests (`SwaggerAccessTest`, `SwaggerLockedDownTest`) lock both modes against regression.

**Sensitive endpoints clearly marked.** A `@SensitiveEndpoint` composed annotation (`@Tag(name = "Sensitive")`) groups every operation that mutates production state, exposes protected data, or performs irreversible deletes. The Swagger UI surfaces them as a dedicated `Sensitive` section. Each annotated endpoint also prefixes its `@Operation(summary = "[SENSITIVE] ...")` so the marker is visible in the operation list view, not just on the detail page. Currently applied to:

- `POST /api/admin/api-keys` (mint plaintext key, returned once)
- `DELETE /api/admin/api-keys/{id}` (revoke, irreversible)
- `POST /api/users` (create user)
- `DELETE /api/users/{userId}` (delete + cascade content access)
- `POST /api/external/devices/{serialNumber}/actions` (issue remote action)

**Enum values documented.** Every enum exposed at the API boundary carries `@Schema(description = ...)` on both the type and each value. The Swagger UI renders the per-value description inline next to the enum picker, so consumers don't have to dig through code to know what `OPERATOR` vs `VIEWER` means or what payload `VOLUME_SET` requires. Currently documented:

- `Role` (ADMIN / OPERATOR / VIEWER / ADVERTISER)
- `DeviceActionType` (REBOOT / SYNC_CONTENT / VOLUME_SET / PLAYBACK_PAUSE / PLAYBACK_RESUME / GET_DIAGNOSTICS)
- `ExportType` (EVENTS / DEVICES / STATS)
- `Event.Priority` (CRITICAL / HIGH / MEDIUM / LOW / INFO)
- `Incident.Status` (OPEN / ACKNOWLEDGED / RESOLVED)

**Request/response examples.** `@ApiResponses` + `@ExampleObject` on key endpoints provide concrete JSON payloads in the Swagger UI's "Example value" panel — the auto-generated schema-only example is uninformative for nested records and Maps. Examples cover every status code the endpoint can produce (200/201/202/204 success, 400/401/403/404/409/429/503 errors). Annotated controllers in this version: `AuthController`, `ExternalDeviceController`, `ApiKeyAdminController`, `UserController`, `ReportController`. The remaining controllers continue to surface auto-generated specs from method signatures and `@PreAuthorize` annotations; full example bodies will be added incrementally.

**Wiring details:**

- `OpenApiConfig` declares the top-level `OpenAPI` bean (info, security schemes, tags, servers) plus six `GroupedOpenApi` beans (one per module).
- `swagger-annotations:2.2.25` added to `domain/build.gradle` so domain enums and entity records can carry `@Schema` without pulling in the full springdoc runtime.
- `OpenApiConfigTest` (3 assertions) verifies both schemes are registered, the `Sensitive` tag is present, and every expected group exists. `SwaggerAccessTest` and `SwaggerLockedDownTest` exercise the public-vs-admin-only switch end-to-end.

### External API: `X-API-Key` Authentication for `/api/external/**`

A second auth scheme — completely separate from JWT — for partner/integration access. Endpoints under `/api/external/**` accept ONLY `X-API-Key` (no JWT path), and JWT-only endpoints reject `X-API-Key` (no role overlap). The two filters are independent and a stolen credential of one type does not grant access to the other.

**Endpoints:**

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/external/devices/{serialNumber}/status` | Current device status |
| GET | `/api/external/devices/{serialNumber}/history` | Paginated event history |
| POST | `/api/external/devices/{serialNumber}/actions` | Issue a remote action |

All external endpoints require role `API_CLIENT`. Devices are addressed by **serial number**, never by internal id — see "Public identifiers only" below.

**Admin lifecycle (`ROLE_ADMIN`):**

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/admin/api-keys` | Mint a key. Plaintext returned ONCE in `rawKey` |
| DELETE | `/api/admin/api-keys/{id}` | Revoke. Irreversible |
| GET | `/api/admin/api-keys` | List metadata (prefix only — never hash, never plaintext) |

**Storage model:**

The `api_key` table (V28 migration) stores the SHA-256 hash of the plaintext key plus an 8-char clear-text prefix for identification in logs/UI. Plaintext is shown to the customer once at creation and is never recoverable — a DB breach does not let an attacker impersonate a customer. SHA-256 is fast enough for per-request lookup; BCrypt would be ~100× slower and is unnecessary because the secret is high-entropy random (not a password).

**Rate limiting (100 req/hr per key):**

Backed by Redis with a per-key fixed-window counter `rate:apikey:{id}:{epochHour}`. The first hit in a bucket sets a 2-hour TTL so stale counters don't accumulate. The reset epoch returned in `X-RateLimit-Reset` is the start of the next hour — clients can compute backoff cleanly without parsing a date.

**Rate-limit headers — every response:**

```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 99
X-RateLimit-Reset: 1746547200
```

These appear on the success path AND on the 429 response body — a client can react to rate state regardless of whether its request was accepted.

**Edge cases:**

- **Revoked key returns 401 immediately.** `ApiKeyAuthenticationService.authenticate` looks up by `(key_hash, status=ACTIVE)` on every request — there is intentionally no in-memory cache. A key revoked one millisecond ago returns empty here, so the auth filter writes 401 on the very next request. Per-key load is bounded to 100 req/hr (the rate limit) so the indexed lookup is cheap; any cache would only widen the revocation window for no operational benefit.
- **Public identifiers only.** External endpoints expose devices by serial number, not internal id. Action and event responses include the serial and the public timestamp/type fields but never `deviceId`, `userId`, internal incident ids, or any other row-level primary key. Enforced at the DTO boundary (`DeviceStatusResponse`, `EventEntry`, `ExternalActionResponse`) and verified by `jsonPath("$.deviceId").doesNotExist()` assertions in the controller test.
- **`SecurityContext` principal is the 8-char prefix, never the internal id.** The `ApiKeyAuthFilter` sets `Authentication.getName()` to `apiKey.getKeyPrefix()`, and audit trails record `"apikey:" + prefix` as `issuedBy`. Internal numeric ids never reach logs or downstream services.
- **Headers on 429.** When rate-limited, the filter writes 429 with the same `X-RateLimit-*` headers as a 200 response — clients use `X-RateLimit-Reset` for backoff regardless of outcome.
- **Exact-100 boundary.** Fixed-window: hit #100 is allowed (remaining=0); hit #101 is rejected. The `tryAcquire` increments first, then compares to the limit, matching how API gateways elsewhere behave.
- **Cross-auth isolation.** `/api/external/**` requires `ROLE_API_CLIENT`. A JWT-derived `ROLE_ADMIN` is rejected with 403. Conversely, `/api/admin/api-keys` requires `ROLE_ADMIN` and rejects `X-API-Key` callers — the API client cannot mint or revoke its own key.
- **No `X-API-Key` header → pass through.** The filter is a no-op when its header is absent; the JWT filter (or anonymous access for permitAll endpoints) handles the request as before.
- **Idempotent context cleanup.** The filter clears `SecurityContextHolder` in a `finally` block so a downstream request on the same thread can never inherit auth.

**Wiring:**

`ApiKeyAuthFilter` is registered conditionally via `ApiKeySecurityConfig` (`@ConditionalOnBean({ApiKeyAuthenticationService.class, ApiKeyRateLimiter.class})`) so test slices that don't have the `service` module beans don't fail to wire the security chain. `SecurityConfig` injects it via `ObjectProvider` and only inserts the filter when present — a request with neither `X-API-Key` nor `Authorization: Bearer` reaches the entry point and gets 401 either way.

A new `StringRedisTemplate` bean was added to `RedisConfig` for the rate-limit `INCR` (the existing `RedisTemplate<String, Object>` uses a JSON value serializer that `INCR` can't parse).

**Distinct error codes:**

- `401` — missing or invalid/revoked `X-API-Key`
- `403` — valid key, but request hits a non-`/api/external/**` endpoint
- `429` — valid key, over the per-hour limit (with `X-RateLimit-*` headers)
- `400` — request validation (serial not found 404; bad volume 400; invalid action type 400)

### Excel Export: `GET /api/reports/export?type=EVENTS|DEVICES|STATS`

Streams an `.xlsx` workbook for offline analysis. Roles: `ADMIN` / `OPERATOR` / `VIEWER`.

**Query parameters:**

| Param | Notes |
|-------|-------|
| `type` (required) | `EVENTS`, `DEVICES`, or `STATS` |
| `facilityId` | Optional — narrow EVENTS/DEVICES to a single facility |
| `deviceId` | Optional — narrow EVENTS to a single device |
| `from` / `to` | ISO-8601 instants. EVENTS uses these for time filtering; STATS defaults to trailing 30 days |

**Response headers:**

```
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="export-devices-1746547200000.xlsx"
X-Export-Timeout-Seconds: 60
```

**Sheets per type:**

- `EVENTS` — `ID | Occurred At | Device ID | Device | Event Type | Priority | Payload`
- `DEVICES` — `ID | Serial | Name | Status | Region | Facility | Last Heartbeat | Content Version | Last IP`
- `STATS` — `Content ID | Content Name | Total Plays | Distinct Devices`

**Streaming pipeline:**

The export uses Apache POI's `SXSSFWorkbook` with a 100-row in-memory window — older rows are flushed to a temp file and only assembled into the final `.xlsx` zip on close. The DB side is paged via `BATCH_SIZE = 1000` so JPA never builds the full result list either. The combined effect is constant memory usage regardless of row count, with verified writes of 10,500-row workbooks in tests.

**Edge cases:**

- **10,000+ rows stream in chunks, not loaded into memory.** SXSSF + paged repository fetches keep memory bounded. Verified by the `deviceExport_streamsOver10000Rows_inMultipleBatches` test which mocks 10,500 devices across 11 page fetches and asserts all rows land in the workbook.
- **60-second timeout.** `ExcelExportService.DEFAULT_TIMEOUT = 60s`. The deadline is computed at the start of the request (`System.currentTimeMillis() + 60_000`) and re-checked between every batch fetch. If exceeded, the loop exits — the in-flight `.xlsx` is closed cleanly and the client sees a truncated download (the response status is already `200` once streaming begins). The timeout is also exposed to the client via the `X-Export-Timeout-Seconds` response header.
- **Max 2 concurrent exports per user → 429.** `ExportConcurrencyLimiter` tracks active slots in a per-username `AtomicInteger` map with a bounded compare-and-set loop. The third concurrent attempt by the same user throws `RateLimitExceededException`, mapped to `429 Too Many Requests` by the global handler. Slot release is guaranteed via try-with-resources inside `StreamingResponseBody`, which fires whether the stream completes, throws, or the client disconnects mid-download.
- **Per-user scoping** — alice and bob each get their own pair of slots; one user being at the cap doesn't affect the other.
- **Idempotent close on the slot** — closing a slot twice is a no-op; the count never goes negative.
- **`SXSSFWorkbook.dispose()`** is called in a finally block so POI never leaves temp files behind, even when the deadline fires or the data layer throws mid-export.
- **Soft-deleted devices and unregistered/deleted rows** are excluded from `DEVICES` — the export reflects the live operational view.
- **Invalid `type` value → 400.** Spring's `MethodArgumentTypeMismatchException` is now mapped to a clean 400 (was previously falling through to 500). Also covers bad `from`/`to` ISO strings and non-numeric `facilityId`.
- **`429` is distinct from `409`.** 429 conveys a transient retryable limit; 409 conveys a permanent state conflict. The new `RateLimitExceededException` in `common/exception` keeps that signal clean for any future per-user/IP throttling.

The dependency on `org.apache.poi:poi-ooxml:5.3.0` lives in `service/build.gradle` so the streaming-write logic stays out of the controller and is unit-testable from a `ByteArrayOutputStream` (verified end-to-end by reading the produced bytes back through `XSSFWorkbook`).

### Aggregated Event Report: `GET /api/reports/events`

Single-shot facility-level (or system-wide) summary covering events, incidents, resolution latency, and the most-impacted devices. Roles: `ADMIN` / `OPERATOR` / `VIEWER`.

**Query parameters:**

| Param | Notes |
|-------|-------|
| `facilityId` | Optional. Omit for system-wide aggregation |
| `from` / `to` | ISO-8601 instants. Default: trailing 30 days. Hard cap: 90 days |

**Sync response (small ranges):**

```json
{
  "status": "COMPLETED",
  "facilityId": 1,
  "from": "2026-04-06T00:00:00Z",
  "to": "2026-05-06T00:00:00Z",
  "totalEvents": 42,
  "countsByType": { "OFFLINE": 30, "SYNC_TIMEOUT": 12 },
  "incidentCount": 3,
  "avgResolutionSeconds": 1800.0,
  "topAffectedDevices": [
    { "deviceId": 1, "deviceName": "TV-1", "eventCount": 30 }
  ]
}
```

**Async response (large facilities):** when the pre-count exceeds `EventReportService.ASYNC_THRESHOLD` (10,000 events for the requested range), the service returns `202 Accepted` with a job id and dispatches the aggregation to the dedicated `reportExecutor` pool:

```json
{ "status": "PENDING", "jobId": "8c1f…" }
```

Poll `GET /api/reports/events/jobs/{jobId}`:

```json
{
  "jobId": "8c1f…",
  "status": "COMPLETED",
  "result": { /* same shape as inline report */ },
  "error": null,
  "expiresAt": "2026-05-06T01:00:00Z"
}
```

`status` flips `PENDING → COMPLETED` (or `FAILED` with the exception message in `error`). Jobs auto-expire 30 minutes after creation; expired ids return `404`.

**Edge cases:**

- **No events in range → zero-filled structure, not 404.** `totalEvents: 0`, empty `countsByType: {}`, empty `topAffectedDevices: []`, `incidentCount: 0`, `avgResolutionSeconds: null`. Lets dashboards render "no activity" without a special branch.
- **Average resolution excludes unresolved incidents.** The native query (`SELECT AVG(EXTRACT(EPOCH FROM (resolved_at - opened_at)))`) only considers incidents with `status = RESOLVED` and a non-null `resolved_at`. When no incident in the range has been resolved, `AVG` returns `null` — surfaced as JSON `null` rather than `0` (which would falsely imply "instant resolution"). Including OPEN/ACKNOWLEDGED incidents would either bias toward 0 or require a synthetic "now − openedAt", contaminating the metric.
- **Date range > 90 days → 400.** Same hard cap as `/api/events` and `/api/stats/content`.
- **`from > to` → 400.**
- **Async dispatch threshold is "above", not "at or above".** Exactly 10,000 events runs sync; 10,001 dispatches.
- **In-memory job store** — single-instance deploy is the baseline. Jobs auto-expire 30 minutes after creation; lookup of an expired or unknown id returns 404.
- **`@Async` proxy invariant** — `EventReportService` injects itself via `@Lazy` and dispatches to `self.submitAsync(...)`. Calling `submitAsync` directly inside `run` would bypass the AOP proxy and execute synchronously on the request thread; the self-injection is what makes the async dispatch actually async.

The dedicated `reportExecutor` (configured in `AuditAsyncConfig`) is separate from `auditExecutor` (fire-and-forget) and from `TranscodeExecutor` (CPU- and memory-bound video work). It uses `AbortPolicy` on overflow so saturation surfaces as a job `FAILED` rather than a silent drop — the polling client always gets a definitive answer.

### Org Tree

The platform organizes physical devices through a four-level tree:

```
project ─┬─* region ──* facility   (geographic / address-bearing)
         │            └─* device   (FK to region; optional FK to facility, device_group)
         └─* device_group          (logical / playlist-targeting; a group may span regions within its project)
```

| Level | Soft-deleted? | Why |
|-------|---------------|-----|
| project  | no  | Top-level container; managed via DB seed/migration |
| region   | no  | Stable identifier — code is referenced from external systems |
| facility | no  | Address-bearing; renaming reflects real-world physical state |
| device_group | yes | Operators frequently retire/recreate ad-hoc groupings |
| device   | yes | Devices come and go; soft-delete preserves event/playback history |

**Only the leaves carry `deletedAt`.** The org-tree levels above devices and device groups are hard-deleted when removed because their identifiers (project name, region code, facility name) are referenced by external systems and operators expect a literal removal — a soft-deleted region with the same code reappearing weeks later would silently break `(project_id, code)` uniqueness assumptions on import scripts.

#### Project CRUD: `/api/projects`

| Method | Path | Roles | Purpose |
|--------|------|-------|---------|
| GET    | `/api/projects` | ADMIN, OPERATOR, VIEWER | Flat list (no pagination) — projects are a small set in practice (single-digit to low-double-digit). Sorted by name ascending. |
| GET    | `/api/projects/{id}` | ADMIN, OPERATOR, VIEWER | Detail with immediate child regions ({@code RegionBrief}: id, code, name, createdAt) and the project's device groups ({@code DeviceGroupBrief}: id, name) |
| POST   | `/api/projects` | ADMIN | Create — body `{name}` |
| PUT    | `/api/projects/{id}` | ADMIN | Rename — body `{name}` |
| DELETE | `/api/projects/{id}` | ADMIN | Hard-delete (no soft-delete) |

**Mutations are ADMIN-only.** Unlike region/facility/device-group CRUD where `OPERATOR` can also create and rename, projects are root-of-tree and reshape the entire org tree above them — operators can navigate and read but cannot create, rename, or delete projects.

**Unpaginated by design.** The list endpoint returns a flat JSON array, not a `Page<...>` envelope. Projects are a small set; admins want the full list at once and the FE doesn't render an infinite-scroll for top-level navigation.

**Duplicate-name rule.** The DB enforces `UNIQUE (name)` via `uq_project_name`, added in migration **V30** (`V30__project_unique_name.sql`). The original V4 migration that created the `project` table did not constrain name to be unique — every other org-tree level (`region`, `facility`, `device_group`, `playlist`) was given a `UNIQUE(parent_id, name)` constraint, but Project sits at the root and has no parent, so its uniqueness key is just the name.

The migration assumes no duplicate names exist. If it fails on an existing dataset, the operator must dedupe manually before retrying — automating dedupe in a migration would silently merge or rename rows operators may want to inspect first.

Both `POST` and `PUT` pre-check the rule app-side and return **409 Conflict** with a message naming the offending name — surfacing the rule before the DB raises a `DataIntegrityViolationException`.

**Two delete guards.** A project deletes only when it has zero regions **and** zero active device groups, each surfaced as a clean **409**: `Project has N region(s); remove first`, then `Project has N device group(s); remove first`. The region cascade would otherwise have to handle facilities and devices in their own delete order — too much policy for a single endpoint, so we push that responsibility to the operator. The operator deletes from the leaves up: devices → facilities → regions, plus the project's device groups (a direct child of the project since V37, FK `fk_device_group_project`, no cascade). The device-group guard counts active groups only — a project whose sole groups are soft-deleted hits the same rare DB-`RESTRICT` edge documented for region delete vs soft-deleted devices.

**Hard delete is final.** No soft-delete bucket; the name is reusable as soon as the row is gone.

#### Region CRUD: `/api/regions`

| Method | Path | Roles | Purpose |
|--------|------|-------|---------|
| GET    | `/api/regions` | ADMIN, OPERATOR, VIEWER | Filterable list — `projectId`, `name` substring, Pageable. Page size capped at 100. |
| GET    | `/api/regions/{id}` | ADMIN, OPERATOR, VIEWER | Detail with facility list (public fields only) — device groups moved to the project detail (V37) |
| POST   | `/api/regions` | ADMIN, OPERATOR | Create — body `{projectId, code, name}` |
| PUT    | `/api/regions/{id}` | ADMIN, OPERATOR | Rename and/or recode — body `{code?, name?}` (PATCH-style; both optional) |
| DELETE | `/api/regions/{id}` | ADMIN | Hard-delete (no soft-delete at the region level) |

**Duplicate-code-per-project rule.** The DB enforces `UNIQUE (project_id, code)` (`uq_region_code_per_project`, V4 migration). The same project cannot host two regions with the same code. Both `POST` and the `code`-changing branch of `PUT` pre-check the rule app-side and return **409 Conflict** with a message naming the offending project — surfacing the rule cleanly before the DB raises a `DataIntegrityViolationException`.

**PATCH semantics on update.** The `PUT` body has both `code` and `name` optional. Sending only one updates only that field; sending both updates both; sending neither is a no-op that returns the current detail. Non-null but blank values (whitespace-only) are rejected with **400** because the entity columns are `NOT NULL` and blanks would corrupt the row. `@Size(max=20)` on `code` and `@Size(max=100)` on `name` still apply when the value is non-null.

**Two sequential delete guards.** A region only deletes once it has zero of each referencing artifact. The guards run in fixed order so the operator addresses one issue at a time:

1. **Active devices.** `Region has N active device(s); reassign first`. The DB-level `ON DELETE RESTRICT` on `device.region_id` (V4 migration) is the safety net; the app-level guard surfaces the count cleanly. Soft-deleted devices are not counted in the message — they don't matter to operators reviewing "what's currently deployed here?" — but a soft-deleted device still pins the FK at the DB level, so in the rare case of stale soft-deleted rows an unhandled `DataIntegrityViolationException` may bubble.
2. **Facilities.** `Region has N facility(ies); remove first`. Facilities are not soft-deletable; the FE must remove them.

There is **no** device-group guard: device groups now belong to the project, not the region (V37), so deleting a region can never orphan a group. The first failing guard short-circuits — the next guard's count is not computed until the prior one passes.

**Hard delete is final.** Once a region passes every guard, the row is removed permanently. There is no soft-delete bucket to restore from. The code is reusable immediately — a fresh `POST` with the same `(projectId, code)` succeeds.

#### Facility CRUD: `/api/facilities`

| Method | Path | Roles | Purpose |
|--------|------|-------|---------|
| GET    | `/api/facilities` | ADMIN, OPERATOR, VIEWER | Filterable list — `regionId`, `name` substring, Pageable. Page size capped at 100. |
| GET    | `/api/facilities/{id}` | ADMIN, OPERATOR, VIEWER | Detail with active member device list |
| POST   | `/api/facilities` | ADMIN, OPERATOR | Create — body `{regionId, name}` |
| PUT    | `/api/facilities/{id}` | ADMIN, OPERATOR | Rename within the existing region — body `{name}` |
| DELETE | `/api/facilities/{id}` | ADMIN | Hard-delete (no soft-delete at the facility level) |

**Duplicate-name-per-region rule.** The DB enforces `UNIQUE (region_id, name)` (`uq_facility_name_per_region`, V4 migration). Both `POST` and `PUT` pre-check the rule app-side and return **409 Conflict** with a message naming the offending region — surfacing the rule before the DB raises a `DataIntegrityViolationException`.

**Cross-region moves are not supported via `PUT`.** The PUT body carries only `name` — there is no `regionId` field, and the service sources the region from the existing row. Stray `regionId` properties in the JSON payload are silently ignored by Jackson's default deserializer (the test `rename_payloadHasNoRegionId_isBoundIgnored` documents this).

The deliberate omission keeps cross-region semantics simple: moving a facility between regions touches device FKs (every member device's `region_id` would also need updating) and assignment targets (CONFIRMED assignments with `targetType = FACILITY` would still resolve, but the geographic intent of the targeting changes underneath the operator). Combining all of that into a rename would silently corrupt downstream state. Operators who need a cross-region move follow the explicit path: reassign the facility's devices to a different facility (or null), hard-delete this facility, and create a new one in the target region.

**Two sequential delete guards.** Same pattern as device-group delete:

1. **Active devices.** `Facility has N active device(s); reassign first`. Devices may legitimately have a null `facility_id` (the FK is nullable), so reassigning to "no facility" is the typical operator workflow before delete.
2. **CONFIRMED assignments.** `Facility is targeted by N assignment(s)`. Counts non-soft-deleted assignments with `targetType = FACILITY` and `status = CONFIRMED`. `DRAFT` (not yet committed) and `CANCELLED` (logically gone) assignments do not count.

The first failing guard short-circuits — the operator addresses one issue at a time.

**Hard delete is final.** Same contract as region delete: no soft-delete bucket; the name is reusable in the same region as soon as the row is gone.

### Device Group Management: `/api/device-groups`

CRUD for device groups with separate role gates per operation. Listing and detail are open to `ADMIN`/`OPERATOR`/`VIEWER`; create and rename require `ADMIN` or `OPERATOR`; delete is `ADMIN`-only and additionally guarded against in-use groups.

| Method | Path | Roles | Purpose |
|--------|------|-------|---------|
| GET    | `/api/device-groups` | ADMIN, OPERATOR, VIEWER | Filterable list — `projectId`, `name` substring, Pageable. Page size capped at 100. |
| GET    | `/api/device-groups/{id}` | ADMIN, OPERATOR, VIEWER | Detail with member device list |
| POST   | `/api/device-groups` | ADMIN, OPERATOR | Create — body `{projectId, name}` |
| PUT    | `/api/device-groups/{id}` | ADMIN, OPERATOR | Rename — body `{name}` |
| DELETE | `/api/device-groups/{id}` | ADMIN | Soft-delete |
| POST   | `/api/device-groups/{id}/actions` | ADMIN, OPERATOR | Bulk action (existing — unchanged) |

**Duplicate-name-per-project rule.** The DB enforces `UNIQUE (project_id, name)` (`uq_device_group_name_per_project`, V37 migration). The same project cannot host two non-deleted groups with the same name — even across different regions, since a group is no longer tied to a region; once a group is soft-deleted, the name is reclaimable. Both `POST /api/device-groups` and `PUT /api/device-groups/{id}` pre-check this rule app-side and return **409 Conflict** with a message naming the offending project — surfacing the rule cleanly to the FE before the DB raises a `DataIntegrityViolationException`.

**Listing aggregate.** `deviceCount` comes from a single batched aggregate query keyed by the page's group ids (`DeviceRepository.countActiveDevicesPerGroup`). Without it, each row would lazy-load its own member collection and fan out into N+1 SQL.

**Edge cases.**

- **Soft-deleted is invisible everywhere.** Listing, detail, and the duplicate-name check all skip rows with `deletedAt IS NOT NULL`. Name reuse after a delete is therefore safe.
- **No-op rename is allowed.** Same name back is a 200 with the unchanged detail; the duplicate guard intentionally skips itself.
- **Delete refuses on active devices — 409.** A group with N non-soft-deleted devices wired to it returns `Group has N active device(s); reassign first`. Operators must reassign or remove the devices before retrying. The check uses `Device.deviceGroup` and `Device.deletedAt is null` per the data model.
- **Delete refuses on CONFIRMED assignments — 409.** A group still targeted by any non-soft-deleted `ContentAssignment` with `targetType = DEVICE_GROUP`, `targetId = this.id`, and `status = CONFIRMED` returns `Group is targeted by N assignment(s)`. `DRAFT` and `CANCELLED` assignments don't count — `DRAFT` is not yet committed, `CANCELLED` is logically gone. Operators must retarget or cancel the assignments first.
- **Already-deleted is not idempotent.** Re-deleting a soft-deleted group returns 404, so the admin UI refreshes rather than silently 204-ing on a stale row.
- **Bulk action endpoint unchanged.** `POST /api/device-groups/{id}/actions` predates this CRUD set and continues to behave exactly as before — its existing tests (`DeviceGroupControllerTest`) still pass after the controller's constructor grew the new dependency.

#### Membership

| Method | Path | Roles | Purpose |
|--------|------|-------|---------|
| POST   | `/api/device-groups/{id}/devices` | ADMIN, OPERATOR | Add devices to a group |
| DELETE | `/api/device-groups/{id}/devices/{deviceId}` | ADMIN, OPERATOR | Remove a device from a group |

**Add devices — `POST /api/device-groups/{id}/devices`.** Body `{ deviceIds: [Long] }` (`@NotEmpty`). The whole batch runs in one `@Transactional`: validation failure on any single device rolls the entire change back, so a partial-success state is impossible.

Validation:

- Every supplied id must resolve to a non-soft-deleted device. Missing or soft-deleted ids → **404** with the offending list rendered in the message (`Devices not found with id: [101, 102]`).
- Every device must already live in the same **project** as the target group (i.e. its region's project). Because a group may span any region within its project, devices from different regions of the same project are accepted. Cross-project offenders → **400** with the offending ids in the message (`Devices belong to a different project (move cross-project first): [..]`). Cross-project membership is intentionally not auto-fixed: moving a device across projects is a separate operation that affects FKs operators may need to audit.
- A restricted **operator** may only mutate (add/remove) membership of a group within their assigned projects; a group outside their scope collapses to **404** (consistent with detail), never 403.

Per-device outcome buckets, all returned in the 200 response:

```json
{
  "addedCount": 3,
  "alreadyMember": [101],
  "movedFrom": { "102": 7, "103": 8 }
}
```

- `addedCount` — devices that actually landed in this group (excludes already-member rows).
- `alreadyMember` — ids the caller asked for that were already attached to this group; no-op for those rows.
- `movedFrom` — `{deviceId → previousGroupId}` for any device silently moved out of a different group. **A device can only belong to one group at a time** — POST overwrites without prompting, but the previous group id is reported here so the admin UI / audit trail can reconstruct the move.

**Remove device — `DELETE /api/device-groups/{id}/devices/{deviceId}`.** Sets `device.deviceGroup = null` and returns 204. The `(groupId, deviceId)` pair must match the device's current membership — passing the wrong group id collapses to **404** (never 400) so the API doesn't leak the device's actual group to a caller who guessed wrong.

**Removing the last device is allowed.** Empty groups remain valid targets for assignment preview and setup; an "empty group" is not the same as a deleted group.

### Playlist Management: `/api/playlists`

CRUD for playlists. Listing and detail are open to `ADMIN`/`OPERATOR`/`VIEWER`; create and rename require `ADMIN` or `OPERATOR`; delete is `ADMIN`-only and additionally guarded against in-use playlists.

| Method | Path | Roles | Purpose |
|--------|------|-------|---------|
| GET    | `/api/playlists` | ADMIN, OPERATOR, VIEWER | Filterable list — `projectId`, `name` substring, Pageable. Page size capped at 100. |
| GET    | `/api/playlists/{id}` | ADMIN, OPERATOR, VIEWER | Detail with ordered items |
| POST   | `/api/playlists` | ADMIN, OPERATOR | Create — body `{projectId, name}` |
| PUT    | `/api/playlists/{id}` | ADMIN, OPERATOR | Rename — body `{name}` |
| DELETE | `/api/playlists/{id}` | ADMIN | Soft-delete |

**Duplicate-name-per-project rule.** The DB enforces `UNIQUE (project_id, name)` (`uq_playlist_name_per_project`, V6 migration). The same project cannot host two non-deleted playlists with the same name; once a playlist is soft-deleted, the name is reclaimable. Both `POST /api/playlists` and `PUT /api/playlists/{id}` pre-check this rule app-side and return **409 Conflict** with a message naming the offending project — surfacing the rule cleanly to the FE before the DB raises a `DataIntegrityViolationException`. The check is case-sensitive to match the DB constraint exactly.

**Detail item shape.** `PlaylistItemDto` exposes both the source `durationSeconds` (the file's natural duration, from the encoder probe) and the per-slot `durationOverride` (operator-supplied, nullable). The effective duration the player uses is `durationOverride ?? durationSeconds`. This lets the FE render "30s (overridden, file is 45s)" for clarity.

**Listing aggregates.** `itemCount` and `totalDurationSeconds` come from a single batched aggregate query keyed by the page's playlist ids — without it, each row would lazy-load its own `playlist.items` collection and fan out into N+1 SQL.

**Edge cases.**

- **Soft-deleted is invisible everywhere.** Listing, detail, and the duplicate-name check all skip rows with `deletedAt IS NOT NULL`. This is what makes name reuse safe after a delete.
- **No-op rename is allowed.** Sending the existing name back is a 200 with the same detail — the duplicate guard intentionally skips itself, so the FE can submit unchanged form values without a 409.
- **In-use delete is refused with 409.** A playlist referenced by any non-`DRAFT`, non-`CANCELLED` (and non-soft-deleted) `ContentAssignment` cannot be deleted; the response body is `Playlist is in use by N active assignment(s)`. Operators must cancel the assignments first. `DRAFT` and `CANCELLED` assignments don't count — they're not in flight.
- **Already-deleted is not idempotent.** Re-deleting a soft-deleted playlist returns 404, so the admin UI refreshes rather than silently 204-ing on a stale row.

#### Item operations

Item CRUD lives at `/api/playlists/{id}/items` and is implemented by `PlaylistItemService` — every method strictly `@Transactional` so a failure mid-shuffle rolls the whole operation back. The sentinel-position pattern documented under <i>Playlist Position Ordering</i> above is what keeps the `UNIQUE(playlist_id, position)` constraint from tripping during in-flight position swaps.

| Method | Path | Roles | Purpose |
|--------|------|-------|---------|
| POST   | `/api/playlists/{id}/items` | ADMIN, OPERATOR | Add an item; appends if `position` omitted |
| DELETE | `/api/playlists/{id}/items/{itemId}` | ADMIN, OPERATOR | Remove an item; subsequent positions compact |
| PUT    | `/api/playlists/{id}/items/{itemId}/move` | ADMIN, OPERATOR | Move one item to a new position via sentinel |
| PUT    | `/api/playlists/{id}/items/reorder` | ADMIN, OPERATOR | Bulk reorder by an exact ordered id list |

**Add item — `POST /api/playlists/{id}/items`.** Body `{ contentFileId, position?, durationSeconds? }`. Edge cases:

- `contentFileId` must reference a non-soft-deleted, `READY` content file. A soft-deleted file → 404 (treated as gone, matching `GET /api/content/{id}`); a non-`READY` file (`UPLOADED`/`TRANSCODING`/`FAILED`/`INVALID`) → 400 with the file's current status — the player has nothing to play yet, so the item would be dead weight in the playlist.
- `position` must be in `[0, currentSize]`. The upper bound is `currentSize` (not `currentSize - 1`) because appending — i.e. landing on the position one past the last item — is valid. Out-of-range → 400.
- Omitted `position` is the canonical "append" path. The service skips the position-shift query entirely on this branch since no existing rows need to move.
- `durationSeconds` is the optional per-slot override (`PlaylistItem.durationSeconds` in the model, surfaced as `durationOverride` on the response DTO). When null, playback uses the file's natural duration.

**Remove item — `DELETE /api/playlists/{id}/items/{itemId}`.** Returns 204. Subsequent items shift up by one so the playlist stays a dense `0..N-1` sequence. The `(playlistId, itemId)` pair is verified — passing an item id that belongs to another playlist returns 404, not 400, so the API doesn't leak the item's existence to a caller who doesn't own its real parent.

**Move item — `PUT /api/playlists/{id}/items/{itemId}/move`.** Body `{ toPosition }`, validated `@Min(0)`. Three-step sentinel sequence:

1. Park the moving item on position `-1` (vacates its current slot).
2. Shift the affected range one step toward the vacated slot — items in `(old, new]` move up if the move is downward, items in `[new, old)` move down if the move is upward.
3. Place the moving item at `toPosition`.

`toPosition` must be in `[0, currentSize - 1]` (no append semantics here — that's what add is for). Out-of-range → 400. `toPosition == currentPosition` is a no-op short-circuit and returns 200 with the unchanged item.

**Set duration override — `PUT /api/playlists/{id}/items/{itemId}/duration`.** Body `{ durationSeconds }`, where `durationSeconds` is either an integer in `[1, 86400]` (sets the per-slot override on `playlist_item.duration_seconds`) or **`null`** (clears the override). Both branches return 200 with the updated `PlaylistItemDto`.

The null branch is the load-bearing one: with the override cleared, the device-side `GET /api/devices/{id}/playlist` falls back to `content_file.duration_seconds` — the encoder-probe value captured at upload. The fallback happens at `DeviceSyncService.getPlaylistView`:

```java
Integer duration = it.getDurationSeconds() != null ? it.getDurationSeconds() : f.getDurationSeconds();
```

So the operator workflow is: set an override to truncate or extend a slot relative to the source clip, clear it (`null`) to revert to "play the file as encoded". Bean Validation's `@Min(1) @Max(86400)` only fires when `durationSeconds` is non-null, which is exactly what enables the clear path. `0` is rejected because a zero-duration item would never play; `86400` (24 hours) is the upper cap, well past any reasonable single-clip length. Out-of-range → 400; unknown item → 404; soft-deleted playlist or item → 404.

**Bulk reorder — `PUT /api/playlists/{id}/items/reorder`.** Body `{ orderedItemIds }`. The list must <b>exactly</b> match the current item set — extra ids, missing ids, or duplicate ids all return 400. Strict equality means the FE submits an authoritative ordering; partial patches are not supported because the implied "what should the other items do?" question has no good default.

Implementation:

1. Stamp every item with a unique <i>negative</i> sentinel (`-1, -2, …, -N`). This vacates every positive position simultaneously, so the constraint is satisfied even when the target order would have two items swapping positions.
2. Walk `orderedItemIds` and assign `0..N-1` in the supplied order.

The two-phase approach is what makes a same-call swap safe: the database never sees both items on the same positive position because the sentinels held the "real" positions until both moved off them.

**Failure semantics.** Every item endpoint runs inside a single `@Transactional` boundary in `PlaylistItemService`. If any step fails — DB error, validation throw, content-file lookup miss — the entire transaction rolls back; partial reorders, half-shifted ranges, or orphaned sentinel rows cannot be observed by another transaction.

### User Management & Advertiser Content Linking: `/api/users`

Admin-only lifecycle for users plus link/unlink of content files to advertisers. All endpoints require `ROLE_ADMIN` except `GET /api/users/{userId}/content` which is also open to `ROLE_OPERATOR` for the admin UI's read views.

| Method | Path | Roles | Purpose |
|--------|------|-------|---------|
| POST | `/api/users` | ADMIN | Create a user with role (typically ADVERTISER) |
| DELETE | `/api/users/{userId}` | ADMIN | Delete a user; advertiser deletion cascades content access |
| GET | `/api/users/{userId}/content` | ADMIN, OPERATOR | List content linked to an advertiser |
| POST | `/api/users/{userId}/content/{contentFileId}` | ADMIN | Grant content access to an advertiser |
| DELETE | `/api/users/{userId}/content/{contentFileId}` | ADMIN | Revoke a single grant (idempotent) |

**Create payload:**
```json
{ "username": "alice", "password": "secret123", "role": "ADVERTISER" }
```

`username` 3–100 chars, `password` ≥ 6 chars, `role` ∈ {ADMIN, OPERATOR, VIEWER, ADVERTISER}. Returns `201` with the user descriptor; `409` if the username is already taken.

**Edge cases:**

- **Linking FAILED or INVALID content is allowed.** The grant is a permission relationship, not an availability claim. Operators may pre-link content while it's still being processed, or keep historical access for audits after a file is marked invalid. Only soft-deleted content (`deletedAt IS NOT NULL`) is rejected with `404` — that file is treated as "gone" and grants would be permanently dangling.
- **Linking to a non-ADVERTISER user → 409.** The access table is meaningful only for the ADVERTISER role; ADMIN/OPERATOR/VIEWER see all content via their broader privileges.
- **Deleting an advertiser cascades all of their grants.** `UserManagementService.delete` runs `AdvertiserContentService.revokeAllForUser` before the user delete in the same transaction — failure rolls both back. The cascade is implemented as a JPQL bulk `DELETE` (`AdvertiserContentAccessRepository.deleteAllByUserId`) so it doesn't pull rows into memory. For non-advertisers the cascade call is skipped (the table never has rows for them).
- **Zero linked content returns an empty array, not 404.** `GET /api/users/{userId}/content` always returns `200 []` when an advertiser has no grants — the dashboard renders "no content yet" without a special branch. The same contract applies to `AdvertiserContentService.getAccessibleContent`, which is used by the advertiser-self view.
- **Unlink is idempotent.** Both an existing grant and a missing grant return `204`. Avoids 404 churn from admin UIs that fire DELETE on every visible row whether or not it's still linked server-side.
- **Duplicate link → 409.** A second POST for the same `(userId, contentFileId)` returns `IllegalStateException` mapped to 409, not silent re-create.

The cascade delete is on the application side rather than via a DB-level `ON DELETE CASCADE` so that the reason and row count are logged on each deletion (visible in audit logs) and so the same rules apply when the user delete is initiated through any other code path.

### Content Playback Stats: `GET /api/stats/content/{contentFileId}`

Aggregated playback statistics for a single content file. Roles: `ADMIN` / `OPERATOR` / `VIEWER` / `ADVERTISER`.

**Query parameters:**

| Param | Notes |
|-------|-------|
| `deviceId` | Optional — narrow stats to a single device |
| `from` / `to` | ISO-8601 instants. Default: trailing 7 days. Hard cap: 90 days |
| `page` / `size` / `sort` | Spring Pageable for the timestamp list. `size` ≤ 100 |

**Response:**

```json
{
  "contentFileId": 10,
  "contentFileName": "ad.mp4",
  "from": "2026-04-29T00:00:00Z",
  "to": "2026-05-06T00:00:00Z",
  "totalPlayCount": 42,
  "perDevice": [
    { "deviceId": 1, "deviceName": "TV-1", "playCount": 30 },
    { "deviceId": 2, "deviceName": "TV-2", "playCount": 12 }
  ],
  "timestampsIncluded": true,
  "timestamps": {
    "content": [ "2026-05-06T00:30:00Z", "2026-05-05T15:00:00Z" ],
    "page": 0, "size": 20, "totalElements": 42, "totalPages": 3
  }
}
```

**Edge cases:**

- **Date range > 30 days → counts only, no timestamps.** `timestampsIncluded: false` and `timestamps: null` in the response. The aggregate counts (`totalPlayCount`, `perDevice`) are still computed; only the per-row timestamp list is skipped. Pulling 90 days of timestamps for a popular content file would mean tens of thousands of rows; counts answer the typical "how often did this play" question without that cost.
- **Date range > 90 days → 400.** Hard upper bound matches the retention window.
- **Page size > 100 → 400.** Same cap as `/api/events`.
- **Advertiser role: scoped to their own content.** When the caller has `ROLE_ADVERTISER`, the service checks `AdvertiserContentAccessRepository.existsByUserIdAndContentFileId` — no grant returns `403 AccessForbiddenException`. Admin/Operator/Viewer bypass this check. The 403 is returned only after confirming the content file exists, so admins still get 404 for truly missing ids while advertisers see 403 for content that exists but isn't theirs.
- **Unknown content file → 404.** Soft-deleted content treated the same.
- **Boundary 30 days exactly** → timestamps still included (the `> 30` rule is strict).

The service-layer `AccessForbiddenException` (new in `common/exception`) → 403 mapping is registered in the global handler so service code can reject without depending on Spring Security types (which aren't on the service module's classpath).

### Playback Reporting: `POST /api/devices/{id}/playback`

Device-side endpoint for reporting content playback. Accepts either a single JSON object or an array of objects.

**Single event:**
```json
{ "contentFileId": 10, "playedAt": "2026-05-06T01:00:00Z", "durationSeconds": 30 }
```

**Batch (up to 500):**
```json
[
  { "contentFileId": 10, "playedAt": "2026-05-06T01:00:00Z" },
  { "contentFileId": 11, "playedAt": "2026-05-06T01:00:30Z" },
  ...
]
```

**Response (always 200, partial success is normal):**
```json
{
  "total": 3,
  "created": 2,
  "duplicate": 1,
  "rejected": 0,
  "rejections": []
}
```

`rejections` carries `{index, reason}` pairs so the device knows exactly which entries failed and can retry just those (or fix the timestamps locally and resubmit).

**Edge cases:**

- **`playedAt` in the future** (beyond a 30s clock-skew tolerance) → entry rejected with reason `played_at is in the future...`. Protects against bad device clocks injecting bogus future events.
- **`playedAt` older than the playback retention window** (`app.retention.playback`, **90 days** by default) → entry rejected with reason `played_at is older than the 90-day retention window`, with the day count taken from the configured value. Read from the same property the nightly cleanup deletes by — accepting older data would be deleted on the next run anyway.
- **Idempotent dedup**: the DB unique constraint `uq_playback_dedup (device_id, content_file_id, played_at)` ensures duplicates are recorded as `duplicate` (silent), not errors. Devices can safely retry the same batch after a network blip.
- **Batch size > 500** → 400 `Batch size N exceeds maximum 500`. Client must chunk. Bounds memory and transaction time per request.
- **Per-batch `ContentFile` cache**: a 500-entry batch with 5 unique content ids hits `content_file` 5 times, not 500.
- **Single bad entry doesn't poison the batch**: each entry runs independently; one rejection / unknown content file leaves the rest tallied normally.

The endpoint is `permitAll` (matches `/heartbeat`, `/sync` — devices use device tokens, not user JWTs).

### Device Diagnostics: `GET /api/devices/{id}/diagnostics`

Aggregate troubleshooting snapshot for the operator console. Roles: `ADMIN` / `OPERATOR` / `VIEWER`.

**Response shape:**

```json
{
  "deviceId": 70,
  "serialNumber": "SN-70",
  "name": "TV-Bar",
  "status": "ONLINE",
  "lastHeartbeatAt": "2026-05-06T01:00:00Z",
  "currentContentVersion": "v-abc",
  "lastKnownIp": "10.0.0.42",
  "pendingActionCount": 3,
  "recentEvents": [ /* up to 10, newest first */ ],
  "recentActions": [ /* up to 5, newest issued first */ ],
  "generatedAt": "2026-05-06T01:00:30Z"
}
```

`pendingActionCount` comes from a dedicated `COUNT(*)` query — it reflects the true total, not the size of the (capped at 5) `recentActions` list. `lastKnownIp` (V27 migration adds the `device.last_known_ip` column) is captured on each heartbeat from `request.getRemoteAddr()`, which Tomcat's `RemoteIpValve` resolves from `X-Forwarded-For` only when the request came through a trusted proxy (v1.0.138, AUTH-04). Values longer than the 45-char column are not stored.

**Edge case: never-heartbeated device returns empty envelope, not error.** A device that's only just been registered (or one that hasn't reported in yet) returns the same envelope with `lastHeartbeatAt: null`, `lastKnownIp: null`, `currentContentVersion: null`, empty `recentEvents` / `recentActions`, and `pendingActionCount: 0`. The operator console renders "no data yet" instead of an error. 404 is reserved for an unknown / soft-deleted device id.

**Cached for 30 seconds.** `@Cacheable(value = "diagnostics", key = "#deviceId")` writes to a dedicated Redis cache (`diag:diagnostics::<id>`) with a 30s TTL configured in `RedisConfig.CACHE_DIAGNOSTICS`. The operator console typically polls while troubleshooting and we don't want to hit five tables on every refresh. Stale data within the 30s window is intentional — for live state the operator opens the WebSocket admin feed.

### Single-Device Actions: `POST /api/devices/{id}/actions`

Operator-issued device commands. Creates a `RemoteAction` (one of the listed types) that the device picks up via WebSocket push or heartbeat poll.

**Request body:**

```json
{ "type": "VOLUME_SET", "volume": 75 }
```

**Accepted types** (`DeviceActionType` enum):

| Type | Payload |
|------|---------|
| `REBOOT` | `{}` |
| `SYNC_CONTENT` | `{}` |
| `VOLUME_SET` | `{"volume": 0..100}` (required) |
| `PLAYBACK_PAUSE` | `{}` |
| `PLAYBACK_RESUME` | `{}` |
| `GET_DIAGNOSTICS` | `{}` |

**Response: `202 Accepted`** with the queued `RemoteAction` (action id, status, payload, expiresAt).

**Edge cases:**

- **Max 1 PENDING action of same type per device → 409.** Enforced inside `RemoteActionService.issue` via `findPendingByDeviceAndType`. A second `REBOOT` before the first is confirmed/expired returns 409 with the in-flight action's id in the message.
- **Action queue capped at 10 PENDING per device → 409.** Before issuing, `DeviceActionService.issueAction` checks `RemoteActionRepository.countPendingByDevice(deviceId)`. Beyond the cap the request is rejected — protects offline / non-draining devices from accumulating an unbounded backlog.
- **`VOLUME_SET` validation.** Missing `volume` or out-of-range `[0, 100]` returns 400. Other types ignore `volume` if provided.
- **Unknown action type → 400.** Jackson rejects the unknown enum value; `GlobalExceptionHandler` now maps `HttpMessageNotReadableException` → 400 (was previously falling to 500).

Roles: `ADMIN` / `OPERATOR`. `VIEWER` and `ADVERTISER` are rejected with 403; unauthenticated requests get 401. The role rule applies uniformly to all action types — including `VOLUME_SET`, since changing audio levels affects what end-customers experience at the venue.

**Server-side volume validation (regardless of frontend).** `volume` is range-checked at two layers:

1. **Bean Validation** on `DeviceActionRequest.volume` — `@Min(0) @Max(100)` trips at request binding, returning 400 with a `fieldErrors` payload before the service is invoked.
2. **Service-layer guard** in `DeviceActionService.buildPayload` — re-checks the range as defense-in-depth (covers internal callers that bypass `@Valid`, future programmatic invocations, etc).

Boundary values 0 and 100 are accepted; -1 and 101 are rejected. `volume` is required only when `type=VOLUME_SET` (conditional rule enforced in the service since Bean Validation can't express it cleanly).

### Playlist Transport Control: `POST /api/devices/{id}/playlist/control`

Operator-issued PREV / NEXT / JUMP commands. Creates a `RemoteAction` (action type `PLAYLIST_CONTROL`) that the device picks up via WebSocket push or heartbeat poll.

**Request body:**

```json
{ "action": "JUMP", "position": 2 }
```

`action` is one of `PREV`, `NEXT`, `JUMP`. `position` is required only for `JUMP`.

**Response: `202 Accepted`**

```json
{
  "actionId": 101,
  "deviceId": 7,
  "actionType": "PLAYLIST_CONTROL",
  "status": "PENDING",
  "payload": "{\"action\":\"JUMP\",\"position\":2}",
  "issuedAt": "2026-05-06T00:00:00Z",
  "expiresAt": "2026-05-06T00:10:00Z",
  "issuedBy": "alice"
}
```

**Edge case: JUMP to invalid position → 400, no action queued.** `PlaylistControlService` resolves the device's current playlist, counts items, and validates `0 <= position < size` BEFORE calling `RemoteActionService.issue`. Out-of-range or missing `position` returns 400; no `RemoteAction` row is created.

**Edge case: control actions expire after 10 min if device offline.** Each control action is issued with `expiresAt = issuedAt + 10min` (vs. the 5-minute default for other action types). `RemoteActionExpirationJob` runs every minute and marks any `PENDING` action past its deadline as `EXPIRED`, so a device coming back online much later doesn't pick up stale commands. Devices that are online (WebSocket connected or polling within the window) receive the action immediately.

**Other guarantees:**
- Only `ADMIN` / `OPERATOR` can issue controls (`@PreAuthorize`)
- One `PLAYLIST_CONTROL` PENDING per device at a time — `RemoteActionService` rejects duplicates with 409 Conflict (operator must wait for the in-flight command to confirm or expire)
- Device with no assigned playlist → 400 (can't control playback that doesn't exist)

`GlobalExceptionHandler` now maps `IllegalArgumentException → 400` and `IllegalStateException → 409` uniformly across the API.

### Sync Confirmation: `POST /api/devices/{id}/sync/confirm`

After downloading, the device reports the version it now has cached.

**Request body:**

```json
{ "reportedVersion": "sha256-..." }
```

**Response:**

```json
{
  "deviceId": 42,
  "status": "CONFIRMED",
  "expectedVersion": "sha256-...",
  "reportedVersion": "sha256-...",
  "syncRequired": false
}
```

**State machine.** `GET /sync` returning a non-empty plan sets `device.sync_pending_version` and `sync_pending_since` (V23 migration). `sync_pending_since` is set ONCE on the first call and is NOT refreshed on subsequent `/sync` calls — the 30-minute timeout window is anchored to the first issued plan, not the latest.

**Edge case: unexpected version confirmed → log mismatch, trigger re-sync.** When `reportedVersion` differs from `sync_pending_version`:
- A `WARN` log line records the divergence
- `device.current_content_version` is updated to whatever the device actually has (so heartbeat/version comparisons stay accurate)
- The pending state is **not** cleared — the 30-minute timer keeps running
- The response carries `status=MISMATCH, syncRequired=true`, telling the device to call `/sync` again to receive fresh presigned URLs and the correct file set

**Edge case: no confirm after 30 min sync state → escalate to incident.** `SyncTimeoutMonitor` runs every minute (`@Scheduled fixedDelay=PT1M`). For any device where `sync_pending_since < now - 30min`, it emits a `SYNC_TIMEOUT` event with `Priority.HIGH`. `IncidentService` dedupes by device + event type, so repeated timeouts increment the existing incident's occurrence count instead of creating duplicates. After escalating, the monitor clears the device's pending markers so the next minute's scan doesn't fire again — operators close the incident manually, and the next `/sync` call re-arms the timer.

`app.device.sync-timeout-minutes` (default 30) configures the threshold.

The endpoint is `permitAll`.

### Two-Stage Validation (V18 — INVALID status)

**Stage 1 — Pre-upload (synchronous, returns 400):** `VideoUploadValidator` (common module) checks before bytes are persisted:

- MIME type starts with `video/`
- Extension in whitelist: `mp4, mov, m4v, mkv, webm, avi, mpeg, mpg, wmv`
- Size > 0

A failure throws `InvalidUploadException` → 400 with `"error": "Invalid Upload"`. The bytes never touch MinIO.

**Stage 2 — Post-upload (async, marks INVALID):** After bytes are stored, `StubTranscoder` pulls them back from MinIO and runs `FFprobeVideoInspector`:

- ffprobe non-zero exit → INVALID with `"FFprobe failed: <stderr>"`
- No `codec_type=video` in output → INVALID with `"No video stream detected"`
- Output mentions encryption → INVALID with `"Video is encrypted or password-protected"`
- ffprobe binary missing → logged as warning, treated as VALID (set `app.video.inspector-enabled: false` in environments without ffmpeg)

The reason is persisted to `content_file.invalid_reason` (TEXT, max 500 chars) so the UI can show why the upload was rejected. INVALID files keep their bytes in `content-raw` for human review, then get cleaned up via the soft-delete + lifecycle policy.

### FFmpeg Transcode Pipeline (V19)

`FFmpegTranscoder` (replaces the prior stub) runs the full pipeline asynchronously:

```
1. Download raw bytes from content-raw → temp file
2. FFprobe inspect input → INVALID on corrupt/encrypted
3. Status: TRANSCODING
4. ffmpeg encode:
     -vf scale='min(1920,iw):min(1080,ih):force_original_aspect_ratio=decrease'
     -c:v libx264 -preset medium -crf 23
     -c:a aac -b:a 128k
     -movflags +faststart -f mp4
5. FFprobe inspect OUTPUT → FAILED if produced output is broken
6. FFprobe extract container duration → INVALID if zero (V20)
7. Upload result to content-processed/<uuid>.mp4
8. Set processed_storage_key + duration_seconds, status: READY
```

- 1080p max (downscales preserving aspect ratio; smaller videos pass through unchanged)
- H.264/AAC MP4 with `+faststart` for HTTP streaming
- 15-minute timeout, then `destroyForcibly()`
- Output validation is mandatory — a corrupt encode is marked FAILED, not READY
- `content_file.processed_storage_key` (V19) holds the new bucket key; `storage_key` still points to the original
- `content_file.duration_seconds` (V20) is populated from the container's format-level duration

### Duration Extraction (V20 — VFR-safe)

`VideoInspector.extractDurationSeconds(InputStream)` runs:

```
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1
```

Reading **container `format=duration`** (not stream-level frame counts) is the only correct approach for variable-frame-rate (VFR) videos:

- Frame count ÷ frame rate produces wrong duration for VFR streams
- The container records the actual end timestamp regardless of frame cadence
- Returns whole seconds (rounded), or 0 if unreadable / not present

**Edge case: Duration = 0 → INVALID.** A successfully transcoded file with an unreadable container duration cannot be played reliably (no seek bar, no scheduling math), so the transcoder marks it `INVALID` with reason `"Could not determine video duration..."` instead of `READY`. The processed bytes are not uploaded to `content-processed`.

**Configurable via `app.video.*`** (nothing is required — every key has a host-safe default):
- `ffmpeg-path` (default `ffmpeg`), `ffprobe-path` (default `ffprobe`)
- `inspector-enabled` / `transcoder-enabled` (default `true` — set `transcoder-enabled=false` in CI
  without ffmpeg; raw bytes are copied through)
- `preset` (default `veryfast`), `crf`, `max-width` (1920), `max-height` (1080), `audio-bitrate`
- `timeout` (default `PT15M`), `poster-timeout` (default `PT60S`)
- `transcode.concurrency` (default `0` = plan it from the host), `transcode.max-concurrency`,
  `transcode.job-memory-mb`, `transcode.reserve-mb`, `transcode.queue-capacity`
- `sweeper.interval`, `sweeper.lost-dispatch-after`, `sweeper.lease-timeout`,
  `sweeper.failed-retry-after`, `sweeper.max-attempts`, `sweeper.stale-alert-after`

**Transcode concurrency adapts to the host** (`TranscodeCapacityPlanner`, v1.0.132). ffmpeg is a
child of the JVM, so its peak RSS is charged to the same cgroup; the pool width is therefore derived
at startup as `min(cpus - 1, (container memory - max heap - reserve) / job-memory-mb)`, clamped to
`[1, max-concurrency]`, and logged. A 1-vCPU / 700 MiB container resolves to **1**; an 8-core / 16 GB
host resolves to **7** — same image, no rebuild. Set `app.video.transcode.concurrency` to override.

**Edge Case: a transcode that never started, never finished, or failed**

Every one of these used to be permanent (see the v1.0.132 release note above). `TranscodeSweeper` runs every
`app.video.sweeper.interval` (default 2 min) and once on `ApplicationReadyEvent`:

1. **Lost dispatch** — `UPLOADED` older than `lost-dispatch-after` (5 min)
2. **Crashed encode** — `TRANSCODING` whose `transcode_started_at` lease is older than
   `lease-timeout` (20 min). Keyed on the **lease**, never `updated_at`, which does not advance
   during an encode; on boot the cutoff is "now", since a fresh JVM owns no in-flight work
3. **Retryable failure** — `FAILED` under `max-attempts` (3) and past the retry cooldown.
   `INVALID` is never re-driven: the container was rejected, and re-running ffmpeg cannot help

Each candidate is **claimed** with a conditional UPDATE (`claimForTranscode`) and dispatched only if
it affected exactly 1 row, so a sweep racing a live upload cannot double-start an encode. At the
attempt cap the row is parked in `FAILED` with `transcode_last_error` populated.

**Raw originals expire; nothing else does** (`RawBucketLifecycleInstaller`, v1.0.133)

At startup the app installs a server-side bucket lifecycle rule on `content-raw`:

```
Expiration: 30 days   filter: prefix "raw/"   (app.minio.raw-expiry-days, 0 disables)
```

Raw sources are the only objects that ever get deleted. **`content-processed` and
`content-thumbnails` are kept forever**, so playback is never affected; the single cost is that
`POST /api/content/{id}/retranscode` on a file older than the window fails, because its source is
gone. Nothing deleted raw objects before this, so every ad cost ~90 MB permanently on a volume that
reached 96% full.

Incomplete multipart uploads are swept by MinIO itself (`stale_uploads_expiry`, 24 h) — no
application job needed. `MinioBucketInitializer` ensures `content-raw` exists first; if MinIO is
unavailable at startup the install is deferred (reported `PENDING`, not failed) and retried on the
next boot.

> **Why this replaced `OrphanedUploadCleaner`.** That class declared
> `AbortIncompleteMultipartUpload` as its *only* action. MinIO's ILM validator requires at least one
> of `Expiration`/`Transition`/`NoncurrentVersion*`/`DelMarkerExpiration`, and the server has no
> abort type in its lifecycle package at all — so the document failed validation and the install lost
> to `MalformedXML` on **every single boot**, leaving a zero-length lifecycle config and one WARN in
> the log. Verified against a disposable MinIO on the deployed release
> (`RELEASE.2025-09-07T16-13-09Z`, `io.minio:minio:8.5.14`): abort-only fails under **both** an empty
> filter and a `raw/` prefix (so `RuleFilter("")` was not the cause), while expiration + `raw/`
> installs and reads back as `status=Enabled prefix=raw/ expirationDays=30 abort=null`. A failed
> install now logs at ERROR **and** shows as `storage-lifecycle` DOWN on `/api/health` — the old WARN
> scrolled past and trained operators to expect cleanup that did not exist.

## Frontend Integration Contract

Stable surface the FE depends on. None of these change between minor versions without a deprecation cycle.

### WebSocket endpoints

| Path | Audience | Notes |
|------|----------|-------|
| `/ws/dashboard` | Admin / Operator browser | Live feed for FE-05 / FE-14 / FE-29. Connect-time `SNAPSHOT` frame followed by live `INCIDENT_CRITICAL`, `INCIDENT_UPDATED`, `DEVICE_STATUS_CHANGE` deltas — see "Dashboard Live Feed" above. **This is the canonical FE WebSocket.** |
| `/ws/admin/incidents` | Admin / Operator browser (legacy) | Original CRITICAL-only feed. Planned for deprecation once the FE cuts over to `/ws/dashboard`'s broader event union. New FE work should target `/ws/dashboard`. |
| `/ws/devices/{id}` | **Device-side only** | TV-Box ↔ server channel. Never exposed to browser clients — the device authenticates with a device token, not a JWT. |

### `/ws/dashboard` auth — `?access_token=<JWT>` query param

Browser WebSocket APIs cannot set arbitrary headers, so the canonical FE auth path is the query parameter rather than `Authorization: Bearer …`. `DashboardHandshakeInterceptor` validates the JWT before the handshake completes:

| Outcome | Status | Cause |
|---------|--------|-------|
| Missing or invalid `access_token` | **401** Unauthorized | No token, expired, signature mismatch, deactivated user |
| Valid token but role is not ADMIN/OPERATOR | **403** Forbidden | VIEWER or ADVERTISER caller — they pass JWT validation but aren't authorized for the dashboard feed |

The 401-vs-403 distinction is preserved by handling auth at the interceptor (rather than via Spring Security path matchers, which conflate both into a generic 401). See "Dashboard Live Feed → Authentication at the handshake" for details.

### FE URL composition

| Env var | Points at | FE composes |
|---------|-----------|-------------|
| `VITE_API_URL` | API origin (`https://api.example.com`) | Endpoint paths absolutely: `${VITE_API_URL}/api/devices`, `${VITE_API_URL}/auth/login` |
| `VITE_WS_URL` | `ws(s)://<host>/ws` (no trailing slash) | Endpoint paths after the `/ws` segment: `${VITE_WS_URL}/dashboard`, `${VITE_WS_URL}/devices/42` |

The `/api`, `/auth`, and `/ws/...` prefixes are part of the path, not the env var, so the FE can swap the origin without rewriting endpoint constants.

### Error response envelope

Every 4xx and 5xx response uses a stable JSON shape. `GlobalExceptionHandler` (`api/src/main/java/uz/orientadvertise/services/api/advice/GlobalExceptionHandler.java`) is the single producer — every handler funnels through `buildResponse` so the shape is enforced regardless of which exception type bubbled up.

```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Project has 3 region(s); remove first",
  "correlationId": "0f150a73-3a05-4971-9894-69438c4be995",
  "timestamp": "2026-05-08T12:34:56.789Z",
  "fieldErrors": [
    { "field": "name", "message": "must not be blank", "rejectedValue": "  " }
  ]
}
```

| Field | Stable contract |
|-------|------------------|
| `status` | HTTP status code (also reflected in the response status line; FE can read either) |
| `error` | Short reason phrase, e.g. `Conflict`, `Validation Failed`, `Bad Request` |
| `message` | Human-readable detail. For specific endpoints (delete-with-references, ASSIGN_CONTENT validation), the FE parses substrings of this — those formats are documented inline with each endpoint. |
| `correlationId` | UUID. Operators paste this into log search to find the full trace — also surfaced in 500-class Telegram alerts. |
| `timestamp` | ISO-8601 instant of the server-side handler. |
| `fieldErrors` | Array of `{field, message, rejectedValue}` for 400 validation responses. `null` for non-validation errors. |

The shape is identical across `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `405 Method Not Allowed`, `409 Conflict`, `429 Too Many Requests`, `500 Internal Server Error`, `503 Service Unavailable`. **FE clients can dispatch on `status`/`error`/`message` without per-endpoint parsing.**

## Profiles

| Profile | Database    | ddl-auto   | HikariCP | Redis          | MinIO          |
|---------|-------------|------------|----------|----------------|----------------|
| dev     | H2 in-mem   | validate   | 5 max    | localhost:6379 | localhost:9000 |
| prod    | PostgreSQL  | none       | 20 max   | env-var config | env-var config |
| test    | H2 in-mem   | validate   | 3 max    | disabled       | mocked         |

### CORS configuration — `app.cors.allowed-origins`

CORS allowed origins are bound to the configuration property `app.cors.allowed-origins` (`List<String>`) so deployments can override without rebuilding. Other CORS settings (methods, exposed headers, `allowCredentials=false`, `maxAge=3600`) are baked into `SecurityConfig` and not configurable per environment.

> **TEMPORARY: fully permissive.** Both dev and prod default to `["*"]` while the FE integrates against unstable hostnames (preview builds, ad-hoc subdomains). The wildcard is legal because `allowCredentials=false`. **Action item before production hardening:** set `APP_CORS_ALLOWED_ORIGINS` to an explicit list (e.g. `https://admin.example.com`).

| Profile | Default | Override |
|---------|---------|----------|
| dev / default (`application.yml`) | `["*"]` | Set `app.cors.allowed-origins` in `application-{profile}.yml` or via env var |
| prod (`application-prod.yml`) | `${APP_CORS_ALLOWED_ORIGINS:*}` (`*` if env var unset) | `APP_CORS_ALLOWED_ORIGINS=https://admin.example.com,https://other.example.com` |

**Why this is safe with `*`.** Three things together: (1) `allowCredentials=false` — cookies never attach cross-origin, so a wildcard cannot be exploited to send authenticated cookie traffic; (2) the API uses Bearer-token auth with body-based refresh, which requires the FE to explicitly attach the `Authorization` header — a hostile site can't read tokens out of localStorage; (3) write endpoints all require an authenticated principal, so a wildcard origin grants no privilege beyond being able to read public health/swagger surfaces. That said, it's still wider than necessary — production should pin to known origins.

**WebSocket endpoints are NOT subject to this CORS.** `/ws/dashboard`, `/ws/devices/**`, and `/ws/admin/incidents` use the WebSocket protocol's own `Origin` header check at handshake (handled by the relevant handshake interceptor), not the HTTP CORS preflight machinery. The `/**` mapping in `SecurityConfig.corsConfigurationSource` is for HTTP endpoints only.

## Build & Run

> **`./gradlew build` and `./gradlew test` need a Redis on `localhost:6379`.** Every full-context
> `@SpringBootTest` in the **infra** module starts a `RedisMessageListenerContainer` during context
> refresh, and that startup is eager — without a reachable Redis the context fails and the whole
> `:infra:test` task goes red with `RedisConnectionFailureException`, which looks like a real test
> failure and is not. Start one first:
> `docker run -d --rm -p 6379:6379 redis:7-alpine`. CircleCI supplies it as a secondary image
> (see `.circleci/config.yml`). The **api** module excludes Redis autoconfiguration in its test
> profile and does not need it.

```bash
# Build all modules (start Redis first — see the note above)
./gradlew build

# Run with dev profile (in-memory H2; requires local Redis + MinIO). H2 is a developmentOnly
# dependency: on the bootRun classpath, never in the production jar.
./gradlew :api:bootRun --args='--spring.profiles.active=dev'

# Run tests only
./gradlew test

# Run full stack (PostgreSQL + Redis + MinIO + app)
docker compose up --build
```

### Health surface — `GET /api/health` (unauthenticated)

Six components; `overallStatus` is `DEGRADED` if any is Down. Each one answers a question that was
once **unobservable in production** — the service kept serving traffic correctly while something
behind it was quietly broken.

| Component | Down means | Added |
|---|---|---|
| `application` | never — the process is answering | — |
| `database` | the JDBC connection is unusable (generic reason; detail is logged, never returned) | — |
| `transcode-backlog` | files stuck in `UPLOADED` past `app.video.sweeper.stale-alert-after` (10 min) — uploads are not being transcoded | v1.0.132 |
| `disk` | free space below `app.health.disk-warn-free-percent` (15%) on `app.health.data-volume-path` | v1.0.133 |
| `storage-lifecycle` | the raw-expiry rule is not installed, so nothing is reclaiming storage | v1.0.133 |
| `storage` | MinIO is unreachable — uploads 503, transcodes fail, `/sync` returns 503. Carries the reason (operation + exception type) and since when | v1.0.144 |

Because the endpoint is unauthenticated, every indicator returns a **generic** reason on probe
failure and never echoes a connection string, path or driver exception. The Telegram `/health`
command reports a wider set (Redis, MinIO, devices, incidents, pools, recent errors) and shares
`app.health.data-volume-path` and `DiskSpace`'s arithmetic with the `disk` component here, so the two
surfaces cannot disagree. Its MinIO check also **writes its verdict back** into the same flag the
`storage` component reads (v1.0.144), so the chat and this endpoint cannot disagree either.

`storage` reports DOWN with `not probed yet (startup)` between the web server accepting connections
and `MinioBucketInitializer` finishing — that window is real, and storage endpoints do 503 in it.

## API Endpoints

| Method | Path                              | Description              | Auth |
|--------|-----------------------------------|--------------------------|------|
| POST   | /auth/login                       | Login, get tokens        | No   |
| POST   | /auth/refresh                     | Rotate refresh token     | No   |
| POST   | /auth/logout                      | Invalidate family        | No   |
| GET    | /api/health                       | Application health       | No   |
| GET    | /api/me                           | Current user profile     | Yes  |
| GET    | /api/content                      | List content (filtered)  | Yes  |
| GET    | /api/content/{id}                 | Content detail           | Yes  |
| DELETE | /api/content/{id}                 | Soft-delete content      | Yes  |
| POST   | /api/content/{id}/retranscode     | Re-queue a stuck/failed transcode | Yes  |
| GET    | /api/schedules                    | List schedules (filtered)| Yes  |
| GET    | /api/schedules/{id}               | Schedule detail          | Yes  |
| GET    | /api/playlists                    | List playlists (filtered)| Yes  |
| GET    | /api/playlists/{id}               | Playlist detail          | Yes  |
| POST   | /api/playlists                    | Create playlist          | Yes  |
| PUT    | /api/playlists/{id}               | Rename playlist          | Yes  |
| DELETE | /api/playlists/{id}               | Soft-delete playlist     | Yes  |
| POST   | /api/playlists/{id}/items         | Add item                 | Yes  |
| DELETE | /api/playlists/{id}/items/{itemId}| Remove item (compacts)   | Yes  |
| PUT    | /api/playlists/{id}/items/{itemId}/move | Move item to new position | Yes |
| PUT    | /api/playlists/{id}/items/reorder | Bulk reorder by id list  | Yes  |
| PUT    | /api/playlists/{id}/items/{itemId}/duration | Set/clear duration override | Yes |
| GET    | /api/device-groups                | List device groups       | Yes  |
| GET    | /api/device-groups/{id}           | Device group detail      | Yes  |
| POST   | /api/device-groups                | Create device group      | Yes  |
| PUT    | /api/device-groups/{id}           | Rename device group      | Yes  |
| DELETE | /api/device-groups/{id}           | Soft-delete device group | Yes  |
| GET    | /api/projects                     | List projects (no page)  | Yes  |
| GET    | /api/projects/{id}                | Project detail           | Yes  |
| POST   | /api/projects                     | Create project           | Yes  |
| PUT    | /api/projects/{id}                | Rename project           | Yes  |
| DELETE | /api/projects/{id}                | Hard-delete project      | Yes  |
| GET    | /api/devices/{id}/actions         | Operator action history  | Yes  |
| GET    | /api/devices/{id}/connection      | Live WS liveness (Connect button) | Yes |
| POST   | /api/devices/{id}/remote          | Start a remote view/control session | Yes |
| GET    | /api/devices/{id}/remote          | Current remote session (no ticket) | Yes |
| DELETE | /api/devices/{id}/remote/{sessionId} | Stop a remote session (idempotent) | Yes |
| POST   | /api/devices/{id}/reregistration-window | Allow one re-registration (ADMIN) | Yes |
| POST   | /api/devices/{id}/remote/{sessionId}/ack | Device acks a remote session | Device token |
| GET    | /api/regions                      | List regions             | Yes  |
| GET    | /api/regions/{id}                 | Region detail            | Yes  |
| POST   | /api/regions                      | Create region            | Yes  |
| PUT    | /api/regions/{id}                 | Rename / recode region   | Yes  |
| DELETE | /api/regions/{id}                 | Hard-delete region       | Yes  |
| GET    | /api/facilities                   | List facilities          | Yes  |
| GET    | /api/facilities/{id}              | Facility detail          | Yes  |
| POST   | /api/facilities                   | Create facility          | Yes  |
| PUT    | /api/facilities/{id}              | Rename facility          | Yes  |
| DELETE | /api/facilities/{id}              | Hard-delete facility     | Yes  |
| POST   | /api/device-groups/{id}/devices   | Add devices to group     | Yes  |
| DELETE | /api/device-groups/{id}/devices/{deviceId} | Remove device from group | Yes |
| POST   | /api/device-groups/{id}/actions   | Bulk action on group     | Yes  |
| POST   | /api/files                        | Upload file (multipart)  | Yes  |
| GET    | /api/files/{name}                 | Download file            | Yes  |
| GET    | /api/files/{name}/presigned-url   | Get presigned URL        | Yes  |
| DELETE | /api/files/{name}                 | Delete file              | Yes  |
| GET    | /api/files/status                 | Storage availability     | Yes  |
| GET    | /actuator/health                  | Spring Actuator health   | No   |
| GET    | /actuator/flyway                  | Flyway migration info    | No   |
| GET    | /swagger-ui.html                  | Interactive API docs     | No   |
| GET    | /v3/api-docs                      | OpenAPI 3.0 spec (JSON)  | No   |

## API Documentation

Springdoc OpenAPI auto-generates the spec from controllers and Bean Validation annotations.

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/v3/api-docs
- **JWT Bearer auth** is configured globally — click "Authorize" in Swagger UI and paste your access token from `POST /auth/login` (the value, not `Bearer <token>`)

Disabled in test profile via `springdoc.api-docs.enabled: false` to keep test contexts lean.

## Project Structure

```
Orient-advertise-backend/
├── api/           → Controllers, DTOs, exception handlers
├── service/       → Business logic, cache services, file storage
├── domain/        → Domain model, JPA entities, StorageClient interface
├── infra/         → DataSource, Redis, MinIO, Flyway, JWT, pub/sub
│   ├── auth/      → JwtTokenProvider, RefreshTokenRepository, JwtProperties
│   ├── cache/     → RedisConfig, RedisCacheErrorHandler
│   ├── pubsub/    → RedisPubSubConfig, ResilientEventPublisher
│   ├── storage/   → MinioConfig, MinioBucketInitializer, MinioStorageClient
│   └── src/main/resources/db/migration/  → Flyway migrations
├── common/        → Shared utilities, exceptions
├── .circleci/     → CI pipeline
├── Dockerfile
└── docker-compose.yml
```
