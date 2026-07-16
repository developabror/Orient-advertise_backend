# Orient-advertise-backend

Multi-module Spring Boot application with strict architectural layering enforced via Gradle module boundaries and ArchUnit tests.

## Version

`1.0.129`

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
- Spring Boot 3.4.5
- Gradle 8.11 (multi-module)
- PostgreSQL 17 (production)
- Redis 7 (session/token cache + pub/sub)
- MinIO (S3-compatible object storage)
- H2 with PostgreSQL mode (dev/test)
- Flyway (versioned schema migrations)
- HikariCP (connection pooling)
- Lettuce (Redis client with auto-reconnect)
- JJWT (JWT token creation/validation)
- Spring Security (stateless JWT authentication)
- Springdoc OpenAPI 2.7 + Swagger UI (API documentation)
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

### Default Users (V12 — persisted in `app_user` table)

| Username    | Password | Role       | Active |
|-------------|----------|------------|--------|
| admin       | password | ADMIN      | yes    |
| operator    | password | OPERATOR   | yes    |
| viewer      | password | VIEWER     | yes    |
| advertiser  | password | ADVERTISER | yes    |
| deactivated | password | VIEWER     | **no** |

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

`POST /api/devices/register` — TV-Box calls this on first boot with its serial number. No authentication required.

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
- **Sensitive fields never appear in audit logs** — `SensitiveFieldMasker` redacts: password, secret, token, accessToken, refreshToken, secretKey, accessKey, authorization, creditCard, ssn, cvv, pin
- **Async execution** — dedicated `auditExecutor` thread pool (2-5 threads, 500 queue). If queue full, entries are silently dropped
- **Body size limit** — max 10KB logged per request/response body (truncated with `...[truncated]`)

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

- Migrations: `infra/src/main/resources/db/migration/V*.sql`
- Rollbacks: `infra/src/test/resources/db/rollback/U*.sql`
- Migration failure **halts application startup** (Flyway default + Spring Boot propagation)

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
- `serial_number` is **globally unique at DB level** — even soft-deleted devices retain their serial

### Edge Cases (enforced at DB level)

- **Deleting region with active devices → blocked** — `ON DELETE RESTRICT` on `device.region_id` FK
- **Facility name unique within region** — `UNIQUE(region_id, name)` constraint
- **serial_number globally unique** — `UNIQUE` constraint on `device.serial_number` (DB-level, regardless of soft-delete state)
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

**Priority Rule: DEVICE_GROUP (3) > FACILITY (2) > REGION (1)**

When resolving which playlist a device should play at a given time, the highest-priority matching assignment wins. If a device belongs to a group with a group-level assignment, that overrides any region or facility assignment.

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
- Resolves the device's currently-assigned playlist using the same priority rules as `resolveForDevice`

**Edge cases:**

| Case | Behavior |
|------|----------|
| 500+ matching devices | First 200 returned, `truncated=true`, `totalDevices` shows the real count |
| Offline devices | Included in the response with `offline=true` flag (operators need full footprint) |
| Zero matches | Empty `devices` array, `totalDevices=0`, never 404 |
| Excluded assignments | Filtered out of `currentAssignmentId` so the preview reflects what the device actually plays |

### Schedules (V8)

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

**Edge case: end-in-the-past validation.**
- For `repeat_type=NONE`: `endTimeUtc` must be after `now()` — `400 Invalid Upload` otherwise
- For repeating: `repeatEndUtc` must be after `now()` — `400 Invalid Upload` otherwise
- Validation runs on both POST and PUT

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
- **Graceful degradation** — if MinIO is unavailable at startup, app starts but upload endpoints return HTTP 503

### Resilience

- `MinioBucketInitializer` (`ApplicationRunner`) — catches all exceptions, marks status DEGRADED
- `MinioHealthStatus` — thread-safe state holder (UP/DEGRADED)
- All storage operations check availability before proceeding
- `StorageUnavailableException` → HTTP 503 via `GlobalExceptionHandler`
- `GET /api/files/status` — reports current storage availability
- **Compose ordering** — `app` declares `depends_on: minio { condition: service_healthy }` (alongside postgres and redis), so the bucket initializer never races a still-starting MinIO. Without this gate the initializer would fail once at boot, latch the DEGRADED flag, and every upload would 503 until the app is restarted manually.

### Configuration

