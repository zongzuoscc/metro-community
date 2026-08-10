# Stage B article revision cutover runbook

This is the single **authoritative** procedure for the Stage B article-revision rollout. The durable
checkpoint, not a process-local flag or a deployment note, is the promotion truth. Application pods
only verify that truth at startup; they never advance it.

The only first-cutover sequence is:

```text
LEGACY -> SHADOW -> VERIFY_FENCE -> POINTER_READ -> CUTOVER
```

After `cutover_epoch > 0`, the only emergency move is `CUTOVER -> POINTER_READ`. Recovery is a
forward fix and a new sentinel, never an old binary or a schema rollback. Live Provider quality is
outside this gate and must be recorded as `NOT RUN` unless a separate, credentialed evaluation was
actually executed.

## Non-negotiable controls

- Pin one immutable image digest. Store the lowercase 64-character digest without `sha256:` in
  `METRO_ARTICLE_ROLLOUT_BUILD_DIGEST`; set non-negative binary and schema generations. A tag is not
  an identity.
- Enforce that digest through the cluster admission allowlist before replacing any pod. The
  checkpoint cannot stop an old binary that predates the startup gate.
- Give each rollout generation new runtime DB credentials and RabbitMQ credentials. Drain every
  old pod, old DB session, RabbitMQ connection and unacked delivery, then revoke the old DB
  credential and RabbitMQ credential before the next mode can serve traffic.
- Use a separate operator login and the reviewed grants in
  `2026-08-10-stage-b-rollout-checkpoint-grants.sql`. Runtime pods have checkpoint `SELECT`; only the
  one-shot operator has the exact checkpoint/backfill/verifier write set. Never advance the
  checkpoint with ad-hoc SQL.
- Run only one operator action at a time. Archive its identity, image digest, checkpoint
  `lock_version` before/after, stdout, report and result. A CAS conflict stops the procedure.
- `VERIFY_FENCE` and `POINTER_READ` remain write-fenced. HTTP 503 with
  `ARTICLE_CUTOVER_IN_PROGRESS` is expected; do not route around it.

## Configuration contract

Every application and one-shot operator image receives the same build identity:

```text
METRO_ARTICLE_ROLLOUT_BUILD_DIGEST=<64 lowercase hex characters>
METRO_ARTICLE_ROLLOUT_BINARY_GENERATION=<non-negative integer>
METRO_ARTICLE_ROLLOUT_SCHEMA_GENERATION=<non-negative integer>
```

`METRO_ARTICLE_REVISION_MODE` is the startup target and must exactly equal the durable checkpoint.
`METRO_STAGE_B_MIGRATION_ACTION` is `NONE`, `BACKFILL`, or `VERIFY`. `BACKFILL` and `VERIFY` also
require a stable audited `METRO_STAGE_B_OPERATOR_IDENTITY`; ordinary pods always use `NONE`.
`VERIFY` additionally requires `METRO_STAGE_B_VERIFICATION_REPORT_PATH`: an absolute path beneath
the controlled evidence archive whose parent already exists and whose target does not. The command
creates that owner-only artifact with `CREATE_NEW`; it never overwrites an older report.
Missing/malformed identity, a mode mismatch, a stale generation, a different digest, or a missing
checkpoint fails startup closed.

Ordinary pods also keep `METRO_STAGE_B_ROLLOUT_ACTION=NONE`. A one-shot checkpoint command sets it
to exactly one of `BOOTSTRAP_LEGACY`, `ADVANCE`, `BEGIN_SENTINEL`, `RECORD_SENTINEL`,
`AUTHORIZE_BUILD`, or `EMERGENCY_FENCE`, while keeping the migration action `NONE`. The same
environment name selects the standalone process and binds the typed action; there is no separate
routing-only alias. Action-specific inputs are:

```text
METRO_STAGE_B_ROLLOUT_TARGET=                         # ADVANCE only
METRO_STAGE_B_ROLLOUT_SENTINEL_RUN_PATH=              # BEGIN_SENTINEL only
METRO_STAGE_B_ROLLOUT_SENTINEL_REPORT_PATH=           # RECORD_SENTINEL only
METRO_STAGE_B_ROLLOUT_TARGET_BINARY_GENERATION=-1     # AUTHORIZE_BUILD only
METRO_STAGE_B_ROLLOUT_TARGET_SCHEMA_GENERATION=-1     # AUTHORIZE_BUILD only
METRO_STAGE_B_ROLLOUT_TARGET_BUILD_DIGEST=             # AUTHORIZE_BUILD only
```

