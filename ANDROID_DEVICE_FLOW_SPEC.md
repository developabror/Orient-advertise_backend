# Android Device ↔ Backend Contract — Authoritative Flow Spec

**Audience:** the OrientedTV Android signage client team.
**Purpose:** the *canonical* register → play flow as the backend **actually** behaves today, what the client **MUST** implement, and what is **NOT** the client's concern. This is the source-of-truth contract; where it disagrees with the older `REGISTER_TO_PLAYBACK_FLOW.md` (which described a now-superseded client state), **this document wins**. See §10 for the deltas you must action.

Conventions: **MUST / MUST NOT / SHOULD** are normative. All timestamps are ISO-8601 UTC. All device endpoints are under `/api/devices`.

---

## 0. Mental model (read this first)

1. **The backend is the single source of truth.** The device never decides *what* to play or *what version* is current — it asks, downloads, verifies, confirms, and plays. Every step is **reactive to state**, not a fixed call chain.
2. **Two independent signals tell the device to sync**, and they arrive on two channels:
   - **`syncRequired` / WebSocket `SYNC_REQUIRED`** — "your content is stale."
   - **A `SYNC_CONTENT` action** in `pendingActions` / WebSocket `ACTION_PENDING` — "an operator forced a re-sync."
   Either one means: run the sync flow. (As of backend v1.0.111, `syncRequired` is `true` for *both* causes — see §4.)
3. **Content version is an opaque token.** It is a server-computed SHA-256 string. The device **MUST NOT** parse, compute, or reason about it — only store it and echo it back verbatim.
4. **The device only ever downloads the *processed* object** from a MinIO presigned URL handed to it in `/sync`. It never touches the raw upload, the bucket, or the transcoder.
5. **Identity:** after registration the device has a numeric `deviceId` and a `deviceToken`. Every authenticated call uses the **path `{id}` == its own `deviceId`** and the **`X-Device-Token` header**. A token for device A calling device B's path is a hard **403**.

---

## 1. Authentication & identity

| Item | Value / behavior |
|---|---|
| Auth header | `X-Device-Token: <deviceToken>` on **every** call except `POST /register` |
| Token format | `dtk_<32-hex>` (opaque — do not parse) |
| Principal | the backend resolves the token to your numeric `deviceId`; the path `{id}` **MUST** equal it |
| WebSocket auth | same token via **`X-Device-Token` header** (preferred) **or** `?device_token=<token>` query param on the handshake |

**Status codes you MUST handle on authed calls:**
- **401 Unauthorized** — token missing, unknown, or **revoked** (e.g. after a re-registration issued a new token). → wipe local token + `deviceId`, re-register (§3), reconnect WS.
- **403 Forbidden** — valid token but the path `{id}` isn't yours. This is a client bug (wrong id in the URL); do not retry blindly.
- **404 Not Found** — the device id no longer exists server-side (deleted). → re-register (§3). The client's "3 consecutive device-scoped 404s → force re-register" policy is the right shape; **any non-404 resets the counter**.

> **Not your concern:** how the token is validated/stored server-side, role mapping, or that the token is currently stored in plaintext. You only hold and present it.

---

## 2. Endpoint inventory (device-facing)

| # | Method & path | Auth | Role in flow |
|---|---|---|---|
| 1 | `POST /api/devices/register` | none (permitAll) | obtain `deviceId` + `deviceToken` |
| 2 | `POST /api/devices/{id}/heartbeat` | device | liveness + sync signal + action pickup |
| 3 | `GET /api/devices/{id}/sync` | device | get the diff (files to add/delete, playlist order, presigned URLs) |
| 4 | `POST /api/devices/{id}/sync/confirm` | device | go-live: tell the server you applied a version |
| 5 | `GET /api/devices/{id}/actions/pending` | device | poll for actions **only when WS is down** |
| 6 | `POST /api/devices/{id}/actions/{actionId}/confirm` | device | ack an executed action |
| 7 | `POST /api/devices/{id}/playback` | device | telemetry (off the critical path) |
| 8 | `GET /api/devices/{id}/time` | device | cheap server clock for offset estimation (**synchronized playback**, §9) |
| 9 | `WS /ws/devices/{id}` | device | live push: `SYNC_REQUIRED`, `ACTION_PENDING` |
| — | `GET /api/devices/{id}/playlist` | device | **optional** snapshot of the resolved playlist — **not required** (see §7) |
| — | MinIO presigned `GET` | URL-signed | download the processed media object |

