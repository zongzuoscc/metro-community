-- Least-privilege roles for the durable Stage B rollout checkpoint.
-- Substitute every ${...} token from the deployment account catalogue before execution.
-- Create the two dedicated login users through the secret manager before running this template.

SET @rollout_database = '${APP_DB_NAME}';
SET @rollout_runtime_user = '${ROLLOUT_RUNTIME_USER}';
SET @rollout_runtime_host = '${ROLLOUT_RUNTIME_HOST}';
SET @rollout_runtime_role = '${ROLLOUT_RUNTIME_ROLE}';
SET @rollout_runtime_role_host = '${ROLLOUT_RUNTIME_ROLE_HOST}';
SET @rollout_operator_user = '${ROLLOUT_OPERATOR_USER}';
SET @rollout_operator_host = '${ROLLOUT_OPERATOR_HOST}';
SET @rollout_operator_role = '${ROLLOUT_OPERATOR_ROLE}';
SET @rollout_operator_role_host = '${ROLLOUT_OPERATOR_ROLE_HOST}';

-- These are four separate security principals. Aliasing a runtime login to the operator login or
-- either role would make the role-edge audit meaningless and could give a runtime pod write access.
SET @rollout_identity_valid =
    NOT (@rollout_runtime_user=@rollout_operator_user
         AND @rollout_runtime_host=@rollout_operator_host)
    AND NOT (@rollout_runtime_user=@rollout_runtime_role
             AND @rollout_runtime_host=@rollout_runtime_role_host)
    AND NOT (@rollout_runtime_user=@rollout_operator_role
             AND @rollout_runtime_host=@rollout_operator_role_host)
    AND NOT (@rollout_operator_user=@rollout_runtime_role
             AND @rollout_operator_host=@rollout_runtime_role_host)
    AND NOT (@rollout_operator_user=@rollout_operator_role
             AND @rollout_operator_host=@rollout_operator_role_host)
    AND NOT (@rollout_runtime_role=@rollout_operator_role
             AND @rollout_runtime_role_host=@rollout_operator_role_host);
SET @ddl = IF(@rollout_identity_valid,
    'SELECT 1', 'SELECT ROLLOUT_GRANT_DRIFT_IDENTITY_ALIAS');
PREPARE rollout_grant_stmt FROM @ddl;
EXECUTE rollout_grant_stmt;
DEALLOCATE PREPARE rollout_grant_stmt;

-- Mandatory roles are effective for every account but intentionally absent from mysql.role_edges.
-- Fail closed instead of attempting to reproduce the server's privilege expansion here.
SET @rollout_mandatory_roles_safe =
    UPPER(TRIM(COALESCE(@@GLOBAL.mandatory_roles,''))) IN ('','NONE');
SET @ddl = IF(@rollout_mandatory_roles_safe,
    'SELECT 1', 'SELECT ROLLOUT_GRANT_DRIFT_MANDATORY_ROLES');
PREPARE rollout_grant_stmt FROM @ddl;
EXECUTE rollout_grant_stmt;
DEALLOCATE PREPARE rollout_grant_stmt;

CREATE ROLE IF NOT EXISTS
    '${ROLLOUT_RUNTIME_ROLE}'@'${ROLLOUT_RUNTIME_ROLE_HOST}',
    '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}';

-- CREATE ROLE IF NOT EXISTS must not silently reuse an unlocked login account with the same name.
SET @rollout_role_accounts_locked = (
    SELECT COUNT(*) FROM mysql.user
    WHERE ((user=@rollout_runtime_role AND host=@rollout_runtime_role_host)
        OR (user=@rollout_operator_role AND host=@rollout_operator_role_host))
      AND account_locked='Y'
);
SET @ddl = IF(@rollout_role_accounts_locked=2,
    'SELECT 1', 'SELECT ROLLOUT_GRANT_DRIFT_ROLE_LOGIN_ENABLED');
PREPARE rollout_grant_stmt FROM @ddl;
EXECUTE rollout_grant_stmt;
DEALLOCATE PREPARE rollout_grant_stmt;

