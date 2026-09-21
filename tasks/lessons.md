# Lessons

Patterns worth re-reading before starting work in this repo. Per `CLAUDE.md` §2: append here
whenever a mistake is made or a non-obvious trap is found, and write the rule that prevents it.

---

## Every new migration has three follow-ups, not one

Adding `V<N>__*.sql` is never a one-file change:

1. **`FlywayMigrationTest.EXPECTED_MIGRATIONS`** hard-codes the applied count *and* asserts a
   contiguous `1..N` run. Forget it and the failure looks like an unrelated infra-test break. The
   count includes the **Java** migration `V36` — there is no `V36__*.sql` on disk.
2. **`U<N>__*.sql` in `infra/src/test/resources/db/rollback/`.** Not enforced by anything (V41 and
   V42 both skipped it), but it is the dominant convention and it is the only rollback path.
3. **`ddl-auto: validate`** is on in the api test profile. A new `@Column` on an entity without a
   matching migration column takes down all seven full-context `@SpringBootTest` classes at once,
   with a Hibernate schema-validation error that names the column but not the cause.

**Rule:** treat "migration + FlywayMigrationTest + rollback script + entity mapping" as one atomic
change set.

## H2 in PostgreSQL mode is the real constraint on DDL

Tests run H2 `MODE=PostgreSQL`; production is PostgreSQL. Every migration must be valid on **both**.
Known divergences already hit in this repo:

- **No partial unique indexes** (`CREATE UNIQUE INDEX … WHERE …`). H2 does not support them. Rules
  that want one — "one PENDING action per (device, type)", "one live remote session per device" —
  are enforced in the **service layer** instead, and the schema test asserts that the *database*
  deliberately permits the second row.
- **`ON CONFLICT` needs its target omitted** (see the v1.0.130 playback-dedup note in `README.md`).

**Rule:** before writing DDL, ask "does H2 have this?" — and when the answer is no, push the rule up
into the service and write a schema test documenting *why* the constraint is absent.

## Never add a non-persistent getter whose name collides with a property path

`Device.getSyncGroupId()` returns a derived `String`. Spring Data's derived-query parser resolves
`…SyncGroupId…` in a method name to *that getter* rather than to the `syncGroup.id` path, and the
repository factory then throws `PathElementException` **at startup** — not at call time.
`DeviceRepository` carries an explicit `@Query` for exactly this reason.

**Rule:** if a repository has (or might have) a derived finder over `x.id`, do not put a
`getXId()` convenience getter on the entity. `RemoteSession` deliberately has none — callers use
`getDevice().getId()`, which is proxy-safe and does not initialise the lazy association.

## Emit wire enum values from `.name()`, never a string literal

`BatchedSyncDispatcher:73-74` emits `"SYNC_CONTENT"`, which is not a member of `PushMessageType`.
The enum is decorative for that message and nothing catches it, because nothing asserts the emitted
string against the enum.

**Rule:** every push emitter formats its `type` from `PushMessageType.<CONSTANT>.name()`, and every
new push type gets a test that asserts the emitted string **equals the enum constant name** — with
the expectation read *from the enum*, so no quoted copy of the name exists anywhere to drift.

## Credentials in echoed data: mask by key PATTERN, after parsing, before truncating

`AuditFilter` persists whole request **and response** bodies into `audit_log`. Until v1.0.139 it
passed them through an exact-key regex masker, and every gap in that list became plaintext in the
database: `viewerTicket`/`agentTicket` (not covered by `token`), then `currentPassword`/`newPassword`/
`confirmPassword` and `rawKey` (AUTH-06). The regex also stopped at an escaped quote, and the body was
cut to 10k *before* masking, so a secret straddling the cut lost its closing quote and was never masked.

**Rule:** decide sensitivity with `SensitiveFieldMasker.isSensitiveKey` (a name pattern:
`password|secret|token|ticket|authorization` anywhere, `…key` at the end, or `pin|ssn|cvv|credit_card`).
Mask by walking the parsed JSON (`AuditBodySanitizer`), then truncate. Anything that isn't JSON is
omitted, not stored raw. Endpoints whose body is nothing but credentials keep no body at all. A new
credential field whose name escapes the pattern still needs a test in the same change.

