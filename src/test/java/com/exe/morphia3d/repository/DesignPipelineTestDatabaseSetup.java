package com.exe.morphia3d.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;

final class DesignPipelineTestDatabaseSetup {
    private DesignPipelineTestDatabaseSetup() { }

    static void migratePostgres16(String url, String username, String password) {
        var dataSource = new DriverManagerDataSource(url, username, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PostgresTestRoleProvisioning.provisionMigrationRoles(jdbc, username);

        flyway(dataSource, MigrationVersion.fromVersion("14")).migrate();
        PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(jdbc);
        Flyway allVersions = flyway(dataSource, null);
        allVersions.migrate();
        allVersions.validate();
    }

    private static Flyway flyway(javax.sql.DataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public").defaultSchema("public").cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
