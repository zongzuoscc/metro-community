-- Operator template for the append-only application principal.
-- Substitute every ${...} token from the deployment account catalogue before execution.
-- Credentials are intentionally out of scope: create the dedicated login through the secret manager first.
-- Run this as a security administrator that can inspect mysql.role_edges and manage roles/grants.

SET @immutable_database = '${APP_DB_NAME}';
SET @immutable_app_user = '${APP_DB_USER}';
SET @immutable_app_host = '${APP_DB_HOST}';
SET @immutable_role_user = '${IMMUTABLE_ROLE}';
SET @immutable_role_host = '${IMMUTABLE_ROLE_HOST}';

-- MySQL expands ALL PRIVILEGES into individual rows in information_schema. Catching UPDATE/DELETE
-- therefore rejects global/schema ALL as well as explicit mutation grants. Direct mutation grants
-- on either append-only table are rejected too; this script never tries an unreliable REVOKE.
SET @immutable_unsafe_count = (
    SELECT COUNT(*)
    FROM (
        SELECT privilege_type
        FROM information_schema.user_privileges
        WHERE grantee = CONCAT('\'', @immutable_app_user, '\'@\'', @immutable_app_host, '\'')
          AND privilege_type IN ('UPDATE', 'DELETE')
        UNION ALL
        SELECT privilege_type
        FROM information_schema.schema_privileges
        WHERE grantee = CONCAT('\'', @immutable_app_user, '\'@\'', @immutable_app_host, '\'')
          AND table_schema = @immutable_database
          AND privilege_type IN ('UPDATE', 'DELETE')
        UNION ALL
        SELECT privilege_type
        FROM information_schema.table_privileges
        WHERE grantee = CONCAT('\'', @immutable_app_user, '\'@\'', @immutable_app_host, '\'')
          AND table_schema = @immutable_database
          AND table_name IN ('article_revision', 'article_moderation_attempt')
          AND privilege_type IN ('UPDATE', 'DELETE')
        UNION ALL
        SELECT privilege_type
        FROM information_schema.column_privileges
        WHERE grantee = CONCAT('\'', @immutable_app_user, '\'@\'', @immutable_app_host, '\'')
          AND table_schema = @immutable_database
          AND table_name IN ('article_revision', 'article_moderation_attempt')
          AND privilege_type IN ('UPDATE', 'DELETE')
    ) unsafe_privileges
);
SET @ddl = IF(@immutable_unsafe_count = 0,
    'SELECT 1',
    'SELECT IMMUTABLE_GRANT_DRIFT_EFFECTIVE_MUTATION_PRIVILEGE');
PREPARE immutable_grant_stmt FROM @ddl;
EXECUTE immutable_grant_stmt;
DEALLOCATE PREPARE immutable_grant_stmt;

-- A dedicated principal may inherit only the controlled immutable role. This makes the effective
-- privilege closure auditable: an unrelated inherited role cannot silently restore mutation rights.
SET @immutable_unapproved_roles = (
    SELECT COUNT(*)
    FROM mysql.role_edges
    WHERE to_user = @immutable_app_user
      AND to_host = @immutable_app_host
      AND NOT (from_user = @immutable_role_user AND from_host = @immutable_role_host)
);
SET @ddl = IF(@immutable_unapproved_roles = 0,
    'SELECT 1',
    'SELECT IMMUTABLE_GRANT_DRIFT_UNAPPROVED_INHERITED_ROLE');
PREPARE immutable_grant_stmt FROM @ddl;
EXECUTE immutable_grant_stmt;
DEALLOCATE PREPARE immutable_grant_stmt;

CREATE ROLE IF NOT EXISTS '${IMMUTABLE_ROLE}'@'${IMMUTABLE_ROLE_HOST}';