There are **8 REST endpoints you need + 1 WebSocket + MinIO GETs.** `GET /…/playlist` exists (contrary to older notes) but you don't need it. Endpoint 8 (`/time`) and the schedule fields on `/sync` (§5.1) are the **synchronized-playback time layer** — additive; a device that ignores them free-runs solo exactly as before.

---

## 3. Registration

**Call:** `POST /api/devices/register` (no `X-Device-Token`).

```jsonc
// request
{ "serialNumber": "<stable per-device id>", "deviceName": "<optional friendly name>" }
// response  (201 = newly created, 200 = re-registered)
{ "deviceId": 12, "deviceToken": "dtk_…", "serialNumber": "…", "status": "registered",
  "syncGroupId": "reg--1" }   // synchronized-playback group; usually the region-level fallback here
```

**MUST:**
- Derive `serialNumber` from a **stable** identity (hardware serial, else a persisted UUID). The server keys the device on this — a changing serial creates duplicate devices.
- Treat `syncGroupId` here as provisional — a freshly-registered device usually sits on the region-level fallback until an operator places it. The **authoritative** group is delivered on every **heartbeat** (§4); re-read it there. `null` ⇒ free-run solo.
- Persist the response **token first, then the `deviceId`** (so a crash can never leave a device row without a usable token).
- Treat **201 and 200 identically** — both yield a valid `deviceId`+`deviceToken`. (200 = the serial already existed; **the returned token is fresh and the previous token is now invalid** — overwrite it.)
- Skip the network call when you already hold **both** a `deviceId` and a token.

**Rate limit:** registration is limited to **10/hour per source IP** → **429** with a JSON error body. Re-register is not a hot path; if you hit 429 you are registering in a loop — back off (capped exponential is fine) and fix the loop cause.

> **Not your concern:** region/facility/group placement (the server assigns a default), and the `status` string (informational only).

---

## 4. Heartbeat — the liveness beat and sync signal

**Call:** `POST /api/devices/{id}/heartbeat` on a fixed cadence (the current 2-minute self-rescheduling beat is appropriate). Skip the POST if you have no `deviceId` yet.

```jsonc
// request body (optional; send your last CONFIRMED version + your current output volume)
{ "contentVersion": "<last confirmed version, or omit/null if none>", "volume": 45 }
// response
{
  "deviceId": 12,
  "status": "ONLINE",                       // ONLINE | OFFLINE | NO_CONTENT | UNREGISTERED
  "serverTime": "2026-06-04T09:20:26Z",
  "pendingActions": [ /* PendingAction… see §6 */ ],
  "expectedContentVersion": "c6fe…",        // the version the server wants you on (may be null if no content)
  "syncRequired": true,
  "desiredVolume": 70,                      // the target output volume (0–100) — set yours to this
  "syncGroupId": "fac-42"                   // synchronized-playback group; null ⇒ free-run solo
}
```

**What you MUST read and act on:**
- **`syncRequired == true`** → stage a sync: remember `expectedContentVersion` as the target and trigger the sync flow (§5). As of backend v1.0.111 this flag is `true` when **either** your reported version ≠ `expectedContentVersion` **or** a `SYNC_CONTENT` action is pending — so it is now a complete "you should sync" signal.
- **`desiredVolume`** (int, 0–100, always present) → the operator-set target output volume. **Set your audio output to this value** and keep reporting your current `volume` on each beat. This is the convergence loop: *report current → obey `desiredVolume`*. Persistent desired state — an offline/reset device self-heals to the right volume on its next beat. (The one-off `VOLUME_SET` pending action still exists for ad-hoc immediate pokes; both paths are valid.)
- **`pendingActions[]`** (independent of `syncRequired`) → ingest and execute (§6). Always present-or-empty.
- **`syncGroupId`** (string, nullable) → the **authoritative** synchronized-playback group (`facility ?? group ?? region`, prefixed). Devices sharing it are one playback group; re-read it every beat (operators relocate devices). `null` ⇒ free-run solo. Only devices sharing `syncGroupId` **and** the same resolved content version end up frame-aligned. Used only by the sync-time layer (§9); ignore it if you are not implementing synchronized playback.