-- A controlled role may be unassigned on the first run or assigned only to its intended login,
-- without ADMIN OPTION. Any third-party member would receive privileges added later in this file.
SET @rollout_controlled_role_membership_drift = (
    SELECT COUNT(*) FROM mysql.role_edges
    WHERE ((from_user=@rollout_runtime_role AND from_host=@rollout_runtime_role_host)
        OR (from_user=@rollout_operator_role AND from_host=@rollout_operator_role_host))
      AND NOT (
        (from_user=@rollout_runtime_role AND from_host=@rollout_runtime_role_host
         AND to_user=@rollout_runtime_user AND to_host=@rollout_runtime_host
         AND with_admin_option='N')
        OR
        (from_user=@rollout_operator_role AND from_host=@rollout_operator_role_host
         AND to_user=@rollout_operator_user AND to_host=@rollout_operator_host
         AND with_admin_option='N'))
);
SET @ddl = IF(@rollout_controlled_role_membership_drift=0,
    'SELECT 1', 'SELECT ROLLOUT_GRANT_DRIFT_CONTROLLED_ROLE_MEMBERSHIP');
PREPARE rollout_grant_stmt FROM @ddl;
EXECUTE rollout_grant_stmt;
DEALLOCATE PREPARE rollout_grant_stmt;

-- Dynamic privileges such as ROLE_ADMIN live in mysql.global_grants rather than the static
-- information_schema privilege views. None of the four rollout principals may hold one.
SET @rollout_dynamic_privilege_count = (
    SELECT COUNT(*) FROM mysql.global_grants
    WHERE (user=@rollout_runtime_user AND host=@rollout_runtime_host)
       OR (user=@rollout_runtime_role AND host=@rollout_runtime_role_host)
       OR (user=@rollout_operator_user AND host=@rollout_operator_host)
       OR (user=@rollout_operator_role AND host=@rollout_operator_role_host)
);
SET @ddl = IF(@rollout_dynamic_privilege_count=0,
    'SELECT 1', 'SELECT ROLLOUT_GRANT_DRIFT_DYNAMIC_PRIVILEGE');
PREPARE rollout_grant_stmt FROM @ddl;
EXECUTE rollout_grant_stmt;
DEALLOCATE PREPARE rollout_grant_stmt;

-- The runtime login is the application's primary database principal, so reviewed table-level rights
-- on other application tables are preserved. Global/schema mutation authority would also cover the
-- checkpoint, however, and direct checkpoint authority must remain SELECT-only. DROP authority also
-- permits TRUNCATE. Other application rights must be direct table grants rather than inherited roles,
-- so the effective checkpoint privilege closure remains auditable.
SET @rollout_runtime_direct_privileges = (
    SELECT COUNT(*) FROM (
        SELECT privilege_type FROM information_schema.user_privileges
        WHERE grantee=CONCAT('\'',@rollout_runtime_user,'\'@\'',@rollout_runtime_host,'\'')
          AND privilege_type NOT IN ('USAGE','SELECT')
        UNION ALL
        SELECT privilege_type FROM information_schema.schema_privileges
        WHERE grantee=CONCAT('\'',@rollout_runtime_user,'\'@\'',@rollout_runtime_host,'\'')
          AND table_schema=@rollout_database
          AND privilege_type<>'SELECT'
        UNION ALL
        SELECT privilege_type FROM information_schema.table_privileges
        WHERE grantee=CONCAT('\'',@rollout_runtime_user,'\'@\'',@rollout_runtime_host,'\'')
          AND table_schema=@rollout_database
          AND table_name='article_revision_rollout_checkpoint'
          AND privilege_type<>'SELECT'
        UNION ALL
        SELECT privilege_type FROM information_schema.column_privileges
        WHERE grantee=CONCAT('\'',@rollout_runtime_user,'\'@\'',@rollout_runtime_host,'\'')
          AND table_schema=@rollout_database
          AND table_name='article_revision_rollout_checkpoint'
          AND privilege_type<>'SELECT'
    ) runtime_direct
);
SET @rollout_runtime_role_unexpected_privileges = (
    SELECT COUNT(*) FROM (
        SELECT privilege_type FROM information_schema.user_privileges
        WHERE grantee=CONCAT('\'',@rollout_runtime_role,'\'@\'',@rollout_runtime_role_host,'\'')
          AND privilege_type<>'USAGE'
        UNION ALL
        SELECT privilege_type FROM information_schema.schema_privileges
        WHERE grantee=CONCAT('\'',@rollout_runtime_role,'\'@\'',@rollout_runtime_role_host,'\'')
        UNION ALL
        SELECT privilege_type FROM information_schema.table_privileges
        WHERE grantee=CONCAT('\'',@rollout_runtime_role,'\'@\'',@rollout_runtime_role_host,'\'')
          AND NOT (table_schema=@rollout_database
                   AND table_name='article_revision_rollout_checkpoint'
                   AND privilege_type='SELECT')
        UNION ALL
        SELECT privilege_type FROM information_schema.column_privileges
        WHERE grantee=CONCAT('\'',@rollout_runtime_role,'\'@\'',@rollout_runtime_role_host,'\'')
    ) runtime_role_unexpected
);
SET @rollout_runtime_unapproved_roles = (
    SELECT COUNT(*) FROM mysql.role_edges
    WHERE to_user=@rollout_runtime_user AND to_host=@rollout_runtime_host
      AND NOT (from_user=@rollout_runtime_role AND from_host=@rollout_runtime_role_host)
);
SET @rollout_runtime_role_inheritance = (
    SELECT COUNT(*) FROM mysql.role_edges
    WHERE to_user=@rollout_runtime_role AND to_host=@rollout_runtime_role_host
);
SET @ddl = IF(@rollout_runtime_direct_privileges=0
              AND @rollout_runtime_role_unexpected_privileges=0
              AND @rollout_runtime_unapproved_roles=0
              AND @rollout_runtime_role_inheritance=0,
    'SELECT 1', 'SELECT ROLLOUT_GRANT_DRIFT_RUNTIME_EFFECTIVE_PRIVILEGE');
