# Android Device ↔ Backend Contract — Authoritative Flow Spec

**Audience:** the OrientedTV Android signage client team.
**Purpose:** everything the device must send, receive, parse, persist and tolerate when it talks to the backend: REST contracts, the WebSocket channel and every frame on it, the MinIO download, timers, limits, failure modes.
**Backend baseline:** `1.0.156` (registration rules updated in 1.0.137, see R21; source-IP handling in 1.0.138, §4; device-facing changes 1.0.139–1.0.156 in R22–R30 below).
**How this was verified:** every statement was checked against the backend source. The wire examples were captured from a live run of `1.0.135` (dev profile, real MinIO and Redis, real ffmpeg transcode, a real WebSocket client) on 2026-09-14. Rules marked *(verified)* were exercised end to end in that run.

Where this document disagrees with the older `REGISTER_TO_PLAYBACK_FLOW.md`, or with an earlier revision of this file, **this document wins**. If you built against the previous revision, read the revision notes right below first.

Conventions: **MUST / MUST NOT / SHOULD / MAY** are normative. `{id}` is always **your own** `deviceId`. A "beat" is one heartbeat. "Server time" means your clock corrected by the offset from §7.1, never the raw device wall clock.

---

## What changed for the device since backend 1.0.138 (read this first)

| # | Since | Change | Client action |
|---|---|---|---|
| R22 | 1.0.144 | `/sync` answers **503** when object storage is unreachable, instead of a plan with the unreachable files silently missing. | Treat it like any 5xx: keep playing cached content, **do not** apply it as "no content" and **do not** delete files, retry with backoff (§1.6, §6.2). |
| R23 | 1.0.146 | A device an admin deleted can register again: the same serial gets **201** as a **new device** (new `deviceId`, new token, default placement, no content until an operator assigns it). It used to fail with 500 forever. | Always persist the `deviceId` **and** token from every `/register` response; never assume the `deviceId` survives a decommission. Keep the local media store. Remove any "decommissioned — stop retrying" logic built for the old 500 (§2, §4). |
| R24 | 1.0.149 | Every newly processed file is **8-bit 4:2:0 H.264 High** (level picked by the encoder). 10-bit / 4:2:2 uploads used to become High 10 / High 4:2:2, which most hardware decoders reject. | Nothing required. Keep the per-item decoder-failure defence for files processed before 1.0.149 (§6.5). |
| R25 | 1.0.150 | `ACTION_PENDING` is **pushed as soon as an action is issued** (after commit), not only replayed on connect. | Handle the frame at any time: fetch `GET /actions/pending`, execute, **deduplicate by `actionId`** (it also arrives by heartbeat and replay). The 30–60 s poll while connected is no longer needed (§8.1). |
| R26 | 1.0.151 | **`PLAYLIST_CONTROL` now really arrives from the dashboard.** The operator device page could never send one before (its panel was broken), so in practice only the external API issued these. The dashboard hides the buttons for a device in synchronised playback, but the external API can still send one at any time. | No wire change. Make sure `PLAYLIST_CONTROL` is implemented as in §8.3 — `PREV`/`NEXT`/`JUMP` with `position` = the 0-based index into your **delivered** list — and that a device following a group anchor answers `FAILED` with `result: "SCHEDULE_MODE"` rather than jumping out of sync. |
| R27 | 1.0.152 | A reported play is checked against the campaign that was live **when it played**, not the one live now, and is credited to it. A play whose campaign ended is still accepted for 30 minutes afterwards. | Nothing required — and you may drop any "flush before a content switch" special case. Keep `playedAt` accurate to the millisecond and keep flushing promptly (§11). |
| R28 | 1.0.153 | **A playlist edit now gets its own `activateAt`, in the future** (at least 2 min, more when the edit adds files to download), instead of reusing the assignment's original anchor whose instant had already passed. Every screen on the assignment switches together at that instant, and the loop restarts from the first item. `/sync` also answers **503 in about 3 s** when object storage hangs, rather than eventually. | **Apply the §7.3 formula to EVERY pending version, edits included**: keep playing the live version until `activateAt`, then switch, and keep the old files until you do. Do not special-case an edit as "switch as soon as confirmed" — that is what pulls one screen out of step with the rest of its site (§7.4). |
| R29 | 1.0.155 | **`URGENT_CONTENT` is no longer sent at all.** It was broadcast to every connected device in the fleet on an "urgent" upload, carried nothing actionable, and this spec already told you to ignore it. `urgent` now means only what it always did on the server: that file jumps the transcode queue. | Nothing to do. If you kept a branch for this frame you may delete it; unknown frame types must still be ignored (§10), so an old client is unaffected either way. |
| R30 | 1.0.156 | **A request with no (or an unsupported) `Content-Type` now answers 415**, naming the media type the endpoint consumes, instead of 500. An `Accept` header that excludes JSON answers 406. Separately, an operator group jump is pushed only **after** it is committed, so a member that syncs on the push can no longer read the group's state before the jump is visible and miss it. | Keep sending `Content-Type: application/json` (§1.2) — a 415 is now a clear client-side signal rather than a server error to retry blindly. Treat 415 and 406 as **final**: never retry them unchanged. The jump change needs nothing from the client. |

No other wire change between 1.0.138 and 1.0.156: request/response shapes, auth, timers and limits
are unchanged (the dependency upgrade to Spring Boot 3.5 in 1.0.148 changed nothing on the wire).
One behaviour change needs no client action but may surprise QA: since 1.0.142, when a short
"Replace" campaign ends, screens go back to the booking underneath it instead of going blank. This
arrives as an ordinary content-version change.

## Revision notes: corrections to the previous revision

These are behaviour differences between the previous spec and what the backend actually does. Each one is a client change.

| # | Previous spec said | Actual behaviour | Section |
|---|---|---|---|
| R1 | Content changes are pushed as WS `SYNC_REQUIRED` | Live content pushes are **`SYNC_CONTENT`** with a `reason`. `SYNC_REQUIRED` is sent **only as a replay when the socket connects**. | §10.3 |
| R2 | New actions arrive live as WS `ACTION_PENDING` | **Since backend v1.0.150, yes**: a frame is pushed as soon as the action is committed, and also replayed on every connect. It is best-effort, so the heartbeat and `GET /actions/pending` remain the guarantee. Before v1.0.150 it was only replayed on connect. | §8.1 |
| R3 | `currentFileIds` = every file you hold "regardless of download status" | List **only fully downloaded and verified** files. The server never issues a URL for a file you claim to hold, so a partial file listed there can never be resumed. | §6.2 |
| R4 | `fullSync` is a hint you can ignore | With `fullSync: true` the server **ignored your `currentFileIds`**. `filesToAdd` repeats files you already have, and `filesToDelete` is always empty, so you must garbage-collect yourself. | §6.3, §6.4 |
| R5 | `URGENT_CONTENT` is never pushed | ~~It **is** pushed to every connected device on the whole fleet.~~ True again since 1.0.155: the frame was removed (R29). Ignore it if an older server sends one. | §10.3 |
| R6 | The server pings every ~90 s | The server **never pings**. The client must send WS pings. | §10.6 |
| R7 | A deleted device gets 404 and should re-register | A deleted (or rotated) token gets **401**. ~~Re-registering a soft-deleted serial currently fails with **500**.~~ Since 1.0.146 it registers as a new device (R23). | §2 |
| R8 | Action `payload` looks like a JSON object | `payload` is a **JSON document encoded as a string**, and it may be `null`. Action types also include `PLAYLIST_CONTROL` and `ASSIGN_CONTENT`. | §8.2, §8.3 |
| R9 | `VOLUME_SET` and `desiredVolume` are both valid | `VOLUME_SET` does **not** change `desiredVolume`, so the next beat pulls the volume back. | §8.3 |
| R10 | `MISMATCH` means content changed mid-sync | `MISMATCH` means you confirmed something other than what **your latest `/sync`** returned. Content changing mid-sync still yields `CONFIRMED`. | §6.6 |
| R11 | Devices flip together at `activateAt` | The anchor is created once **per assignment**. After a playlist edit `activateAt` is usually in the past. A group jump changes the anchor **without** a version change, and heartbeats do not signal it. | §7 |
| R12 | Timestamps are "ISO-8601" | Up to **9** fractional digits. The same instant can render differently on the WS frame and on the heartbeat. | §1.4 |
| R13 | — | `Content-Type: application/json` is mandatory on every body. Without it the request **500s**. | §1.2 |
| R14 | — | Sending a stale `X-Device-Token` on `/register` gets **401**, so registration never succeeds. | §4 |
| R15 | `contentType` describes the download | `contentType` and `name` describe the **original upload** (e.g. `video/quicktime`). The download is always MP4. | §6.3 |
| R16 | — | The same `fileId` can appear at several `index` positions with different durations. | §6.4 |
| R17 | Expired URL → 403/410 | MinIO answers **403**, never 410. `ETag` is not the SHA-256. Send `Accept-Encoding: identity`. | §6.5 |
| R18 | 10 registrations/hour per IP | The window is a fixed **clock hour**. Attempts that fail with 500 also count. Every box behind one NAT shares the budget. | §4 |
| R19 | — | `agentTicket` is the **same string on every beat**. Acking `ENDED` after an operator stop returns 409, which is expected. Remote control is **disabled in production** today. | §9 |
| R20 | Playback rejects bad entries per item | One malformed entry fails the **whole batch** with 400. A `null` field fails the whole batch with 500. Entries are checked against the playlist assigned **at `playedAt`** (since 1.0.152, R27), with a 30-minute grace after a campaign ends. | §11 |
| R21 | Re-registering an existing serial returns 200 with a fresh token | **Since 1.0.137:** an already-registered serial gets **409** unless an admin clicked *Allow re-registration* for that device in the last hour (then 200 as before, once). Over-long or badly formed `serialNumber`/`deviceName` now get **400** instead of 500. **Client change:** on 409 from `/register`, keep playing cached content and retry every ~5 min with jitter; show "waiting for operator to allow re-registration" in diagnostics. Attempts on a registered serial use a **separate** budget (60/hour per IP), not the 10/hour new-device budget. A heartbeat closes any open window. | §1.6, §2, §4, §13 |

---

## 0. Mental model

1. **The backend is the single source of truth.** The device never decides *what* to play or *which version* is current. It reports what it has, asks for the diff, downloads, verifies, confirms, then plays.
2. **The heartbeat is the contract; the WebSocket is an accelerator.** Almost every WS frame carries information you can also get from the heartbeat, `/sync` or `/actions/pending`. The one exception is §7.5 (the group jump, which needs `/sync`). Build every feature so it works with the socket down.
3. **Content version is an opaque 64-char lowercase hex token.** Store it, echo it, compare it for equality. Never parse or compute it.
4. **You only download the processed MP4** from a MinIO presigned URL that `/sync` hands you. You never touch buckets, raw uploads or the transcoder.
5. **Identity is `deviceId` + `deviceToken`.** Every authenticated call puts your own `deviceId` in the path and the token in `X-Device-Token`.
6. **Nothing is exactly-once.** Actions repeat until confirmed, WS frames can be dropped, confirms can be retried. Every handler must be idempotent and keyed by id.

---

## 1. Transport & wire conventions

### 1.1 Hosts

