# Stage B article revision mode rollout

`METRO_ARTICLE_REVISION_MODE` is read once when each application process starts. The process-local
resolver freezes that value; changing a ConfigMap, environment variable, or configuration file does
not switch a running instance. Every startup logs `Article revision mode frozen at startup: <MODE>`.

The only forward sequence is:

```text
LEGACY -> SHADOW -> VERIFY_FENCE -> POINTER_READ -> CUTOVER
```

SHADOW may return to LEGACY before backfill/cutover. Once POINTER_READ has served traffic, an old
binary is not a safe rollback target: fence article writes and deploy a forward fix.

## Multi-instance switch protocol

Treat a mode transition as a deployment, never as a live feature-flag flip.

1. Stop new article write traffic at the gateway. Pause article write queue consumers and scheduled
   article writers; allow in-flight database transactions to drain.
2. Record the intended mode, build digest, replica count and operator/time in the deployment log.
3. Replace all application replicas with the same build and the same
   `METRO_ARTICLE_REVISION_MODE`. Do not let mixed-mode replicas serve article writes.
4. Check every replica's startup log and deployment environment. The observed mode and build digest
   must match the deployment record for every replica before write traffic resumes.
5. Run the mode-specific smoke/gate. Resume consumers, schedulers and article write traffic only
   after the gate passes.

For the LEGACY-to-SHADOW deployment, no backfill may start until step 4 proves that every writer is
on SHADOW. For VERIFY_FENCE, leave writes and article-mutating consumers paused while the final
backfill/verification window runs. A 503 with reason `ARTICLE_CUTOVER_IN_PROGRESS` is the expected
response from any write that reaches a fenced replica.

Promotion from VERIFY_FENCE to POINTER_READ requires the durable verifier PASS produced by Task 4.
POINTER_READ remains write-fenced. Promotion to CUTOVER uses the same stopped-traffic replacement
protocol; only after every replica reports CUTOVER may draft/revision writes resume.
