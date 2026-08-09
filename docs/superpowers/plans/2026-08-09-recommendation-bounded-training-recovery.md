# Recommendation Bounded Training and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound every recommendation training/recovery database scan and persist the immutable author attribution key.

**Architecture:** Read at most `trainingExposureScanLimit + 1` recent exposure rows through the chronological index, reject a truncated dataset, and apply per-user/global cohort caps in Java. Attribute follows through a persisted author snapshot. Select profile repairs through an explicit pending flag and leading-column index.

**Tech Stack:** Java 21, Spring Boot, JdbcTemplate/MyBatis-Plus, MySQL 8, JUnit 5, Testcontainers

## Global Constraints

- Do not modify WebSocket ticket files or stop the live process on port 18080.
- Production data paths use real MySQL; tests use the existing MySQL Testcontainer.
- Forward migrations remain idempotent and preserve unresolved legacy exposure rows without fabricating authors.
- An incomplete exposure or fact scan never publishes or replaces the active model.

---

### Task 1: Bound exposure cohort construction

**Files:**
- Modify: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationTrainingIntegrationTest.java`
- Modify: `src/test/java/cumt/zongzuo/community/recommendation/config/RecommendationPropertiesTest.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/training/RecommendationTrainingDataset.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/training/RecommendationTrainingService.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/config/RecommendationProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-example.yml`
- Modify: `.env.example`

- [x] Write integration tests showing an exact exposure scan limit remains usable and limit plus one returns `EXPOSURE_SCAN_LIMIT_EXCEEDED` without replacing the active model.
- [x] Run the focused Java 21 test and verify the new status/property expectations fail for the missing behavior.
- [x] Replace the window CTE with an indexed `LIMIT scanLimit + 1` chronological read and Java-side per-user/global selection; expose the new property and publication reason.
- [x] Re-run focused tests and keep the existing fact-limit behavior distinct.

### Task 2: Persist author attribution snapshots

**Files:**
- Modify: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationFeedIntegrationTest.java`
- Modify: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationTrainingIntegrationTest.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationExposureDraft.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/entity/RecommendationExposure.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/mapper/RecommendationExposureMapper.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationFeedService.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationExposureService.java`
- Modify: `script.sql`

- [x] Write real-database tests proving feed delivery persists `article_author_id` and follow attribution remains tied to that snapshot after the article row changes.
- [x] Run the focused Java 21 tests and verify they fail because the column/API/query is absent.
- [x] Carry author ID from the already hydrated article into the exposure insert and replace the follow attribution join with the snapshot predicate.
- [x] Re-run focused tests and verify replay idempotency is unchanged.

### Task 3: Index only pending profile repairs

**Files:**
- Modify: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationEventIntegrationTest.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationProfileRecoveryService.java`
- Modify: `script.sql`

- [x] Write real-database tests proving completion clears `needs_rebuild`, a newer request restores it, and completed history cannot consume a bounded repair batch.
- [x] Run the focused Java 21 test and verify it fails on the missing column/state transitions.
- [x] Add monotonic conditional state updates and select only pending rows through the new composite index.
- [x] Re-run the focused event integration test.

### Task 4: Idempotent forward migration and documentation

**Files:**
- Modify: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationTrainingIntegrationTest.java`
- Modify: `docs/database/migrations/2026-08-09-recommendation-training.sql`
- Modify: `README.md`
- Modify: `.superpowers/sdd/task-9-report.md` (ignored progress report)

- [x] Extend the migration smoke test with legacy exposure/checkpoint tables and assert two executions backfill author/pending state and create both composite indexes.
- [x] Run the migration test RED, then implement additive columns, deterministic backfills, and idempotent index creation.
- [x] Document both scan limits, snapshot attribution, pending repair selection, and call `.env.example` the main rather than complete parameter set.
- [x] Run focused recommendation tests, `./mvnw test`, `git diff --check`, inspect scope, write the ignored report, and create one local commit without pushing.
