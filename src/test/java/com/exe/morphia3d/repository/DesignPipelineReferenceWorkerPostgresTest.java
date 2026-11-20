package com.exe.morphia3d.repository;

import com.exe.morphia3d.design.*;
import com.exe.morphia3d.entity.DesignPipelineJob;
import com.exe.morphia3d.entity.DesignReferenceSetView;
import com.exe.morphia3d.service.MediaUploadProperties;
import com.exe.morphia3d.service.MediaValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({DesignPipelineCommandService.class, DesignUsageReservationService.class, DesignUsageLimits.class,
        DesignImageValidator.class, MediaUploadProperties.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DesignPipelineReferenceWorkerPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("design_reference_worker_test")
            .withUsername("design_reference_worker_test")
            .withPassword("design_reference_worker_test_only");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        POSTGRES.start();
        DesignPipelineTestDatabaseSetup.migratePostgres16(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private DesignPipelineCommandService commands;
    @Autowired private DesignUsageLimits limits;
    @Autowired private DesignUsageReservationService usage;
    @Autowired private DesignImageValidator imageValidator;
    @Autowired private DesignPipelineJobRepository jobs;
    @Autowired private DesignProjectRepository projects;
    @Autowired private DesignReferenceSetVersionRepository referenceVersions;
    @Autowired private DesignProjectInputRepository inputs;
    @Autowired private DesignReferenceImageVersionRepository imageVersions;
    @Autowired private DesignReferenceSetViewRepository views;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void configureSmallTestQuota() {
        jdbc.update("DELETE FROM design_usage_reservations");
        jdbc.update("DELETE FROM design_pipeline_jobs");
        jdbc.update("DELETE FROM design_reference_approvals");
        jdbc.update("DELETE FROM design_model_approvals");
        jdbc.update("DELETE FROM design_reference_set_views");
        jdbc.update("DELETE FROM design_reference_image_versions");
        jdbc.update("DELETE FROM design_model_versions");
        jdbc.update("DELETE FROM design_reference_set_versions");
        jdbc.update("DELETE FROM design_reference_sets");
        jdbc.update("DELETE FROM design_projects");
        jdbc.update("DELETE FROM design_usage_budget_windows");
        jdbc.update("DELETE FROM users WHERE email LIKE 'reference-worker-%@example.invalid'");
        limits.setPerJobUnits(20);
        limits.setPerOwnerDailyUnits(100);
        limits.setGlobalDailyUnits(1_000);
        limits.setReferenceJobUnits(20);
        limits.setModelJobUnits(20);
    }

    @Test
    void pendingJobCreatesFourValidatedArtifactsAndCompletesExactlyOnce(@TempDir Path tempDir) throws Exception {
        Seed seed = enqueueReferenceJob("worker-success");
        TEST_ONLY_FakeReferenceImageProvider provider = new TEST_ONLY_FakeReferenceImageProvider();
        TEST_ONLY_FilesystemDesignArtifactStore artifacts = new TEST_ONLY_FilesystemDesignArtifactStore(tempDir);
        DesignPipelineWorker worker = worker(provider, artifacts);

        assertThat(worker.processOne()).isTrue();

        DesignPipelineJob completed = jobs.findById(seed.jobId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(DesignPipelineJobStatus.COMPLETED);
        assertThat(completed.getAttemptCount()).isEqualTo(1);
        assertThat(projects.findById(seed.projectId()).orElseThrow().getStatus()).isEqualTo(DesignProjectStatus.REFERENCES_REVIEW);
        assertThat(referenceVersions.findByIdAndOwnerUserId(seed.versionId(), seed.ownerId()).orElseThrow().getStatus())
                .isEqualTo(DesignVersionStatus.READY_FOR_REVIEW);
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(provider.lastRequestedSlots()).containsExactly(ReferenceViewSlot.FRONT, ReferenceViewSlot.LEFT,
                ReferenceViewSlot.RIGHT, ReferenceViewSlot.BACK);

        List<DesignReferenceSetView> savedViews = views.findByReferenceVersionIdAndOwnerUserId(seed.versionId(), seed.ownerId());
        assertThat(savedViews).hasSize(4);
        assertThat(savedViews).extracting(DesignReferenceSetView::getViewSlot)
                .containsExactlyInAnyOrder("front", "left", "right", "back");
        assertThat(imageVersions.count()).isEqualTo(4);
        for (DesignReferenceSetView view : savedViews) {
            var image = imageVersions.findByIdAndOwnerUserId(view.getImageVersionId(), seed.ownerId()).orElseThrow();
            byte[] content = artifacts.read(seed.ownerId(), seed.projectId(), image.getArtifactKey());
            imageValidator.validate(content, image.getMimeType());
            assertThat(Files.exists(tempDir.resolve("owners").resolve(Integer.toString(seed.ownerId())).resolve("projects")
                    .resolve(seed.projectId().toString()).resolve(image.getArtifactKey().substring(image.getArtifactKey().lastIndexOf(':') + 1) + ".artifact"))).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE job_id=?", String.class, seed.jobId()))
                .isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT settled_units FROM design_usage_reservations WHERE job_id=?", Long.class, seed.jobId()))
                .isEqualTo(20L);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM design_usage_budget_windows WHERE scope_key='GLOBAL'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT settled_units FROM design_usage_budget_windows WHERE scope_key='GLOBAL'", Long.class))
                .isEqualTo(20L);

        assertThat(worker.processOne()).isFalse();
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(DesignPipelineJobStatus.COMPLETED);
        assertThat(imageVersions.count()).isEqualTo(4);
        assertThat(views.findByReferenceVersionIdAndOwnerUserId(seed.versionId(), seed.ownerId())).hasSize(4);
        assertThat(regularFileCount(tempDir)).isEqualTo(4);
    }

    @Test
    void invalidProviderImageFailsJobWithoutPersistingSuccessfulReferenceSet(@TempDir Path tempDir) throws Exception {
        Seed seed = enqueueReferenceJob("worker-invalid");
        TEST_ONLY_FakeReferenceImageProvider provider = new TEST_ONLY_FakeReferenceImageProvider();
        provider.setInvalidOutput(true);
        TEST_ONLY_FilesystemDesignArtifactStore artifacts = new TEST_ONLY_FilesystemDesignArtifactStore(tempDir);
        DesignPipelineWorker worker = worker(provider, artifacts);

        assertThat(worker.processOne()).isTrue();

        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(DesignPipelineJobStatus.FAILED);
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getSafeErrorCode()).isEqualTo("DESIGN_PROVIDER_OUTPUT_INVALID");
        assertThat(projects.findById(seed.projectId()).orElseThrow().getStatus()).isEqualTo(DesignProjectStatus.FAILED);
        assertThat(referenceVersions.findByIdAndOwnerUserId(seed.versionId(), seed.ownerId()).orElseThrow().getStatus())
                .isEqualTo(DesignVersionStatus.FAILED);
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(imageVersions.count()).isZero();
        assertThat(views.findByReferenceVersionIdAndOwnerUserId(seed.versionId(), seed.ownerId())).isEmpty();
        assertThat(regularFileCount(tempDir)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE job_id=?", String.class, seed.jobId()))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM design_usage_budget_windows WHERE scope_key='GLOBAL'", Long.class))
                .isEqualTo(20L);
        assertThat(worker.processOne()).isFalse();
        assertThat(provider.invocationCount()).isEqualTo(1);
    }

    @Test
    void persistedChangesRequestedApprovalAdvancesHistoryOrderButReplayDoesNot(@TempDir Path tempDir) throws Exception {
        Seed seed = enqueueReferenceJob("history-feedback");
        TEST_ONLY_FilesystemDesignArtifactStore artifacts = new TEST_ONLY_FilesystemDesignArtifactStore(tempDir);
        DesignPipelineWorker worker = worker(new TEST_ONLY_FakeReferenceImageProvider(), artifacts);
        assertThat(worker.processOne()).isTrue();

        UUID olderProjectId = UUID.randomUUID();
        jdbc.update("INSERT INTO design_projects(project_id,owner_user_id,product_type,style_id,input_mode,normalized_prompt,"
                        + "prompt_profile_version,status,create_idempotency_key,request_fingerprint) "
                        + "VALUES (?,?,'figure','test','text','older project','v1','DRAFT',?,?)",
                olderProjectId, seed.ownerId(), "older-project-" + olderProjectId, "c".repeat(64));
        Instant baseline = Instant.parse("2020-01-01T00:00:00Z");
        jdbc.update("UPDATE design_projects SET updated_at=? WHERE project_id=?", Timestamp.from(baseline), seed.projectId());
        jdbc.update("UPDATE design_projects SET updated_at=? WHERE project_id=?",
                Timestamp.from(baseline.minusSeconds(1)), olderProjectId);

        commands.approveReferences(seed.ownerId(), seed.projectId(), seed.versionId(),
                ApprovalDecision.CHANGES_REQUESTED, "adjust the front view", "history-feedback-key");

        Instant afterApproval = jdbc.queryForObject("SELECT updated_at FROM design_projects WHERE project_id=?",
                Timestamp.class, seed.projectId()).toInstant();
        assertThat(afterApproval).isAfter(baseline);
        assertThat(projects.findById(seed.projectId()).orElseThrow().getStatus()).isEqualTo(DesignProjectStatus.REFERENCES_REVIEW);
        assertThat(projects.findByOwnerUserId(seed.ownerId(), PageRequest.of(0, 10,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")))).getContent())
                .extracting(project -> project.getId()).startsWith(seed.projectId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM design_reference_approvals WHERE reference_version_id=?",
                Integer.class, seed.versionId())).isEqualTo(1);

        commands.approveReferences(seed.ownerId(), seed.projectId(), seed.versionId(),
                ApprovalDecision.CHANGES_REQUESTED, "adjust the front view", "history-feedback-key");

        Instant afterReplay = jdbc.queryForObject("SELECT updated_at FROM design_projects WHERE project_id=?",
                Timestamp.class, seed.projectId()).toInstant();
        assertThat(afterReplay).isEqualTo(afterApproval);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM design_reference_approvals WHERE reference_version_id=?",
                Integer.class, seed.versionId())).isEqualTo(1);
    }

    private Seed enqueueReferenceJob(String suffix) {
        int ownerId = jdbc.queryForObject("INSERT INTO users(name,email,password) VALUES (?,?,?) RETURNING user_id",
                Integer.class, "Reference worker test", "reference-worker-" + suffix + "@example.invalid", "test-only");
        UUID projectId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();
        jdbc.update("INSERT INTO design_projects(project_id,owner_user_id,product_type,style_id,input_mode,normalized_prompt,prompt_profile_version,status,create_idempotency_key,request_fingerprint) VALUES (?,?, 'figure','test','text','small test design','v1','DRAFT',?,?)",
                projectId, ownerId, "project-" + suffix, "a".repeat(64));
        jdbc.update("INSERT INTO design_reference_sets(reference_set_id,project_id,owner_user_id) VALUES (?,?,?)", setId, projectId, ownerId);
        DesignPipelineCommandService.CommandAccepted accepted = commands.enqueueReferences(ownerId, projectId,
                "reference-" + suffix, null, null);
        return new Seed(ownerId, projectId, accepted.jobId(), accepted.versionId());
    }

    private DesignPipelineWorker worker(TEST_ONLY_FakeReferenceImageProvider provider,
                                        TEST_ONLY_FilesystemDesignArtifactStore artifacts) {
        MeshyModelProvider unusedModelProvider = new MeshyModelProvider() {
            @Override public String submit(ModelRequest request) { throw new AssertionError("model provider must not be called"); }
            @Override public TaskStatus status(String reference) { throw new AssertionError("model provider must not be called"); }
            @Override public GeneratedModel result(String reference) { throw new AssertionError("model provider must not be called"); }
        };
        return new DesignPipelineWorker(jobs, projects, inputs, referenceVersions, imageVersions, views, null,
                null, artifacts, provider, unusedModelProvider, imageValidator, (MediaValidationService) null,
                usage, transactionManager);
    }

    private long regularFileCount(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private record Seed(int ownerId, UUID projectId, UUID jobId, UUID versionId) { }
}
