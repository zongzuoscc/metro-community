package cumt.zongzuo.community.article.migration;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${metro.migration.stage-b.action:NONE}' == 'BACKFILL' || "
        + "'${metro.migration.stage-b.action:NONE}' == 'VERIFY'")
public class StageBMigrationRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(StageBMigrationRunner.class);

    private final StageBMigrationProperties properties;
    private final ArticleRevisionModeResolver modeResolver;
    private final StageBArticleMigrationService migrationService;
    private final StageBArticleMigrationVerifier verifier;

    public StageBMigrationRunner(StageBMigrationProperties properties,
                                 ArticleRevisionModeResolver modeResolver,
                                 StageBArticleMigrationService migrationService,
                                 StageBArticleMigrationVerifier verifier) {
        this.properties = properties;
        this.modeResolver = modeResolver;
        this.migrationService = migrationService;
        this.verifier = verifier;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        runOperatorAction();
    }

    StageBMigrationExecution runOperatorAction() {
        StageBMigrationAction action = properties.getAction();
        ArticleRevisionMode mode = modeResolver.current();
        assertAllowed(action, mode);

        MigrationRunResult backfill = migrationService.backfillAll(properties.getBatchSize());
        LOGGER.info("Stage B {} backfill finished: batches={}, scanned={}, migrated={}, issues={}",
                action, backfill.batches(), backfill.scanned(), backfill.migrated(), backfill.issues());

        if (action == StageBMigrationAction.BACKFILL) {
            if (backfill.issues() != 0) {
                throw new IllegalStateException("Stage B backfill found unresolved migration issues="
                        + backfill.issues());
            }
            return new StageBMigrationExecution(action, backfill, null);
        }

        StageBMigrationReport report = verifier.verifyAll();
        LOGGER.info("Stage B VERIFY finished: passed={}, mismatches={}, unresolvedArticles={}, "
                        + "mysqlPages={}, elasticsearchPages={}",
                report.passed(), report.mismatchCount(), report.unresolvedIssueArticleCount(),
                report.mysqlPages(), report.elasticsearchPages());
        if (!report.passed()) {
            throw new IllegalStateException("Stage B verification failed: mismatches="
                    + report.mismatchCount() + ", unresolvedArticles="
                    + report.unresolvedIssueArticleCount());
        }
        return new StageBMigrationExecution(action, backfill, report);
    }

    private static void assertAllowed(StageBMigrationAction action, ArticleRevisionMode mode) {
        if (action == StageBMigrationAction.BACKFILL
                && mode != ArticleRevisionMode.SHADOW
                && mode != ArticleRevisionMode.VERIFY_FENCE) {
            throw new IllegalStateException("Stage B BACKFILL requires SHADOW or VERIFY_FENCE mode");
        }
        if (action == StageBMigrationAction.VERIFY && mode != ArticleRevisionMode.VERIFY_FENCE) {
            throw new IllegalStateException("Stage B VERIFY requires VERIFY_FENCE mode");
        }
        if (action != StageBMigrationAction.BACKFILL && action != StageBMigrationAction.VERIFY) {
            throw new IllegalStateException("Stage B migration runner requires an explicit operator action");
        }
    }
}