Every command below assumes the audited operator DB credentials, current build identity and
`METRO_STAGE_B_OPERATOR_IDENTITY` are already exported. Run the immutable artifact as
`java -jar metro-community.jar`; a missing input, illegal transition, stale CAS, or malformed
controlled file must make that process exit non-zero.

## 0. Backup and expand

1. Freeze the release commit and OCI digest. Capture the admission allowlist, replica count,
   runtime/operator principal names, RabbitMQ virtual host and Elasticsearch read alias.
2. Take a recoverable MySQL backup and prove a restore in isolation. Capture current article,
   draft, revision, job, migration-issue, Outbox and Inbox counts.
3. Follow `2026-08-10-stage-b-schema-expand-runbook.md`. Run the additive migration twice and retain
   the successful prefix-interruption recovery evidence. Do not seed the checkpoint in the schema
   migration.
4. Render and execute both reviewed grant templates. Their pre/post checks must pass with empty
   mandatory roles, no dynamic privileges, distinct locked roles and distinct runtime/operator
   logins.
5. Confirm the retention delete schedule is still off:
   `METRO_DOMAIN_EVENT_RETENTION_SCHEDULING_ENABLED=false`. The read-only backlog sampler remains on.

Stop here on backup, schema, grant, identity, or metadata drift.

## 1. Establish LEGACY on the gated binary

1. Stop new article writes at the gateway. Drain existing requests and article-mutating jobs.
2. Remove every pre-gate old pod from service, drain its DB/RabbitMQ connections and revoke its
   credentials. Verify the admission allowlist accepts only the frozen digest.
3. Bootstrap exactly once with the production command below. A pre-existing row is an error, not
   permission to overwrite it.

   ```bash
   METRO_STAGE_B_MIGRATION_ACTION=NONE \
   METRO_STAGE_B_ROLLOUT_ACTION=BOOTSTRAP_LEGACY \
   java -jar metro-community.jar
   ```
4. Start all replicas with `METRO_ARTICLE_REVISION_MODE=LEGACY` and migration action `NONE`. Check
   every pod log for the frozen mode and verify its runtime image digest and build generations.
5. Run ordinary article/public/search/admin regression. The legacy admin audit is enabled; the new
   revision decision API is disabled. No backfill is allowed in this mode.

Checkpoint read-only evidence:

```sql
SELECT checkpoint_id, mode, schema_generation, minimum_binary_generation,
       required_build_digest, backfill_started_at, cutover_epoch, lock_version,
       updated_by, updated_at
FROM article_revision_rollout_checkpoint
WHERE checkpoint_id = 1;
```

## 2. SHADOW dual-write and online backfill

1. Stop article writes again. Drain and remove the LEGACY replica set and its credentials.
2. Advance with the one-shot command below. Start only SHADOW replicas whose configured target,
   digest and generations match the checkpoint.

   ```bash
   METRO_STAGE_B_MIGRATION_ACTION=NONE \
   METRO_STAGE_B_ROLLOUT_ACTION=ADVANCE \
   METRO_STAGE_B_ROLLOUT_TARGET=SHADOW \
   java -jar metro-community.jar
   ```
3. Resume traffic after every replica is verified. New draft/revision/job data is transactionally
   dual-written, published editing remains disabled, and the old admin audit can no longer decide.
4. Run a one-shot image with:

   ```text
   METRO_ARTICLE_REVISION_MODE=SHADOW
   METRO_STAGE_B_MIGRATION_ACTION=BACKFILL
   METRO_STAGE_B_OPERATOR_IDENTITY=<audited identity>
   ```

   The runner durably marks backfill started before changing legacy rows. From that point SHADOW
   cannot return to LEGACY.
5. Repeat bounded/idempotent backfill until unresolved migration issues are zero. Observe moderation
   jobs, Outbox lag, RabbitMQ lag, search/notification Inbox lag and projection watermark lag.

Stop if any row is guessed, any issue is unresolved, a lease is stuck, a hash differs, or lag is not
converging.

## 3. VERIFY_FENCE and durable verification proof

1. Stop article-write traffic and every scheduled article writer. Disable Task 7 recovery with
   `METRO_AI_MODERATION_RECOVERY_ENABLED=false`.
