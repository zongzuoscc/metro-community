# Stage B schema expand runbook

This runbook applies the forward-only Stage B schema expansion. It does not switch runtime reads or
writes. Run it first on a production-sized clone and then inside an approved maintenance window.

## Change gate

1. Confirm MySQL 8, the target schema name, and the exact Git commit containing the migration.
2. Take a restorable backup and record its restore drill or snapshot identifier. The rollback strategy
   is forward repair plus restore; never improvise `DROP` statements against the expanded schema.
3. Measure the legacy tables before choosing the maintenance window:

   ```sql
   SELECT table_name, table_rows,
          ROUND(data_length / 1024 / 1024, 2) AS data_mib,
          ROUND(index_length / 1024 / 1024, 2) AS index_mib
   FROM information_schema.tables
   WHERE table_schema = DATABASE() AND table_name IN ('article', 'message', 'tag');
   ```

4. Verify that replica delay, disk headroom, backup retention, and application error budgets permit
   the expand. Stop before execution if any prerequisite is not recorded.

## Metadata-lock preflight

Run both checks immediately before the change. Resolve old transactions and unexpected pending or
granted locks with their owners; do not kill sessions without the service owner's approval.

```sql
SELECT trx_mysql_thread_id, trx_started, trx_state, trx_operation_state,
       TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS age_seconds,
       LEFT(trx_query, 300) AS trx_query
FROM information_schema.innodb_trx
ORDER BY trx_started;

SELECT object_schema, object_name, lock_type, lock_duration, lock_status,
       owner_thread_id, owner_event_id
FROM performance_schema.metadata_locks
WHERE object_schema = DATABASE() AND object_name IN ('article', 'message', 'tag')
ORDER BY object_name, lock_status, owner_thread_id;
```

The migration sets `SESSION lock_wait_timeout = 3`, restores the previous value after success, and
must run through a dedicated non-pooled connection. This timeout bounds only how long the migration
waits to acquire a metadata lock; it does not limit how long the DDL holds the lock after acquisition.
If a metadata lock blocks an ALTER, the run fails quickly. Close that failed session, resolve the
blocker, and rerun the complete migration; every object is guarded and a committed partial prefix is a
supported recovery state.

## Online-DDL rehearsal

MySQL may use `ALGORITHM=INSTANT` for supported column additions and `ALGORITHM=INPLACE` with
`LOCK=NONE` for some index operations, but version, row format, column order, unique indexes, and
foreign-key validation can change that choice. Even online DDL takes metadata locks at its boundaries.
The `tag.name` collation change can rebuild the tag table and its unique index, so include its measured
size, duplicate preflight, lock behavior, and copy time in the maintenance-window decision.
On the production-sized clone, inspect MySQL warnings and timings for every generated ALTER; do not
assume `INSTANT`, `INPLACE`, or `LOCK=NONE` merely because an operation is additive. Enlarge the
maintenance window or use the organization's reviewed online-schema-change tooling if rehearsal shows
a table copy or an unacceptable lock interval. Do not rewrite this guarded migration ad hoc in production.

## Execute and recover

Use a security-controlled client option file supplied by the deployment secret manager; do not put a
password in shell history, this repository, or either SQL template.

```bash
mysql --defaults-extra-file=/secure/runtime/stage-b-migration.cnf \
  --database="${APP_DB_NAME}" \
  < docs/database/migrations/2026-08-10-article-revision-moderation-outbox.sql
```

On `Lock wait timeout exceeded`, retain the log, close the migration connection, repeat the metadata-lock
preflight, release the approved blocker, and rerun the same file from the beginning. On `SCHEMA_DRIFT`,
stop: capture `SHOW CREATE TABLE`, `information_schema.columns`, `information_schema.statistics`, and
the foreign-key metadata before preparing a reviewed forward repair. Do not bypass a drift guard.

After success, execute the migration a second time. It must be a no-op, and the schema contract test must
match the production metadata. Record start/end times, warnings, row counts, and the backup identifier.

## Append-only application role

Create a new dedicated application login through the secret manager. Give it other application rights as
reviewed table-level grants; never give it global or schema-level `UPDATE`, `DELETE`, or `ALL PRIVILEGES`.
Render every placeholder in
`docs/database/operations/2026-08-10-stage-b-immutable-table-grants.sql` from the deployment account
catalogue, validate the rendered account/database tokens, and run it as a security administrator. The
template rejects inherited roles and effective mutation grants, creates one least-privilege role, and
grants only `SELECT`/`INSERT` on `article_revision` and `article_moderation_attempt`. Promotion is blocked
unless a fresh connection as the application login can select/insert and is denied update/delete on both.

## Rollout-checkpoint roles

The runtime checkpoint reader uses the primary application database login. Preserve its reviewed rights
on other application tables as direct table-level grants; do not replace that login with a checkpoint-only
account. Render and execute
`docs/database/operations/2026-08-10-stage-b-rollout-checkpoint-grants.sql` as a security administrator.
The template rejects global or schema-level mutation/DDL rights and inherited application roles. For
the runtime login/reader role it also rejects any non-`SELECT` checkpoint table or column grant. It adds
one default checkpoint-reader role. The separate
operator role receives only `SELECT`/`INSERT`/`UPDATE` on the checkpoint. Because the backfill,
fingerprint, verifier, and checkpoint operator share one primary DataSource, the operator role also
receives the template's reviewed exact table allow-list: `article` (`SELECT`,`UPDATE`), `article_tag` and
`tag` (`SELECT`), `article_draft`, `article_revision`, and `article_moderation_job`
(`SELECT`,`INSERT`), plus `article_revision_migration_issue` (`SELECT`,`INSERT`,`UPDATE`). It must still
be unable to `DELETE`, `ALTER`, `DROP`, or `TRUNCATE` the checkpoint. The admission precondition is an empty (or `NONE`)
`@@GLOBAL.mandatory_roles`; the template also rejects every dynamic privilege recorded in
`mysql.global_grants` for the runtime/operator logins and their controlled roles. Each controlled role
account must remain login-locked, may be granted only to its intended login, and its role edge must have
`WITH_ADMIN_OPTION='N'`.
