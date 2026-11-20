package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.repository.*;
import com.exe.morphia3d.service.MediaValidationService;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Durable worker. External provider calls execute outside database transactions. */
@Service
public class DesignPipelineWorker {
    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(2);
    private final DesignPipelineJobRepository jobs;
    private final DesignProjectRepository projects;
    private final DesignProjectInputRepository inputs;
    private final DesignReferenceSetVersionRepository referenceVersions;
    private final DesignReferenceImageVersionRepository imageVersions;
    private final DesignReferenceSetViewRepository views;
    private final DesignModelVersionRepository models;
    private final DesignReferenceApprovalRepository referenceApprovals;
    private final DesignArtifactStore artifacts;
    private final ReferenceImageProvider imageProvider;
    private final MeshyModelProvider modelProvider;
    private final DesignImageValidator imageValidator;
    private final MediaValidationService mediaValidation;
    private final DesignUsageReservationService usage;
    private final TransactionTemplate transactions;
    private final DesignProviderProperties.Meshy meshyProperties;

    @Autowired
    public DesignPipelineWorker(DesignPipelineJobRepository jobs, DesignProjectRepository projects,
            DesignProjectInputRepository inputs, DesignReferenceSetVersionRepository referenceVersions,
            DesignReferenceImageVersionRepository imageVersions, DesignReferenceSetViewRepository views,
            DesignModelVersionRepository models, DesignReferenceApprovalRepository referenceApprovals,
            DesignArtifactStore artifacts, ReferenceImageProvider imageProvider, MeshyModelProvider modelProvider,
            DesignImageValidator imageValidator, MediaValidationService mediaValidation,
            DesignUsageReservationService usage, PlatformTransactionManager manager, DesignProviderProperties properties) {
        this(jobs, projects, inputs, referenceVersions, imageVersions, views, models, referenceApprovals, artifacts,
                imageProvider, modelProvider, imageValidator, mediaValidation, usage, manager,
                properties == null ? new DesignProviderProperties().getMeshy() : properties.getMeshy());
    }

    public DesignPipelineWorker(DesignPipelineJobRepository jobs, DesignProjectRepository projects,
            DesignProjectInputRepository inputs, DesignReferenceSetVersionRepository referenceVersions,
            DesignReferenceImageVersionRepository imageVersions, DesignReferenceSetViewRepository views,
            DesignModelVersionRepository models, DesignReferenceApprovalRepository referenceApprovals,
            DesignArtifactStore artifacts, ReferenceImageProvider imageProvider, MeshyModelProvider modelProvider,
            DesignImageValidator imageValidator, MediaValidationService mediaValidation,
            DesignUsageReservationService usage, PlatformTransactionManager manager) {
        this(jobs, projects, inputs, referenceVersions, imageVersions, views, models, referenceApprovals, artifacts,
                imageProvider, modelProvider, imageValidator, mediaValidation, usage, manager,
                new DesignProviderProperties().getMeshy());
    }

    private DesignPipelineWorker(DesignPipelineJobRepository jobs, DesignProjectRepository projects,
            DesignProjectInputRepository inputs, DesignReferenceSetVersionRepository referenceVersions,
            DesignReferenceImageVersionRepository imageVersions, DesignReferenceSetViewRepository views,
            DesignModelVersionRepository models, DesignReferenceApprovalRepository referenceApprovals,
            DesignArtifactStore artifacts, ReferenceImageProvider imageProvider, MeshyModelProvider modelProvider,
            DesignImageValidator imageValidator, MediaValidationService mediaValidation,
            DesignUsageReservationService usage, PlatformTransactionManager manager, DesignProviderProperties.Meshy meshyProperties) {
        this.jobs=jobs; this.projects=projects; this.inputs=inputs; this.referenceVersions=referenceVersions;
        this.imageVersions=imageVersions; this.views=views; this.models=models; this.referenceApprovals=referenceApprovals;
        this.artifacts=artifacts; this.imageProvider=imageProvider; this.modelProvider=modelProvider;
        this.imageValidator=imageValidator; this.mediaValidation=mediaValidation; this.usage=usage;
        this.transactions=new TransactionTemplate(manager);
        this.meshyProperties = meshyProperties;
    }