2. Stop the Task 7 moderation listener on the old SHADOW replicas, drain provider calls and RabbitMQ
   unacked deliveries, then remove those replicas. VERIFY_FENCE startup also keeps the listener
   factory stopped; recovery is a no-op and a defensive worker call is deferred without ACKing work.
3. Drain/revoke the old pod DB credential and RabbitMQ credential. Confirm there are no old-image DB
   sessions or broker connections.
4. Advance to the fence with the exact command below. Start a full VERIFY_FENCE replica set from
   the immutable digest. Do not resume writes.

   ```bash
   METRO_STAGE_B_MIGRATION_ACTION=NONE \
   METRO_STAGE_B_ROLLOUT_ACTION=ADVANCE \
   METRO_STAGE_B_ROLLOUT_TARGET=VERIFY_FENCE \
   java -jar metro-community.jar
   ```
5. Wait at least one configured maximum in-flight moderation window. During that whole window,
   `article_moderation_job.updated_at` must not change. Clear Outbox lag, both consumer Inbox lags and
   search projection lag; hold the article ES target stable for the final comparison.
6. Run the one-shot verifier with:

   ```text
   METRO_ARTICLE_REVISION_MODE=VERIFY_FENCE
   METRO_STAGE_B_MIGRATION_ACTION=VERIFY
   METRO_STAGE_B_OPERATOR_IDENTITY=<audited identity>
   METRO_STAGE_B_VERIFICATION_REPORT_PATH=/controlled/stage-b/verify-<run-id>.json
   ```

   `beginVerification` is its first durable operation and clears any older proof. It then performs
   the final backfill and the full MySQL count/hash/pointer plus ES verification. A crash, mismatch,
   unresolved issue, start/end fingerprint difference, or live-fingerprint drift leaves no usable
   proof and exits non-zero.
7. Archive the complete verification report, its SHA-256 report hash, start/end fingerprint, page
   counts, mismatch list (empty), operator/build identity and the checkpoint row containing
   `verified_at`. The archived hash must equal `verify_report_hash`.

## 4. POINTER_READ sentinel

1. Without reopening writes, advance to `POINTER_READ`. The transition recomputes the live article
   fingerprint under the checkpoint lock and rejects stale verification proof.

   ```bash
   METRO_STAGE_B_MIGRATION_ACTION=NONE \
   METRO_STAGE_B_ROLLOUT_ACTION=ADVANCE \
   METRO_STAGE_B_ROLLOUT_TARGET=POINTER_READ \
   java -jar metro-community.jar
   ```
2. Replace the fenced replicas with POINTER_READ replicas from the same authorized digest. Confirm
   every article mutation still returns 503 and no Task 7 listener or recovery worker is running.
3. Begin the sentinel with a new absolute output path. This independently clears any previous
   sentinel and creates an owner-only run file bound to the checkpoint version, authorized build
   digest and verified fingerprint.

   ```bash
   METRO_STAGE_B_MIGRATION_ACTION=NONE \
   METRO_STAGE_B_ROLLOUT_ACTION=BEGIN_SENTINEL \
   METRO_STAGE_B_ROLLOUT_SENTINEL_RUN_PATH=/controlled/stage-b/sentinel-run-<run-id>.json \
   java -jar metro-community.jar
   ```
4. Against that exact token, run public/detail/search/recommendation leak checks, published-pointer
   and immutable-hash checks, mirror/tag equality checks, deleted/unpublished tombstone checks and ES
   document pointer/hash checks. Do not relabel an old report for a new build.
5. Archive the signed sentinel report and its SHA-256. The report must be an owner-only regular
   non-symlink typed `StageBPointerSentinelReport` with the same token/build/fingerprint. Record it
   with the command below. A failed run must be recorded as failed; an abandoned run leaves proof
   empty. Confirm `sentinel_report_hash` and `sentinel_verified_at` in the checkpoint.

   ```bash
   METRO_STAGE_B_MIGRATION_ACTION=NONE \
   METRO_STAGE_B_ROLLOUT_ACTION=RECORD_SENTINEL \
   METRO_STAGE_B_ROLLOUT_SENTINEL_REPORT_PATH=/controlled/stage-b/sentinel-report-<run-id>.json \
   java -jar metro-community.jar
   ```