**Request `volume`** (optional, 0–100) is your device's *current* output volume; the server stores it for the operator console and never fails the beat on a bad/missing value. Omit it only if you genuinely can't read it.

**What you SHOULD ignore:** `status` and `deviceId` are echoes for debugging; the device does not need to branch on `status` (the server derives it from your heartbeat freshness + content state). `serverTime` is only useful as the clock for your own "sync pending since" timer. **Do NOT** ignore `desiredVolume` — it is authoritative.

> **Not your concern:** how `status` is derived, the 15-min offline window, incident/alert escalation. Those are server-side.

---

## 5. The sync flow (the heart of register→play)

This is a strict, gated pipeline. Run it whenever a sync is staged (heartbeat `syncRequired`, WS `SYNC_REQUIRED`, a `SYNC_CONTENT` action, a presigned-URL expiry, or an offline→online edge while a sync is still pending).

### 5.1 Fetch the diff
**Call:** `GET /api/devices/{id}/sync?currentVersion={v}&currentFileIds={id}&currentFileIds={id}…`
- `currentVersion` = your **last CONFIRMED** version (omit/blank when you have none → the server returns a full set).
- `currentFileIds` = the ids of **every** content file you currently hold locally (repeat the param per id), regardless of download status. The server diffs against this.

```jsonc
// response
{
  "deviceId": 12,
  "expectedContentVersion": "c6fe…",
  "fullSync": true,                          // informational — see below
  "filesToAdd": [
    {
      "fileId": 1,
      "name": "promo.mp4",
      "contentType": "video/mp4",
      "sizeBytes": 1890347,                  // EXACT size of the object the URL serves (see §8)
      "durationSeconds": 39,
      "checksum": "9be63ff9…64hex",          // SHA-256 hex of that object (see §8)
      "presignedUrl": "https://minio/content-processed/processed/…?X-Amz-…"
    }
  ],
  "filesToDelete": [ 7, 8 ],                 // fileIds you hold but should drop
  "playlistOrder": [
    { "index": 0, "position": 0, "fileId": 1, "durationSeconds": 39,
      "slotStartMs": 0,     "slotDurationMs": 39000 },   // ── sync-time layer (§9) ──
    { "index": 1, "position": 1, "fileId": 2, "durationSeconds": 15,
      "slotStartMs": 39000, "slotDurationMs": 15000 }
  ],
  "presignedUrlExpiryMinutes": 120,
  "presignedUrlsExpireAt": "2026-06-04T11:20:26Z",

  // ─── synchronized-playback time layer (§9). Epoch-MILLISECOND longs, NOT ISO-8601.
  //     Absent/null for a solo/ungrouped device or before the loop is anchored. ───
  "syncGroupId": "fac-42",                   // == the heartbeat's syncGroupId (§4)
  "anchorEpochMs": 1719830400000,            // loop T0 (== activateAt)
  "loopDurationMs": 95000,                   // Σ slotDurationMs; 0 when nothing is deliverable
  "activateAt": 1719830400000               // coordinated cut-over instant (epoch ms)
}
```

