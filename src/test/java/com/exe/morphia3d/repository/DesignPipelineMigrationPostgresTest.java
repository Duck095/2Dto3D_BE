package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.DesignModelApproval;
import com.exe.morphia3d.entity.DesignModelVersion;
import com.exe.morphia3d.entity.DesignPipelineJob;
import com.exe.morphia3d.entity.DesignProject;
import com.exe.morphia3d.entity.DesignProjectInput;
import com.exe.morphia3d.entity.DesignReferenceApproval;
import com.exe.morphia3d.entity.DesignReferenceImageVersion;
import com.exe.morphia3d.entity.DesignReferenceSet;
import com.exe.morphia3d.entity.DesignReferenceSetVersion;
import com.exe.morphia3d.entity.DesignReferenceSetView;
import com.exe.morphia3d.entity.DesignUsageBudgetWindow;
import com.exe.morphia3d.entity.DesignUsageReservation;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class DesignPipelineMigrationPostgresTest {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String SHA256 = "b".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("design_pipeline_isolated_test")
            .withUsername("design_pipeline_test")
            .withPassword("design_pipeline_test_only");

    @Test
    void v14MigratesValidatesAndEnforcesPrivateDesignPipelineWithoutChangingLegacyAiTables() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        provisionMigrationRoles(jdbc);
        Flyway throughV14 = flyway(dataSource, MigrationVersion.fromVersion("14"));
        assertThat(throughV14.migrate().migrationsExecuted).isEqualTo(14);
        throughV14.validate();
        jdbc.execute("GRANT REFERENCES ON TABLE public.model_3d, public.media_asset, public.materials, public.users TO admin3d_owner");

        Flyway flyway = flyway(dataSource, null);
        int remainingMigrations = flyway.migrate().migrationsExecuted;
        assertThat(14 + remainingMigrations).isEqualTo(19);
        assertThat(remainingMigrations).isEqualTo(5);
        flyway.validate();
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public'", String.class))
                .contains("design_projects", "design_project_inputs", "design_reference_sets", "design_reference_set_versions",
                        "design_reference_image_versions", "design_reference_set_views", "design_model_versions",
                        "design_reference_approvals", "design_model_approvals", "design_pipeline_jobs",
                        "design_usage_reservations", "design_usage_budget_windows", "ai_generation_jobs");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_generation_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '14' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' "
                + "AND tablename='design_projects' AND indexname='ix_design_projects_owner_updated_history'", Integer.class))
                .isEqualTo(1);

        validateDesignEntityMappings();
        assertLegacyAiSchemaUnchanged(jdbc);
        assertDesignConstraintsAndLineage(jdbc);

        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private static Flyway flyway(javax.sql.DataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public").defaultSchema("public").cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static void provisionMigrationRoles(JdbcTemplate jdbc) {
        jdbc.execute("CREATE ROLE admin3d_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        jdbc.execute("CREATE ROLE admin3d_lock_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        jdbc.execute("CREATE ROLE admin3d_command_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        jdbc.execute("CREATE ROLE payos_trust_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        jdbc.execute("CREATE ROLE app_runtime NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        jdbc.execute("GRANT admin3d_owner, admin3d_lock_owner, admin3d_command_owner, payos_trust_owner TO "
                + POSTGRES.getUsername() + " WITH INHERIT FALSE, SET TRUE");
        jdbc.execute("GRANT USAGE, CREATE ON SCHEMA public TO admin3d_owner, admin3d_lock_owner, admin3d_command_owner, payos_trust_owner");
    }

    private static void validateDesignEntityMappings() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", POSTGRES.getJdbcUrl())
                .applySetting("hibernate.connection.username", POSTGRES.getUsername())
                .applySetting("hibernate.connection.password", POSTGRES.getPassword())
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        try {
            new MetadataSources(registry)
                    .addAnnotatedClass(DesignProject.class)
                    .addAnnotatedClass(DesignProjectInput.class)
                    .addAnnotatedClass(DesignReferenceSet.class)
                    .addAnnotatedClass(DesignReferenceSetVersion.class)
                    .addAnnotatedClass(DesignReferenceImageVersion.class)
                    .addAnnotatedClass(DesignReferenceSetView.class)
                    .addAnnotatedClass(DesignModelVersion.class)
                    .addAnnotatedClass(DesignReferenceApproval.class)
                    .addAnnotatedClass(DesignModelApproval.class)
                    .addAnnotatedClass(DesignPipelineJob.class)
                    .addAnnotatedClass(DesignUsageReservation.class)
                    .addAnnotatedClass(DesignUsageBudgetWindow.class)
                    .buildMetadata().buildSessionFactory().close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static void assertLegacyAiSchemaUnchanged(JdbcTemplate jdbc) {
        assertColumns(jdbc, "ai_generation_sessions", List.of("session_id", "user_id", "original_image_path",
                "target_size_x_mm", "target_size_y_mm", "target_size_z_mm", "prompt", "created_at"));
        assertColumns(jdbc, "ai_generation_jobs", List.of("generation_job_id", "user_id", "retry_of", "result_model_id",
                "idempotency_key", "request_hash", "prompt", "style", "quality", "input_mode", "input_artifact_key",
                "status", "provider_reference", "attempt_count", "error_code", "error_message", "result_artifact_key",
                "processing_lease_owner", "processing_lease_until", "created_at", "updated_at", "finished_at", "next_attempt_at"));
    }

    private static void assertColumns(JdbcTemplate jdbc, String table, List<String> expected) {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position",
                String.class, table)).containsExactlyElementsOf(expected);
    }

    private static void assertDesignConstraintsAndLineage(JdbcTemplate jdbc) {
        int ownerId = jdbc.queryForObject("INSERT INTO users(name,email,password) VALUES ('Design Test','design-test@example.invalid','test-only') RETURNING user_id", Integer.class);
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();
        UUID refVersionId = UUID.randomUUID();
        UUID modelVersionId = UUID.randomUUID();
        jdbc.update("INSERT INTO design_projects(project_id,owner_user_id,product_type,style_id,input_mode,normalized_prompt,prompt_profile_version,status,create_idempotency_key,request_fingerprint) VALUES (?,?, 'figure','test','text','prompt','v1','DRAFT','project-key',?)",
                projectId, ownerId, FINGERPRINT);
        jdbc.update("INSERT INTO design_projects(project_id,owner_user_id,product_type,style_id,input_mode,normalized_prompt,prompt_profile_version,status,create_idempotency_key,request_fingerprint) VALUES (?,?, 'figure','test','text','prompt','v1','DRAFT','other-project-key',?)",
                otherProjectId, ownerId, FINGERPRINT);
        jdbc.update("INSERT INTO design_reference_sets(reference_set_id,project_id,owner_user_id) VALUES (?,?,?)", setId, projectId, ownerId);
        jdbc.update("INSERT INTO design_reference_set_versions(reference_version_id,reference_set_id,project_id,owner_user_id,version_number,status,normalized_prompt,prompt_profile_version) VALUES (?,?,?, ?,1,'READY_FOR_REVIEW','prompt','v1')",
                refVersionId, setId, projectId, ownerId);

        UUID wrongOwnerInputId = UUID.randomUUID();
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_project_inputs(input_id,project_id,owner_user_id,input_order,artifact_key,mime_type,byte_size,sha256) VALUES (?,?,?,0,'input.png','image/png',10,?)",
                wrongOwnerInputId, projectId, ownerId + 1, SHA256));
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_projects(project_id,owner_user_id,product_type,style_id,input_mode,normalized_prompt,prompt_profile_version,status,create_idempotency_key,request_fingerprint) VALUES (?,?, 'figure','test','text','prompt','v1','NOT_A_STATUS','invalid-status-key',?)",
                UUID.randomUUID(), ownerId, FINGERPRINT));
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_projects(project_id,owner_user_id,product_type,style_id,input_mode,normalized_prompt,prompt_profile_version,status,create_idempotency_key,request_fingerprint) VALUES (?,?, 'figure','test','text','prompt','v1','DRAFT','project-key',?)",
                UUID.randomUUID(), ownerId, FINGERPRINT));

        UUID[] imageIds = new UUID[4];
        String[] views = {"front", "left", "right", "back"};
        for (int i = 0; i < views.length; i++) {
            imageIds[i] = UUID.randomUUID();
            jdbc.update("INSERT INTO design_reference_image_versions(image_version_id,reference_set_id,project_id,owner_user_id,view_slot,version_number,artifact_key,mime_type,byte_size,sha256,normalized_prompt,prompt_profile_version) VALUES (?,?,?,?,?,1,?,'image/png',10,?,'prompt','v1')",
                    imageIds[i], setId, projectId, ownerId, views[i], views[i] + ".png", SHA256);
            jdbc.update("INSERT INTO design_reference_set_views(reference_version_id,reference_set_id,project_id,view_slot,image_version_id,owner_user_id) VALUES (?,?,?,?,?,?)",
                    refVersionId, setId, projectId, views[i], imageIds[i], ownerId);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM design_reference_set_views WHERE reference_version_id=?", Integer.class, refVersionId)).isEqualTo(4);
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_reference_set_views(reference_version_id,reference_set_id,project_id,view_slot,image_version_id,owner_user_id) VALUES (?,?,?,?,?,?)",
                refVersionId, setId, projectId, "front", imageIds[1], ownerId));
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_reference_set_versions(reference_version_id,reference_set_id,project_id,owner_user_id,version_number,status,normalized_prompt,prompt_profile_version) VALUES (?,?,?, ?,2,'INVALID','prompt','v1')",
                UUID.randomUUID(), setId, projectId, ownerId));

        jdbc.update("INSERT INTO design_model_versions(model_version_id,project_id,owner_user_id,reference_version_id,version_number,status) VALUES (?,?,?,?,1,'READY_FOR_REVIEW')",
                modelVersionId, projectId, ownerId, refVersionId);
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_model_versions(model_version_id,project_id,owner_user_id,reference_version_id,version_number,status) VALUES (?,?,?,?,2,'GENERATING')",
                UUID.randomUUID(), otherProjectId, ownerId, refVersionId));

        jdbc.update("INSERT INTO design_reference_approvals(approval_id,reference_version_id,owner_user_id,decision,idempotency_key,request_fingerprint) VALUES (?,?,?,'APPROVED','ref-approval-key',?)",
                UUID.randomUUID(), refVersionId, ownerId, FINGERPRINT);
        jdbc.update("INSERT INTO design_model_approvals(approval_id,model_version_id,owner_user_id,decision,idempotency_key,request_fingerprint) VALUES (?,?,?,'CHANGES_REQUESTED','model-approval-key',?)",
                UUID.randomUUID(), modelVersionId, ownerId, FINGERPRINT);
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_reference_approvals(approval_id,reference_version_id,owner_user_id,decision,idempotency_key,request_fingerprint) VALUES (?,?,?,'APPROVED','ref-approval-key',?)",
                UUID.randomUUID(), refVersionId, ownerId, FINGERPRINT));

        UUID referenceJobId = UUID.randomUUID();
        jdbc.update("INSERT INTO design_pipeline_jobs(job_id,project_id,owner_user_id,stage,status,reference_version_id,idempotency_key,request_fingerprint) VALUES (?,?,?,'REFERENCE_IMAGE','PENDING',?,'shared-job-key',?)",
                referenceJobId, projectId, ownerId, refVersionId, FINGERPRINT);
        jdbc.update("INSERT INTO design_pipeline_jobs(job_id,project_id,owner_user_id,stage,status,model_version_id,idempotency_key,request_fingerprint) VALUES (?,?,?,'MODEL_MESH','PROCESSING',?,'shared-job-key',?)",
                UUID.randomUUID(), projectId, ownerId, modelVersionId, FINGERPRINT);
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_pipeline_jobs(job_id,project_id,owner_user_id,stage,status,reference_version_id,idempotency_key,request_fingerprint) VALUES (?,?,?,'REFERENCE_IMAGE','INVALID',?,'invalid-job-status',?)",
                UUID.randomUUID(), projectId, ownerId, refVersionId, FINGERPRINT));
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_pipeline_jobs(job_id,project_id,owner_user_id,stage,status,reference_version_id,idempotency_key,request_fingerprint) VALUES (?,?,?,'REFERENCE_IMAGE','PENDING',?,'shared-job-key',?)",
                UUID.randomUUID(), projectId, ownerId, refVersionId, FINGERPRINT));
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_pipeline_jobs(job_id,project_id,owner_user_id,stage,status,reference_version_id,idempotency_key,request_fingerprint) VALUES (?,?,?,'REFERENCE_IMAGE','PENDING',?,'cross-project-job',?)",
                UUID.randomUUID(), otherProjectId, ownerId, refVersionId, FINGERPRINT));

        UUID globalWindowId = UUID.randomUUID();
        UUID ownerWindowId = UUID.randomUUID();
        jdbc.update("INSERT INTO design_usage_budget_windows(budget_window_id,scope_key,window_date,limit_units) VALUES (?,'GLOBAL',CURRENT_DATE,10)", globalWindowId);
        jdbc.update("INSERT INTO design_usage_budget_windows(budget_window_id,scope_key,window_date,limit_units) VALUES (?, ?,CURRENT_DATE,10)", ownerWindowId, "USER:" + ownerId);
        UUID reservationId = UUID.randomUUID();
        jdbc.update("INSERT INTO design_usage_reservations(reservation_id,job_id,owner_user_id,global_budget_window_id,owner_budget_window_id,reserved_units,status) VALUES (?,?,?,?,?,1,'RESERVED')",
                reservationId, referenceJobId, ownerId, globalWindowId, ownerWindowId);
        assertConstraintRejects(() -> jdbc.update("INSERT INTO design_usage_reservations(reservation_id,job_id,owner_user_id,global_budget_window_id,owner_budget_window_id,reserved_units,status) VALUES (?,?,?,?,?,1,'RESERVED')",
                UUID.randomUUID(), referenceJobId, ownerId + 1, globalWindowId, ownerWindowId));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM design_reference_approvals WHERE reference_version_id=?", Integer.class, refVersionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM design_model_approvals WHERE model_version_id=?", Integer.class, modelVersionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM design_pipeline_jobs WHERE owner_user_id=? AND idempotency_key='shared-job-key'", Integer.class, ownerId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE reservation_id=?", String.class, reservationId)).isEqualTo("RESERVED");
    }

    private static void assertConstraintRejects(ThrowableAssert.ThrowingCallable insert) {
        assertThatThrownBy(insert).isInstanceOf(DataIntegrityViolationException.class);
    }
}
