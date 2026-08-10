package cumt.zongzuo.community.article.rollout;

import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.migration.DefaultStageBArticleMigrationVerifier;
import cumt.zongzuo.community.article.migration.JdbcStageBArticleMigrationService;
import cumt.zongzuo.community.article.migration.JdbcStageBArticleFingerprintService;
import cumt.zongzuo.community.article.migration.StageBArticleMigrationService;
import cumt.zongzuo.community.article.migration.StageBArticleMigrationVerifier;
import cumt.zongzuo.community.article.migration.StageBArticleFingerprintService;
import cumt.zongzuo.community.article.migration.StageBMigrationAction;
import cumt.zongzuo.community.article.migration.StageBMigrationProperties;
import cumt.zongzuo.community.article.migration.StageBMigrationRunner;
import cumt.zongzuo.community.article.migration.StageBVerificationArtifactWriter;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.elasticsearch.client.RestClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Locale;

public final class StageBRolloutOperatorApplication {

    private static final String STANDALONE_PROFILE = "stage-b-standalone-operator";

    private static final String ACTION_PROPERTY = "metro.article.rollout-operator.action";
    private static final String ACTION_ARGUMENT = "--" + ACTION_PROPERTY + "=";
    private static final String ACTION_ENVIRONMENT = "METRO_STAGE_B_ROLLOUT_ACTION";
    private static final String MIGRATION_ACTION_PROPERTY = "metro.migration.stage-b.action";
    private static final String MIGRATION_ACTION_ARGUMENT = "--" + MIGRATION_ACTION_PROPERTY + "=";
    private static final String MIGRATION_ACTION_ENVIRONMENT = "METRO_STAGE_B_MIGRATION_ACTION";

    private StageBRolloutOperatorApplication() {
    }

    public static boolean isRequested(String[] arguments) {
        for (String argument : arguments) {
            if (argument.startsWith(ACTION_ARGUMENT)) {
                if (isExplicitAction(argument.substring(ACTION_ARGUMENT.length()))) {
                    return true;
                }
            }
            if (argument.startsWith(MIGRATION_ACTION_ARGUMENT)) {
                if (isExplicitAction(argument.substring(MIGRATION_ACTION_ARGUMENT.length()))) {
                    return true;
                }
            }
        }
        String systemAction = System.getProperty(ACTION_PROPERTY);
        if (isExplicitAction(systemAction)) {
            return true;
        }
        String migrationSystemAction = System.getProperty(MIGRATION_ACTION_PROPERTY);
        if (isExplicitAction(migrationSystemAction)) {
            return true;
        }
        return isExplicitAction(System.getenv(ACTION_ENVIRONMENT))
                || isExplicitAction(System.getenv(MIGRATION_ACTION_ENVIRONMENT));
    }

    public static void run(String... arguments) {
        try (ConfigurableApplicationContext ignored = start(arguments)) {
            // ApplicationRunner completes the one-shot command before start() returns.
        }
    }