## 5. CUTOVER and reopen

1. Confirm writes are still stopped, the verification report and sentinel report are archived, the
   checkpoint still names the immutable digest, and the live fingerprint has not moved.
2. Advance with the command below. It rechecks the live fingerprint and current sentinel, then
   increments `cutover_epoch`.

   ```bash
   METRO_STAGE_B_MIGRATION_ACTION=NONE \
   METRO_STAGE_B_ROLLOUT_ACTION=ADVANCE \
   METRO_STAGE_B_ROLLOUT_TARGET=CUTOVER \
   java -jar metro-community.jar
   ```
3. Start only CUTOVER replicas from the admitted digest. Confirm all replicas and the checkpoint
   agree before enabling the Task 7 listener/recovery and reopening article writes.
4. Smoke test new submit, published edit, approve, reject-with-old-publication, reject-without-old-
   publication, recycle and restore. Confirm exactly one decision event, Rabbit fan-out to search and
   notification with the same event ID, immutable ES content, Inbox rows and lifecycle tombstones.
5. Archive the final checkpoint row, pod/image inventory, credential rotation evidence, lag graphs
   and smoke results. Keep legacy article fields and tags as published compatibility mirrors.

Do not enable destructive retention as part of cutover. After a separate review of watermarks and
operator-resolved DEAD facts, opt in with
`METRO_DOMAIN_EVENT_RETENTION_SCHEDULING_ENABLED=true`. Deletes remain bounded by batch/max-batches;
the default-on read-only metrics report unresolved DEAD count and oldest pending age even while
deletion is off.

## Emergency fence and forward fix

1. Stop writes/listeners/recovery and drain current work.
2. Run the `emergencyFence` action below from an authorized current-build operator. This is the only legal
   `CUTOVER -> POINTER_READ` move. `cutover_epoch` remains non-zero, permanently forbidding LEGACY,
   SHADOW and VERIFY_FENCE.

   ```bash
   METRO_STAGE_B_MIGRATION_ACTION=NONE \
   METRO_STAGE_B_ROLLOUT_ACTION=EMERGENCY_FENCE \
   java -jar metro-community.jar
   ```
3. Build a forward fix as a new immutable image digest with a non-decreasing binary generation and
   the exact current schema generation. A schema-generation increase requires a separate audited
   schema migration and a new full VERIFY cycle; `authorizeBuild` cannot claim it.
   Keep the old authorized operator image isolated while it runs the command below; this clears the
   old sentinel. The target schema generation must exactly equal the checkpoint schema generation.

   ```bash
   METRO_STAGE_B_MIGRATION_ACTION=NONE \
   METRO_STAGE_B_ROLLOUT_ACTION=AUTHORIZE_BUILD \
   METRO_STAGE_B_ROLLOUT_TARGET_BINARY_GENERATION=<new-binary-generation> \
   METRO_STAGE_B_ROLLOUT_TARGET_SCHEMA_GENERATION=<exact-current-schema-generation> \
   METRO_STAGE_B_ROLLOUT_TARGET_BUILD_DIGEST=<new-64-lowercase-hex-digest> \
   java -jar metro-community.jar
   ```
4. Update the admission allowlist, rotate credentials, deploy the new build in POINTER_READ and
   repeat the bound sentinel procedure. Only then transition to CUTOVER and reopen.

Never restore an old schema, clear `cutover_epoch`, edit checkpoint proof columns, or start an old
binary. Admission enforcement plus old DB/RabbitMQ credential revocation is what makes that rule
effective for binaries that do not contain the checkpoint gate.

## Required evidence bundle

- backup/restore proof; migration-twice and prefix-recovery logs; schema/grant manifests;
- immutable image digest, admission allowlist and per-pod digest/mode/generation inventory;
- old pod drain, old DB session and RabbitMQ connection/unacked-drain evidence, plus credential
  revocation/rotation evidence;
- checkpoint before/after each operator CAS, including `lock_version`, operator and timestamps;
- zero unresolved issues; stable `job.updated_at` window; zero Outbox lag and consumer/search lag;
- complete verification report + hash/fingerprint and complete sentinel report + token/hash;
- CUTOVER smoke, Rabbit/Inbox/ES evidence and final checkpoint with `cutover_epoch > 0`;
- live Provider quality result recorded as `NOT RUN` unless its separate opt-in test truly ran.