PREPARE rollout_grant_stmt FROM @ddl;
EXECUTE rollout_grant_stmt;
DEALLOCATE PREPARE rollout_grant_stmt;

SET @rollout_operator_direct_privileges = (
    SELECT COUNT(*) FROM (
        SELECT privilege_type FROM information_schema.user_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_user,'\'@\'',@rollout_operator_host,'\'')
          AND privilege_type<>'USAGE'
        UNION ALL
        SELECT privilege_type FROM information_schema.schema_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_user,'\'@\'',@rollout_operator_host,'\'')
          AND table_schema=@rollout_database
        UNION ALL
        SELECT privilege_type FROM information_schema.table_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_user,'\'@\'',@rollout_operator_host,'\'')
        UNION ALL
        SELECT privilege_type FROM information_schema.column_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_user,'\'@\'',@rollout_operator_host,'\'')
    ) operator_direct
);
SET @rollout_operator_role_unexpected_privileges = (
    SELECT COUNT(*) FROM (
        SELECT privilege_type FROM information_schema.user_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_role,'\'@\'',@rollout_operator_role_host,'\'')
          AND privilege_type<>'USAGE'
        UNION ALL
        SELECT privilege_type FROM information_schema.schema_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_role,'\'@\'',@rollout_operator_role_host,'\'')
        UNION ALL
        SELECT privilege_type FROM information_schema.table_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_role,'\'@\'',@rollout_operator_role_host,'\'')
          AND NOT (table_schema=@rollout_database AND (
               (table_name='article_revision_rollout_checkpoint'
                    AND privilege_type IN ('SELECT','INSERT','UPDATE'))
            OR (table_name='article' AND privilege_type IN ('SELECT','UPDATE'))
            OR (table_name IN ('article_tag','tag','article_moderation_attempt')
                    AND privilege_type='SELECT')
            OR (table_name IN ('article_draft','article_revision','article_moderation_job')
                    AND privilege_type IN ('SELECT','INSERT'))
            OR (table_name='article_revision_migration_issue'
                    AND privilege_type IN ('SELECT','INSERT','UPDATE'))))
        UNION ALL
        SELECT privilege_type FROM information_schema.column_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_role,'\'@\'',@rollout_operator_role_host,'\'')
    ) operator_role_unexpected
);
SET @rollout_operator_unapproved_roles = (
    SELECT COUNT(*) FROM mysql.role_edges
    WHERE to_user=@rollout_operator_user AND to_host=@rollout_operator_host
      AND NOT (from_user=@rollout_operator_role AND from_host=@rollout_operator_role_host)
);
SET @rollout_operator_role_inheritance = (
    SELECT COUNT(*) FROM mysql.role_edges
    WHERE to_user=@rollout_operator_role AND to_host=@rollout_operator_role_host
);
SET @ddl = IF(@rollout_operator_direct_privileges=0
              AND @rollout_operator_role_unexpected_privileges=0
              AND @rollout_operator_unapproved_roles=0
              AND @rollout_operator_role_inheritance=0,
    'SELECT 1', 'SELECT ROLLOUT_GRANT_DRIFT_OPERATOR_EFFECTIVE_PRIVILEGE');