    public boolean processOne() {
        UUID jobId = claim();
        if (jobId == null) return false;
        try {
            DesignPipelineJob job = jobs.findById(jobId).orElseThrow();
            if ("PROVIDER_SUBMISSION_STARTED".equals(job.getSafeErrorCode()) && job.getProviderTaskRef() == null) {
                holdUnknown(jobId, "PROVIDER_OUTCOME_UNKNOWN");
                return true;
            }
            if (job.getStage() == DesignStage.REFERENCE_IMAGE) processReferences(job);
            else processModel(job);
        } catch (ProviderUnavailableException failure) {
            if (failure.getMessage() != null && failure.getMessage().endsWith("_TRANSPORT_UNAVAILABLE")) {
                fail(jobId, "PROVIDER_UNAVAILABLE", true);
            } else {
                holdUnknown(jobId, "PROVIDER_OUTCOME_UNKNOWN");
            }
        } catch (DesignProviderReadRetryException retryableRead) {
            rescheduleReadRetry(jobId, retryableRead.getSafeCode());
        } catch (DesignProviderCallException providerCall) {
            if (providerCall.getDispatchState() == DesignProviderCallException.DispatchState.NOT_DISPATCHED) {
                fail(jobId, providerCall.getSafeCode(), true);
            } else {
                holdUnknown(jobId, providerCall.getSafeCode());
            }
        } catch (Exception failure) {
            DesignPipelineJob current = jobs.findById(jobId).orElse(null);
            boolean provenNotSubmitted = current != null && current.getProviderTaskRef() == null
                    && !"PROVIDER_SUBMISSION_STARTED".equals(current.getSafeErrorCode());
            fail(jobId, safeCode(failure), provenNotSubmitted);
        }
        return true;
    }

    private UUID claim() {
        return transactions.execute(status -> {
            Instant now = Instant.now();
            DesignPipelineJob job = jobs.lockClaimable(DesignPipelineJobStatus.PENDING, now, PageRequest.of(0, 1)).stream().findFirst()
                    .or(() -> jobs.lockClaimable(DesignPipelineJobStatus.PROCESSING, now, PageRequest.of(0, 1)).stream().findFirst())
                    .orElse(null);
            if (job == null) return null;
            boolean firstClaim = job.getStatus() == DesignPipelineJobStatus.PENDING;
            job.setStatus(DesignPipelineJobStatus.PROCESSING); job.setAttemptCount(job.getAttemptCount() + 1);
            job.setLeaseUntil(now.plus(lease())); jobs.save(job);
            if (firstClaim) {
                DesignProject project = projects.findLockedByIdAndOwner(job.getProjectId(), job.getOwnerUserId()).orElseThrow();
                DesignProjectStateMachine machine = new DesignProjectStateMachine();
                project.setStatus(job.getStage() == DesignStage.REFERENCE_IMAGE
                        ? machine.startReferences(project.getStatus()) : machine.startModel(project.getStatus()));
                projects.save(project);
            }
            return job.getId();
        });
    }

    private void processReferences(DesignPipelineJob job) throws IOException {
        DesignReferenceSetVersion version = referenceVersions.findByIdAndOwnerUserId(job.getReferenceVersionId(), job.getOwnerUserId()).orElseThrow();
        DesignProject project = projects.findByIdAndOwnerUserId(job.getProjectId(), job.getOwnerUserId()).orElseThrow();
        List<ReferenceImageProvider.InputArtifact> referenceInputs = new ArrayList<>();
        for (DesignProjectInput input : inputs.findByProjectIdAndOwnerUserIdOrderByInputOrder(job.getProjectId(), job.getOwnerUserId())) {
            byte[] content = artifacts.read(job.getOwnerUserId(), job.getProjectId(), input.getArtifactKey());
            verifyPersistedArtifact(input.getByteSize(), input.getSha256(), content);
            referenceInputs.add(new ReferenceImageProvider.InputArtifact(input.getArtifactKey(), input.getMimeType(),
                    input.getByteSize(), input.getSha256(), content));
        }
        List<ReferenceViewSlot> expected = job.getRequestedViewSlot() == null
                ? ReferenceViewSlot.canonicalOrder() : List.of(ReferenceViewSlot.fromApiValue(job.getRequestedViewSlot()));
        var request = new ReferenceImageProvider.ReferenceImageRequest(job.getId(), job.getIdempotencyKey(),
                version.getNormalizedPrompt(), version.getPromptProfileVersion(), List.copyOf(referenceInputs), expected);
        markSubmissionStarted(job.getId());
        List<ReferenceImageProvider.GeneratedReferenceImage> outputs = imageProvider.generate(request);
        if (outputs == null || outputs.size() != expected.size()
                || !outputs.stream().map(ReferenceImageProvider.GeneratedReferenceImage::slot).collect(Collectors.toSet()).equals(Set.copyOf(expected))) {
            throw new DesignProjectException("DESIGN_PROVIDER_OUTPUT_INVALID");
        }
        Map<ReferenceViewSlot, DesignArtifactDescriptor> stored = new EnumMap<>(ReferenceViewSlot.class);
        try {
            for (var output : outputs) {
                imageValidator.validate(output.content(), output.mimeType());
                stored.put(output.slot(), artifacts.put(job.getOwnerUserId(), job.getProjectId(), output.mimeType(), output.content()));
            }
            transactions.executeWithoutResult(status -> completeReferences(job.getId(), stored));
        } catch (IOException | RuntimeException failure) {
            for (DesignArtifactDescriptor artifact : stored.values()) try { artifacts.delete(job.getOwnerUserId(), job.getProjectId(), artifact.artifactKey()); } catch (IOException ignored) { }
            throw failure;
        }
    }

