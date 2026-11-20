package com.exe.morphia3d.repository;

import com.exe.morphia3d.design.*;
import com.exe.morphia3d.entity.DesignModelVersion;
import com.exe.morphia3d.entity.DesignPipelineJob;
import com.exe.morphia3d.entity.DesignProject;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({DesignPipelineCommandService.class, DesignUsageReservationService.class, DesignUsageLimits.class,
        MediaValidationService.class, MediaUploadProperties.class, DesignImageValidator.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DesignPipelineModelWorkerPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("design_model_worker_test")
            .withUsername("design_model_worker_test")
            .withPassword("design_model_worker_test_only");

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
    @Autowired private DesignPipelineJobRepository jobs;
    @Autowired private DesignProjectRepository projects;
    @Autowired private DesignProjectInputRepository inputs;
    @Autowired private DesignReferenceSetVersionRepository referenceVersions;
    @Autowired private DesignReferenceImageVersionRepository imageVersions;
    @Autowired private DesignReferenceSetViewRepository views;
    @Autowired private DesignReferenceApprovalRepository approvals;
    @Autowired private DesignModelVersionRepository modelVersions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MediaValidationService mediaValidation;
    @Autowired private DesignImageValidator imageValidator;

    @BeforeEach
    void clearDisposableDesignRowsAndConfigureQuota() {
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
        jdbc.update("DELETE FROM users WHERE email LIKE 'model-worker-%@example.invalid'");
        limits.setPerJobUnits(20);
        limits.setPerOwnerDailyUnits(100);
        limits.setGlobalDailyUnits(1_000);
        limits.setReferenceJobUnits(20);
        limits.setModelJobUnits(20);
    }

    @Test
    void approvedReferencesSubmitPollAndPersistCompletedModel(@TempDir Path tempDir) throws Exception {
        TEST_ONLY_FilesystemDesignArtifactStore artifacts = new TEST_ONLY_FilesystemDesignArtifactStore(tempDir);
        Seed seed = seedApprovedReferencesAndEnqueueModel("success", artifacts);
        TEST_ONLY_FakeMeshyModelProvider provider = new TEST_ONLY_FakeMeshyModelProvider();
        DesignPipelineWorker worker = worker(provider, artifacts);

        provider.setTaskStatuses(MeshyModelProvider.TaskStatus.PROCESSING, MeshyModelProvider.TaskStatus.SUCCEEDED);
        assertThat(worker.processOne()).isTrue();
        DesignPipelineJob submitted = jobs.findById(seed.jobId()).orElseThrow();
        assertThat(submitted.getStatus()).isEqualTo(DesignPipelineJobStatus.PROCESSING);
        assertThat(submitted.getProviderTaskRef()).isEqualTo("test-task-" + seed.jobId());
        assertThat(modelVersions.findByIdAndOwnerUserId(seed.modelVersionId(), seed.ownerId()).orElseThrow().getProviderTaskRef())
                .isEqualTo(submitted.getProviderTaskRef());
        assertThat(provider.submitCalls()).isEqualTo(1);
        assertThat(provider.statusCalls()).isZero();
        assertThat(provider.resultCalls()).isZero();
        MeshyModelProvider.ModelRequest submittedRequest = provider.submittedRequest();
        assertThat(submittedRequest.normalizedPrompt()).isEqualTo("normalized model prompt");
        assertThat(submittedRequest.revisionFeedback()).isEqualTo("increase base thickness");
        assertThat(submittedRequest.approvedReferences()).hasSize(4)
                .extracting(MeshyModelProvider.ApprovedReference::slot)
                .containsExactly("front", "left", "right", "back");
        List<DesignReferenceSetView> approvedViews = views.findByReferenceVersionIdAndOwnerUserId(
                seed.referenceVersionId(), seed.ownerId());
        for (MeshyModelProvider.ApprovedReference approved : submittedRequest.approvedReferences()) {
            imageValidator.validate(approved.content(), "image/png");
            DesignReferenceSetView savedView = approvedViews.stream()
                    .filter(view -> view.getViewSlot().equals(approved.slot())).findFirst().orElseThrow();
            var imageVersion = imageVersions.findByIdAndOwnerUserId(savedView.getImageVersionId(), seed.ownerId()).orElseThrow();
            assertThat(approved.sha256()).isEqualTo(imageVersion.getSha256());
            assertThat(approved.artifactKey()).isEqualTo(imageVersion.getArtifactKey());
            assertThat(approved.content()).isEqualTo(artifacts.read(seed.ownerId(), seed.projectId(), imageVersion.getArtifactKey()));
        }
        assertThat(worker.processOne()).isFalse();

        expireLease(seed.jobId());
        assertThat(worker.processOne()).isTrue();
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(DesignPipelineJobStatus.PROCESSING);
        assertThat(provider.statusCalls()).isEqualTo(1);
        assertThat(provider.resultCalls()).isZero();
        assertThat(worker.processOne()).isFalse();

        expireLease(seed.jobId());
        assertThat(worker.processOne()).isTrue();

        DesignPipelineJob completed = jobs.findById(seed.jobId()).orElseThrow();
        DesignModelVersion model = modelVersions.findByIdAndOwnerUserId(seed.modelVersionId(), seed.ownerId()).orElseThrow();
        DesignProject project = projects.findByIdAndOwnerUserId(seed.projectId(), seed.ownerId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(DesignPipelineJobStatus.COMPLETED);
        assertThat(completed.getAttemptCount()).isEqualTo(3);
        assertThat(model.getStatus()).isEqualTo(DesignVersionStatus.READY_FOR_REVIEW);
        assertThat(model.getProviderTaskRef()).isEqualTo(submitted.getProviderTaskRef());
        assertThat(project.getStatus()).isEqualTo(DesignProjectStatus.MODEL_REVIEW);
        assertThat(model.getArtifactKey()).isNotBlank();

        byte[] storedGlb = artifacts.read(seed.ownerId(), seed.projectId(), model.getArtifactKey());
        mediaValidation.validateGlb(storedGlb, "stored-model.glb", "model/gltf-binary");
        assertValidGlbJsonChunk(storedGlb);
        assertThat(model.getByteSize()).isEqualTo((long) storedGlb.length);
        assertThat(Files.exists(tempDir.resolve("owners").resolve(Integer.toString(seed.ownerId())).resolve("projects")
                .resolve(seed.projectId().toString()).resolve(model.getArtifactKey().substring(model.getArtifactKey().lastIndexOf(':') + 1) + ".artifact"))).isTrue();
        assertThat(provider.submitCalls()).isEqualTo(1);
        assertThat(provider.statusCalls()).isEqualTo(2);
        assertThat(provider.resultCalls()).isEqualTo(1);
        assertThat(views.findByReferenceVersionIdAndOwnerUserId(seed.referenceVersionId(), seed.ownerId()))
                .hasSize(4).extracting(DesignReferenceSetView::getViewSlot)
                .containsExactlyInAnyOrder("front", "left", "right", "back");

        assertThat(jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE job_id=?", String.class, seed.jobId()))
                .isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT settled_units FROM design_usage_reservations WHERE job_id=?", Long.class, seed.jobId()))
                .isEqualTo(20L);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM design_usage_budget_windows WHERE scope_key='GLOBAL'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT settled_units FROM design_usage_budget_windows WHERE scope_key='GLOBAL'", Long.class))
                .isEqualTo(20L);

        assertThat(worker.processOne()).isFalse();
        assertThat(provider.submitCalls()).isEqualTo(1);
        assertThat(regularFileCount(tempDir)).isEqualTo(5);
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(DesignPipelineJobStatus.COMPLETED);

        Instant approvalBaseline = Instant.parse("2020-01-01T00:00:00Z");
        jdbc.update("UPDATE design_projects SET updated_at=? WHERE project_id=?",
                Timestamp.from(approvalBaseline), seed.projectId());
        commands.approveModel(seed.ownerId(), seed.projectId(), seed.modelVersionId(),
                ApprovalDecision.CHANGES_REQUESTED, "increase the base", "model-history-feedback-key");
        Instant afterFeedback = jdbc.queryForObject("SELECT updated_at FROM design_projects WHERE project_id=?",
                Timestamp.class, seed.projectId()).toInstant();
        assertThat(afterFeedback).isAfter(approvalBaseline);
        assertThat(projects.findById(seed.projectId()).orElseThrow().getStatus()).isEqualTo(DesignProjectStatus.MODEL_REVIEW);

        commands.approveModel(seed.ownerId(), seed.projectId(), seed.modelVersionId(),
                ApprovalDecision.CHANGES_REQUESTED, "increase the base", "model-history-feedback-key");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM design_projects WHERE project_id=?",
                Timestamp.class, seed.projectId()).toInstant()).isEqualTo(afterFeedback);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM design_model_approvals WHERE model_version_id=?",
                Integer.class, seed.modelVersionId())).isEqualTo(1);
    }

    @Test
    void invalidGlbFailsModelWithoutSavingArtifactAndKeepsUsageUnknown(@TempDir Path tempDir) throws Exception {
        TEST_ONLY_FilesystemDesignArtifactStore artifacts = new TEST_ONLY_FilesystemDesignArtifactStore(tempDir);
        Seed seed = seedApprovedReferencesAndEnqueueModel("invalid", artifacts);
        TEST_ONLY_FakeMeshyModelProvider provider = new TEST_ONLY_FakeMeshyModelProvider();
        provider.setInvalidResult(true);
        DesignPipelineWorker worker = worker(provider, artifacts);

        assertThat(worker.processOne()).isTrue();
        assertThat(provider.submitCalls()).isEqualTo(1);
        expireLease(seed.jobId());
        assertThat(worker.processOne()).isTrue();

        DesignPipelineJob failed = jobs.findById(seed.jobId()).orElseThrow();
        DesignModelVersion model = modelVersions.findByIdAndOwnerUserId(seed.modelVersionId(), seed.ownerId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DesignPipelineJobStatus.FAILED);
        assertThat(model.getStatus()).isEqualTo(DesignVersionStatus.FAILED);
        assertThat(model.getArtifactKey()).isNull();
        assertThat(model.getByteSize()).isNull();
        assertThat(projects.findByIdAndOwnerUserId(seed.projectId(), seed.ownerId()).orElseThrow().getStatus())
                .isEqualTo(DesignProjectStatus.FAILED);
        assertThat(provider.submitCalls()).isEqualTo(1);
        assertThat(provider.statusCalls()).isEqualTo(1);
        assertThat(provider.resultCalls()).isEqualTo(1);
        assertThat(regularFileCount(tempDir)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE job_id=?", String.class, seed.jobId()))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM design_usage_budget_windows WHERE scope_key='GLOBAL'", Long.class))
                .isEqualTo(20L);
        assertThat(worker.processOne()).isFalse();
        assertThat(provider.submitCalls()).isEqualTo(1);
        assertThat(regularFileCount(tempDir)).isEqualTo(4);
    }

    @Test
    void meshStatusReadRetryKeepsReservationAndResumesSameTask(@TempDir Path tempDir) throws Exception {
        TEST_ONLY_FilesystemDesignArtifactStore artifacts = new TEST_ONLY_FilesystemDesignArtifactStore(tempDir);
        Seed seed = seedApprovedReferencesAndEnqueueModel("status-read-retry", artifacts);
        TEST_ONLY_FakeMeshyModelProvider provider = new TEST_ONLY_FakeMeshyModelProvider();
        DesignPipelineWorker worker = worker(provider, artifacts);
        assertThat(worker.processOne()).isTrue();
        String taskRef = jobs.findById(seed.jobId()).orElseThrow().getProviderTaskRef();
        provider.failStatusReadOnce(); expireLease(seed.jobId());
        assertThat(worker.processOne()).isTrue();
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getProviderTaskRef()).isEqualTo(taskRef);
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(DesignPipelineJobStatus.PROCESSING);
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getLeaseUntil()).isAfter(Instant.now());
        assertThat(jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE job_id=?", String.class, seed.jobId()))
                .isEqualTo("RESERVED");
        assertThat(provider.submitCalls()).isEqualTo(1);
        assertThat(worker.processOne()).isFalse();
        expireLease(seed.jobId()); assertThat(worker.processOne()).isTrue();
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(DesignPipelineJobStatus.COMPLETED);
        assertThat(provider.submitCalls()).isEqualTo(1);
        assertThat(provider.statusCalls()).isEqualTo(2);
    }

    @Test
    void meshResultReadRetryKeepsReservationAndRefreshesResultWithoutResubmit(@TempDir Path tempDir) throws Exception {
        TEST_ONLY_FilesystemDesignArtifactStore artifacts = new TEST_ONLY_FilesystemDesignArtifactStore(tempDir);
        Seed seed = seedApprovedReferencesAndEnqueueModel("result-read-retry", artifacts);
        TEST_ONLY_FakeMeshyModelProvider provider = new TEST_ONLY_FakeMeshyModelProvider();
        DesignPipelineWorker worker = worker(provider, artifacts);
        assertThat(worker.processOne()).isTrue();
        provider.failResultReadOnce(); expireLease(seed.jobId());
        assertThat(worker.processOne()).isTrue();
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(DesignPipelineJobStatus.PROCESSING);
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getLeaseUntil()).isAfter(Instant.now());
        assertThat(jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE job_id=?", String.class, seed.jobId()))
                .isEqualTo("RESERVED");
        assertThat(provider.submitCalls()).isEqualTo(1);
        expireLease(seed.jobId()); assertThat(worker.processOne()).isTrue();
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(DesignPipelineJobStatus.COMPLETED);
        assertThat(provider.submitCalls()).isEqualTo(1);
        assertThat(provider.resultCalls()).isEqualTo(2);
    }

    @Test
    void unknownMeshyStatusHoldsReservationAndDefinitiveFailureSettlesFullReservation(@TempDir Path tempDir) throws Exception {
        TEST_ONLY_FilesystemDesignArtifactStore artifacts = new TEST_ONLY_FilesystemDesignArtifactStore(tempDir);
        Seed unknown = seedApprovedReferencesAndEnqueueModel("unknown-status", artifacts);
        TEST_ONLY_FakeMeshyModelProvider unknownProvider = new TEST_ONLY_FakeMeshyModelProvider();
        unknownProvider.setTaskStatuses(MeshyModelProvider.TaskStatus.UNKNOWN);
        DesignPipelineWorker unknownWorker = worker(unknownProvider, artifacts);
        assertThat(unknownWorker.processOne()).isTrue(); expireLease(unknown.jobId());
        assertThat(unknownWorker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE job_id=?", String.class, unknown.jobId()))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM design_usage_budget_windows WHERE scope_key='GLOBAL'", Long.class))
                .isGreaterThan(0);
        assertThat(unknownProvider.submitCalls()).isEqualTo(1);

        Seed failed = seedApprovedReferencesAndEnqueueModel("definitive-failed", artifacts);
        TEST_ONLY_FakeMeshyModelProvider failedProvider = new TEST_ONLY_FakeMeshyModelProvider();
        failedProvider.setTaskStatuses(MeshyModelProvider.TaskStatus.FAILED);
        DesignPipelineWorker failedWorker = worker(failedProvider, artifacts);
        assertThat(failedWorker.processOne()).isTrue(); expireLease(failed.jobId());
        assertThat(failedWorker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE job_id=?", String.class, failed.jobId()))
                .isEqualTo("SETTLED");
        Long reserved = jdbc.queryForObject("SELECT reserved_units FROM design_usage_reservations WHERE job_id=?", Long.class, failed.jobId());
        assertThat(jdbc.queryForObject("SELECT settled_units FROM design_usage_reservations WHERE job_id=?", Long.class, failed.jobId()))
                .isEqualTo(reserved);
        assertThat(failedProvider.submitCalls()).isEqualTo(1);
        assertThat(failedProvider.resultCalls()).isZero();
    }

    @Test
    void requiresLatestApprovedReferenceForNewModelButPreservesHistoricalModelReplay(@TempDir Path tempDir) throws Exception {
        TEST_ONLY_FilesystemDesignArtifactStore artifacts = new TEST_ONLY_FilesystemDesignArtifactStore(tempDir);
        Seed seed = seedApprovedReferencesAndEnqueueModel("latest-reference-guard", artifacts);

        TEST_ONLY_FakeMeshyModelProvider modelProvider = new TEST_ONLY_FakeMeshyModelProvider();
        modelProvider.setTaskStatuses(MeshyModelProvider.TaskStatus.PROCESSING, MeshyModelProvider.TaskStatus.SUCCEEDED);
        DesignPipelineWorker modelWorker = worker(modelProvider, artifacts);
        assertThat(modelWorker.processOne()).isTrue();
        expireLease(seed.jobId());
        assertThat(modelWorker.processOne()).isTrue();
        expireLease(seed.jobId());
        assertThat(modelWorker.processOne()).isTrue();
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(DesignPipelineJobStatus.COMPLETED);
        commands.approveModel(seed.ownerId(), seed.projectId(), seed.modelVersionId(),
                ApprovalDecision.APPROVED, null, "approve-model-v1");
        assertThat(projects.findById(seed.projectId()).orElseThrow().getStatus()).isEqualTo(DesignProjectStatus.COMPLETED);

        var revision = commands.enqueueReferences(seed.ownerId(), seed.projectId(), "reference-v2-key",
                "front", "revise the front view");
        TEST_ONLY_FakeReferenceImageProvider referenceProvider = new TEST_ONLY_FakeReferenceImageProvider();
        DesignPipelineWorker referenceWorker = new DesignPipelineWorker(jobs, projects, inputs, referenceVersions,
                imageVersions, views, modelVersions, approvals, artifacts, referenceProvider, null,
                imageValidator, null, usage, transactionManager);
        assertThat(referenceWorker.processOne()).isTrue();
        assertThat(referenceVersions.findByIdAndOwnerUserId(revision.versionId(), seed.ownerId()).orElseThrow().getStatus())
                .isEqualTo(DesignVersionStatus.READY_FOR_REVIEW);
        assertThat(views.findByReferenceVersionIdAndOwnerUserId(revision.versionId(), seed.ownerId())).hasSize(4);
        commands.approveReferences(seed.ownerId(), seed.projectId(), revision.versionId(),
                ApprovalDecision.APPROVED, null, "approve-reference-v2");

        assertThatThrownBy(() -> commands.enqueueModel(seed.ownerId(), seed.projectId(), seed.referenceVersionId(),
                "new-model-from-v1", null)).isInstanceOfSatisfying(DesignProjectException.class,
                failure -> assertThat(failure.getCode()).isEqualTo("DESIGN_REFERENCE_VERSION_NOT_LATEST"));
        var modelV2 = commands.enqueueModel(seed.ownerId(), seed.projectId(), revision.versionId(), "model-v2-key", null);
        assertThat(modelV2.versionId()).isNotEqualTo(seed.modelVersionId());

        var historicalReplay = commands.enqueueModel(seed.ownerId(), seed.projectId(), seed.referenceVersionId(),
                "model-latest-reference-guard", "increase base thickness");
        assertThat(historicalReplay.jobId()).isEqualTo(seed.jobId());
        assertThat(historicalReplay.versionId()).isEqualTo(seed.modelVersionId());
        assertThat(jobs.count()).isEqualTo(3);
        assertThat(modelVersions.count()).isEqualTo(2);
    }

    @Test
    void tamperedPersistedReferenceFailsBeforeProviderSubmissionAndReleasesReservation(@TempDir Path tempDir) throws Exception {
        TEST_ONLY_FilesystemDesignArtifactStore artifacts = new TEST_ONLY_FilesystemDesignArtifactStore(tempDir);
        Seed seed = seedApprovedReferencesAndEnqueueModel("tampered-artifact", artifacts);
        DesignReferenceSetView view = views.findByReferenceVersionIdAndOwnerUserId(seed.referenceVersionId(), seed.ownerId()).get(0);
        var image = imageVersions.findByIdAndOwnerUserId(view.getImageVersionId(), seed.ownerId()).orElseThrow();
        String artifactUuid = image.getArtifactKey().substring(image.getArtifactKey().lastIndexOf(':') + 1);
        Path stored = tempDir.resolve("owners").resolve(Integer.toString(seed.ownerId())).resolve("projects")
                .resolve(seed.projectId().toString()).resolve(artifactUuid + ".artifact");
        Files.write(stored, new byte[]{1, 2, 3});

        TEST_ONLY_FakeMeshyModelProvider provider = new TEST_ONLY_FakeMeshyModelProvider();
        assertThat(worker(provider, artifacts).processOne()).isTrue();
        assertThat(provider.submitCalls()).isZero();
        assertThat(jobs.findById(seed.jobId()).orElseThrow().getStatus()).isEqualTo(DesignPipelineJobStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE job_id=?", String.class, seed.jobId()))
                .isEqualTo("RELEASED");
    }

    private Seed seedApprovedReferencesAndEnqueueModel(String suffix,
            TEST_ONLY_FilesystemDesignArtifactStore artifacts) throws Exception {
        int ownerId = jdbc.queryForObject("INSERT INTO users(name,email,password) VALUES (?,?,?) RETURNING user_id",
                Integer.class, "Model worker test", "model-worker-" + suffix + "@example.invalid", "test-only");
        UUID projectId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();
        UUID referenceVersionId = UUID.randomUUID();
        jdbc.update("INSERT INTO design_projects(project_id,owner_user_id,product_type,style_id,input_mode,normalized_prompt,prompt_profile_version,status,create_idempotency_key,request_fingerprint) VALUES (?,?, 'figure','test','text','normalized model prompt','v1','REFERENCES_REVIEW',?,?)",
                projectId, ownerId, "model-project-" + suffix, "a".repeat(64));
        jdbc.update("INSERT INTO design_reference_sets(reference_set_id,project_id,owner_user_id) VALUES (?,?,?)", setId, projectId, ownerId);
        jdbc.update("INSERT INTO design_reference_set_versions(reference_version_id,reference_set_id,project_id,owner_user_id,version_number,status,normalized_prompt,prompt_profile_version) VALUES (?,?,?, ?,1,'READY_FOR_REVIEW','normalized model prompt','v1')",
                referenceVersionId, setId, projectId, ownerId);

        byte[] png = validPng();
        List<ReferenceViewSlot> slots = ReferenceViewSlot.canonicalOrder();
        for (ReferenceViewSlot slot : slots) {
            DesignArtifactDescriptor artifact = artifacts.put(ownerId, projectId, "image/png", png);
            UUID imageVersionId = UUID.randomUUID();
            jdbc.update("INSERT INTO design_reference_image_versions(image_version_id,reference_set_id,project_id,owner_user_id,view_slot,version_number,artifact_key,mime_type,byte_size,sha256,normalized_prompt,prompt_profile_version) VALUES (?,?,?,?,?,1,?,'image/png',?,?,?,'v1')",
                    imageVersionId, setId, projectId, ownerId, slot.apiValue(), artifact.artifactKey(), artifact.byteSize(), artifact.sha256(), "normalized model prompt");
            jdbc.update("INSERT INTO design_reference_set_views(reference_version_id,reference_set_id,project_id,view_slot,image_version_id,owner_user_id) VALUES (?,?,?,?,?,?)",
                    referenceVersionId, setId, projectId, slot.apiValue(), imageVersionId, ownerId);
        }

        commands.approveReferences(ownerId, projectId, referenceVersionId, ApprovalDecision.APPROVED,
                null, "approve-" + suffix);
        assertThat(referenceVersions.findByIdAndOwnerUserId(referenceVersionId, ownerId).orElseThrow().getStatus())
                .isEqualTo(DesignVersionStatus.APPROVED);
        DesignPipelineCommandService.CommandAccepted accepted = commands.enqueueModel(ownerId, projectId,
                referenceVersionId, "model-" + suffix, "increase base thickness");
        assertThat(projects.findByIdAndOwnerUserId(projectId, ownerId).orElseThrow().getStatus())
                .isEqualTo(DesignProjectStatus.MODEL_QUEUED);
        assertThat(approvals.findTopByReferenceVersionIdAndOwnerUserIdOrderByCreatedAtDesc(referenceVersionId, ownerId))
                .get().extracting(approval -> approval.getDecision()).isEqualTo(ApprovalDecision.APPROVED);
        return new Seed(ownerId, projectId, referenceVersionId, accepted.jobId(), accepted.versionId());
    }

    private DesignPipelineWorker worker(TEST_ONLY_FakeMeshyModelProvider provider,
                                        TEST_ONLY_FilesystemDesignArtifactStore artifacts) {
        ReferenceImageProvider unusedImageProvider = request -> { throw new AssertionError("reference provider must not be called"); };
        return new DesignPipelineWorker(jobs, projects, inputs, referenceVersions, imageVersions, views, modelVersions,
                approvals, artifacts, unusedImageProvider, provider, imageValidator, mediaValidation, usage, transactionManager);
    }

    private void expireLease(UUID jobId) {
        assertThat(jdbc.update("UPDATE design_pipeline_jobs SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE job_id=?",
                jobId)).isEqualTo(1);
    }

    private byte[] validPng() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff336699);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, "png", output)).isTrue();
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    private void assertValidGlbJsonChunk(byte[] glb) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(buffer.getInt()).isEqualTo(0x46546c67);
        assertThat(buffer.getInt()).isEqualTo(2);
        assertThat(buffer.getInt()).isEqualTo(glb.length);
        int jsonChunkLength = buffer.getInt();
        assertThat(buffer.getInt()).isEqualTo(0x4e4f534a);
        assertThat(jsonChunkLength % 4).isZero();
        assertThat(buffer.remaining()).isEqualTo(jsonChunkLength);
        byte[] jsonBytes = new byte[jsonChunkLength];
        buffer.get(jsonBytes);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonBytes).path("asset").path("version").asText())
                .isEqualTo("2.0");
    }

    private long regularFileCount(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private record Seed(int ownerId, UUID projectId, UUID referenceVersionId, UUID jobId, UUID modelVersionId) { }
}