PREPARE rollout_grant_stmt FROM @ddl;
EXECUTE rollout_grant_stmt;
DEALLOCATE PREPARE rollout_grant_stmt;

GRANT SELECT ON `${APP_DB_NAME}`.`article_revision_rollout_checkpoint`
    TO '${ROLLOUT_RUNTIME_ROLE}'@'${ROLLOUT_RUNTIME_ROLE_HOST}';
GRANT SELECT, INSERT, UPDATE ON `${APP_DB_NAME}`.`article_revision_rollout_checkpoint`
    TO '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}';
GRANT SELECT, UPDATE ON `${APP_DB_NAME}`.`article`
    TO '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}';
GRANT SELECT ON `${APP_DB_NAME}`.`article_tag`
    TO '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}';
GRANT SELECT ON `${APP_DB_NAME}`.`tag`
    TO '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}';
GRANT SELECT, INSERT ON `${APP_DB_NAME}`.`article_draft`
    TO '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}';
GRANT SELECT, INSERT ON `${APP_DB_NAME}`.`article_revision`
    TO '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}';
GRANT SELECT, INSERT ON `${APP_DB_NAME}`.`article_moderation_job`
    TO '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}';
GRANT SELECT ON `${APP_DB_NAME}`.`article_moderation_attempt`
    TO '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}';
GRANT SELECT, INSERT, UPDATE ON `${APP_DB_NAME}`.`article_revision_migration_issue`
    TO '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}';
GRANT '${ROLLOUT_RUNTIME_ROLE}'@'${ROLLOUT_RUNTIME_ROLE_HOST}'
    TO '${ROLLOUT_RUNTIME_USER}'@'${ROLLOUT_RUNTIME_HOST}';
GRANT '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}'
    TO '${ROLLOUT_OPERATOR_USER}'@'${ROLLOUT_OPERATOR_HOST}';
SET DEFAULT ROLE '${ROLLOUT_RUNTIME_ROLE}'@'${ROLLOUT_RUNTIME_ROLE_HOST}'
    TO '${ROLLOUT_RUNTIME_USER}'@'${ROLLOUT_RUNTIME_HOST}';
SET DEFAULT ROLE '${ROLLOUT_OPERATOR_ROLE}'@'${ROLLOUT_OPERATOR_ROLE_HOST}'
    TO '${ROLLOUT_OPERATOR_USER}'@'${ROLLOUT_OPERATOR_HOST}';