```yaml
app:
  minio:
    url: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    buckets:
      - uploads
      - content-raw
      - content-processed
    raw-bucket: content-raw
    presigned-url-expiry-minutes: 60
    orphan-cleanup-after-hours: 24
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

The upload endpoint **does not fail** when `projectId` is omitted or refers to a project that doesn't exist. The bytes are accepted, a `content_file` row is persisted with `project_id = null`, async transcode kicks off, and the response carries `"projectId": null` plus a message that explicitly mentions "orphan content."

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

**Caveats:**
- Orphan content is **not** subject to the `urgent=true` device fan-out — there's no project audience to push to. Re-issue an `urgent` flow after the project is bound (or use a manual sync trigger).
- The DB schema (`V31`) drops the `NOT NULL` constraint on `content_file.project_id`. The FK to `project(id)` stays in place, so non-null values remain referentially valid.

### Urgent Uploads + WebSocket Push

`POST /api/content/upload?urgent=true` skips the regular transcode queue and triggers a real-time fan-out to connected devices.

**Two layers of delivery:**

1. **WebSocket push (best-effort, immediate)** — `DeviceWebSocketHandler` broadcasts to every device with an open `/ws/devices/{id}` socket. The push is fire-and-forget; failures are recorded in the response (`webSocketPush.sent / skipped / failed`) but do not error the request.
2. **Heartbeat fallback (durable, eventual)** — devices that aren't currently connected pick up the same content on their next heartbeat poll via the existing `remote_action` queue.

**Front-of-queue transcoding:**

```
@Bean("urgentTranscodeExecutor")  — 2-4 threads, queue 20, CallerRunsPolicy
@Bean("auditExecutor")             — 2-5 threads, queue 500, normal traffic
```

`Transcoder.transcodeAsyncUrgent(id)` runs on the dedicated `urgentTranscodeExecutor` so urgent uploads don't queue behind backlogged normal jobs. Same pipeline (FFprobe inspect → ffmpeg → output check → upload), just on a different pool.

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

`RetentionCleanupService` deletes `event` and `playback_log` rows older than 90 days. Scheduled at **02:00 in `Asia/Karachi` (UTC+5)** via Spring `@Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Karachi")`.

**How it runs:**

- Each table is drained in 1000-row batches
- Each batch is its own `Propagation.REQUIRES_NEW` transaction (self-injection via `@Lazy` so the proxy boundary is crossed) — partial progress commits even if a later batch fails
- Per-run cap of `MAX_BATCHES_PER_RUN = 100` (≤ 100k rows per table per run) prevents a long backlog from running for hours
- A failure on the events drain doesn't block the playback drain (orchestrated independently with isolated try/catch)
- Each run logs: `Retention cleanup done: deleted events=N, playback_logs=M, threshold=...`

**Edge cases:**

- **Events linked to open incidents are skipped.** `EventRepository.findExpiredIdsSkippingOpenIncidents(...)` filters out any event referenced as the `firstEvent` or `lastEvent` of a non-RESOLVED incident — losing those would orphan the incident's audit trail. After the incident is resolved, the event becomes deletable on the next nightly run.
- **Time-of-day guard.** Even if the scheduler fires late or an operator triggers `runCleanup()` manually from a console, the method aborts with a no-op return when the current time is outside the **01:00–04:00 Asia/Karachi** window. Set `app.retention.guard-window=false` to disable in non-prod environments.
- **Bounded blast radius.** Per-table cap × batch size = 100k rows max per run; `findIds` is `ORDER BY id ASC` so progress moves monotonically and successive runs don't repeat work.

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

**Auto-resolve on device recovery.** `IncidentService.autoResolveOnRecovery(deviceId, eventType)` looks up the open incident for `(deviceId, eventType)` and, if found and not already resolved, transitions it to RESOLVED with `resolved_by = "system"`. Wired into `DeviceHeartbeatService` at two points:

| Trigger | Auto-resolves |
|---------|---------------|
| Status transitions FROM `OFFLINE` to anything else | `DEVICE_OFFLINE` |
| Heartbeat reports a matching version after a previous mismatch | `CONTENT_VERSION_MISMATCH` |

The recovery hook calls `tryAutoResolve(...)` in a try/catch — a failure resolving an incident never fails the heartbeat itself.