    static ConfigurableApplicationContext start(String... arguments) {
        SpringApplication application = new SpringApplication(OperatorConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.setLazyInitialization(true);
        application.setAdditionalProfiles(STANDALONE_PROFILE);
        return application.run(arguments);
    }

    private static boolean isExplicitAction(String value) {
        return value != null && !value.isBlank()
                && !"NONE".equals(value.trim().toUpperCase(Locale.ROOT));
    }

    @Configuration(proxyBeanMethods = false)
    @Profile(STANDALONE_PROFILE)
    @EnableConfigurationProperties({
            DataSourceProperties.class,
            StageBRolloutBuildProperties.class,
            StageBRolloutCommandProperties.class,
            StageBMigrationProperties.class,
            ElasticsearchProperties.class
    })
    @Import({StageBRolloutBuildConfiguration.class, MigrationOperatorConfiguration.class})
    static class OperatorConfiguration {

        @Bean
        DataSource dataSource(DataSourceProperties properties) {
            return properties.initializeDataSourceBuilder().build();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        StageBRolloutCheckpointReader checkpointReader(JdbcTemplate jdbcTemplate) {
            return new JdbcStageBRolloutCheckpointReader(jdbcTemplate);
        }

        @Bean
        StageBArticleFingerprintService fingerprintService(
                JdbcTemplate jdbcTemplate, StageBMigrationProperties properties) {
            return new JdbcStageBArticleFingerprintService(jdbcTemplate, properties);
        }

        @Bean
        StageBRolloutOperator rolloutOperator(
                JdbcTemplate jdbcTemplate,
                StageBRolloutCheckpointReader checkpointReader,
                StageBArticleFingerprintService fingerprintService,
                PlatformTransactionManager transactionManager) {
            return new StageBRolloutOperator(jdbcTemplate, checkpointReader,
                    fingerprintService, transactionManager);
        }

        @Bean
        ApplicationRunner standaloneActionDispatcher(
                StageBRolloutCommandProperties commandProperties,
                StageBMigrationProperties migrationProperties,
                StageBRolloutCheckpointReader checkpointReader,
                Environment environment,
                ObjectProvider<StageBRolloutCommandRunner> commandRunner,
                ObjectProvider<StageBMigrationRunner> migrationRunner) {
            return arguments -> {
                boolean rollout = commandProperties.getAction()
                        != StageBRolloutCommandAction.NONE;
                boolean migration = migrationProperties.getAction()
                        == StageBMigrationAction.BACKFILL
                        || migrationProperties.getAction() == StageBMigrationAction.VERIFY;
                if (rollout == migration) {
                    throw new IllegalStateException(
                            "exactly one Stage B rollout or migration action is required");
                }
                if (rollout) {
                    commandRunner.getObject().run(arguments);
                }
                else {
                    ArticleRevisionMode declaredMode = requireExplicitDeclaredMode(
                            arguments, environment);
                    ArticleRevisionMode checkpointMode = checkpointReader.require().mode();
                    if (declaredMode != checkpointMode) {
                        throw new IllegalStateException(
                                "declared article revision mode does not match rollout checkpoint");
                    }
                    migrationRunner.getObject().run(arguments);
                }
            };
        }

        @Bean
        StageBRolloutCommandRunner rolloutCommandRunner(
                StageBRolloutCommandProperties commandProperties,
                StageBMigrationProperties migrationProperties,
                StageBRolloutOperator operator,
                ArticleRevisionBuildIdentity buildIdentity,
                ObjectMapper objectMapper) {
            return new StageBRolloutCommandRunner(commandProperties, migrationProperties,
                    operator, buildIdentity, objectMapper);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile(STANDALONE_PROFILE)
    @Lazy
    @ImportAutoConfiguration({
            ElasticsearchRestClientAutoConfiguration.class,
            ElasticsearchClientAutoConfiguration.class
    })
    static class MigrationOperatorConfiguration {

        @Bean
        ArticleRevisionModeResolver articleRevisionModeResolver(
                Environment environment) {
            return () -> parseMode(environment.getProperty("metro.article.revision-mode"));
        }

        @Bean
        ArticleContentCanonicalizer articleContentCanonicalizer(ObjectMapper objectMapper) {
            return new ArticleContentCanonicalizer(objectMapper);
        }

        @Bean
        StageBArticleMigrationService articleMigrationService(
                DataSource dataSource,
                ArticleContentCanonicalizer canonicalizer,
                ObjectMapper objectMapper,
                ArticleRevisionModeResolver modeResolver) {
            return new JdbcStageBArticleMigrationService(
                    dataSource, canonicalizer, objectMapper, modeResolver);
        }

        @Bean
        StageBArticleMigrationVerifier articleMigrationVerifier(
                JdbcTemplate jdbcTemplate,
                ElasticsearchClient elasticsearchClient,
                RestClient elasticsearchRestClient,
                ArticleContentCanonicalizer canonicalizer,
                ObjectMapper objectMapper,
                ArticleRevisionModeResolver modeResolver,
                StageBMigrationProperties properties,
                StageBArticleFingerprintService fingerprintService) {
            return new DefaultStageBArticleMigrationVerifier(
                    jdbcTemplate, elasticsearchClient, elasticsearchRestClient,
                    canonicalizer, objectMapper, modeResolver, properties, fingerprintService);
        }

        @Bean
        StageBVerificationArtifactWriter verificationArtifactWriter(
                StageBMigrationProperties properties, ObjectMapper objectMapper) {
            return new StageBVerificationArtifactWriter(properties, objectMapper);
        }

        @Bean
        StageBMigrationRunner migrationRunner(
                StageBMigrationProperties properties,
                ArticleRevisionModeResolver modeResolver,
                StageBArticleMigrationService migrationService,
                StageBArticleMigrationVerifier verifier,
                StageBRolloutOperator rolloutOperator,
                ArticleRevisionBuildIdentity buildIdentity,
                StageBVerificationArtifactWriter artifactWriter) {
            return new StageBMigrationRunner(properties, modeResolver, migrationService,
                    verifier, rolloutOperator, buildIdentity, artifactWriter);
        }
    }

    private static ArticleRevisionMode requireExplicitDeclaredMode(
            ApplicationArguments arguments, Environment environment) {
        boolean explicit = arguments.containsOption("metro.article.revision-mode")
                || isExplicitAction(System.getProperty("metro.article.revision-mode"))
                || isExplicitAction(System.getenv("METRO_ARTICLE_REVISION_MODE"));
        if (!explicit) {
            throw new IllegalStateException(
                    "standalone migration requires explicit metro.article.revision-mode");
        }
        return parseMode(environment.getProperty("metro.article.revision-mode"));
    }

    private static ArticleRevisionMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("article revision mode is required");
        }
        try {
            return ArticleRevisionMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("article revision mode is invalid", invalid);
        }
    }
}
