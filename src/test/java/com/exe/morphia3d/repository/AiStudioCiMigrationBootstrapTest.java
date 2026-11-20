package com.exe.morphia3d.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Applies V1–V19 only to the explicitly named local/disposable CI database. */
class AiStudioCiMigrationBootstrapTest {
    @Test
    void migratesTheDisposablePostgres16DatabaseAndValidatesAllVersions() {
        String url = required("AI_STUDIO_CI_DB_URL");
        String username = required("AI_STUDIO_CI_DB_USERNAME");
        String password = required("AI_STUDIO_CI_DB_PASSWORD");
        if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/ai_studio_ci(?:\\?.*)?")) {
            throw new IllegalStateException("AI_STUDIO_CI_DB_URL must name the local disposable ai_studio_ci database");
        }
        var source = new DriverManagerDataSource(url, username, password);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        String version = jdbc.queryForObject("SELECT version()", String.class);
        assertThat(version).contains("PostgreSQL 16");

        DesignPipelineTestDatabaseSetup.migratePostgres16(url, username, password);

        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", Integer.class))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for the isolated CI migration test");
        return value;
    }
}