    private void completeReferences(UUID jobId, Map<ReferenceViewSlot, DesignArtifactDescriptor> stored) {
        DesignPipelineJob job = jobs.findById(jobId).orElseThrow();
        DesignReferenceSetVersion version = referenceVersions.findByIdAndOwnerUserId(job.getReferenceVersionId(), job.getOwnerUserId()).orElseThrow();
        for (var entry : stored.entrySet()) {
            ReferenceViewSlot slot = entry.getKey(); DesignArtifactDescriptor artifact = entry.getValue();
            DesignReferenceImageVersion previous = imageVersions.findTopByReferenceSetIdAndViewSlotOrderByVersionNumberDesc(
                    version.getReferenceSetId(), slot.apiValue()).orElse(null);
            DesignReferenceImageVersion image = new DesignReferenceImageVersion();
            image.setId(UUID.randomUUID()); image.setReferenceSetId(version.getReferenceSetId()); image.setOwnerUserId(job.getOwnerUserId());
            image.setProjectId(job.getProjectId());
            image.setViewSlot(slot.apiValue()); image.setVersionNumber(previous == null ? 1 : previous.getVersionNumber() + 1);
            image.setSupersedesImageVersionId(previous == null ? null : previous.getId()); image.setArtifactKey(artifact.artifactKey());
            image.setMimeType(artifact.mimeType()); image.setByteSize(artifact.byteSize()); image.setSha256(artifact.sha256());
            image.setNormalizedPrompt(version.getNormalizedPrompt()); image.setPromptProfileVersion(version.getPromptProfileVersion());
            imageVersions.save(image);
            DesignReferenceSetView view = new DesignReferenceSetView(); view.setReferenceVersionId(version.getId());
            view.setReferenceSetId(version.getReferenceSetId()); view.setProjectId(job.getProjectId());
            view.setViewSlot(slot.apiValue()); view.setImageVersionId(image.getId()); view.setOwnerUserId(job.getOwnerUserId()); views.save(view);
        }
        if (views.findByReferenceVersionIdAndOwnerUserId(version.getId(), job.getOwnerUserId()).size() != 4) {
            throw new DesignProjectException("DESIGN_REFERENCE_OUTPUT_INCOMPLETE");
        }
        version.setStatus(DesignVersionStatus.READY_FOR_REVIEW); referenceVersions.save(version);
        DesignProject project = projects.findLockedByIdAndOwner(job.getProjectId(), job.getOwnerUserId()).orElseThrow();
        project.setStatus(new DesignProjectStateMachine().finishReferences(project.getStatus())); projects.save(project);
        job.setStatus(DesignPipelineJobStatus.COMPLETED); job.setLeaseUntil(null); job.setSafeErrorCode(null); jobs.save(job);
        usage.settleReserved(jobId, null);
    }