## An unstubbed `Integer`-returning mock getter returns `0`, not `null`

Mockito's default answer for a boxed `Integer` return is `0`. A branch that tests "the device never
reported a value" therefore never fires unless the getter is stubbed to `null` **explicitly**. This
bit `DeviceVolumeResolver` and again the remote `maxWidth` clamp.

**Rule:** in any test exercising a null / inherit / not-reported branch, stub the boxed getter to
`null` by hand and say so in a comment.

## The heartbeat is a liveness signal first — nothing optional may fail it

`volume`, `remote` capability, sync-group resolution and the desired-remote-session lookup are all
wrapped so that a malformed value, an unknown enum token, or an outright exception degrades to a
WARN and the beat still returns 200. A device with a buggy optional reporter must never be able to
read as offline, stop syncing content, or trip incident escalation.

**Rule:** every new heartbeat input is clamped/normalised and every new heartbeat output is computed
inside a try/catch that falls back to `null`. Add both the "absent" and the "malformed" test.

## Never assert that a random value "does not contain" something

`assertFalse(sessionId.contains(String.valueOf(deviceId)))` looks like a strictness win — "prove the
key is not derived from the device id" — and it is actually a **flake**. 32 random hex characters
contain any given two-character substring roughly 11% of the time, so the assertion passes on most
runs and fails on the rest for no reason at all. It shipped green and broke on the next full build.

**Rule:** test the *structure* of a random value (a regex over its alphabet and length) and its
*distinctness* (N samples, zero collisions). Those pin the real property — no deterministic
derivation, no sequential counter — with a failure probability you can actually compute. An
absence-of-substring assertion over random data pins nothing and costs you a red build later.

## An assertion made directly against an unstubbed mock cannot fail

`assertEquals(Optional.empty(), repo.findFirstBy…(…))` on a bare Mockito mock asserts Mockito's
default answer, not any production behaviour. It exercises no code, survives every mutant, and
reads like coverage. Found by an adversarial review of the v1.0.131 remote-control tests.

**Rule:** every assertion must be downstream of a call into production code. When the thing you want
to pin is an *argument* the production code passes (which statuses a guard queries, which ids get
pushed), capture it with an `ArgumentCaptor` and assert on the captured value — then prove the test
works by temporarily breaking the production constant and watching it go red.

## Size for the host you land on, not the box you measured

A spec can arrive with hard numbers measured on one server — pool size 1, a 700 MiB cgroup,
`-XX:+UseSerialGC`, a specific x264 preset. Baking those in as constants caps every *other* host and
makes lifting the cap a code change plus a rebuild.

**Rule:** derive the number at runtime and expose an override.
`X = configured > 0 ? configured : min(cpuBudget, memoryBudget)`, clamped to a max, with defaults that
resolve to the measured-safe value on the small box. `Runtime.availableProcessors()` is
container-aware; the real memory limit is in `/sys/fs/cgroup/memory.max` (v2) or
`memory/memory.limit_in_bytes` (v1), with the OS bean as the last fallback. Log the resolved value at
startup so the decision is auditable. Let JVM ergonomics choose the GC (it already picks SerialGC at
≤1 CPU and G1 above), and put JVM flags behind `${JAVA_TOOL_OPTIONS:-<default>}`.
See `TranscodeCapacityPlanner` (v1.0.132).

## `@Transactional` inside an AFTER_COMMIT listener joins a DEAD transaction

Doing database work from `@TransactionalEventListener(phase = AFTER_COMMIT)` — or from a
`TransactionSynchronization.afterCommit()` callback — looks like it runs outside a transaction. It
does not. The completed transaction's resources are **still bound to the thread**, so a `REQUIRED`
method joins a transaction that has already committed and Hibernate throws
`TransactionRequiredException: no transaction is in progress`. The synchronization machinery then
logs and **swallows** it, so the work is silently lost and the caller sees success.

**Rule:** any repository/service call made from an after-commit callback must be
`@Transactional(propagation = REQUIRES_NEW)`. Do NOT instead make the *listener* transactional: that
puts the write back inside a transaction the async worker cannot see yet, which is the very race the
after-commit dispatch exists to remove. Bit the v1.0.132 transcode-claim path; caught only because an
integration test asserted the row's visibility rather than mocking the collaborator.

