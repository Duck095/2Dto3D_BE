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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AiGenerationIdempotencyPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_generation_idempotency_test")
            .withUsername("ai_generation_idempotency_test")
            .withPassword("ai_generation_idempotency_test_only");

    @Test
    void migrationsAndDatabaseConstraintEnforceOwnerScopedIdempotency() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PostgresTestRoleProvisioning.provisionMigrationRoles(jdbc, POSTGRES.getUsername());
        Flyway throughV14 = flyway(dataSource, MigrationVersion.fromVersion("14"));
        assertThat(throughV14.migrate().migrationsExecuted).isEqualTo(14);
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(jdbc);
        Flyway flyway = flyway(dataSource, null);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        flyway.validate();

        Integer firstOwner = insertUser(jdbc, "idem-first");
        Integer secondOwner = insertUser(jdbc, "idem-second");
        UUID firstJob = UUID.randomUUID();
        insertJob(jdbc, firstJob, firstOwner, "same-key");
        insertJob(jdbc, UUID.randomUUID(), secondOwner, "same-key");

        assertThatThrownBy(() -> insertJob(jdbc, UUID.randomUUID(), firstOwner, "same-key"))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_generation_jobs", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'ai_generation_jobs'
                   AND column_name = 'next_attempt_at'
                """, Integer.class)).isEqualTo(1);
    }

    private Flyway flyway(DataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public").defaultSchema("public").cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private Integer insertUser(JdbcTemplate jdbc, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO users(name, email, password, role, status)
                VALUES (?, ?, 'TEST_ONLY', 'USER', 'ACTIVE') RETURNING user_id
                """, Integer.class, suffix, suffix + "@example.invalid");
    }

    private void insertJob(JdbcTemplate jdbc, UUID id, Integer ownerId, String key) {
        jdbc.update("""
                INSERT INTO ai_generation_jobs(generation_job_id, user_id, idempotency_key, request_hash,
                    prompt, style, quality, input_mode, status, attempt_count)
                VALUES (?, ?, ?, ?, 'TEST_ONLY prompt', 'TEST_ONLY style', 'TEST_ONLY quality', 'text', 'PENDING', 0)
                """, id, ownerId, key, "a".repeat(64));
    }
}
