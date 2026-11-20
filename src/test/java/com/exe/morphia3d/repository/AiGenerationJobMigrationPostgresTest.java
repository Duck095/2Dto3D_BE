package com.exe.morphia3d.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AiGenerationJobMigrationPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_generation_isolated_test")
            .withUsername("ai_generation_test")
            .withPassword("ai_generation_test_only");

    @Test
    void aiGenerationMigrationsPreserveLegacyRowsAndMakeQualityOptional() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway throughV8 = flyway(dataSource, MigrationVersion.fromVersion("8"));
        assertThat(throughV8.migrate().migrationsExecuted).isEqualTo(8);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer userId = jdbc.queryForObject("""
                INSERT INTO users(name, email, password, role, status)
                VALUES ('AI generation migration fixture', 'ai-generation-migration@example.invalid', 'test-only', 'USER', 'ACTIVE')
                RETURNING user_id
                """, Integer.class);
        Integer sessionId = jdbc.queryForObject("""
                INSERT INTO ai_generation_sessions(user_id, prompt, created_at)
                VALUES (?, 'legacy session must remain unchanged', TIMESTAMP '2026-01-01 00:00:00')
                RETURNING session_id
                """, Integer.class, userId);
        Integer modelId = jdbc.queryForObject("""
                INSERT INTO model_3d(source_type, user_id, name, model_status, session_id)
                VALUES ('AI_MODEL', ?, 'legacy AI model', 'PENDING', ?)
                RETURNING model_id
                """, Integer.class, userId, sessionId);

        Flyway throughV10 = flyway(dataSource, MigrationVersion.fromVersion("10"));
        assertThat(throughV10.migrate().migrationsExecuted).isEqualTo(2);
        throughV10.validate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_generation_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT prompt, created_at FROM ai_generation_sessions WHERE session_id = ? AND user_id = ?
                """, sessionId, userId)).containsEntry("prompt", "legacy session must remain unchanged")
                .containsEntry("created_at", java.sql.Timestamp.valueOf("2026-01-01 00:00:00"));
        assertThat(jdbc.queryForMap("""
                SELECT source_type, user_id, name, model_status, session_id
                FROM model_3d WHERE model_id = ? AND session_id = ? AND user_id = ?
                """, modelId, sessionId, userId))
                .containsEntry("source_type", "AI_MODEL")
                .containsEntry("user_id", userId)
                .containsEntry("name", "legacy AI model")
                .containsEntry("model_status", "PENDING")
                .containsEntry("session_id", sessionId);

        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'ai_generation_jobs'
                """, String.class)).containsAll(List.of(
                "generation_job_id", "user_id", "status", "prompt", "style", "quality", "input_mode",
                "input_artifact_key", "request_hash", "idempotency_key", "retry_of",
                "provider_reference", "attempt_count", "error_code", "error_message", "result_model_id",
                "result_artifact_key", "processing_lease_owner", "processing_lease_until", "created_at",
                "updated_at", "finished_at", "next_attempt_at"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace
                  AND conname IN ('ck_ai_generation_jobs_status', 'ck_ai_generation_jobs_attempt_count',
                                  'uq_ai_generation_jobs_owner_idempotency')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN ('ix_ai_generation_jobs_owner_history', 'ix_ai_generation_jobs_pending_claim',
                                    'ix_ai_generation_jobs_retry_schedule')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'ai_generation_jobs'
                  AND column_name = 'next_attempt_at'
                """, String.class)).isEqualTo("timestamp with time zone");
        String retryIndex = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'ix_ai_generation_jobs_retry_schedule'
                """, String.class);
        assertThat(retryIndex).contains("status", "next_attempt_at", "created_at", "PENDING");

        UUID legacyJobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_generation_jobs(generation_job_id, user_id, idempotency_key, request_hash,
                    prompt, style, quality, input_mode, status, attempt_count)
                VALUES (?, ?, 'legacy-quality-row', ?, 'legacy prompt', 'legacy style', 'legacy quality', 'text', 'FAILED', 1)
                """, legacyJobId, userId, "a".repeat(64));

        PostgresTestRoleProvisioning.provisionMigrationRoles(jdbc, POSTGRES.getUsername());
        Flyway throughV14 = flyway(dataSource, MigrationVersion.fromVersion("14"));
        assertThat(throughV14.migrate().migrationsExecuted).isEqualTo(4);
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(jdbc);

        Flyway current = flyway(dataSource, null);
        assertThat(current.migrate().migrationsExecuted).isEqualTo(5);
        current.validate();
        assertThat(jdbc.queryForObject("""
                SELECT quality FROM ai_generation_jobs WHERE generation_job_id = ?
                """, String.class, legacyJobId)).isEqualTo("legacy quality");
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'ai_generation_jobs' AND column_name = 'quality'
                """, String.class)).isEqualTo("YES");
        assertThat(current.migrate().migrationsExecuted).isZero();
    }

    private Flyway flyway(DataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

}