**MUST:**
- Apply as a **delta upsert**: `filesToAdd` → mark for download (preserve already-DOWNLOADED/DOWNLOADING rows for the same id); `filesToDelete` → mark for deletion; stage `playlistOrder` as the *pending* order (do not promote it to the live playlist yet — promotion happens at confirm, §5.3).
- **Order by `index`** (a contiguous, 0-based ordinal over the delivered list) — **not** `position`. `position` is the raw playlist slot (kept for reference) and **may be sparse** (e.g. `0, 2`) when an undeliverable item was filtered out; never use it as a dense array index or for `next = (i+1) % n`. Use `index`.
- **`playlistOrder[].durationSeconds` is the EFFECTIVE per-item duration** (the per-item override, else the file's natural duration) and is the **source of truth for item timing** — non-null for any deliverable item. Do **not** read timing from `filesToAdd[].durationSeconds`: `filesToAdd` excludes files you already hold, so a held item has no entry there.
- Treat `presignedUrlsExpireAt` as the **hard deadline** for the URLs in this response. Re-run `/sync` to refresh URLs before they expire if downloads are still in flight (refreshing ~10 min early is sensible).
- Only files the server considers ready appear in `filesToAdd`. The URL always points at the **processed** object in `content-processed`. (`/playlist` is a parallel, stateless freshness snapshot of the same deliverable set, also carrying `index`; `/sync` is the authoritative play contract.)

**SHOULD ignore:** `fullSync` — the device behavior is identical either way (always a delta upsert against your held ids). It's a server hint, not an instruction. `expectedContentVersion` here equals the version you're syncing toward; if it equals your `currentVersion` the diff is empty (no-op).

> **A version change can have NO file delta.** `expectedContentVersion` differs from your `currentVersion` whenever the order changes too — a **pure reorder** returns empty `filesToAdd`/`filesToDelete` but a new `expectedContentVersion` and a new `playlistOrder`. Apply the new order and **still confirm** it (see §5.3). Don't treat "no files to add/delete" as "nothing to do". *(As of backend v1.0.127 a pure **dwell-time edit** also yields a new `expectedContentVersion` — item durations are now folded into the version hash — so offline/reconnecting devices pick up new slot timings.)*

> **Sync-time layer (§9), synchronized playback — optional, additive.** When `syncGroupId` is non-null and `anchorEpochMs`/`activateAt` are present, the response is a deterministic **schedule**: `slotStartMs`/`slotDurationMs` per item (a prefix-sum timeline; `loopDurationMs = Σ slotDurationMs`), a shared loop anchor `anchorEpochMs`, and a coordinated cut-over `activateAt`. All are **epoch-millisecond `long`s** (not ISO-8601). Every device that resolves the same content version gets the **identical** schedule, so you compute "what's on screen right now" as a pure function of a synced clock, with **no device-to-device traffic**:
> - Estimate your clock offset from `GET /{id}/time` (§2 endpoint 8): sample it a few times against a **monotonic** device clock (`elapsedRealtime`), keep the min-RTT sample. Never trust the device wall clock.
> - `positionInLoop()`: if `syncedNow < activateAt` hold the previous version; else `elapsed = floorMod(syncedNow − anchorEpochMs, loopDurationMs)`, then the item is the last slot with `slotStartMs ≤ elapsed`, offset `elapsed − slotStartMs`.
> - A device that reboots/reconnects re-derives the **live** position and seeks straight there — it **never restarts the loop at item 0**. Devices flip to a new version together at `activateAt`; a straggler that misses it joins at the live position when ready.
> - `slotDurationMs` is authoritative and always positive (a null-duration image falls back to a default dwell). Audio stays out-of-band via heartbeat `desiredVolume` + `VOLUME_SET` — **not** a sync concern. A device that ignores these fields free-runs solo exactly as before.

### 5.2 Download + verify each file (MinIO)
**Call:** `GET <presignedUrl>` (support `Range: bytes=<resumeFrom>-` to resume partials).

**MUST:**
- **Pre-flight space check** before starting (e.g. `sizeBytes` + headroom). `sizeBytes` is now the true size of the object you will receive (§8), so this check is reliable.
- **Verify integrity on completion (this is now mandatory and reliable):**
  1. **Byte size:** bytes written **MUST** equal `sizeBytes`. (Backend v1.0.112 fixed `sizeBytes` to be the processed object's size — the historical mismatch that forced you to disable this check is gone. **Re-enable the strict size check.**)
  2. **Checksum:** when `checksum != null`, compute SHA-256 of the downloaded bytes and it **MUST** equal `checksum` (lowercase hex). Backend v1.0.114 populates this for newly-processed files and backfills legacy ones; treat it as **"verify if present,"** and it will be present for essentially all files post-rollout. Mismatch → discard the partial, re-download.
- On **403/410** from MinIO (URL expired) → the URL is stale, not the file. Keep any partial bytes, re-run `/sync` to get a fresh URL, resume via `Range`.
- A file only becomes **playable** once fully downloaded **and** verified.

### 5.3 Confirm (go-live)
**Gate:** all files required by the pending version are downloaded+verified (nothing still PENDING/DOWNLOADING/ERROR). Deletions need not block.

**Call:** `POST /api/devices/{id}/sync/confirm` body `{ "reportedVersion": "<the version you just fully applied>" }`.

```jsonc
// response
{ "deviceId": 12, "status": "CONFIRMED", "expectedVersion": "c6fe…", "reportedVersion": "c6fe…", "syncRequired": false }
```

**MUST confirm a reorder too:** a pure reorder is a **no-file sync** — `expectedContentVersion` changes but `filesToAdd`/`filesToDelete` are both empty. You **MUST still POST `/sync/confirm`** with the new `expectedContentVersion` after applying the new order. The server arms a sync-pending marker on a reorder, and only your confirm clears it; skipping confirm-on-no-files leaves the marker to time out into an incident.

**Handle `status`:**
- **`CONFIRMED`** → atomically promote the pending playlist to live, set your `currentVersion = reportedVersion`, clear the "sync pending" state. You are live on the new content.
- **`MISMATCH`** (`syncRequired: true`) → the server expected a different version (content changed again mid-sync). **Do NOT retry confirm.** Keep the pending state and **re-run `/sync`** — you'll get the corrected file set + fresh URLs. Loop until `CONFIRMED`.

> **Not your concern:** the server's 30-minute sync-timeout incident timer (it keeps running across a MISMATCH on purpose). You just keep re-syncing until CONFIRMED.

---

## 6. Remote actions

Actions arrive two ways and the device handles them identically:
- **WebSocket** `ACTION_PENDING` frame (live), and replayed on connect.
- **Heartbeat** `pendingActions[]` (poll fallback), and **`GET /api/devices/{id}/actions/pending`** while the WS is disconnected (poll ~30s; stop when WS connects, since the server replays on connect).

```jsonc
// PendingAction (from heartbeat / actions-pending)
{ "actionId": 7, "actionType": "SYNC_CONTENT", "payload": "{}", "issuedAt": "…", "expiresAt": "…" }
```

**Action types and what the device MUST do:**

| `actionType` | Payload | Device action |
|---|---|---|
| `SYNC_CONTENT` | `{}` | Trigger the sync flow (§5) immediately — same as `syncRequired` |
| `REBOOT` | `{}` | Soft-reboot |
| `VOLUME_SET` | `{"volume": 0–100}` | Set output volume |
| `PLAYBACK_PAUSE` | `{}` | Pause playback |
| `PLAYBACK_RESUME` | `{}` | Resume playback |
| `GET_DIAGNOSTICS` | `{}` | Gather diagnostics, return them in the confirm `result` |
| `PLAYLIST_CONTROL` | `{"action":"PREV"|"NEXT"|"JUMP","position":N}` | Transport control. For `JUMP`, **`position` is the 0-based `index` into your delivered playlist** (the same contiguous `index` from `/sync` `playlistOrder` — **not** the raw `position` field). The server validates it against the deliverable count; treat an out-of-range value defensively (clamp/ignore). |

`expiresAt` is ~5 min after issue (`PLAYLIST_CONTROL` ~10 min). Actions are best-effort; an expired action you confirm late is still recorded (see below).

**Confirm every action:** `POST /api/devices/{id}/actions/{actionId}/confirm`

```jsonc
{ "status": "SUCCESS", "result": "<optional free-text / diagnostics JSON>" }   // status ∈ { SUCCESS, FAILED }
```

**MUST:**
- Send `status` as exactly **`SUCCESS`** or **`FAILED`** (these are the only accepted values).
- Confirm is **idempotent and never 404s** for an unknown/expired action — the response `outcome` is one of `CONFIRMED | CONFIRMED_LATE | FAILED | UNKNOWN | ALREADY_FINALIZED`; you may treat all as terminal-success and stop retrying. Retry only on network/5xx; never drop a confirm (queue it if offline).

> **Not your concern:** the server-side action lifecycle (`PENDING → CONFIRMED/CONFIRMED_LATE/EXPIRED/FAILED`), late-confirm bookkeeping, or de-duplication. Just confirm once with SUCCESS/FAILED.

---

## 7. WebSocket (`/ws/devices/{id}`)

Open after registration; reconnect with backoff on any non-`superseded` close.

- **Auth:** `X-Device-Token` header (or `?device_token=`). Missing → 401, wrong id → 403, unknown/revoked → 401.
- **Inbound frames (JSON, discriminated by `type`) — exactly two are emitted today:**
  - `{"type":"SYNC_REQUIRED","expectedVersion":"…"}` → stage a sync toward `expectedVersion` (§5).
  - `{"type":"ACTION_PENDING","actionId":…,"actionType":"…","issuedAt":"…"}` → pick up/execute the action (§6).
  - *(`URGENT_CONTENT` exists in the protocol enum but the backend does **not** currently push it. You may keep a dormant handler, but do not depend on receiving it.)*
- **On connect the server replays** all currently-pending actions and a `SYNC_REQUIRED` if your version is stale — so a fresh connection self-heals missed messages.
- **Duplicate connection:** if the same device opens a second socket, the **older** socket is closed with code **1008 "superseded"**. On `1008 superseded` → do **not** reconnect (another instance owns the socket).
- The device sends **no outbound frames**; the server pings ~every 90s.
- **While the WS is connected, do not poll** `/actions/pending`. Use the poll only as the disconnected fallback.

> **Not your concern:** which server event produced a push (assignment confirmed, content changed, operator action). The frame tells you what to do; the cause is server-side.

---

## 8. Content integrity: `sizeBytes` & `checksum` (important — changed)

The device downloads the **processed** object via the presigned URL. The backend now guarantees the `/sync` metadata describes **that** object:

- **`sizeBytes`** = the exact byte size of the processed object the URL serves (== MinIO `Content-Length`). **MUST** match bytes received. *(Fixed in v1.0.112; legacy rows backfilled in v1.0.114.)*
- **`checksum`** = **SHA-256 hex (lowercase, 64 chars)** of that same processed object. **MUST** match when non-null. *(Added in v1.0.114; backfilled for legacy files on the server's first boot after deploy.)*

**Required client posture:**
1. Re-enable the strict `bytesOnDisk == sizeBytes` check — the prior size drift is resolved.
2. Verify SHA-256 whenever `checksum != null`; you may treat `Content-Length` as a corroborating source of truth, but `sizeBytes` is now correct and required for the pre-download free-space check.
3. During the rollout window a not-yet-reconciled legacy file *could* briefly still have `checksum == null`; "verify if present" keeps you safe. Post-rollout, expect it always present.

> **Not your concern:** how/when the backend computes size+checksum (at transcode time, plus a one-time startup reconciler for old files). You just verify what's handed to you.

---

## 9. What is NOT the device's responsibility (server-side concerns)

Do **not** implement, compute, or branch on any of these:

- **Content version computation.** It's an opaque SHA-256 over the assignment/playlist. Echo `currentVersion`, store `expectedContentVersion`. Never derive it.
- **Assignment resolution** — which region/facility/device-group/playlist applies, priorities, time windows, exclusions. The server resolves it and hands you the final playlist via `/sync`.
- **The diff itself.** You send your held `currentFileIds` + `currentVersion`; the server computes `filesToAdd`/`filesToDelete`. Don't try to pre-compute it.
- **`fullSync`** — ignore it; always do a delta upsert.
- **Transcoding / buckets / object keys / URL signing.** You only follow the presigned URL.
- **`GET /api/devices/{id}/playlist`** — this returns the resolved playlist (with URLs/size/checksum) as a *snapshot*, intended mainly for the operator UI. It is **redundant** with `/sync` for the device. You do **not** need to call it; `/sync` + confirm is the authoritative path.
- **Health/status derivation, incidents, dashboards, telemetry analytics, the sync-timeout timer.** Server-side.
- **Playback logging correctness beyond batching.** `POST /…/playback` accepts a batch of `{contentFileId, playedAt, durationSeconds}` (≤500), is idempotent on `(deviceId, contentFileId, playedAt)`, rejects future/>90-day entries per-item, and is **off the critical path** — fire-and-forget on a slow cadence; a failure never blocks playback.

---

## 10. Deltas from the previous client behavior (action items)

The older `REGISTER_TO_PLAYBACK_FLOW.md` documented a client built against an earlier backend. Update these:

1. **Re-enable the download size check.** The "TEMP-HACK (2026-06-04)" that commented out `bytesOnDisk != sizeBytes` was a workaround for a backend bug where `sizeBytes` reported the *original upload* size instead of the processed object. **That bug is fixed** (v1.0.112 + v1.0.114 backfill). `sizeBytes` now equals the served object; the strict check should be restored.
2. **Start enforcing `checksum`.** It is no longer "frequently null" — the backend now stores the processed object's **SHA-256 hex** and backfills legacy files. Verify it whenever present.
3. **`syncRequired` is now a superset.** It is `true` for a version mismatch **or** a pending `SYNC_CONTENT` action (was: version-mismatch only). Your existing "treat `SYNC_CONTENT` as a sync trigger" logic is correct and now reinforced; no regression, but the flag alone is sufficient to detect both cases.
4. **`/playlist` endpoint exists.** The older doc's "there is no `/playlist` endpoint" is inaccurate — `GET /api/devices/{id}/playlist` is live and device-gated. It remains **optional** for the device (see §7/§9); `/sync` is authoritative.
5. **Registration `429` is real.** The backend rate-limits registration to 10/hour per source IP. Infinite uniform backoff is acceptable, but a 429 means you're registering in a loop — surface/fix the root cause rather than hammering.
6. **`URGENT_CONTENT` is not currently pushed.** Keep any handler dormant; don't rely on it. The live triggers are `SYNC_REQUIRED` and `ACTION_PENDING` (+ heartbeat fields).

---

## 11. End-to-end sequence (canonical)

```mermaid
sequenceDiagram
    participant Dev as Device
    participant API as Backend REST
    participant WS as WS /ws/devices/{id}
    participant CDN as MinIO (presigned)

    Note over Dev: cold start — no token or no deviceId
    Dev->>API: POST /api/devices/register {serialNumber}
    API-->>Dev: 201/200 {deviceId, deviceToken}
    Dev->>Dev: persist token THEN deviceId
    Dev->>WS: connect (X-Device-Token)
    WS-->>Dev: onOpen; server replays pending actions + SYNC_REQUIRED if stale

    loop every ~2 min
        Dev->>API: POST /{id}/heartbeat {contentVersion=lastConfirmed}
        API-->>Dev: {syncRequired, expectedContentVersion, pendingActions[]}
    end

    alt syncRequired==true  OR  WS SYNC_REQUIRED  OR  SYNC_CONTENT action
        Dev->>API: GET /{id}/sync?currentVersion&currentFileIds
        API-->>Dev: {filesToAdd[sizeBytes,checksum,presignedUrl], filesToDelete, playlistOrder, presignedUrlsExpireAt}
        loop per file to add
            Dev->>CDN: GET presignedUrl (Range resume)
            CDN-->>Dev: bytes (200/206)
            Dev->>Dev: verify bytes==sizeBytes AND sha256==checksum
        end
        Note over Dev: all required files downloaded+verified
        Dev->>API: POST /{id}/sync/confirm {reportedVersion}
        alt CONFIRMED
            API-->>Dev: CONFIRMED
            Dev->>Dev: promote pending playlist → live, currentVersion=reportedVersion
        else MISMATCH
            API-->>Dev: MISMATCH (syncRequired)
            Dev->>API: GET /{id}/sync (re-fetch; never retry confirm)
        end
    end

    Note over Dev: play DOWNLOADED+verified files in playlistOrder; loop
    opt URL expired mid-download (403/410)
        Dev->>API: GET /{id}/sync (fresh URLs); resume via Range
    end
    opt action arrives (WS ACTION_PENDING or heartbeat pendingActions)
        Dev->>Dev: execute action
        Dev->>API: POST /{id}/actions/{actionId}/confirm {status: SUCCESS|FAILED}
    end
```

---

## 12. Quick reference — status codes

| Code | Where | Meaning → device action |
|---|---|---|
| 200 / 201 | register | ok (200 re-register, 201 new) — both give a fresh token |
| 200 | heartbeat/sync/confirm/actions/playback | ok |
| 202 | (operator-issued actions) | n/a to device |
| 401 | any authed call / WS | bad/revoked token → re-register, reconnect |
| 403 | any authed call / WS | wrong device id in path → client bug |
| 404 | device-scoped call | device deleted → re-register (3-strikes policy) |
| 409 | (operator paths only) | not produced on the device's own calls |
| 429 | register | registering too often per IP → back off, fix loop |

All non-2xx carry a JSON envelope: `{status, error, message, correlationId, timestamp, fieldErrors?, details?}`. Log `correlationId` — it's how backend engineers trace your request.