| What | Where | Notes |
|---|---|---|
| REST API | `https://<api-host>/api/devices/...` | Production sits behind a TLS reverse proxy. The base URL **MUST** be configurable. **MUST NOT** hardcode an IP or port `8080`: since v1.0.133 the app port listens on loopback only and is reachable solely through the proxy. |
| WebSocket | `wss://<api-host>/ws/devices/{id}` | Same host as REST. Use `wss://` whenever REST is `https://`. |
| Media | whatever host the `presignedUrl` names | A **different host** from the API (the MinIO public vhost). Network security config, allow-lists and certificate pinning must cover both hosts. |

### 1.2 Request rules

- **`Content-Type: application/json` on every request that has a body** (register, heartbeat, both confirms, ack, playback). Adding `; charset=utf-8` is fine. A body without this header makes the server throw and answer **500** *(verified)*. OkHttp sets it for you when you build the body with a JSON `MediaType`.
- Bodies are UTF-8 JSON. Unknown fields in your requests are ignored, so extra fields are harmless.
- **Enum strings inside JSON bodies are case-sensitive.** `SUCCESS`, `FAILED`, `READY`, `ENDED` must be uppercase. `"success"` gets **400** *(verified)*.
- **JSON type errors fail the whole request with 400** *(verified)*. `"volume": "loud"` and `"supported": "yes"` are rejected. Numeric strings like `"45"` are coerced and floats are truncated, but send real integers and booleans.
- **String length limits.** Exceeding them fails the request with **500** (the database rejects the value) *(verified)*:

  | Field | Max |
  |---|---|
  | `serialNumber` | 100 |
  | `deviceName` | 200 |
  | heartbeat `contentVersion`, confirm `reportedVersion` | 64. Only ever echo a server-issued version. |
  | remote ack `reason` | 64. Longer values are truncated, not rejected. |

- **Headers you MUST NOT send:**
  - `X-Device-Token` on `POST /register`. A stale token there gets 401 before registration runs (§4).
  - `X-Device-Token` to the MinIO host. It is ignored there, but it leaks your credential into proxy logs.
  - `Origin` on the WebSocket handshake. An `Origin` that is not on the server's browser allow-list gets 403 (§10.1), and a device never belongs on that list.
- There is no request-id header. The server generates a fresh `correlationId` for each error response.
- A wrong HTTP method gets **405**.

### 1.3 Response rules

- Everything is `application/json` except MinIO (media bytes, or S3 XML errors) and WS handshake rejections (empty body, see §10.1).
- **Nullable fields are always present as `null`**, never omitted. The one exception is `details` in the error envelope.
- **Ignore unknown response fields.** The backend adds fields additively.
- **Arrays are never `null`.** `pendingActions`, `filesToAdd`, `filesToDelete`, `playlistOrder`, `items` and `rejections` are always arrays, possibly empty.
- All ids (`deviceId`, `fileId`, `actionId`) and every `*Ms` / `*EpochMs` / `activateAt` value are **int64**. Use `Long`.

### 1.4 Time

- **Server → device timestamps** are ISO-8601 UTC with `Z` and **0 to 9 fractional digits** *(verified)*, e.g. `"2100-01-01T00:00:00Z"`, `"2026-09-14T17:42:21.468521Z"`, `"2026-09-14T17:40:28.406465577Z"`. Parse with `java.time.Instant.parse` (API 26+, or core-library desugaring). **Never** use a fixed-pattern `SimpleDateFormat`.
- **The same instant can be rendered with different precision on different channels.** One remote session's `expiresAt` came through as `…13:12.041682612Z` on the WS frame and `…13:12.041683Z` on the heartbeat *(verified)*. Compare parsed instants, never strings, and key objects by their id.
- **Epoch-millisecond fields** are `/time` `serverUnixMs`, `/sync` `anchorEpochMs`, `activateAt`, `loopDurationMs`, `slotStartMs` and `slotDurationMs`.
- **Device → server timestamps** (playback `playedAt`) **MUST** be ISO-8601 strings with `Z` or an explicit offset (`+05:00` is accepted). An offset-less `"2026-09-14T17:00:00"` fails the whole batch with 400 *(verified)*. A JSON number is read as epoch **seconds**, so an epoch-millisecond value lands far in the future and is rejected.
- **Do not trust the device wall clock.** TV boxes often boot with a wrong clock. Keep a server-time offset (§7.1) measured against `SystemClock.elapsedRealtime()` and use it for `playedAt`, action expiry, remote-session `expiresAt`, presigned-URL deadlines and `activateAt`.

### 1.5 Error envelope

Every non-2xx JSON response uses the same shape *(verified)*:

```json
{
  "status": 400,
  "error": "Validation Failed",
  "message": "Request validation failed — see fieldErrors for details",
  "correlationId": "e0b6b535-e63b-4e1b-90d7-24c91eda2e34",
  "timestamp": "2026-09-14T17:40:27.987848609Z",
  "fieldErrors": [ { "field": "serialNumber", "message": "Serial number is required", "rejectedValue": " " } ]
}
```

- `fieldErrors` is `null` except on bean-validation failures. `details` is omitted; it never appears on device endpoints.
- **Branch on `status` only.** `message` is human text. Log `message` together with `correlationId`, which is how backend engineers find your request.
- A production 500 carries `"message": "An unexpected error occurred. Reference: <correlationId>"`.

### 1.6 Generic retry classification

| Outcome | Meaning | Client behaviour |
|---|---|---|
| network error / timeout | — | Retry with exponential backoff and jitter. |
| 5xx | Server fault. **Some 500s are deterministic** from bad input (§16). | Retry with backoff, but cap retries per payload. If the same payload 500s repeatedly, treat it as poison and drop or split it. |
| 400 | Your request is malformed. | **Do not** retry the same payload. Log it as a client bug. |
| 401 | Token missing, unknown or revoked. | §2: close the WS, wipe credentials, re-register. |
| 403 | Path `{id}` ≠ your token's device. | Client bug. Re-read the stored `deviceId` and do not loop. |
| 404 | Endpoint-specific (§14). | See §14. |
| 405 | Wrong method. | Client bug. |
| 409 | Endpoint-specific. **`POST /register`:** serial already registered and no admin window open (§4). **Remote ack:** session terminal. | `/register`: keep retrying every ~5 min with jitter (§4). Remote ack: terminal, stop retrying. |
| 429 | Registration rate limit. | Back off until the next UTC clock hour (§4). |
| 503 | Temporarily unavailable. | Retry later with backoff. |

Suggested timeouts: connect 10 s, read 30 s. Use a 60 s read timeout for `/sync`, which checks storage once per file it offers.

---

## 2. Identity, token lifecycle & auth failures

| Item | Behaviour |
|---|---|
| `deviceId` | int64 assigned at registration. Stable across re-registrations of the same serial inside an admin window. **A new `deviceId`** is assigned when the serial registers again after an admin deleted the device (R23). |
| `deviceToken` | `dtk_` + 32 lowercase hex (36 chars). Opaque. No expiry, no refresh endpoint. |
| Header | `X-Device-Token: <deviceToken>` on every call except `POST /register` and MinIO GETs. |
| Validation | Checked against the database on **every** REST request with no cache, so revocation is immediate for REST. |
| Token becomes invalid when | (a) the same `serialNumber` re-registers **inside an admin-opened window** (§4), which rotates it, or (b) an admin soft-deletes the device. Since 1.0.137 nobody can rotate your token by just calling `/register`. |
| Path binding | The path `{id}` **MUST** equal the token's device, otherwise 403. |

**Auth failures on REST** *(verified)*:

| Response | Cause | Action |
|---|---|---|
| `401` `"Authentication required"` | You sent **no** `X-Device-Token`, or a proxy stripped it. | Fix the client. Re-registering will not help if the header never leaves the device. |
| `401` `"Invalid or revoked device token"` | Unknown token, rotated by an admin-allowed re-registration, or device soft-deleted. | Close the WebSocket, wipe `deviceId` and token, re-register (§4), reconnect. **Keep your local media store**: re-registration does not require re-downloading. If `/register` answers 409, keep playing cached content and retry (§4). |
| `403` `"Insufficient permissions"` | Path `{id}` is not your device. | Client bug. |
| `404` on a device endpoint | Only reachable in a race (a deleted device gets 401 first). | Keep the "3 consecutive device-scoped 404s → re-register" guard as defence. Any non-404 resets the counter. |

**An open WebSocket is not closed when your token is revoked or your device is deleted** *(verified both)*. It keeps receiving pushes. A REST 401 is the authoritative signal, so close the socket yourself.

**Decommissioned devices:** after an admin soft-deletes a device, your token gets 401. Re-registering **the same serial** (since 1.0.146) returns **201** and creates a **new device**: new `deviceId`, new token, default placement, no content until an operator assigns it (R23). It counts against the new-device registration budget (10/hour per IP). Persist the new `deviceId` and token and carry on with the normal flow; your media store stays useful if the same content is assigned again. On repeated 5xx from `/register`, still back off exponentially up to 1 hour.