    private void processModel(DesignPipelineJob job) throws IOException {
        DesignModelVersion model = models.findByIdAndOwnerUserId(job.getModelVersionId(), job.getOwnerUserId()).orElseThrow();
        if (job.getProviderTaskRef() == null) {
            DesignReferenceSetVersion referenceVersion = referenceVersions.findByIdAndOwnerUserId(model.getReferenceVersionId(), job.getOwnerUserId()).orElseThrow();
            if (referenceVersion.getStatus() != DesignVersionStatus.APPROVED
                    || referenceApprovals.findTopByReferenceVersionIdAndOwnerUserIdOrderByCreatedAtDesc(referenceVersion.getId(), job.getOwnerUserId())
                        .map(approval -> approval.getDecision() != ApprovalDecision.APPROVED).orElse(true)) {
                throw new DesignProjectException("DESIGN_REFERENCES_NOT_APPROVED");
            }
            List<DesignReferenceSetView> viewRows = views.findByReferenceVersionIdAndOwnerUserId(referenceVersion.getId(), job.getOwnerUserId());
            if (viewRows.size() != 4) throw new DesignProjectException("DESIGN_REFERENCE_SNAPSHOT_INCOMPLETE");
            List<MeshyModelProvider.ApprovedReference> approved = new ArrayList<>();
            for (ReferenceViewSlot orderedSlot : ReferenceViewSlot.canonicalOrder()) {
                DesignReferenceSetView view = viewRows.stream()
                        .filter(row -> orderedSlot.apiValue().equals(row.getViewSlot())).findFirst()
                        .orElseThrow(() -> new DesignProjectException("DESIGN_REFERENCE_SNAPSHOT_INCOMPLETE"));
                DesignReferenceImageVersion image = imageVersions.findByIdAndOwnerUserId(view.getImageVersionId(), job.getOwnerUserId()).orElseThrow();
                byte[] content = artifacts.read(job.getOwnerUserId(), job.getProjectId(), image.getArtifactKey());
                verifyPersistedArtifact(image.getByteSize(), image.getSha256(), content);
                approved.add(new MeshyModelProvider.ApprovedReference(view.getViewSlot(), image.getArtifactKey(), image.getSha256(),
                        image.getMimeType(), content));
            }
            DesignProject project = projects.findByIdAndOwnerUserId(job.getProjectId(), job.getOwnerUserId()).orElseThrow();
            markSubmissionStarted(job.getId());
            String taskRef = modelProvider.submit(new MeshyModelProvider.ModelRequest(job.getId(), job.getIdempotencyKey(),
                    project.getNormalizedPrompt(), List.copyOf(approved), model.getRevisionFeedback()));
            transactions.executeWithoutResult(status -> {
                DesignPipelineJob current = jobs.findById(job.getId()).orElseThrow();
                DesignModelVersion currentModel = models.findByIdAndOwnerUserId(model.getId(), job.getOwnerUserId()).orElseThrow();
                current.setProviderTaskRef(taskRef); current.setLeaseUntil(Instant.now().plus(lease())); jobs.save(current);
                currentModel.setProviderTaskRef(taskRef); models.save(currentModel);
            });
            return;
        }
        MeshyModelProvider.TaskStatus taskStatus = modelProvider.status(job.getProviderTaskRef());
        if (taskStatus == MeshyModelProvider.TaskStatus.PENDING || taskStatus == MeshyModelProvider.TaskStatus.PROCESSING) {
            transactions.executeWithoutResult(status -> jobs.findById(job.getId()).ifPresent(current -> {
                current.setLeaseUntil(Instant.now().plus(lease())); jobs.save(current);
            }));
            return;
        }
        if (taskStatus == MeshyModelProvider.TaskStatus.UNKNOWN) {
            holdUnknown(job.getId(), "MESHY_TASK_STATUS_UNKNOWN");
            return;
        }
        if (taskStatus == MeshyModelProvider.TaskStatus.FAILED) {
            failDefinitiveProvider(job.getId(), "DESIGN_MODEL_PROVIDER_FAILED");
            return;
        }
        MeshyModelProvider.GeneratedModel output = modelProvider.result(job.getProviderTaskRef());
        mediaValidation.validateGlb(output.content(), "design-model.glb", output.mimeType());
        DesignArtifactDescriptor stored = artifacts.put(job.getOwnerUserId(), job.getProjectId(), "model/gltf-binary", output.content());
        try {
            transactions.executeWithoutResult(status -> completeModel(job.getId(), stored));
        } catch (RuntimeException failure) {
            try { artifacts.delete(job.getOwnerUserId(), job.getProjectId(), stored.artifactKey()); } catch (IOException ignored) { }
            throw failure;
        }
    }

    private void completeModel(UUID jobId, DesignArtifactDescriptor artifact) {
        DesignPipelineJob job = jobs.findById(jobId).orElseThrow();
        DesignModelVersion model = models.findByIdAndOwnerUserId(job.getModelVersionId(), job.getOwnerUserId()).orElseThrow();
        model.setArtifactKey(artifact.artifactKey()); model.setMimeType("model/gltf-binary");
        model.setByteSize(artifact.byteSize()); model.setSha256(artifact.sha256()); model.setStatus(DesignVersionStatus.READY_FOR_REVIEW); models.save(model);
        DesignProject project = projects.findLockedByIdAndOwner(job.getProjectId(), job.getOwnerUserId()).orElseThrow();
        project.setStatus(new DesignProjectStateMachine().finishModel(project.getStatus())); projects.save(project);
        job.setStatus(DesignPipelineJobStatus.COMPLETED); job.setLeaseUntil(null); job.setSafeErrorCode(null); jobs.save(job);
        usage.settleReserved(jobId, job.getProviderTaskRef());
    }