## Never build a mock inside a `when(...).thenReturn(...)` argument

`when(repo.find(...)).thenReturn(List.of(makeMockRow(1L)))` throws
`UnfinishedStubbingException` — the helper's own `when(...)` calls run while the outer stubbing is
still open, and the failure message points at the outer line, not the helper. Assign the mock to a
local first, then stub. Cost a full red suite twice in v1.0.132.

## A mock-verified call can be a bug that is live in production

`verify(transcoder).transcodeAsync(42L)` passed on every run while two production uploads were being
silently lost, because the defect was *when* the call happened relative to the transaction commit —
something mock verification cannot observe.

**Rule:** for anything whose correctness depends on ordering, visibility, or commit boundaries, write
an integration test through the real transaction manager and assert the observable state (e.g. "was
the row readable from a `REQUIRES_NEW` transaction at dispatch time?"). Same class of gap: asserting
the pre-serialisation `String` a publisher builds instead of the bytes that reach the wire — that one
let a double-JSON-encoded WebSocket frame kill the whole live feed in production.

## Changing `POSTGRES_PASSWORD` does NOT rotate an existing database

The env var is read **only when the data directory is first initialised**. Recreating the container
against an existing volume with a new value leaves the old password working and the new one failing —
verified directly against `postgres:17-alpine`. Edit `.env` first and you have an outage: the app
presents a password the server does not have.

**Rule:** rotate the server first, then the file.
`ALTER USER <user> WITH PASSWORD '<new>'` inside the running container → update `.env` → recreate only
the app. The mirror-image case is MinIO, which *does* rotate cleanly from env on restart (new root
credentials work, objects survive, old credentials are rejected — also verified). Two adjacent
services, opposite semantics: **test the rotation before writing it into a runbook.**

## A lifecycle/config API can accept a document and silently discard half of it

MinIO's ILM validator requires at least one of `Expiration`/`Transition`/`NoncurrentVersion*`/
`DelMarkerExpiration`. A rule whose only action is `AbortIncompleteMultipartUpload` fails with
`MalformedXML` — under an empty filter *and* under a prefix filter, so the obvious "it's the empty
`RuleFilter`" guess is wrong. Send abort *alongside* an expiration and it is accepted, then dropped
on readback.

**Rule:** when an external service accepts or rejects a document you construct, verify against a
disposable instance of the **deployed version** and **read the result back** — a mock only proves you
sent what you meant to send. And never let a failing startup install sit behind a lone WARN: this one
failed on every boot for the life of the deployment while operators believed cleanup was running.
Surface it on a health indicator.

## Publishing a port in compose binds 0.0.0.0 — including in production

`ports: - "5432:5432"` is not "expose to the host", it is "expose to every interface". On subzero
that meant Postgres, Redis, the plaintext API, the SPA and MinIO all answering the internet, on the
credentials committed in this repo, with no upstream firewall.

**Rule:** publish as `"${BIND_ADDR:-127.0.0.1}:<host>:<container>"` so loopback is the default and
LAN exposure is an explicit opt-in. Give secrets `${VAR:?message}` rather than a working default —
a committed default is not a placeholder, it is the production credential. **And before deleting a
publish, find out who dials it:** the host's Caddy is a systemd process that cannot resolve compose
service names, so removing MinIO's 9000 mapping would have broken presigned media on every TV box.

## `@ConditionalOnMissingBean(name = ...)` makes bean NAMES a public API you can collide with

A `@Component` called `DiskSpaceHealthIndicator` registers as `diskSpaceHealthIndicator` — the exact
name Spring Boot's actuator auto-configuration guards with
`@ConditionalOnMissingBean(name = "diskSpaceHealthIndicator")`. That matches by **name, not type**, so
an unrelated class silently backs off Boot's contributor. Verified: with the colliding name the
actuator contributors were `[db, ping, redis, ssl]`; after renaming, `diskSpace` returned. Nothing
fails, nothing logs — `/actuator/health` just quietly stops checking the disk, which on this
deployment is what drives the container's health state.

**Rule:** before naming a `@Component` after a well-known framework bean, check whether the framework
guards it by name. Prefer a distinct name (`DiskFreeHealthIndicator`), and when a bean's name is
load-bearing say so in its javadoc — the next person's instinct is to "fix" the odd name.

## Route a broadcast on the resource's VISIBILITY key, not on the nearest one to hand

Every dashboard event was routed per session on `_projectId`, so the obvious fix for "content frames
reach every operator" was to wrap them in the same envelope. It would have shipped a silent
regression: an operator's content is **owned ∪ granted**, never project-gated, and an orphan upload
(`project_id IS NULL`, a supported flow) has no project at all — so project-only routing drops the
uploader's own transcode updates, which is the exact bug the live feed exists to prevent. The
envelope gained an `_owner` term alongside `_projectId`.

**Rule:** before scoping a broadcast, go read the read-path's access rule for that resource and route
on the same dimension. When the two cannot be made identical (a per-row grant cannot be captured at
handshake), prefer over-delivery to the *owner* and say in the code and the release note which case
degrades to a poll. Under-delivery on a live feed is invisible and looks like the original bug.

## `getId()` on a LAZY proxy outside a persistence context throws — it does not shortcut

`file.getProject().getId()` is safe inside a transaction and safe under OSIV, and it is a
`LazyInitializationException` on a pool thread. Hibernate can answer the identifier without
initialising a proxy only when it has an identifier *getter method* to intercept; with field access
(`@Id` on the field, which is this codebase's convention) it has a field getter, so the call
initialises. `open-in-view: false` here means only the web request thread ever had cover.

**Rule:** in any component that runs off a request thread — transcode pool, sweeper, scheduled job,
after-commit callback — never navigate an association. Read the FK with a scalar query
(`SELECT c.project.id FROM ContentFile c WHERE c.id = :id`, which Hibernate resolves against the FK
column with no join), once, and carry the value down the call chain.

## Every path that sets a password or grants access must revoke the user's sessions

`BootstrapAdminProvisioner` (v1.0.136) first re-activated an account, promoted it to ADMIN and set a
new password — and left its refresh-token families alone. `AuthService.refresh` only re-checks
`isActive` and the *current* role, so a session opened with the old public password (`admin`/
`password`) kept minting tokens, and a promoted `viewer`'s old session came back as **ADMIN**. The
unit tests passed, because nothing asserted on `RefreshTokenRepository`. Caught by review, not by
tests.

**Rule:** any code that changes a password, re-activates an account or raises its role calls
`refreshTokenRepository.invalidateAllForUser(username)` in the same change — as `PasswordService`
does — with a test that verifies the call. That includes create-paths: a hard-deleted username can
be re-created and inherit live families.

## A rate-limit "refund" makes the refunded path free — give it its own budget instead

AUTH-02 (v1.0.137) first refunded the per-IP registration slot whenever `/register` answered 409,
so a wiped box retrying every few minutes wouldn't starve the other boxes behind the same NAT. Review
caught what that really meant: every refused attempt was now **unmetered**. An attacker could poll a
known serial without limit to snatch an admin's re-registration window, and each refusal logged a
WARN, which is forwarded to Telegram, so the same loop could burn the global alert budget. The refund
also recomputed the bucket key, so at an hour boundary it decremented the *new* bucket.

**Rule:** never make a failure path free by refunding. Route it to its **own** bounded budget, chosen
before the work runs (`checkReregistration` vs `check`), and log expected, client-driven refusals at
INFO, not WARN. WARN and above reach Telegram, so anything an unauthenticated caller can trigger in a
loop must not log at WARN.

## An `Error` escapes `catch (Exception)` — put the must-happen step in its own `finally`

`AuditFilter` used to run `writeAudit(...)` and then `copyBodyToResponse()` in one `finally`.
`writeAudit` caught `Exception`, but any `Error` (an `OutOfMemoryError` while buffering a large
body, a `StackOverflowError` from a recursive walk) would have skipped the copy, and the client would
have received an **empty body** for a request that succeeded.

**Rule:** in a filter that buffers the response, `copyBodyToResponse()` (or any other step that must
always happen) sits in an inner `finally` of its own, not after best-effort work in the same block.

## Validation `rejectedValue` echoes the input — including passwords

`GlobalExceptionHandler` returned `fieldErrors[].rejectedValue` verbatim, so a too-short password on
`POST /api/users` or login came straight back in the 400 body, and from there into `audit_log`
through `AuditFilter`. No masker saw it, because the key was `rejectedValue`, not `password`.

**Rule:** anything that reflects request input back, whether error payloads, logs or audit rows, drops
the value when the field name is sensitive (`GlobalExceptionHandler.safeRejectedValue`, keyed on
the last path segment).

## A `@Transactional` method called on `this` gets no transaction

Spring applies `@Transactional` in a proxy that wraps the bean. A call from inside the same class
(`this.escalate(...)`, or a bare `escalate(...)`) skips the proxy, so the annotation, including
`REQUIRES_NEW`, has no effect at all. Nothing warns about it. Both incident monitors did this
(LOGIC-02/03, fixed in v1.0.140):

- `SyncTimeoutMonitor.escalate` loaded the device outside any transaction, so the entity it
  called `clearSyncPending()` on was detached and never written. The same stuck device
  re-escalated every minute, forever.
- `DeviceHealthMonitor.escalate` did the same. `IncidentService.processEvent` then opened its own
  transaction around a detached device, and the critical broadcast's `device.getRegion().getProject()`
  threw `LazyInitializationException`. That exception was swallowed, so the incident was saved and
  no operator was alerted live.

The Mockito tests passed throughout. `verify(device).clearSyncPending()` proves the method was
called, not that the change reached the database. A unit test also builds the class with `new`, so
there is no proxy to bypass: the bug cannot exist there.

**Rule:** call a transactional method through the proxy: a `@Lazy self` constructor parameter (the
`RetentionCleanupService` pattern) or a separate bean. Cover it with a real-DB test that asserts the
committed state, e.g. the column is NULL in the database or the broadcast carries a value that only
a managed entity could supply. Then swap `self.` back to `this.` and confirm the test goes red. Be
extra careful where the downstream work is best-effort: a `catch (Exception)` turns this failure into
a routine-looking WARN.

## `REQUIRES_NEW` inside a request transaction holds two pooled connections

The first v1.0.140 fix isolated the heartbeat's auto-resolve with `REQUIRES_NEW`, so that a failing
resolve could not mark the beat rollback-only. That was correct, but it suspends the beat's
transaction **without releasing its connection**, so every recovery beat held two connections from
the pool. A regional outage makes every device a recovery beat at the same moment. With 20 or more
concurrent beats, every pooled connection is held by an outer transaction that is waiting for a
second one, and the pool deadlocks until the connection timeout. Review caught it; the tests never
could, because they run one beat at a time.

**Rule:** side work that must not be able to fail the main transaction does not belong inside it,
whether joined or nested. Return what needs doing from the transactional method (here
`HeartbeatResult.resolveIncidentTypes`). A caller that holds no transaction runs it after the method
returns, so the main transaction has committed and released its connection. Prove the ordering with
a real-DB test: have a spy on the side-work method read the main row from a fresh transaction, and
fail the test if the row isn't committed yet. A `REQUIRES_NEW` reachable from a request path needs
an explicit answer to "how many connections does one request hold, and what happens when N of them
arrive at once?"

## The device→playlist rule lives in FOUR places — and a partial tie-break diverges silently

"Which playlist does this device play right now" is implemented four times: the Java comparator
(`ContentAssignment.PRECEDENCE`, consumed by `resolveForDevice` **and** `previewForTarget` — the
preview had its own private `max(comparingInt(priority))`), the JPQL `ORDER BY` in
`ContentAssignmentRepository.findActiveAtTime`, and the SQL inside `device_status_view`, which
embeds the predicate **three times** (`active_playlist_id`, `active_playlist_name`,
`computed_status`). Add a term to one and the others keep answering the old question. Nothing fails:
the device plays one playlist while the operator console names another.

Worse than an incomplete rollout is an incomplete **tie-break**. `Stream.max(comparingInt(priority))`
returns the FIRST maximal element, so on a tie it silently inherits whatever order the query
returned; SQL's `ORDER BY priority DESC` with no further term is free to return either row. Both
"work" until two rows tie — which v1.0.142 made an ordinary, intended state (a campaign overlapping
the booking it replaces) rather than something the overlap guard prevented. The v1.0.113 note had
already flagged the mismatch as "unreachable in practice"; it stayed unreachable right up until a
feature made it the normal case.

**Rule:** a resolution rule that exists in more than one language gets ONE named definition
(`ContentAssignment.PRECEDENCE`) that the Java side references rather than re-spells, and every
other copy quotes it verbatim in a comment. The order must be **total** — break every tie down to
the primary key, so no two distinct rows can compare equal in either language. Pin it with a
real-DB test that runs the JPQL, the comparator and the view over the SAME rows and asserts they
name the same winner, with a fixture whose ids run AGAINST the intended answer (so a reverted term
goes red instead of passing by luck). Ordering on a column another code path mutates (`updatedAt`,
moved by `bumpVersion`/`truncateEndTo`) is the same bug wearing a different hat — add the column
that means what you actually mean.

## A permanent side effect written at confirm time must be gated on the window it belongs to

v1.0.142 gated the *retire* paths of `supersede` on the new assignment's window and left the
*narrowing* path ungated, because exclusions "only affect the devices being handed over". But an
exclusion row has no window: it takes effect the instant it is written and never expires. Confirming
a takeover that opens next week therefore blanked the handed-over device immediately — the very
failure the change existed to remove, reintroduced two branches away from the fix. Review caught it;
every test passed, because they all confirmed windows starting *now*.

The same asymmetry has a second half: a permanent row also outlives the thing that caused it.
Cancelling the superseding assignment left its narrowings behind, stranding devices on a predecessor
they were still excluded from. The exclusion's FK points at the assignment it *narrows*, not the one
that *caused* it, so there was nothing to cascade from.

**Rule:** when a decision writes a row that has no end time, ask both questions before writing it —
*"does this take effect before the thing that justifies it?"* and *"who deletes it when that thing
goes away?"* — and give the row a key that answers the second (here the reason string, with one
formatter shared by the write and the undo). Then write the test at a time OTHER than `now`: a
fixture whose window starts at `Instant.now()` cannot tell a correct gate from a missing one. The
same trap sits in any snapshot-versus-now comparison — `detachReassignedSyncGroupMembers` sampled
the resolved playlist at `now` and irreversibly unwired sales points for campaigns that would not
start for a week.

## Dropping a hot path from a log drops its FAILURES too

DATA-01 stopped auditing the five device-agent endpoints because 98.6% of `audit_log` was their
successful, identical, unread traffic. The first cut returned before the chain, which also deleted
the only trail of the interesting 1.4%: device A presenting its token against device B's
`/heartbeat` is a `@PreAuthorize` **403**, raised inside the controller invocation and unwound back
through the filter. The noise and the evidence travel the same route and are told apart only by the
outcome. (Cheap to keep: no buffering, so the row has no bodies — and a healthy fleet produces
none of these rows at all.)

**Rule:** when a filter/interceptor skips work for volume, branch on the **result**, not on the
route — run the chain, then keep the record when `status >= 400`. And know where the statuses come
from before claiming coverage: a **401** is emitted by the security filter chain, which runs before
`AuditFilter` and short-circuits, so it never reached the audit, before or after the change. Say
which case is *still* invisible in the javadoc rather than implying the skip covers it.

## A property that bounds a DELETE needs a unit and a floor, or it deletes everything

`app.retention.playback` sets `now - playback` as the "delete older than" threshold. Spring Boot
binds an unsuffixed duration as **milliseconds**, so `APP_RETENTION_PLAYBACK=90` — the obvious way
to write "90 days" — is 90 ms: the threshold lands at ~now, the 02:00 run deletes every
proof-of-play row (billing data), and `PlaybackLogService`, which reads the same property, then
rejects every incoming report, so nothing refills it. `PT0S` and a negative value do the same.
`batch-size=0` throws inside `PageRequest.of`, is caught per table, and no-ops the whole job behind
one WARN that looks routine.

**Rule:** a configuration value that bounds a destructive operation gets (1) `@DurationUnit` so the
bare number means the unit a human meant — days for a window, minutes for a timeout — and (2)
`@Validated` with a real floor (`@DurationMin`, `@Positive`) so a bad value **fails startup naming
the property** instead of running at 02:00 against production. Test both: that a bare `90` binds as
90 days, and that each bad value fails the context.

## A health flag with no re-probe is a latch, and a blanket `catch` makes it fleet-wide

`MinioHealthStatus` (DATA-02, fixed in v1.0.144) had two halves of the same bug:

1. **Nothing could set it back.** It starts DEGRADED and the only `markUp()` in the application was
   in `MinioBucketInitializer`, which runs once at startup. Every other writer could only degrade
   it. So the flag was not a health signal, it was a **latch**: a MinIO restart, a full disk or a
   five-second blip disabled storage for the life of the process, and the only recovery was for a
   human to notice and restart the backend.
2. **Every failure wrote to it.** `catch (Exception) → markDegraded()` on upload/download/exists/
   delete meant a deleted key, a denied ACL or a full quota — all answers from a *healthy* MinIO
   about *one request* — flipped the process-wide flag. One bad object became a fleet-wide outage.

The third thing it broke was invisible: `/sync` swallowed both storage calls to `null`, so it
answered **HTTP 200 with an incomplete plan**. A device cannot tell that from a correct answer, so
it applied it and played a short loop. A 503 would have been retried.

**Rule:** every health flag needs **two** writers — the thing that degrades it and a scheduled
re-probe that can clear it — before it is merged; a flag only a restart can clear is a latch, and
say so in its javadoc. Degrade only on evidence that the *dependency* is unreachable
(`IOException`, a 5xx), never on evidence that one *request* was wrong (an S3/API error code, an
argument bug, or an exception you don't recognise): under-degrading costs one request, over-degrading
costs everything. Mark the transition, not the call — log once, don't move `since`, and don't let a
fleet-sized flood of identical WARNs reach Telegram. And when a per-item helper swallows failures to
`null` so the batch survives, check what *class* of failure it is swallowing: "this one object is
gone" and "I cannot reach storage at all" arrive at the same catch block and have opposite correct
answers. Returning a partial result as 200 is the worst of the three outcomes, because it is the
only one nobody can detect.

**Three corrections to the above, from the review of that same change** — each one a way the fix
could have been worse than the bug:

- **A health probe must be cheap to FAIL, not just cheap to pass.** The probe ran on Spring's
  default `@Scheduled` pool, which is **one thread** shared by every scheduled job in the
  application, against a client with minio-java's 5-minute OkHttp defaults. A *blackholed*
  dependency (packets dropped, not refused) would hang that thread for five minutes a tick, for the
  whole outage — stopping the Telegram log forwarder and the incident monitors, i.e. the alerting
  that exists to report the outage. Give any liveness probe its own short-timeout client, and size
  `spring.task.scheduling.pool.size` above 1 before adding a job that does I/O.
- **Once the flag self-heals, the bias inverts.** "Treat anything unrecognised as per-request,
  because over-degrading costs the fleet" was right while a degraded flag was a latch. With a probe
  clearing it in 30 s, the costs became 30 s of 503s versus *"MinIO is up but broken and nothing
  ever says so"*. Re-derive a risk trade-off after you change the thing that made it asymmetric.
- **Classify against the SDK's behaviour, not its exception names.** minio-java builds
  `ServerException` in exactly one place; a 5xx carrying an S3 body arrives as
  `ErrorResponseException`, and `throwEncapsulatedException` wraps anything outside nine declared
  types in a bare `RuntimeException` — so both a type-only check and a status-only check are wrong,
  in opposite directions. (`XMinioStorageFull` is HTTP **507**: a status-only rule degrades on a
  full disk, and since a full MinIO still serves reads the probe heals it immediately — a
  degrade/recover pair, with its WARN and INFO, per upload attempt.) Decompile the version you
  depend on.

**And two that are about what an outage EMITS, not what it is:**

- **A per-request value in an alert's message text defeats deduplication.** `TelegramRateLimiter`
  hashes the rendered message, so a correlation id in the text made every 503 a distinct hash: the
  5-per-5-minutes fold never applied and a fleet-wide outage could evict every other alert from a
  500-entry buffer. Alert text is constant; per-request detail goes on a separate line below the
  forwarding threshold.
- **Widening who can reach an error widens what that error leaks.** Making `/sync` propagate the
  storage failure put MinIO's own message — endpoint, bucket, object key — into a response a device
  token can fetch. When you change who can trigger a path, re-read what that path returns.
