# Stage B revision-mode rollout (superseded)

This document is **superseded**. The single authoritative procedure is
[2026-08-10-stage-b-cutover-runbook.md](2026-08-10-stage-b-cutover-runbook.md).

Do not use a process-local `METRO_ARTICLE_REVISION_MODE` change as a promotion mechanism. The new
runbook requires the durable checkpoint/operator CAS, immutable-build admission, old-principal
drain/revocation, fenced verification, a build-bound pointer sentinel and forward-fix recovery.