    private void fail(UUID jobId, String code, boolean knownZeroCost) {
        transactions.executeWithoutResult(status -> {
            DesignPipelineJob job = jobs.findById(jobId).orElseThrow();
            job.setStatus(DesignPipelineJobStatus.FAILED); job.setLeaseUntil(null); job.setSafeErrorCode(code); jobs.save(job);
            projects.findLockedByIdAndOwner(job.getProjectId(), job.getOwnerUserId()).ifPresent(project -> {
                project.setStatus(DesignProjectStatus.FAILED); projects.save(project);
            });
            if (job.getReferenceVersionId() != null) referenceVersions.findByIdAndOwnerUserId(job.getReferenceVersionId(), job.getOwnerUserId())
                    .ifPresent(version -> { version.setStatus(DesignVersionStatus.FAILED); referenceVersions.save(version); });
            if (job.getModelVersionId() != null) models.findByIdAndOwnerUserId(job.getModelVersionId(), job.getOwnerUserId())
                    .ifPresent(model -> { model.setStatus(DesignVersionStatus.FAILED); models.save(model); });
            if (knownZeroCost) usage.releaseZeroCost(jobId);
            else usage.settle(jobId, -1, job.getProviderTaskRef());
        });
    }

    private void holdUnknown(UUID jobId, String code) {
        transactions.executeWithoutResult(status -> {
            DesignPipelineJob job = jobs.findById(jobId).orElseThrow();
            job.setSafeErrorCode(code); job.setLeaseUntil(Instant.now().plus(Duration.ofDays(3650))); jobs.save(job);
            usage.settle(jobId, -1, job.getProviderTaskRef());
        });
    }

    private void failDefinitiveProvider(UUID jobId, String code) {
        transactions.executeWithoutResult(status -> {
            DesignPipelineJob job = jobs.findById(jobId).orElseThrow();
            job.setStatus(DesignPipelineJobStatus.FAILED); job.setLeaseUntil(null); job.setSafeErrorCode(code); jobs.save(job);
            projects.findLockedByIdAndOwner(job.getProjectId(), job.getOwnerUserId()).ifPresent(project -> {
                project.setStatus(DesignProjectStatus.FAILED); projects.save(project);
            });
            models.findByIdAndOwnerUserId(job.getModelVersionId(), job.getOwnerUserId())
                    .ifPresent(model -> { model.setStatus(DesignVersionStatus.FAILED); models.save(model); });
            usage.settleReserved(jobId, job.getProviderTaskRef());
        });
    }

    private void rescheduleReadRetry(UUID jobId, String code) {
        transactions.executeWithoutResult(status -> jobs.findById(jobId).ifPresent(job -> {
            long exponent = Math.min(30, Math.max(0, job.getAttemptCount() - 2));
            long base = Math.max(1L, meshyProperties.getReadRetryBaseDelay().toSeconds());
            long max = Math.max(base, meshyProperties.getReadRetryMaxDelay().toSeconds());
            long delaySeconds = base > (max >> Math.min(30, exponent)) ? max : Math.min(max, base << exponent);
            job.setStatus(DesignPipelineJobStatus.PROCESSING);
            job.setSafeErrorCode(code);
            job.setLeaseUntil(Instant.now().plusSeconds(delaySeconds));
            jobs.save(job);
        }));
    }

    private Duration lease() { return meshyProperties.getPollInterval() == null ? DEFAULT_LEASE : meshyProperties.getPollInterval(); }

    private static void verifyPersistedArtifact(Long expectedSize, String expectedSha, byte[] content) {
        if (content == null || expectedSize == null || expectedSize != content.length || expectedSha == null
                || !expectedSha.equalsIgnoreCase(sha256(content))) {
            throw new DesignProjectException("DESIGN_ARTIFACT_INTEGRITY_FAILED");
        }
    }

    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void markSubmissionStarted(UUID jobId) {
        transactions.executeWithoutResult(status -> jobs.findById(jobId).ifPresent(job -> {
            job.setSafeErrorCode("PROVIDER_SUBMISSION_STARTED");
            jobs.save(job);
        }));
    }

    private String safeCode(Exception failure) {
        if (failure instanceof DesignProjectException project) return project.getCode();
        if (failure instanceof IOException) return "ARTIFACT_STORE_ERROR";
        return "PIPELINE_JOB_FAILED";
    }
}