-- Fail closed if an existing same-name role was broadened before this idempotent re-run.
SET @immutable_role_unsafe_count = (
    SELECT COUNT(*)
    FROM (
        SELECT privilege_type
        FROM information_schema.user_privileges
        WHERE grantee = CONCAT('\'', @immutable_role_user, '\'@\'', @immutable_role_host, '\'')
          AND privilege_type IN ('UPDATE', 'DELETE')
        UNION ALL
        SELECT privilege_type
        FROM information_schema.schema_privileges
        WHERE grantee = CONCAT('\'', @immutable_role_user, '\'@\'', @immutable_role_host, '\'')
          AND table_schema = @immutable_database
          AND privilege_type IN ('UPDATE', 'DELETE')
        UNION ALL
        SELECT privilege_type
        FROM information_schema.table_privileges
        WHERE grantee = CONCAT('\'', @immutable_role_user, '\'@\'', @immutable_role_host, '\'')
          AND table_schema = @immutable_database
          AND table_name IN ('article_revision', 'article_moderation_attempt')
          AND privilege_type IN ('UPDATE', 'DELETE')
        UNION ALL
        SELECT privilege_type
        FROM information_schema.column_privileges
        WHERE grantee = CONCAT('\'', @immutable_role_user, '\'@\'', @immutable_role_host, '\'')
          AND table_schema = @immutable_database
          AND table_name IN ('article_revision', 'article_moderation_attempt')
          AND privilege_type IN ('UPDATE', 'DELETE')
    ) unsafe_role_privileges
);
SET @immutable_role_inheritance = (
    SELECT COUNT(*)
    FROM mysql.role_edges
    WHERE to_user = @immutable_role_user AND to_host = @immutable_role_host
);
SET @ddl = IF(@immutable_role_unsafe_count = 0 AND @immutable_role_inheritance = 0,
    'SELECT 1',
    'SELECT IMMUTABLE_GRANT_DRIFT_ROLE_MUTATION_PRIVILEGE');
PREPARE immutable_grant_stmt FROM @ddl;
EXECUTE immutable_grant_stmt;
DEALLOCATE PREPARE immutable_grant_stmt;

GRANT SELECT, INSERT ON `${APP_DB_NAME}`.`article_revision`
    TO '${IMMUTABLE_ROLE}'@'${IMMUTABLE_ROLE_HOST}';
GRANT SELECT, INSERT ON `${APP_DB_NAME}`.`article_moderation_attempt`
    TO '${IMMUTABLE_ROLE}'@'${IMMUTABLE_ROLE_HOST}';
GRANT '${IMMUTABLE_ROLE}'@'${IMMUTABLE_ROLE_HOST}'
    TO '${APP_DB_USER}'@'${APP_DB_HOST}';
SET DEFAULT ROLE '${IMMUTABLE_ROLE}'@'${IMMUTABLE_ROLE_HOST}'
    TO '${APP_DB_USER}'@'${APP_DB_HOST}';

-- Postcondition: the immutable role has exactly the four allow-listed table privileges.
SET @immutable_allowed_count = (
    SELECT COUNT(*)
    FROM information_schema.table_privileges
    WHERE grantee = CONCAT('\'', @immutable_role_user, '\'@\'', @immutable_role_host, '\'')
      AND table_schema = @immutable_database
      AND table_name IN ('article_revision', 'article_moderation_attempt')
      AND privilege_type IN ('SELECT', 'INSERT')
);
SET @immutable_unexpected_table_privileges = (
    SELECT COUNT(*)
    FROM information_schema.table_privileges
    WHERE grantee = CONCAT('\'', @immutable_role_user, '\'@\'', @immutable_role_host, '\'')
      AND table_schema = @immutable_database
      AND table_name IN ('article_revision', 'article_moderation_attempt')
      AND privilege_type NOT IN ('SELECT', 'INSERT')
);
SET @immutable_unexpected_column_privileges = (
    SELECT COUNT(*)
    FROM information_schema.column_privileges
    WHERE grantee = CONCAT('\'', @immutable_role_user, '\'@\'', @immutable_role_host, '\'')
      AND table_schema = @immutable_database
      AND table_name IN ('article_revision', 'article_moderation_attempt')
);
SET @ddl = IF(@immutable_allowed_count = 4
               AND @immutable_unexpected_table_privileges = 0
               AND @immutable_unexpected_column_privileges = 0,
    'SELECT 1',
    'SELECT IMMUTABLE_GRANT_DRIFT_ROLE_POSTCONDITION');
PREPARE immutable_grant_stmt FROM @ddl;
EXECUTE immutable_grant_stmt;
DEALLOCATE PREPARE immutable_grant_stmt;