**Serial uniqueness is your responsibility.** Two boxes with the same `serialNumber` cannot both work: since 1.0.137 the second one gets 409 on `/register` for as long as the first holds the serial (before, they evicted each other's token in an endless 401 → re-register ping-pong). Derive the serial from hardware identity where the app has the privilege. Otherwise use `Settings.Secure.ANDROID_ID` (unique per signing key + user + device, resets on factory reset). Only as a last resort use a generated UUID persisted in app storage. **Never ship a device image with pre-populated app data.** The serial is case-sensitive and must match `^[A-Za-z0-9][A-Za-z0-9._:-]*$` (≤ 100 chars): whitespace or any other character gets 400.

---

## 3. Endpoint inventory (device-facing)

| # | Method & path | Auth | Request | Success | Purpose |
|---|---|---|---|---|---|
| 1 | `POST /api/devices/register` | **none** | JSON | `201` / `200` | Obtain `deviceId` + `deviceToken`. §4 |
| 2 | `POST /api/devices/{id}/heartbeat` | token | JSON, optional | `200` | Liveness, desired state, action pickup. §5 |
| 3 | `GET /api/devices/{id}/sync` | token | query | `200` | Content diff, play order, URLs, schedule. §6 |
| 4 | `POST /api/devices/{id}/sync/confirm` | token | JSON | `200` | Go-live acknowledgement. §6.6 |
| 5 | `GET /api/devices/{id}/time` | token | — | `200` | Server clock for offset estimation. §7.1 |
| 6 | `GET /api/devices/{id}/actions/pending` | token | — | `200` (array) | Pending actions with payloads. §8 |
| 7 | `POST /api/devices/{id}/actions/{actionId}/confirm` | token | JSON | `200` | Report an action's outcome. §8.5 |
| 8 | `POST /api/devices/{id}/remote/{sessionId}/ack` | token | JSON | `200` | Remote-session outcome. §9 |
| 9 | `POST /api/devices/{id}/playback` | token | JSON object or array | `200` | Proof-of-play telemetry. §11 |
| 10 | `GET /api/devices/{id}/playlist` | token | — | `200` | **Optional** read-only snapshot. §6.8 |
| 11 | `WS /ws/devices/{id}` | token | — | `101` | Push channel. §10 |
| 12 | `GET <presignedUrl>` | signature in URL | — | `200` / `206` | Media download. §6.5 |

Nothing else is meant for devices. Operator endpoints return **403** for a device token. The few public endpoints (`/api/auth/**`, `/api/health`, password reset) are for humans and monitoring, not part of this contract.

---

## 4. Registration

**`POST /api/devices/register`**, with no `X-Device-Token` header.

```jsonc
// request
{
  "serialNumber": "SPEC-VERIFY-1",   // REQUIRED. ≤100 chars, ^[A-Za-z0-9][A-Za-z0-9._:-]*$, stable per physical box (§2)
  "deviceName":   "Verify box"       // OPTIONAL. ≤200 chars. Used only when the device row is created
}
```

```jsonc
// 201 Created (first registration) — verified
{ "deviceId": 1, "deviceToken": "dtk_2730e287694d40d7bd39aa71b7f2e442",
  "serialNumber": "SPEC-VERIFY-1", "status": "registered", "syncGroupId": "reg--1" }

// 200 OK (re-registration of an existing serial — only inside an admin-opened window, since 1.0.137)
{ "deviceId": 1, "deviceToken": "dtk_2425d2344a3444299c794e185a66a937",
  "serialNumber": "SPEC-VERIFY-1", "status": "re-registered", "syncGroupId": "reg--1" }
```

| Field | Type | Meaning |
|---|---|---|
| `deviceId` | int64 | Your id for every path. Unchanged on re-registration inside an admin window; **new** after the device was deleted (R23). Always persist it. |
| `deviceToken` | string | **Always a fresh token.** On 200 the previous token died at that instant. |
| `serialNumber` | string | Echo. |
| `status` | `"registered"` \| `"re-registered"` | Informational only. |
| `syncGroupId` | string \| null | Provisional sync-group label. A new device sits in the "Unassigned" region, so this is `"reg--1"` (note the double hyphen: the id is `-1`). Opaque. The authoritative value arrives on every heartbeat. |

**Server-side effects you must know about:**
- A **new** device is placed in the default "Unassigned" region, named `deviceName` or `"Device-<serial>"`.
- **Re-registration of an already-registered serial is refused with `409`** unless an ADMIN opened a re-registration window for that device (device page → *Allow re-registration*, 1 hour by default). The first registration inside the window uses it up; a second one gets 409 again. This closes device takeover by anyone who knows a serial.
- **Re-registration** (inside a window) rotates the token and **clears the server's record of your confirmed content version** and any in-flight sync marker. Placement, groups, volume and name are all kept; `deviceName` is **ignored** on re-registration. Because the stored version is cleared, your next WS connect replays `SYNC_REQUIRED` (§10.2). Sending your confirmed version on the next heartbeat restores the server's view without a re-download.
- **Registration does not count as a heartbeat.** The operator console shows the device OFFLINE until the first beat, so **send a heartbeat immediately after registering**.

**MUST:**
- Persist the **token first, then `deviceId`**, so a crash can never leave an id without a usable token.
- Treat 201 and 200 identically. Both return a valid pair; overwrite what you had.
- On **409** (already registered, no window): do **not** wipe your media store and do **not** treat it as terminal. Keep playing cached content, retry every ~5 min with ±60 s jitter, and show "waiting for operator to allow re-registration" in diagnostics. The operator fixes it with one click; your next retry then gets 200. (A window is closed early by any heartbeat from the device, since a beat proves the box still holds its token.)
- Skip the call when you already hold both a `deviceId` and a token.
- Never send `X-Device-Token` on this call. A stale header gets `401 "Invalid or revoked device token"` before registration runs *(verified)*, and would lock a revoked device out forever.

**Rate limit** *(verified)*: **10 attempts per source IP per UTC clock hour.**
- The window is fixed (bucket = `epochSeconds / 3600`), not rolling. It resets at the top of each hour.
- The "source IP" is your public address as seen by the reverse proxy (since 1.0.138 the backend ignores any `X-Forwarded-For` the client itself sends), so **every box behind one venue NAT shares the 10**.
- Every attempt that passes body validation counts, **including ones that then fail with 500**. 400s and stale-token 401s do not count.
- **Since 1.0.137, attempts on an already-registered serial (409, or 200 inside an admin window) count against a separate budget of 60 per source IP per hour**, so a box waiting for re-registration does not starve new boxes behind the same NAT. Over that budget you get 429 as above.
- The 11th attempt gets `429` with `"message": "Device registration rate limit exceeded (10/hour) for this source"` and **no `Retry-After` header**.
- **Client behaviour:** on 429, wait until the next UTC hour plus random jitter of 0–5 min. For a fleet install of more than 10 boxes at one site, stagger registrations or ask the backend to raise `app.device.register-rate-limit-per-hour`.

| Status | Cause |
|---|---|
| 201 | Created, or first registration of a pre-provisioned serial. |
| 200 | Re-registration inside an admin window; token rotated. |
| 400 | Missing, blank, over-long (> 100) or badly formed `serialNumber`; `deviceName` > 200; malformed JSON. |
| 401 | You sent an invalid `X-Device-Token` header. |
| 409 | Serial already registered and no admin re-registration window open. Retry every ~5 min. |
| 429 | Rate limit. |
| 500 | The serial belongs to a soft-deleted device (§2), or a missing `Content-Type`. |

---

## 5. Heartbeat

**`POST /api/devices/{id}/heartbeat`**. It is idempotent and safe to retry.

### 5.1 Request

The body is optional: no body, an empty body and `{}` are all accepted *(verified)*.

```jsonc
{
  "contentVersion": "875250576ce90c2a2d9edad015654e974d6e0972460aadf6149c1ff4b55364af",
  "volume": 45,
  "remote": {                     // OPTIONAL — remote view/control capability (§9.1)
    "supported": true,
    "input": "ROOT",              // ROOT | ACCESSIBILITY | NONE
    "transport": "SCRCPY_WS",     // SCRCPY_WS | NONE
    "maxWidth": 1280,
    "maxHeight": 720
  }
}
```

| Field | Type | Rules |
|---|---|---|
| `contentVersion` | string \| null | Your **last CONFIRMED** version (§6.6). `null`, absent and `""` all mean "I hold no content". **Send it on every beat once you have one.** If you omit it while content is assigned, `syncRequired` stays `true` *(verified)* and after 30 minutes the server opens a `CONTENT_VERSION_MISMATCH` incident. Whatever non-blank value you send is stored as your current version, which drives the WS replay (§10.2). Max 64 chars, otherwise the beat fails with 500. |
| `volume` | int 0–100 \| null | Your **actual** output volume on a 0–100 scale. Out-of-range values are clamped. `null` or absent keeps the last known value. |
| `remote` | object \| null | §9.1. Semantically bad values are dropped with a server WARN, but **JSON type errors fail the whole beat with 400** *(verified)*. |

### 5.2 Response

*(verified)*

```json
{
  "deviceId": 1,
  "status": "ONLINE",
  "serverTime": "2026-09-14T17:42:23.705927232Z",
  "pendingActions": [
    { "actionId": 2, "actionType": "VOLUME_SET", "payload": "{\"volume\":35}",
      "issuedAt": "2026-09-14T17:42:21.521544Z", "expiresAt": "2026-09-14T17:47:21.521544Z" }
  ],
  "expectedContentVersion": "a7bb9017ee84741ef1f668e57e4b3ce3c246d3ba95f8c982e1dd88f69730e668",
  "syncRequired": true,
  "desiredVolume": 100,
  "syncGroupId": "reg--1",
  "desiredRemoteSession": null
}
```

| Field | Type | What you do with it |
|---|---|---|
| `deviceId` | int64 | Echo; ignore. |
| `status` | string | `ONLINE` or `NO_CONTENT` in practice; the enum also has `OFFLINE` and `UNREGISTERED`. Server-derived. **Do not branch on it**; log it. |
| `serverTime` | ISO instant | The server clock when the response was built. Good enough for a coarse clock offset; use `/time` for precision (§7.1). |
| `pendingActions` | array of PendingAction | Ordered by `issuedAt` ascending. **May include actions whose `expiresAt` has already passed** but that the janitor has not swept yet (it runs every minute) *(verified)*. Handle per §8. |
| `expectedContentVersion` | string \| null | The version the server wants you on. `null` means no content is assigned (§6.7). |
| `syncRequired` | bool | `true` when **either** content is assigned and it differs from the `contentVersion` you sent, **or** a `SYNC_CONTENT` action is pending. Run the sync flow (§6). It does **not** become true for a sync-group jump (§7.5). |
| `desiredVolume` | int 0–100 | **Always present.** The per-device override, else the device-group volume, else 100. Set your output to it on every beat. It is desired state: an offline or reset device self-heals on its next beat. |
| `syncGroupId` | string \| null | Opaque playback-group label (`"sg-9"`, `"fac-42"`, `"grp-7"`, `"reg-3"`, `"reg--1"`). Re-read it every beat, since operators re-group devices and reassignment can clear an `sg-` membership. **Never parse the prefix.** Informational for the time layer (§7.2). In practice never `null`, because every device has a region. |
| `desiredRemoteSession` | object \| null | Desired remote-session state (§9). **Always `null` while remote control is disabled on the server, which is the case in production today.** |

### 5.3 Cadence

- Every **120 s ± 10 s jitter**, self-rescheduling. Never run two beats concurrently.
- Also beat **immediately** after registration, on app start, and when the network comes back.
- The server marks a device OFFLINE after **15 minutes + 60 s grace** without a beat and raises a `DEVICE_OFFLINE` incident, so one missed beat is harmless.
- Skip the beat when you have no `deviceId`.
- Status handling: 401 → §2. 400 → fix the payload; a beat that 400s does not count as liveness. 5xx or network → try again on the next tick.

---

## 6. Content sync

### 6.1 Triggers

Run the sync flow (§6.2–§6.6) when any of these happens. Use a **single-flight** coordinator: if a trigger arrives mid-sync, mark the coordinator dirty and run exactly one more pass after the current one. Never abort in-flight downloads.

| Trigger | Channel |
|---|---|
| `syncRequired: true` on a heartbeat | REST |
| WS `SYNC_CONTENT`, **any** `reason`, even if you believe you are current, because the anchor may have moved (§7.5) | WS |
| WS `SYNC_REQUIRED` | WS connect replay |
| A pending `SYNC_CONTENT` action | heartbeat, poll or replay (§8) |
| App start, and every WebSocket (re)connect | local; this is how you recover a group jump you missed (§7.5) |
| Every 10 min while in schedule mode (§7) | local |
| Downloads still pending with `presignedUrlsExpireAt` less than 10 min away, or a 403 from MinIO | local |
| A local media file is missing or fails re-verification | local; re-sync **without** that id in `currentFileIds` |

Coalesce bursts, e.g. debounce 500 ms, but keep the delay well under the 5 s jump lead (§7.5). Space non-push-triggered `/sync` calls at least 30 s apart.

### 6.2 `GET /api/devices/{id}/sync`: request

```
GET /api/devices/12/sync?currentVersion=a7bb90…e668&currentFileIds=1&currentFileIds=7
GET /api/devices/12/sync?currentVersion=a7bb90…e668&currentFileIds=1,7          (equivalent)
GET /api/devices/12/sync                                                           (nothing confirmed yet)
```

| Param | Rules |
|---|---|
| `currentVersion` | Your last CONFIRMED version. **Omit the parameter entirely** when you have none. An empty `currentVersion=` is **not** treated as absent: it is a non-matching version with `fullSync: false` *(verified)*. |
| `currentFileIds` | int64 ids of the media files you hold **fully downloaded and SHA-256/size-verified on disk right now**. Use either repeated params or a comma list, **never both**: `currentFileIds=5,6&currentFileIds=7` gets 400 *(verified)*. Non-numeric values get 400. Omit it when you hold none. **Never** include partial downloads (the server gives no URL for a file you claim to hold) or files you have already deleted. It is ignored when `currentVersion` is omitted. |

- **Side effect:** when the response carries work (non-empty add/delete, or `expectedContentVersion ≠ currentVersion`), the server arms a sync-pending marker for that `expectedContentVersion`. A matching confirm clears it. If 30 minutes pass without a matching confirm, a `SYNC_TIMEOUT` incident opens and the marker clears; the next `/sync` re-arms it. The 30 minutes count from the **first** `/sync` of the cycle, so refreshing URLs does not reset them.
- **Every call re-signs URLs**, and probes storage once for each file it offers you. It is not free.
- **`503` = object storage is unreachable** (since 1.0.144, R22). The server refuses to hand you a plan that silently leaves files out. Keep playing what you have, do not delete anything, and retry with backoff like any 5xx.
- Keep the URL under ~8 KB (the server's header limit). The comma form is shorter; ~1000 ids fit comfortably.

### 6.3 Response

*(verified; the same `fileId` appears twice because the playlist holds the same clip at two slots)*

```json
{
  "deviceId": 1,
  "expectedContentVersion": "a7bb9017ee84741ef1f668e57e4b3ce3c246d3ba95f8c982e1dd88f69730e668",
  "fullSync": true,
  "filesToAdd": [
    {
      "fileId": 1,
      "name": "clip.mov",
      "contentType": "video/quicktime",
      "sizeBytes": 86135,
      "durationSeconds": 4,
      "checksum": "05bc5a6a3e274f4f5eca4054f57702e3532ae85b52ee0106442d7f6c3cb928df",
      "presignedUrl": "https://minio.<domain>/content-processed/processed/d0733eb4-5b92-470f-9e4f-6d696cebe519.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=…%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20260914T174158Z&X-Amz-Expires=7200&X-Amz-SignedHeaders=host&X-Amz-Signature=…"
    }
  ],
  "filesToDelete": [],
  "playlistOrder": [
    { "index": 0, "position": 0, "fileId": 1, "durationSeconds": 4, "slotStartMs": 0,    "slotDurationMs": 4000 },
    { "index": 1, "position": 1, "fileId": 1, "durationSeconds": 2, "slotStartMs": 4000, "slotDurationMs": 2000 }
  ],
  "presignedUrlExpiryMinutes": 120,
  "presignedUrlsExpireAt": "2026-09-14T19:41:58.893460829Z",
  "syncGroupId": "reg--1",
  "anchorEpochMs": 1789407838945,
  "loopDurationMs": 6000,
  "activateAt": 1789407838945
}
```

**Top level:**

| Field | Type | Meaning |
|---|---|---|
| `deviceId` | int64 | Echo. |
| `expectedContentVersion` | string(64) \| null | The target version. `null` means no content (§6.7). **This is the value you confirm.** |
| `fullSync` | bool | `true` **iff you omitted `currentVersion`**. When true the server ignored `currentFileIds`: `filesToAdd` lists **every** deliverable file, including ones you already hold, and `filesToDelete` is **always `[]`**. |
| `filesToAdd` | array | Files to download. Each `fileId` appears once, in first-play order. Only files that are transcoded **and** present in storage. |
| `filesToDelete` | int64[] | Ascending. The subset of your `currentFileIds` that is no longer deliverable. With no content assigned it is **all** your `currentFileIds`. |
| `playlistOrder` | array | The play order, sorted by `index`. |
| `presignedUrlExpiryMinutes` | int | URL lifetime (default 120). |
| `presignedUrlsExpireAt` | ISO instant | Hard deadline for **these** URLs. It is computed a few ms before signing, so it is safely conservative. |
| `syncGroupId` | string \| null | Same label as the heartbeat. |
| `anchorEpochMs` | int64 \| null | Loop T0 for schedule mode (§7). `null` when there is no content or the anchor was unavailable this round. |
| `loopDurationMs` | int64 | Σ `slotDurationMs`. **Always present**; `0` when `playlistOrder` is empty. |
| `activateAt` | int64 \| null | Coordinated cut-over instant (§7). |

**`filesToAdd[]`:**

| Field | Type | Meaning |
|---|---|---|
| `fileId` | int64 | Stable id. **The bytes behind a delivered `fileId` never change**, so cache by `fileId`. |
| `name` | string | The **original upload filename** (e.g. `clip.mov`). Display and logging only; never derive an extension from it. |
| `contentType` | string | MIME type of the **original upload** (e.g. `video/quicktime`). **Not** the type you download, which is always `video/mp4` *(verified)*. Do not pick a decoder from it. |
| `sizeBytes` | int64 | Exact byte size of the object the URL serves (== `Content-Length`) *(verified)*. |
| `durationSeconds` | int \| null | The file's **natural** duration, rounded to the nearest second. **Not** the slot duration; use `playlistOrder[]` for timing. |
| `checksum` | string \| null | SHA-256 of the served bytes as 64 lowercase hex chars *(verified)*. Verify when non-null. |
| `presignedUrl` | string | HTTPS GET URL (§6.5). |

**`playlistOrder[]`:**

| Field | Type | Meaning |
|---|---|---|
| `index` | int | **Contiguous 0-based play position.** Order by it, address by it (`PLAYLIST_CONTROL` JUMP), log by it. |
| `position` | int | The raw playlist slot. **May be sparse** (e.g. `0, 2`) when an item was filtered out. Ignore it. |
| `fileId` | int64 | The media for this slot. **May repeat across entries.** |
| `durationSeconds` | int \| null | Effective dwell: the operator's per-item override if set, otherwise the file's natural duration. |
| `slotStartMs` | int64 | Loop-relative start (prefix sum). |
| `slotDurationMs` | int64 | **Always > 0.** `durationSeconds × 1000`, or `10000` when `durationSeconds` is null or 0. **This is the authoritative slot length.** |

**The deliverable-set rule:** `playlistOrder` contains exactly the files offered in `filesToAdd` this round plus the files you reported holding that are still in the playlist. If the server cannot presign a file you do not hold (a storage glitch), that item is **absent** from `playlistOrder` this round and the `index` values are renumbered around it. Its pending order is shorter; it is not broken.

### 6.4 Applying the diff

1. **Stage, don't switch.** Store the response as the *pending* state: version, `playlistOrder`, `anchorEpochMs`, `activateAt`, `loopDurationMs`, `syncGroupId`. The live playlist keeps playing.
2. **`filesToAdd`**: if you already hold that `fileId` with a matching size and checksum (routine when `fullSync` is true), do nothing. Otherwise queue a download and keep any partial bytes from earlier attempts.
3. **`filesToDelete`**: mark those ids for deletion. **Physically delete only after** the new version is live (and in schedule mode after `activateAt`), and only if neither the live nor the pending playlist references the id.
4. **Model the playlist by `index`, not by `fileId`.** A `fileId` can sit at several indexes with different `slotDurationMs` values *(verified: the same clip at index 0 for 4 s and index 1 for 2 s)*.
5. **A version change can come with no file delta.** Reorders, dwell edits, and adding a file you already hold all produce a new `expectedContentVersion` with empty add and delete lists. Apply the new order and **still confirm** (§6.6).
6. **Nothing changed** (same version, empty diff, same anchor and `activateAt`): nothing to do. Confirming anyway is harmless.
7. **Garbage-collect after every CONFIRMED promotion:** delete every local media file whose id is not referenced by the live or pending `playlistOrder`. This is **required**, because a full sync never lists deletions.

**What changes `expectedContentVersion`:** a different resolved assignment, or any change to the assigned playlist's items. That covers add, remove, move, reorder, dwell edit, and a file's processed object changing. **What does not:** a volume change, sync-group membership, operator exclusions of *other* devices, and a group jump.

### 6.5 Download & verify (MinIO)

**Request:**
- `GET <presignedUrl>` **verbatim**. Only the `Host` header is signed. Do not re-encode, reorder or rebuild the query string: turning `%2F` in `X-Amz-Credential` back into `/` breaks the signature and gets 403.
- Send `Accept-Encoding: identity`. OkHttp silently adds `Accept-Encoding: gzip` when there is no `Range` header, and a compressing proxy in front of MinIO would then strip `Content-Length` and `Accept-Ranges`.
- Send `Range: bytes=<alreadyHave>-` to resume a partial download.
- Do not send `X-Device-Token`.

**Responses** *(verified)*:

| Status | Meaning | Action |
|---|---|---|
| `200` | Full body. `Content-Length == sizeBytes`, `Content-Type: video/mp4`, `Accept-Ranges: bytes`. | Stream to a temp file. If you had asked for a `Range` and still got 200, truncate the temp file and restart from 0. |
| `206` | Partial body, with `Content-Range: bytes 100-86134/86135`. | Append. |
| `403` | Signature expired or invalid; the body is S3 XML. MinIO never answers 410. | Keep the partial bytes, run `/sync` for a fresh URL, then resume. This works only because you did **not** list the file in `currentFileIds` (§6.2). |
| `404` | Object gone. | Discard the partial and re-sync; the server will stop offering it. |
| `5xx` / network | — | Retry with backoff. |

**`ETag` is not the checksum** (it is an MD5-style S3 ETag). Never compare it to `checksum`.

**MUST:**
- **Check free space before downloading.** Require `sizeBytes` of every queued download plus a safety margin, e.g. 10 % or 50 MB.
- **Verify on completion:**
  1. Bytes written **==** `sizeBytes`.
  2. When `checksum != null`, the lowercase-hex SHA-256 of the file **==** `checksum`.
  3. On a mismatch, delete the file and re-download from byte 0. After 2 consecutive failures mark the file ERROR and retry on a later sync.
- `fsync` the temp file, then atomically rename it into the store. A file becomes **playable** and **reportable in `currentFileIds`** only after verification.
- **Refresh URLs proactively:** when server time is within 10 min of `presignedUrlsExpireAt` and downloads are still pending, run `/sync`.
- Download at most **2** files in parallel. The storage host is small.

**What you receive** (the transcoder's output):
- **Container:** MP4 with fast-start.
- **Video:** H.264 (High profile for normal 8-bit sources, verified), scaled down to fit **1920×1080** by default with aspect ratio preserved. That cap is server-configurable. The frame rate is kept from the source.
- **Audio:** AAC-LC, 128 kbit/s, **present only if the source had audio**.
- **Pixel format and H.264 profile are forced since backend v1.0.149:** every processed file is 8-bit 4:2:0 H.264 High profile (level chosen by the encoder: 3.1 for 720p30 up to 4.2 for 1080p60). Files transcoded before v1.0.149 from a 10-bit or 4:2:2 source can still be High 10 or High 4:2:2 until they are retranscoded, so keep the defence below. Handle a decoder failure per item: keep the slot timing, show black or hold the previous frame for that slot, and record the `fileId` plus error for diagnostics (§8.3 `GET_DIAGNOSTICS`). **Never crash the loop.**

### 6.6 Confirm (go-live)

**Gate:** every `fileId` referenced by the pending `playlistOrder` is held and verified. Deletions do not gate the confirm.

**`POST /api/devices/{id}/sync/confirm`**

```jsonc
// request
{ "reportedVersion": "<expectedContentVersion of your MOST RECENT /sync response>" }

// response — verified
{ "deviceId": 1, "status": "CONFIRMED",
  "expectedVersion": "a7bb9017ee84741ef1f668e57e4b3ce3c246d3ba95f8c982e1dd88f69730e668",
  "reportedVersion": "a7bb9017ee84741ef1f668e57e4b3ce3c246d3ba95f8c982e1dd88f69730e668",
  "syncRequired": false }

// response on a wrong version — verified
{ "deviceId": 1, "status": "MISMATCH", "expectedVersion": "a7bb…e668", "reportedVersion": "old", "syncRequired": true }
```

**How the server judges it:**
- It compares `reportedVersion` with the version your **most recent `/sync` that had work** armed (§6.2).
- If no marker is armed (the last `/sync` had nothing to do, or the marker timed out), it compares with the live expected version.
- If no content is assigned at all, any non-blank value is `CONFIRMED` with `"expectedVersion": null` *(verified)*. **Do not confirm** in that state anyway (§6.7).
- **Whatever you report is stored as your current version, even on `MISMATCH`.**
- **Content changing between your `/sync` and your confirm does not produce `MISMATCH`.** You get `CONFIRMED` for the version you downloaded, and the newer version reaches you through a `SYNC_CONTENT` push or the next heartbeat's `syncRequired`. Keep listening after every CONFIRMED.

**Handle the response:**
- **`CONFIRMED`**: atomically promote pending → live (in schedule mode the promotion takes effect on screen at `activateAt`, §7.3), set `confirmedVersion = reportedVersion`, persist, run the §6.4 GC, and send `confirmedVersion` on subsequent heartbeats.
- **`MISMATCH`** (`syncRequired: true`): you confirmed something other than what your latest `/sync` gave you. **Do not retry the confirm with the same value.** Run `/sync` again and confirm its `expectedContentVersion`.

**Rules:**
- **The confirm is idempotent.** Repeating a CONFIRMED confirm returns `CONFIRMED` again *(verified)*, so retrying on network or 5xx errors is safe.
- **Confirm no-file syncs too** (reorders and dwell edits). The server armed a marker, and only your confirm clears it.
- **Confirm promptly.** The 30-minute `SYNC_TIMEOUT` incident clock started at the first `/sync` of the cycle.
- In schedule mode, confirm **as soon as the files are ready, even before `activateAt`**. `activateAt` governs what is on screen, not when you confirm, and your heartbeat reports the confirmed version while the old content is still playing out.
- A blank or missing `reportedVersion` gets 400. More than 64 chars gets 500. A missing `Content-Type` gets 500.

### 6.7 The "no content" state

When `expectedContentVersion` is `null` (no active assignment, the window ended, or the device was excluded) *(verified)*:
- `/sync` returns `filesToAdd: []`, `playlistOrder: []`, `loopDurationMs: 0`, and `anchorEpochMs` / `activateAt` both `null`.
- `filesToDelete` holds your `currentFileIds`, but only when you sent `currentVersion`.
- `syncGroupId` is still set.
- The heartbeat returns `status: "NO_CONTENT"` and `syncRequired: false`.

**Device behaviour:**
1. Stop playback and show the idle/branding screen.
2. Delete held media. That is the product decision: a lapse and a cancellation look the same to the server.
3. Set `confirmedVersion = null`, so heartbeats send `contentVersion: null`.
4. **Do not confirm** anything.

### 6.8 `GET /api/devices/{id}/playlist` (optional)

A stateless snapshot. **Not required**; `/sync` plus confirm is the play contract. It re-signs a URL for every item on each call, has no slot or anchor fields, and neither arms nor clears the sync marker. Use it for diagnostics only.

```json
{ "deviceId": 1, "playlistId": 1, "playlistName": "verify",
  "contentVersion": "875250576ce90c2a2d9edad015654e974d6e0972460aadf6149c1ff4b55364af",
  "totalDurationSeconds": 5,
  "items": [
    { "index": 0, "position": 0, "fileId": 1, "name": "clip.mov", "contentType": "video/quicktime",
      "presignedUrl": "https://…", "durationSeconds": 3,
      "checksum": "05bc5a6a…28df", "sizeBytes": 86135 }
  ] }
```

With no content it returns `200` with `playlistId: null`, `playlistName: null`, `contentVersion: null`, `totalDurationSeconds: 0` and `items: []` *(verified)*. It never returns 404 for "no playlist".

---

## 7. Synchronized playback: the time layer

This layer is optional and additive. A device that ignores it free-runs the loop (§7.8).

### 7.1 Server clock: `GET /api/devices/{id}/time`

```json
{ "serverUnixMs": 1789407628919 }
```

The endpoint is cheap: no database work and no liveness side effects.

**Offset estimation:**
1. Take 5 samples back to back.
2. For each, record `t0 = elapsedRealtime()` before the call and `t1` after it; `rtt = t1 − t0`.
3. Keep the sample with the smallest `rtt`.
4. `serverNowAt(e) = serverUnixMs + (e − (t0 + t1) / 2)`.

Re-estimate every ~10 min, after network changes, and after any wall-clock change broadcast. Never trust the device wall clock.

### 7.2 When schedule mode applies

- Schedule mode is on when `anchorEpochMs != null && activateAt != null && loopDurationMs > 0`.
- **The anchor belongs to the assignment, not to `syncGroupId`.** Every device that resolves the same assignment gets the same anchor and the same slot timeline, whatever its `syncGroupId` label. `syncGroupId` is informational.

### 7.3 What is on screen right now

```text
syncedNow = serverNow()

schedule = (pending exists AND syncedNow >= pending.activateAt) ? pending : live
           // before activateAt keep the live version; after it, the confirmed pending version takes over
           // no live version at all (fresh device)? use pending immediately — the formula below is
           // well-defined for negative elapsed, and every device computes the same answer

elapsed  = floorMod(syncedNow − schedule.anchorEpochMs, schedule.loopDurationMs)
slot     = last entry with slotStartMs <= elapsed
offsetMs = elapsed − slot.slotStartMs          // seek the media here on join
```

- After a reboot or reconnect, re-derive the position and seek straight there. **Never restart the loop at index 0.**
- Only switch to a pending schedule whose files are all verified. A straggler joins at the live position when it is ready.
- `slotDurationMs` is authoritative.

### 7.4 `activateAt` in practice

- The anchor is created the first time anyone (a device's `/sync` or the server's readiness monitor) sees a **content version**: `activateAt = anchorEpochMs = now + lead`. Since backend 1.0.153 the lead is the download the cut-over implies — at least 2 minutes, at most 15, longer the more bytes it adds.
- It is **immutable for the life of that content version.** A playlist edit produces a new `expectedContentVersion` and therefore a **new anchor with a new, future `activateAt`** — every device on the assignment is handed the same one, so they all switch at that instant.
- Before 1.0.153 an edit reused the assignment's original anchor, whose instant had long passed, so each screen switched the moment it finished downloading and a multi-screen site drifted apart until the slowest download landed. If you special-cased edits as "switch as soon as confirmed", **remove that** — it now breaks the very sync it used to approximate.
- Get the behaviour from the §7.3 formula alone: hold the live version until `activateAt`, then switch. A past `activateAt` (a brand-new assignment you are late to) still switches at once, so one rule covers both.
- Expect the on-screen position to jump at the switch when `loopDurationMs` changed. That jump is correct.
- Keep the old version's files until the cut-over has happened.

### 7.5 Operator group jump

An operator can make an explicit sync group (`sg-…`) jump to a chosen index. The server writes a group override with `activateAt = now + 5 s` and `anchorEpochMs = activateAt − slotStartMs[index]`, then pushes WS `SYNC_CONTENT` with `"reason": "sync-group-jump"`.

- **`expectedContentVersion` does not change and there is no file delta.** Only `anchorEpochMs` and `activateAt` change. **Re-read both from every `/sync` response** and apply them at `activateAt` exactly like a cut-over. There is no new field and no new action.
- **Heartbeats do not signal a jump** (`syncRequired` stays false), and **the WS connect replay does not resend it.** A device that was disconnected at jump time learns about it only on its next `/sync`. That is why §6.1 requires a `/sync` on every WS (re)connect and every 10 min in schedule mode.
- The override self-clears when the content version changes; the next `/sync` returns the base anchor again.
- A device placed in or removed from a sync group sees it only as a different `syncGroupId` label.

### 7.6 Media length vs slot length

- `durationSeconds` is rounded to whole seconds, so media is up to ±0.5 s off its natural slot, and operators can set any dwell from 1 s to 86400 s. The media will often be shorter or longer than its slot.
- The backend does not prescribe what happens. **Recommended:**
  - The slot is authoritative. Cut the media at the slot end.
  - If the media ends early, **hold the last frame** until the slot ends.
  - When joining with `offsetMs` beyond the media length, show the last frame.
- Pick one behaviour and apply it on every device, or frame alignment breaks.

### 7.7 Audio

Volume stays out of band (heartbeat `desiredVolume`, §5.2). It is not a schedule concern.

### 7.8 Free-run (no schedule)

When the schedule fields are null, loop `playlistOrder` by `index`, holding each item for `slotDurationMs`. Start at index 0 only on a cold start with no remembered position.

---

## 8. Remote actions

### 8.1 How actions reach you

*(verified)*

| Channel | Carries payload? | When |
|---|---|---|
| Heartbeat `pendingActions[]` | yes | Every beat, until the action is confirmed, expired **and** swept. |
| `GET /api/devices/{id}/actions/pending` | yes | Any time. Returns a plain JSON array of PendingAction, same shape and order as the heartbeat. |
| WS `ACTION_PENDING` frame | **no** (id, type and `issuedAt` only) | **Pushed when the action is issued** (backend v1.0.150+, after the issuing transaction commits), and **replayed on every socket connect**. |

Consequences:
- While the socket is connected, a new action normally reaches you **within a second** as an `ACTION_PENDING` frame. The push is best-effort (a failed write is not retried), so the **next heartbeat** (≤120 s) is still the guarantee.
- While the socket is **disconnected**, poll `/actions/pending` every **30 s**, and stop when the socket connects (the connect replay covers you).
- The 30–60 s poll while connected, recommended before v1.0.150 for transport controls, is no longer needed.
- An `ACTION_PENDING` frame has no payload. On receiving one, call `GET /actions/pending` once and execute from that response. The same action arrives by push, heartbeat and replay: **deduplicate by `actionId`** (§8.3).

### 8.2 PendingAction shape

```json
{ "actionId": 2, "actionType": "VOLUME_SET", "payload": "{\"volume\":35}",
  "issuedAt": "2026-09-14T17:42:21.521544Z", "expiresAt": "2026-09-14T17:47:21.521544Z" }
```

| Field | Type | Notes |
|---|---|---|
| `actionId` | int64 | Idempotency key. |
| `actionType` | string | See §8.3. Treat it as an open set. |
| `payload` | **string** \| null | A JSON document **encoded as a string**: parse the string, then parse the JSON. Actions issued through the device-group bulk path may carry `null` or any operator-supplied string. If it is missing or does not parse, treat it as `{}`. |
| `issuedAt` / `expiresAt` | ISO instant | `expiresAt` is `issuedAt` + 5 min (+10 min for `PLAYLIST_CONTROL`). |

### 8.3 Action types

Every string you can receive:

| `actionType` | Payload | Device behaviour | Confirm |
|---|---|---|---|
| `SYNC_CONTENT` | `{}` | Trigger the sync flow (§6.1). While it is pending, heartbeats also say `syncRequired: true`. | `SUCCESS` as soon as `/sync` returned 200 and the diff is staged. Do **not** wait for downloads, which can outlive the 5-min expiry. `FAILED` if `/sync` fails non-retryably. |
| `REBOOT` | `{}` | Soft reboot, using the confirm-before-reboot protocol in §8.4. | `SUCCESS` **before** rebooting. |
| `VOLUME_SET` | `{"volume": 0–100}` | Apply immediately. **Caveat:** it does **not** change `desiredVolume`, so the next heartbeat pulls the volume back to `desiredVolume` *(verified: after `VOLUME_SET 35`, `desiredVolume` stayed 100)*. `desiredVolume` is authoritative. | `SUCCESS`, or `FAILED` with a reason. |
| `PLAYBACK_PAUSE` | `{}` | Pause output. The backend keeps **no** paused state: it is not on the heartbeat and not re-sent after a restart. **Do not persist** a pause across process restarts. | `SUCCESS` / `FAILED` |
| `PLAYBACK_RESUME` | `{}` | Resume. In schedule mode re-derive `positionInLoop()`; never continue from the paused frame. | `SUCCESS` / `FAILED` |
| `GET_DIAGNOSTICS` | `{}` | Collect diagnostics and return them in `result` as a JSON string. The backend does not parse it, and operators see it verbatim. Recommended keys: `appVersion`, `androidVersion`, `model`, `serialNumber`, `uptimeSec`, `confirmedVersion`, `pendingVersion`, `syncState`, `liveIndex`, `scheduleMode`, `clockOffsetMs`, `wsConnected`, `freeStorageBytes`, `files: [{fileId, state, sizeBytes}]`, `decoderErrors`, `lastErrors`. Keep it ≤ 64 KB. | `SUCCESS` with `result`. |
| `PLAYLIST_CONTROL` | `{"action":"PREV"}`, `{"action":"NEXT"}`, `{"action":"JUMP","position":N}` | Solo transport control. `position` is the **`index`** from `/sync`, not the raw `position` field. The server range-checked it against its own deliverable count **at issue time**, so it can be stale: clamp or ignore out-of-range values. **In schedule mode** the next `positionInLoop()` tick overrides any manual move; there, don't execute it. The coordinated mechanism is the group jump (§7.5). | Solo: `SUCCESS`. Schedule mode: `FAILED` with `result: "SCHEDULE_MODE"`, so the operator sees why nothing happened. |
| `ASSIGN_CONTENT` | `{"playlistId": N}` | Issued only by the legacy device-group bulk endpoint. **It has no device-side meaning**: assignments are made server-side and content arrives through `/sync`. Do nothing. | `FAILED` with `result: "UNSUPPORTED: content is assigned server-side"`. |
| anything else | — | Ignore. | `FAILED` with `result: "UNSUPPORTED_ACTION_TYPE"`. |

Server guarantees: at most one PENDING action per `(device, actionType)`, and at most 10 pending actions per device.

### 8.4 Execution rules

- **Deduplicate by `actionId`.** The same action shows up on every beat, poll and replay until it is confirmed and swept. Persist the ids you have executed or confirmed for at least 24 h.
- **Execute in `issuedAt` order.**
- **Expired before execution:** if server time is already past `expiresAt` when you first see an action, **do not** perform a side effect (`REBOOT`, `PLAYBACK_*`, `VOLUME_SET`, `PLAYLIST_CONTROL`). Confirm it `FAILED` with `result: "EXPIRED_BEFORE_EXECUTION"`. Stale actions can still appear because the janitor sweeps only once a minute *(verified)*.
- **REBOOT protocol:**
  1. Persist `actionId` in the executed set.
  2. `POST` the confirm with `SUCCESS`, waiting up to 5 s.
  3. If the confirm did not succeed, put it in the durable confirm queue.
  4. Reboot.
  5. After boot, **flush the confirm queue before processing any pending actions**, and skip ids already in the executed set. Without this, a REBOOT that reappears after the reboot causes a reboot loop until it expires.
- **Durable confirm queue:** confirms go into a persisted queue. Retry on network errors and 5xx with backoff. Drop on 4xx after logging.

### 8.5 Confirm contract

**`POST /api/devices/{id}/actions/{actionId}/confirm`**

```jsonc
// request
{ "status": "SUCCESS", "result": "optional free text or a JSON string" }   // status ∈ {SUCCESS, FAILED}, uppercase, required

// response — verified
{ "actionId": 1, "outcome": "CONFIRMED", "finalStatus": "CONFIRMED" }
```

| `outcome` | When | `finalStatus` |
|---|---|---|
| `CONFIRMED` | `SUCCESS` before `expiresAt` | `CONFIRMED` |
| `CONFIRMED_LATE` | `SUCCESS` after `expiresAt`, including an action already swept to EXPIRED *(verified)* | `CONFIRMED_LATE` |
| `FAILED` | `FAILED` reported, at any time | `FAILED` |
| `UNKNOWN` | No such action, or it belongs to another device *(verified)* | `null` |
| `ALREADY_FINALIZED` | The action was already `CONFIRMED`, `CONFIRMED_LATE` or `FAILED` *(verified)* | the existing status |

- Every outcome is `200` and **terminal**. Never retry after a 200.
- A missing `status` gets 400 with `fieldErrors`, and a lowercase status gets 400 *(verified)*. A missing `Content-Type` gets 500.
- `result` is stored as-is with no server limit. Keep it ≤ 64 KB.

---

## 9. Remote view/control sessions

**Current status: the feature ships dark.** Production runs with `app.remote.enabled=false` and the relay server is not deployed, so `desiredRemoteSession` is always `null` and no session frames are sent. The ack endpoint answers regardless of the flag. Everything below is the contract for when the feature is enabled. Reporting the capability block (§9.1) is safe to ship today.

A session is a short-lived rendezvous between one operator browser and your device through a separate **relay**. **The backend never carries media.** It tells both sides where to meet and gives each a signed ticket. Sessions are not remote actions: they have their own channel and their own ack endpoint.

### 9.1 Capability block (heartbeat `remote`)

| Field | Type | Server handling *(verified)* |
|---|---|---|
| `supported` | bool | `false` blocks operators from starting a session: they get **422**. Never reporting the block means "unknown", and operators may still try. |
| `input` | `ROOT` \| `ACCESSIBILITY` \| `NONE` | Trimmed and upper-cased (`"root"` → `ROOT`). Unknown values are dropped with a WARN. `NONE` makes the operator UI show a **View only** badge. |
| `transport` | `SCRCPY_WS` \| `NONE` | Same normalisation. |
| `maxWidth`, `maxHeight` | int | Kept if in (0, 7680], otherwise dropped. `maxWidth` caps the `maxWidth` the server sends you. |

- A `null` or omitted field keeps the previously stored value, so a partial block is fine.
- Semantic problems never fail the beat. **JSON type errors do** (400).

### 9.2 How a session reaches you

- **Heartbeat `desiredRemoteSession`** is the reliable path, with up to one beat of latency.
- **WS `REMOTE_SESSION_START`** carries the same fields, sooner.

```jsonc
// desiredRemoteSession on the heartbeat — verified (WS frame has the same fields plus "type")
{
  "sessionId":   "rs_ab8c2b96ce3bca418b543a29d6af23f5",   // "rs_" + 32 hex; opaque key
  "relayUrl":    "wss://relay.example.uz/agent",
  "agentTicket": "eyJzaWQiOiJyc19hYjhj….pkkcauwUB-XlnocW_oy44WFzCmr6eDmehqqJpEcp9U8",  // opaque
  "expiresAt":   "2026-09-14T18:13:12.041683Z",           // issue time + 30 min (server default)
  "viewOnly":    true,
  "maxWidth":    1280,      // min(server cap 1280, your reported maxWidth)
  "maxFps":      15,
  "bitRate":     2000000
}
```

- **`agentTicket` is deterministic.** It is the identical string on every beat for a session *(verified)*. Never treat a ticket change as a signal; key everything on `sessionId`. The ticket is opaque, so do not decode it.
- There is no `maxHeight`. Preserve aspect ratio from `maxWidth`.
- `desiredRemoteSession` becomes `null` when:
  - an operator stops the session;
  - you ack `FAILED` or `ENDED`;
  - server time passes `expiresAt`, even before the janitor marks it EXPIRED;
  - the feature is disabled.

### 9.3 MUST

1. **Converge; don't queue.** A non-null `desiredRemoteSession` for a `sessionId` you are not running → start it (stop any other session first). `null` → stop whatever you are running. A beat naming the session you already run is a no-op. `ACTIVE` sessions keep appearing on every beat *(verified)*.
2. **Dial `relayUrl` with `agentTicket`.** The relay-side protocol (the expected `?ticket=<agentTicket>` query, stream framing, input events) is defined by the relay contract, **not by this backend**, and is not published yet, so treat it as provisional. The ticket is **single use** per `(sessionId, role)` at the relay. After a process restart during an ACTIVE session the heartbeat still names the session, but the relay will refuse the reused ticket: ack `FAILED` with `error: "relay refused reconnect after restart"` so the operator can start a new session.
3. **Kill the stream at `expiresAt` using server time (§1.4).** This is a hard ceiling that you enforce yourself. An unreachable backend must never leave a box streaming.
4. **Honour `viewOnly`**: send video and accept no input.
5. **Ack every outcome** (§9.4).

### 9.4 Ack: `POST /api/devices/{id}/remote/{sessionId}/ack`

```jsonc
// request
{ "status": "READY",          // READY | FAILED | ENDED — uppercase, required (lowercase → 400, verified)
  "width": 1280, "height": 720, // real capture size; non-positive → stored as null
  "error": null,               // SHOULD be set on FAILED (human-readable; shown to the operator)
  "reason": null }             // on ENDED: "EXPIRED" | "RELAY_LOST" | "USER_STOP" | …; ≤64 chars (truncated); blank → "DEVICE_ENDED"

// 200 — verified
{ "sessionId": "rs_ab8c2b96ce3bca418b543a29d6af23f5", "status": "ACTIVE" }
```

| Ack `status` | Effect | Response `status` |
|---|---|---|
| `READY` | PENDING → ACTIVE. **A repeated READY while ACTIVE is idempotent (200)** *(verified)* and just refreshes the dimensions. | `ACTIVE` |
| `FAILED` | → FAILED. Do not retry silently; the operator needs to see `error`. | `FAILED` |
| `ENDED` | → ENDED. Use `reason: "EXPIRED"` when you hit `expiresAt`. | `ENDED` |

| Code | Meaning | Action |
|---|---|---|
| 200 | Recorded. | — |
| 400 | Bad or missing `status`, or malformed JSON. | Fix the client. |
| 401 / 403 | §2. | — |
| 404 | Unknown session, or it belongs to another device. | Terminal: stop retrying. |
| 409 | The session is already terminal (ENDED, FAILED or EXPIRED). **Expected** when you ack `ENDED` after an operator stop *(verified)*. | Terminal: stop retrying. |

### 9.5 Stop

- An operator stop sends WS `REMOTE_SESSION_STOP` (§10.3), and the next heartbeat returns `desiredRemoteSession: null`.
- Tear the session down.
- You may ack `ENDED`; a 409 is normal there.

---

## 10. WebSocket: `/ws/devices/{id}`

### 10.1 Connecting

- **URL:** `wss://<api-host>/ws/devices/{id}`. The last path segment must be your numeric `deviceId`: no trailing slash, no extra segments.
- **Auth:** the `X-Device-Token` header (**preferred**), or `?device_token=<token>` as a fallback. The query form ends up in proxy access logs.
- **Do not send `Origin`.** No subprotocol is required.
- Open the socket after registration. Hold **one** socket per device.

**Handshake results** *(verified)*:

| Response | Cause | Body |
|---|---|---|
| `101 Switching Protocols` | OK. | — |
| `400` | Path segment is not numeric. | empty |
| `401` | No token, or an unknown or revoked token. | JSON envelope if the bad token came in the header, otherwise empty |
| `403` | The token belongs to a different device id. | empty |
| `403` | An `Origin` header not on the server's browser allow-list was sent. Just don't send `Origin`. | empty |

A 401 on the handshake is handled like a REST 401 (§2), unless you simply forgot to send the token.

### 10.2 What happens on open

On **every** successful connection the server immediately replays, in this order *(verified)*:

1. One **`ACTION_PENDING`** frame per PENDING action, by `issuedAt` ascending. Expired-but-unswept actions are included.
2. **`SYNC_REQUIRED`**, only if content is assigned and it differs from the version the server has stored for you. The stored version is updated by your heartbeat `contentVersion` and by every confirm (even a MISMATCH), and **cleared by re-registration**.

Nothing else is replayed: no `SYNC_CONTENT`, no group jump, and no remote-session frame (the heartbeat covers sessions). So after every (re)connect you **MUST** also run `/sync` once (§6.1).

### 10.3 Inbound frames

Frames are JSON text, discriminated by `type`. **Ignore unknown `type` values** and log them.

**`SYNC_CONTENT`**: live "content or schedule changed" push *(verified)*.
```json
{"type":"SYNC_CONTENT","reason":"playlist-reordered"}
```
- `reason` is informational and open-ended. Current values:
  - `assignment-confirmed`: a new assignment covers you.
  - `assignment-cancelled`: an assignment that drove you was cancelled or narrowed.
  - `playlist-reordered`: your playlist changed (item add, remove, move, reorder or dwell edit).
  - `sync-group-jump`: §7.5.
- Action: run the sync flow (§6.1), **always**, even if your version looks current.
- Server-side batching: pushes go out in batches of 50 devices, 100 ms apart, capped at 5000 devices per confirmed assignment (devices beyond the cap rely on the heartbeat).

**`SYNC_REQUIRED`**: sent **only as a connect replay** (§10.2) *(verified)*.
```json
{"type":"SYNC_REQUIRED","expectedVersion":"875250576ce90c2a2d9edad015654e974d6e0972460aadf6149c1ff4b55364af"}
```
- Action: run the sync flow. `expectedVersion` is advisory: confirm whatever `/sync` returns.

**`ACTION_PENDING`**: pushed when an action is issued (backend v1.0.150+) and replayed on connect (§8.1).
```json
{"type":"ACTION_PENDING","actionId":3,"actionType":"SYNC_CONTENT","issuedAt":"2026-09-14T17:42:21.579375Z"}
```
- It has no `payload` and no `expiresAt`. Action: call `GET /actions/pending` once, then execute per §8.

**`URGENT_CONTENT`**: **removed in backend 1.0.155** (R29). It used to be broadcast to every connected device in the fleet on an "urgent" upload:
```json
{"type":"URGENT_CONTENT","contentFileId":2,"projectId":1}
```
- It was never actionable — at that moment the file is not transcoded and is in no playlist — so this spec always said to ignore it. The server no longer sends it. A file reaches you the ordinary way: it is added to a playlist, and you receive `SYNC_CONTENT`.
- Against an older server, ignore it as before (debug log only). It **MUST NOT** trigger a download or a sync.

**`REMOTE_SESSION_START`** *(verified)*.
```json
{"type":"REMOTE_SESSION_START","sessionId":"rs_ab8c2b96ce3bca418b543a29d6af23f5","relayUrl":"wss://relay.example.uz/agent","agentTicket":"eyJ…U8","expiresAt":"2026-09-14T18:13:12.041682612Z","viewOnly":true,"maxWidth":1280,"maxFps":15,"bitRate":2000000}
```
- Action: same as a heartbeat `desiredRemoteSession` naming this session (§9.3).

**`REMOTE_SESSION_STOP`** *(verified)*.
```json
{"type":"REMOTE_SESSION_STOP","sessionId":"rs_ab8c2b96ce3bca418b543a29d6af23f5"}
```
- Action: tear down that session now (§9.5).

### 10.4 Delivery guarantees: there are none

- Frames are **at most once and best effort**: no acknowledgement, no sequence numbers, no redelivery.
- A frame can be lost to a reconnect window, a network blip, or two server threads writing to your socket at the same moment.
- The socket map lives inside one backend instance. Behind several replicas, only the replica holding your socket can push to you.
- Design rule: a frame only makes you *act sooner*. Correctness must come from the heartbeat, `/sync` and `/actions/pending`.

### 10.5 Outbound: send nothing

- The device sends **no application frames**. All acknowledgements go over REST.
- A text frame you send is silently ignored and the socket stays open *(verified)*.
- A **binary** frame closes the socket with **1003 "Binary messages not supported"** *(verified)*.

### 10.6 Keepalive

- **The server never sends pings.** Configure client pings; with OkHttp use `pingInterval(30, SECONDS)`.
- The server answers every ping with a pong automatically.
- Without pings, NAT and proxy idle timeouts kill the socket silently and you wait forever. With pings, OkHttp fails the socket on a missed pong and you reconnect.

### 10.7 Close codes

| Code / reason | Cause | Action |
|---|---|---|
| **1008 `superseded`** *(verified)* | Another connection with your token opened after yours. | See §10.8. |
| **1003 `Binary messages not supported`** *(verified)* | You sent a binary frame. | Client bug; reconnect. |
| 1001 | Server shutting down or redeploying. | Reconnect with backoff. |
| 1006 (no close frame) | Network drop, proxy reload, missed pong. | Reconnect with backoff. |
| anything else | — | Reconnect with backoff. |

### 10.8 Reconnect policy

- **Backoff:** 1 s, 2 s, 4 s … capped at 60 s, with ±20 % jitter. Reset the backoff after the socket has stayed up for 60 s.
- **After every successful (re)connect:** process the replay, then run `/sync` once.
- **On `1008 superseded`:**
  - If this process opened a newer socket itself (your own reconnect race), do nothing; the new socket is live.
  - Otherwise **another client holds your token**, e.g. a cloned box (§2). Do **not** reconnect immediately: two clients evicting each other loop forever. Wait **≥ 5 min**, log `"ws superseded by another client"` into diagnostics, and keep heartbeating meanwhile.
- **Revocation is not delivered over the socket** (§2). When a REST call returns 401, close the socket, re-register, and reconnect with the new token.
- **While disconnected:** poll `/actions/pending` every 30 s. Heartbeats and every other behaviour are unaffected.

---

## 11. Playback telemetry: `POST /api/devices/{id}/playback`

This path is off the critical path: a failure here must never block playback.

```jsonc
// request: a single object OR an array of ≤500 objects
[
  { "contentFileId": 1, "playedAt": "2026-09-14T17:44:02.123Z", "durationSeconds": 4 }
]

// 200 — verified
{
  "total": 5, "created": 1, "duplicate": 1, "rejected": 3,
  "rejections": [
    { "index": 2, "reason": "played_at is in the future beyond clock skew tolerance (30s)" },
    { "index": 3, "reason": "contentFileId not assigned to device: 2" },
    { "index": 4, "reason": "played_at is older than the 90-day retention window" }
  ]
}
```

**Entry fields:**

| Field | Type | Rules |
|---|---|---|
| `contentFileId` | int64 | **REQUIRED, never null.** A `null` fails the **whole batch with 500**, and nothing in it is stored *(verified)*. |
| `playedAt` | ISO-8601 string | **REQUIRED, never null.** A `null` also gives 500 for the whole batch. Use `Z` or an explicit offset: an offset-less value gives 400 for the whole batch. Take it from server time (§1.4). Use **millisecond precision**, generate it once, and reuse the exact same value on retries so duplicates are detected. |
| `durationSeconds` | int \| null | Seconds actually shown. |

Unknown fields are ignored.

**Per-entry outcomes** (the request itself still returns 200):
- **created**
- **duplicate**: the same `(device, contentFileId, playedAt)` already exists, or appears earlier in the same batch. Idempotent, so retries are safe.
- **rejected**, final, so never retry these:
  - `playedAt` more than 30 s ahead of server time;
  - `playedAt` older than the server's playback retention window (`app.retention.playback`, **90 days** by default; the reason string quotes the configured value);
  - unknown `contentFileId`;
  - `contentFileId` **not in the playlist your device was assigned at that `playedAt`** (backend 1.0.152+; before that it was checked against the playlist assigned *now*, so plays from either side of a campaign switch were thrown away). The reason string is now `contentFileId not assigned to device at playedAt: X`. A play is still accepted for up to 30 minutes after its campaign ended — you keep playing the old loop until the cut-over — so an ordinary flush after a switch is safe. Flushing promptly still matters, but you no longer have to flush *before* a switch.

**Whole-request failures:**
- more than 500 entries → 400 `"Batch size 501 exceeds maximum 500"` *(verified)*;
- malformed JSON or a malformed entry → 400;
- null fields → 500;
- missing `Content-Type` → 500.

An empty array returns 200 with all zeros *(verified)*.

**Recommended client behaviour:**
- Write **one entry per play of a slot**, completed or interrupted: `playedAt` = slot start in server time, `durationSeconds` = seconds shown.
- Keep a durable local queue. Flush every 60–300 s or when it reaches 200 entries, in chunks of ≤ 500.
- **On 200:** drop every entry in the chunk, rejected ones included.
- **On 400:** split the chunk in halves to isolate the bad entry, then drop it.
- **On 5xx or network error:** retry with backoff. After 3 identical 5xx responses, split the chunk as for 400.
- Cap the queue (e.g. 10 000 entries, dropping the oldest first).
- The endpoint has no rate limit.

---

## 12. Server timers & limits (reference)

| What | Value | Where it matters |
|---|---|---|
| Device OFFLINE threshold | 15 min + 60 s grace | Heartbeat cadence (§5.3). |
| `DEVICE_OFFLINE` / `CONTENT_VERSION_MISMATCH` incident | 15 min offline / 30 min mismatched | Send `contentVersion` every beat. |
| `SYNC_TIMEOUT` incident | 30 min after the first `/sync` with work, if no matching confirm | Confirm promptly (§6.6). |
| Presigned sync URL lifetime | 120 min | §6.5 |
| Coordinated cut-over lead (new assignment) | 2 min | §7.4 |
| Group-jump lead | 5 s | §7.5 |
| Action expiry | 5 min (`PLAYLIST_CONTROL` 10 min) | §8.2 |
| Action / session janitor sweep | every 1 min | Stale items can still appear (§8.4). |
| Max pending actions | 1 per type, 10 per device | — |
| Remote session TTL | 30 min | §9.3 |
| Remote encoder hints | maxWidth 1280, maxFps 15, bitRate 2 000 000 | §9.2 |
| Registration rate limit | 10 per source IP per UTC clock hour | §4 |
| Playback batch max | 500 entries | §11 |
| Playback clock-skew tolerance | +30 s | §11 |
| Playback retention | 90 days (server default; `app.retention.playback`) | §11 |
| Slot fallback dwell | 10 s | §6.3 |
| Operator dwell range | 1 – 86 400 s | §7.6 |
| Transcode cap | 1920×1080 (configurable), H.264 + AAC 128k | §6.5 |
| WS sync push batching | 50 devices per 100 ms, cap 5000 per assignment confirm | §10.3 |
| Server WS pings | **none** | §10.6 |

The server can override every value through its configuration. Do not hardcode server-side values into client logic except as display text.

---

## 13. Recommended client algorithm

**Persistent state:**
- `deviceToken`, `deviceId`
- `confirmedVersion`
- live and pending playlists (`playlistOrder`, `anchorEpochMs`, `activateAt`, `loopDurationMs`, `syncGroupId`)
- the file store (`fileId → path, sizeBytes, sha256, state`)
- the executed-action-id set
- the action-confirm queue
- the playback queue
- the last clock offset

**Boot:**
1. Load credentials. If either is missing, **register** (§4).
2. **Estimate the clock offset** (§7.1).
3. **Flush the action-confirm queue.**
4. **Heartbeat immediately.** Apply `desiredVolume`, ingest `pendingActions`, handle `desiredRemoteSession`.
5. **Open the WebSocket** (§10). Process the replay.
6. **Run `/sync` once** (§6).
7. Start playback from the live playlist, seeking to the live position in schedule mode.

**Steady-state schedulers:**
- heartbeat every 120 s ± 10 s;
- clock re-estimate every 10 min;
- playback flush every 60–300 s;
- `/actions/pending` poll every 30 s while the WS is down;
- `/sync` every 10 min in schedule mode;
- URL-expiry watchdog.

**Sync coordinator (single-flight):**
1. `/sync`, then stage the response (§6.4).
2. Download and verify.
3. Once every pending file is verified, confirm.
4. CONFIRMED → promote at cut-over, GC. MISMATCH → loop back to step 1.

**On any REST 401:** stop the schedulers, close the WS, wipe credentials, re-register, then run Boot from step 2. While `/register` answers **409**, keep playing cached content and retry every ~5 min with jitter (§4).

---

## 14. Status-code matrix

| Endpoint | 200/201 | 400 | 401 | 403 | 404 | 409 | 429 | 500 (deterministic causes) |
|---|---|---|---|---|---|---|---|---|
| `POST /register` | 201 new (also a serial whose device was deleted, R23), 200 re-reg (admin window only) | blank/over-long/badly formed serial, name > 200, bad JSON | stale `X-Device-Token` sent | — | — | already registered, no window (retry) | rate limit | — (**415** for a missing/unsupported Content-Type since 1.0.156, R30) |
| `POST /heartbeat` | ok | JSON type errors | §2 | wrong id | race only | — | — | `contentVersion` > 64 (**415** for Content-Type, R30) |
| `GET /sync` | ok | bad or mixed `currentFileIds` | §2 | wrong id | race only | — | — | — (**503** while object storage is unreachable, R22) |
| `POST /sync/confirm` | CONFIRMED or MISMATCH | blank `reportedVersion` | §2 | wrong id | race only | — | — | > 64 chars (**415** for Content-Type, R30) |
| `GET /time` | ok | — | §2 | wrong id | — | — | — | — |
| `GET /actions/pending` | ok (array) | — | §2 | wrong id | — | — | — | — |
| `POST /actions/{aid}/confirm` | every outcome | missing or lowercase `status` | §2 | wrong id | **never** (UNKNOWN instead) | — | — | — (**415** for Content-Type, R30) |
| `POST /remote/{sid}/ack` | ok | bad `status` | §2 | wrong id | unknown or foreign session | session terminal | — | — (**415** for Content-Type, R30) |
| `POST /playback` | ok (per-entry tally) | > 500, malformed entry | §2 | wrong id | race only | — | — | null `contentFileId` / `playedAt`, no Content-Type |
| `GET /playlist` | ok | — | §2 | wrong id | race only | — | — | — |
| `WS /ws/devices/{id}` | 101 | non-numeric id | no / bad token | wrong id, `Origin` sent | — | — | — | — |
| MinIO GET | 200 / 206 | — | — | expired or bad signature | object gone | — | — | — |

---

## 15. Not the device's concern

Do **not** implement, compute or branch on any of these:

- **Content version computation.** It is a server hash over assignment, playlist items and durations. Echo it; never derive it.
- **Assignment resolution:** targets, priorities, time windows, exclusions and supersede rules. You receive the final order through `/sync`.
- **The diff itself.** Report held ids and the version; the server computes add and delete.
- **Transcoding, buckets, object keys and URL signing.** Follow the URL.
- **Status derivation, incidents, dashboards, telemetry analytics, the sync-timeout timer and cut-over readiness monitoring.**
- **Remote-session bookkeeping:** who is watching, ticket signing, the one-session-per-device rule, the expiry sweep.
- **Operator APIs:** issuing actions, volume overrides, sync-group management, jumps.

---

## 16. Known backend gaps the client must defend against

These are current backend behaviours that are arguably bugs. The client rules above already work around each one. They are listed here so both teams can track them; when one is fixed, this list and the affected section get updated.

| ID | Gap | Client defence |
|---|---|---|
| G-1 | ~~Issuing an action does not push `ACTION_PENDING`; it is only replayed on connect.~~ **Fixed in backend v1.0.150:** pushed after commit. | Heartbeat pickup stays the guarantee (§8.1). |
| G-2 | A group jump is not signalled by the heartbeat or the connect replay. | `/sync` on every (re)connect and every 10 min in schedule mode (§7.5). |
| G-3 | `VOLUME_SET` does not update `desiredVolume`, so it reverts on the next beat. | Treat `desiredVolume` as authoritative (§8.3). |
| G-4 | ~~A missing `Content-Type` gives 500 instead of 415.~~ **Fixed in backend v1.0.156:** it is a 415 naming what the endpoint consumes (R30). | Always send it (§1.2); treat the 415 as final. |
| G-5 | Over-length strings give 500 instead of 400 for versions > 64. **Fixed for `/register` in 1.0.137** (serial and name now give 400). | Enforce the limits client-side (§1.2). |
| G-6 | ~~A soft-deleted device cannot re-register its serial (500).~~ **Fixed in backend 1.0.146:** it registers as a new device (R23). | Persist the new `deviceId` (§2). |
| G-7 | A null `contentFileId` or `playedAt` gives 500 and rolls back the whole playback batch. | Validate before enqueueing (§11). |
| G-8 | Token rotation or device deletion does not close an open WebSocket. | Close it yourself on REST 401 (§10.8). |
| G-9 | ~~`URGENT_CONTENT` is broadcast fleet-wide, unscoped, and carries nothing actionable.~~ **Fixed in backend v1.0.155:** the frame was removed (R29). | Ignore it on older servers (§10.3). |
| G-10 | Concurrent server pushes to one socket are not serialised, so a frame can be dropped. | Frames only accelerate; correctness comes from REST (§10.4). |
| G-11 | ~~The transcoder does not force `yuv420p` or an H.264 profile.~~ **Fixed in backend v1.0.149:** output is forced to 8-bit 4:2:0 High. | Tolerate decoder failures per item (§6.5). |
| G-12 | `filesToAdd[].contentType` and `name` describe the original upload, not the served MP4. | Ignore both for decoding (§6.3). |
| G-13 | The legacy bulk path can issue `ASSIGN_CONTENT`, or REBOOT/SYNC_CONTENT with an arbitrary or null payload. | Tolerant payload parsing; FAILED for unsupported types (§8.2, §8.3). |
| G-14 | ~~The coordinated cut-over (`activateAt` in the future) happens only for a new assignment; edits reuse the old anchor.~~ **Fixed in backend v1.0.153:** the anchor is keyed on the content version, so an edit gets its own future cut-over (R28). | Apply §7.3 to every pending version (§7.4). |
| G-15 | `SYNC_CONTENT` is emitted as a literal string and is not a member of the backend's push-type enum. The wire string is stable and documented here. | None needed. |

---

## 17. End-to-end sequence

```mermaid
sequenceDiagram
    participant Dev as Device
    participant API as Backend REST
    participant WS as WS /ws/devices/{id}
    participant S3 as MinIO (presigned)

    Note over Dev: cold start — no token or no deviceId
    Dev->>API: POST /api/devices/register {serialNumber}  (no X-Device-Token)
    API-->>Dev: 201/200 {deviceId, deviceToken, syncGroupId}
    Dev->>Dev: persist token THEN deviceId
    Dev->>API: GET /{id}/time ×5 (min-RTT offset)
    Dev->>API: POST /{id}/heartbeat {contentVersion?, volume, remote?}
    API-->>Dev: {syncRequired, expectedContentVersion, desiredVolume, pendingActions[], desiredRemoteSession}
    Dev->>WS: connect (X-Device-Token, no Origin, client pings 30s)
    WS-->>Dev: replay: ACTION_PENDING×N, then SYNC_REQUIRED if stale
    Dev->>API: GET /{id}/sync (always once after connect)

    loop every 120s ±10s
        Dev->>API: POST /{id}/heartbeat {contentVersion=confirmed, volume}
        API-->>Dev: desired state + pendingActions (the ONLY live path for new actions while connected)
    end

    alt syncRequired OR WS SYNC_CONTENT/SYNC_REQUIRED OR SYNC_CONTENT action OR (re)connect
        Dev->>API: GET /{id}/sync?currentVersion&currentFileIds(verified only)
        API-->>Dev: {expectedContentVersion, fullSync, filesToAdd[], filesToDelete[], playlistOrder[index…], anchorEpochMs, activateAt}
        loop each file to add (≤2 parallel)
            Dev->>S3: GET presignedUrl (verbatim, Accept-Encoding: identity, Range to resume)
            S3-->>Dev: 200/206 video/mp4
            Dev->>Dev: verify bytes==sizeBytes AND sha256==checksum; atomic rename
        end
        opt 403 from MinIO / URLs near expiry
            Dev->>API: GET /{id}/sync (fresh URLs; partial file NOT listed as held)
        end
        Dev->>API: POST /{id}/sync/confirm {reportedVersion = latest expectedContentVersion}
        alt CONFIRMED
            Dev->>Dev: promote pending→live (on screen at activateAt in schedule mode); GC unreferenced files
        else MISMATCH
            Dev->>API: GET /{id}/sync again (never re-confirm the same value)
        end
    end

    opt pending action (heartbeat / poll / replay)
        Dev->>Dev: dedupe by actionId; skip side effects if already past expiresAt
        Dev->>API: POST /{id}/actions/{actionId}/confirm {status: SUCCESS|FAILED, result}
        Note over Dev: REBOOT: persist id → confirm → reboot → flush confirm queue on boot
    end

    opt any REST 401
        Dev->>WS: close
        Dev->>API: POST /api/devices/register (fresh token) → heartbeat → reconnect → /sync
    end

    Note over Dev: play by index; schedule mode: floorMod(serverNow − anchorEpochMs, loopDurationMs)
    Dev->>API: POST /{id}/playback [≤500 entries] (every 60–300s, off the critical path)
```