**Edge case: auto-resolve must not override manually resolved incidents.** Two layers of protection:
1. `incidentRepository.findOpenByDeviceAndEventType(...)` filters by `status <> RESOLVED`, so manually-closed incidents are not even returned to the auto-resolver.
2. Inside `autoResolveOnRecovery`, an additional `incident.isResolved()` guard handles the race where someone resolves the incident between the lookup and the decision.

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
2. **Check `incident.findOpenByDeviceAndEventType(...)`**. If an open incident already exists for this `(deviceId, eventType)`, skip — without this guard, every 5-minute pass would bump the existing incident's occurrence count forever, even after the underlying condition cleared.

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
- New columns are **projection-only** (not sortable). No `U34` rollback (the rollback chain lapsed after U28; `FlywayRollbackTest` only exercises U1/U2). Follow-ups (separate PRs): (1) `GET /api/devices` still lacks the `size<=100` page cap its sibling endpoints enforce; (2) the view's display tie-break (`id DESC` on a same-priority same-target tie) is deterministic, whereas `resolveForDevice`'s `Stream.max(priority)` leaves same-priority ties unspecified — so on such a tie the *displayed* playlist name could differ from the one actually served. This is unreachable in practice (the confirm-time overlap guard rejects two CONFIRMED assignments overlapping on the same target+devices), but a deterministic `id DESC` tie-break should be added to `resolveForDevice` for strict parity.

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

> **⚠️ Flagged (product decision): partial-intersection replace.** When `replaceConflicting:true` retires a predecessor that drives *more* devices than the new assignment (predecessor drives [1,2,3], new drives [3]), the **whole** predecessor is retired — devices [1,2] lose it too (they re-resolve to lower-priority/none). There is no partial-device supersede (it would mean adding exclusions to the predecessor). Acceptable for the common forever/whole-region case; flagged for a product call on whether partial supersede or a reject is wanted.

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

The dedicated `reportExecutor` (configured in `AuditAsyncConfig`) is separate from `auditExecutor` (fire-and-forget) and `urgentTranscodeExecutor` (CPU-bound). It uses `AbortPolicy` on overflow so saturation surfaces as a job `FAILED` rather than a silent drop — the polling client always gets a definitive answer.

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
- **`playedAt` older than 90 days** → entry rejected with reason `played_at is older than the 90-day retention window`. Matches the nightly cleanup window — accepting older data would be deleted on the next run anyway.
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

`pendingActionCount` comes from a dedicated `COUNT(*)` query — it reflects the true total, not the size of the (capped at 5) `recentActions` list. `lastKnownIp` (V27 migration adds the `device.last_known_ip` column) is captured on each heartbeat from `X-Forwarded-For` (first hop) or `request.getRemoteAddr()` as fallback.

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

**Configurable via `app.video.*`:**
- `ffmpeg-path` (default `ffmpeg`)
- `ffprobe-path` (default `ffprobe`)
- `inspector-enabled` (default `true`)
- `transcoder-enabled` (default `true` — set `false` in CI without ffmpeg; raw bytes are copied through)

**Edge Case: Orphaned TRANSCODING jobs on startup**

If the JVM is killed mid-transcode, rows are left in `TRANSCODING` state forever otherwise. `OrphanedTranscodeRecoverer` runs on `ApplicationReadyEvent`:

1. `findByStatusAndDeletedAtIsNull(TRANSCODING)` finds all stuck jobs
2. For each, in a `REQUIRES_NEW` transaction: reset status to `UPLOADED`, then call `transcoder.transcodeAsync(id)` to requeue
3. Errors on individual rows are logged and don't block siblings (no rollback cascade)

**Edge Case: Orphaned MinIO parts after 24h**

`OrphanedUploadCleaner` registers a server-side **bucket lifecycle policy** at startup:

```
AbortIncompleteMultipartUpload after 1 day
```

MinIO itself sweeps incomplete multipart uploads older than 24h — no application cron job needed. The `MinioBucketInitializer` ensures `content-raw` exists before the lifecycle is applied; if MinIO is unavailable at startup, the lifecycle install is skipped (logged as non-critical) and the app remains DEGRADED until MinIO returns.

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

```bash
# Build all modules
./gradlew build

# Run with dev profile (requires local Redis + MinIO)
./gradlew :service:bootRun --args='--spring.profiles.active=dev'

# Run tests only
./gradlew test

# Run full stack (PostgreSQL + Redis + MinIO + app)
docker compose up --build
```

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