-- Re-read the grant tables after mutation. The two roles have exact checkpoint allow-lists, both
-- logins have no disallowed direct privilege, and neither login can inherit the other rollout role.
SET @rollout_runtime_allowed = (
    SELECT COUNT(*) FROM information_schema.table_privileges
    WHERE grantee=CONCAT('\'',@rollout_runtime_role,'\'@\'',@rollout_runtime_role_host,'\'')
      AND table_schema=@rollout_database
      AND table_name='article_revision_rollout_checkpoint'
      AND privilege_type='SELECT'
);
SET @rollout_operator_allowed = (
    SELECT COUNT(*) FROM information_schema.table_privileges
    WHERE grantee=CONCAT('\'',@rollout_operator_role,'\'@\'',@rollout_operator_role_host,'\'')
      AND table_schema=@rollout_database
      AND ((table_name='article_revision_rollout_checkpoint'
                AND privilege_type IN ('SELECT','INSERT','UPDATE'))
        OR (table_name='article' AND privilege_type IN ('SELECT','UPDATE'))
        OR (table_name IN ('article_tag','tag','article_moderation_attempt')
                AND privilege_type='SELECT')
        OR (table_name IN ('article_draft','article_revision','article_moderation_job')
                AND privilege_type IN ('SELECT','INSERT'))
        OR (table_name='article_revision_migration_issue'
                AND privilege_type IN ('SELECT','INSERT','UPDATE')))
);
SET @rollout_operator_unexpected_after = (
    SELECT COUNT(*) FROM (
        SELECT privilege_type FROM information_schema.user_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_role,'\'@\'',@rollout_operator_role_host,'\'')
          AND privilege_type<>'USAGE'
        UNION ALL
        SELECT privilege_type FROM information_schema.schema_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_role,'\'@\'',@rollout_operator_role_host,'\'')
        UNION ALL
        SELECT privilege_type FROM information_schema.table_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_role,'\'@\'',@rollout_operator_role_host,'\'')
          AND NOT (table_schema=@rollout_database AND (
               (table_name='article_revision_rollout_checkpoint'
                    AND privilege_type IN ('SELECT','INSERT','UPDATE'))
            OR (table_name='article' AND privilege_type IN ('SELECT','UPDATE'))
            OR (table_name IN ('article_tag','tag','article_moderation_attempt')
                    AND privilege_type='SELECT')
            OR (table_name IN ('article_draft','article_revision','article_moderation_job')
                    AND privilege_type IN ('SELECT','INSERT'))
            OR (table_name='article_revision_migration_issue'
                    AND privilege_type IN ('SELECT','INSERT','UPDATE'))))
        UNION ALL
        SELECT privilege_type FROM information_schema.column_privileges
        WHERE grantee=CONCAT('\'',@rollout_operator_role,'\'@\'',@rollout_operator_role_host,'\'')
    ) operator_role_unexpected_after
);
SET @rollout_role_edges_valid =
    (SELECT COUNT(*) FROM mysql.role_edges
     WHERE (from_user=@rollout_runtime_role AND from_host=@rollout_runtime_role_host)
        OR (from_user=@rollout_operator_role AND from_host=@rollout_operator_role_host))=2
    AND (SELECT COUNT(*) FROM mysql.role_edges
         WHERE to_user=@rollout_runtime_user AND to_host=@rollout_runtime_host
           AND from_user=@rollout_runtime_role AND from_host=@rollout_runtime_role_host
           AND with_admin_option='N')=1
    AND (SELECT COUNT(*) FROM mysql.role_edges
         WHERE to_user=@rollout_operator_user AND to_host=@rollout_operator_host
           AND from_user=@rollout_operator_role AND from_host=@rollout_operator_role_host
           AND with_admin_option='N')=1
    AND @rollout_runtime_unapproved_roles=0
    AND @rollout_operator_unapproved_roles=0;
SET @rollout_default_roles_valid =
    (SELECT COUNT(*) FROM mysql.default_roles
     WHERE user=@rollout_runtime_user AND host=@rollout_runtime_host
       AND default_role_user=@rollout_runtime_role
       AND default_role_host=@rollout_runtime_role_host)=1
    AND
    (SELECT COUNT(*) FROM mysql.default_roles
     WHERE user=@rollout_operator_user AND host=@rollout_operator_host
       AND default_role_user=@rollout_operator_role
       AND default_role_host=@rollout_operator_role_host)=1;
SET @rollout_role_accounts_locked_after = (
    SELECT COUNT(*) FROM mysql.user
    WHERE ((user=@rollout_runtime_role AND host=@rollout_runtime_role_host)
        OR (user=@rollout_operator_role AND host=@rollout_operator_role_host))
      AND account_locked='Y'
);
SET @ddl = IF(@rollout_runtime_allowed=1
              AND @rollout_operator_allowed=17
              AND @rollout_runtime_direct_privileges=0
              AND @rollout_operator_direct_privileges=0
              AND @rollout_runtime_role_unexpected_privileges=0
              AND @rollout_operator_unexpected_after=0
              AND @rollout_role_edges_valid
              AND @rollout_default_roles_valid
              AND @rollout_role_accounts_locked_after=2,
    'SELECT 1', 'SELECT ROLLOUT_GRANT_DRIFT_POSTCONDITION');
PREPARE rollout_grant_stmt FROM @ddl;
EXECUTE rollout_grant_stmt;
DEALLOCATE PREPARE rollout_grant_stmt;
