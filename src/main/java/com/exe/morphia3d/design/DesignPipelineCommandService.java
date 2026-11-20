package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class DesignPipelineCommandService {
    public record CommandAccepted(UUID jobId, UUID versionId, String projectStatus) { }
    public record ApprovalResult(UUID approvalId, String projectStatus, String versionStatus) { }

    private final DesignProjectRepository projects;
    private final DesignReferenceSetRepository referenceSets;
    private final DesignReferenceSetVersionRepository referenceVersions;
    private final DesignReferenceSetViewRepository referenceViews;
    private final DesignModelVersionRepository modelVersions;
    private final DesignReferenceApprovalRepository referenceApprovals;
    private final DesignModelApprovalRepository modelApprovals;
    private final DesignPipelineJobRepository jobs;
    private final DesignUsageReservationService usage;
    private final DesignUsageLimits usageLimits;
    private final DesignProjectStateMachine stateMachine = new DesignProjectStateMachine();

    public DesignPipelineCommandService(DesignProjectRepository projects,
            DesignReferenceSetRepository referenceSets, DesignReferenceSetVersionRepository referenceVersions,
            DesignReferenceSetViewRepository referenceViews, DesignModelVersionRepository modelVersions,
            DesignReferenceApprovalRepository referenceApprovals, DesignModelApprovalRepository modelApprovals,
            DesignPipelineJobRepository jobs, DesignUsageReservationService usage, DesignUsageLimits usageLimits) {
        this.projects = projects; this.referenceSets = referenceSets;
        this.referenceVersions = referenceVersions; this.referenceViews = referenceViews;
        this.modelVersions = modelVersions; this.referenceApprovals = referenceApprovals;
        this.modelApprovals = modelApprovals; this.jobs = jobs; this.usage = usage; this.usageLimits = usageLimits;
    }

    @Transactional
    public CommandAccepted enqueueReferences(Integer ownerId, UUID projectId, String idempotencyKey,
                                               String requestedSlot, String revisionFeedback) {
        requireKey(idempotencyKey);
        DesignProject project = ownedLocked(projectId, ownerId);
        ReferenceViewSlot slot = requestedSlot == null || requestedSlot.isBlank() ? null : ReferenceViewSlot.fromApiValue(requestedSlot);
        String fingerprint = fingerprint(projectId.toString(), project.getNormalizedPrompt(),
                slot == null ? "all" : slot.apiValue(), revisionFeedback);
        var priorJob = jobs.findByOwnerUserIdAndStageAndIdempotencyKey(ownerId, DesignStage.REFERENCE_IMAGE, idempotencyKey);
        if (priorJob.isPresent()) {
            if (!priorJob.get().getRequestFingerprint().equals(fingerprint)) throw new DesignProjectException("DESIGN_IDEMPOTENCY_CONFLICT");
            return new CommandAccepted(priorJob.get().getId(), priorJob.get().getReferenceVersionId(), project.getStatus().name());
        }

        DesignReferenceSet set = referenceSets.findByProjectIdAndOwnerUserId(projectId, ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_PROJECT_NOT_FOUND"));
        DesignReferenceSetVersion previous = referenceVersions.findTopByReferenceSetIdAndOwnerUserIdOrderByVersionNumberDesc(set.getId(), ownerId).orElse(null);
        if (project.getStatus() == DesignProjectStatus.REFERENCES_REVIEW
                && (previous == null || previous.getStatus() != DesignVersionStatus.CHANGES_REQUESTED || slot == null)) {
            throw new DesignProjectException("DESIGN_REFERENCE_REVISION_NOT_ALLOWED");
        }
        if (project.getStatus() == DesignProjectStatus.DRAFT && slot != null) {
            throw new DesignProjectException("DESIGN_REFERENCE_REVISION_NOT_ALLOWED");
        }
        if (project.getStatus() == DesignProjectStatus.COMPLETED && slot != null
                && (previous == null || previous.getStatus() != DesignVersionStatus.APPROVED)) {
            throw new DesignProjectException("DESIGN_REFERENCE_REVISION_NOT_ALLOWED");
        }

        DesignProjectStatus nextProjectStatus = stateMachine.enqueueReferences(project.getStatus());
        UUID versionId = UUID.randomUUID();
        DesignReferenceSetVersion version = new DesignReferenceSetVersion();
        version.setId(versionId); version.setReferenceSetId(set.getId()); version.setOwnerUserId(ownerId);
        version.setProjectId(projectId);
        version.setVersionNumber(previous == null ? 1 : previous.getVersionNumber() + 1);
        version.setParentVersionId(previous == null ? null : previous.getId());
        version.setStatus(DesignVersionStatus.GENERATING); version.setNormalizedPrompt(project.getNormalizedPrompt());
        version.setPromptProfileVersion(project.getPromptProfileVersion()); version.setRevisionFeedback(revisionFeedback);
        referenceVersions.save(version);
        if (slot != null && previous != null) {
            List<DesignReferenceSetView> priorViews = referenceViews.findByReferenceVersionIdAndOwnerUserId(previous.getId(), ownerId);
            if (priorViews.size() != 4) throw new DesignProjectException("DESIGN_REFERENCE_SNAPSHOT_INCOMPLETE");
            for (DesignReferenceSetView priorView : priorViews) {
                if (slot.apiValue().equals(priorView.getViewSlot())) continue;
                DesignReferenceSetView reused = new DesignReferenceSetView();
                reused.setReferenceVersionId(versionId); reused.setViewSlot(priorView.getViewSlot());
                reused.setReferenceSetId(set.getId()); reused.setProjectId(projectId);
                reused.setImageVersionId(priorView.getImageVersionId()); reused.setOwnerUserId(ownerId);
                referenceViews.save(reused);
            }
        }
        DesignPipelineJob job = newJob(project, ownerId, DesignStage.REFERENCE_IMAGE, versionId, null, slot, idempotencyKey, fingerprint);
        jobs.save(job);
        usage.reserve(job.getId(), ownerId, usageUnit(DesignStage.REFERENCE_IMAGE));
        project.setStatus(nextProjectStatus); projects.save(project);
        return new CommandAccepted(job.getId(), versionId, project.getStatus().name());
    }

    @Transactional
    public ApprovalResult approveReferences(Integer ownerId, UUID projectId, UUID versionId,
                                             ApprovalDecision decision, String feedback, String idempotencyKey) {
        requireKey(idempotencyKey);
        DesignProject project = ownedLocked(projectId, ownerId);
        DesignReferenceSetVersion version = referenceVersions.findByIdAndOwnerUserId(versionId, ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_REFERENCE_VERSION_NOT_FOUND"));
        if (!referenceSets.findByProjectIdAndOwnerUserId(projectId, ownerId).map(set -> set.getId().equals(version.getReferenceSetId())).orElse(false)) {
            throw new DesignProjectException("DESIGN_REFERENCE_VERSION_NOT_FOUND");
        }
        String fingerprint = fingerprint(versionId.toString(), decision.name(), feedback);
        var prior = referenceApprovals.findByOwnerUserIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (prior.isPresent()) {
            if (!prior.get().getRequestFingerprint().equals(fingerprint)) throw new DesignProjectException("DESIGN_IDEMPOTENCY_CONFLICT");
            return new ApprovalResult(prior.get().getId(), project.getStatus().name(), version.getStatus().name());
        }
        if (project.getStatus() != DesignProjectStatus.REFERENCES_REVIEW || version.getStatus() != DesignVersionStatus.READY_FOR_REVIEW
                || referenceViews.findByReferenceVersionIdAndOwnerUserId(versionId, ownerId).size() != 4) {
            throw new DesignProjectException("DESIGN_APPROVAL_NOT_ALLOWED");
        }
        DesignReferenceApproval approval = new DesignReferenceApproval();
        approval.setId(UUID.randomUUID()); approval.setReferenceVersionId(versionId); approval.setOwnerUserId(ownerId);
        approval.setDecision(decision); approval.setFeedback(feedback); approval.setIdempotencyKey(idempotencyKey);
        approval.setRequestFingerprint(fingerprint); referenceApprovals.save(approval);
        version.setStatus(decision == ApprovalDecision.APPROVED ? DesignVersionStatus.APPROVED : DesignVersionStatus.CHANGES_REQUESTED);
        referenceVersions.save(version);
        if (decision == ApprovalDecision.APPROVED) project.setStatus(stateMachine.approveReferences(project.getStatus()));
        markProjectActivity(project);
        projects.save(project);
        return new ApprovalResult(approval.getId(), project.getStatus().name(), version.getStatus().name());
    }

    @Transactional
    public CommandAccepted enqueueModel(Integer ownerId, UUID projectId, UUID approvedReferenceVersionId,
                                         String idempotencyKey, String revisionFeedback) {
        requireKey(idempotencyKey);
        DesignProject project = ownedLocked(projectId, ownerId);
        DesignReferenceSetVersion refs = referenceVersions.findByIdAndOwnerUserId(approvedReferenceVersionId, ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_REFERENCE_VERSION_NOT_FOUND"));
        if (!referenceSets.findByProjectIdAndOwnerUserId(projectId, ownerId).map(set -> set.getId().equals(refs.getReferenceSetId())).orElse(false)) {
            throw new DesignProjectException("DESIGN_REFERENCE_VERSION_NOT_FOUND");
        }
        String fingerprint = fingerprint(projectId.toString(), refs.getId().toString(), revisionFeedback);
        var prior = jobs.findByOwnerUserIdAndStageAndIdempotencyKey(ownerId, DesignStage.MODEL_MESH, idempotencyKey);
        if (prior.isPresent()) {
            if (!prior.get().getRequestFingerprint().equals(fingerprint)) throw new DesignProjectException("DESIGN_IDEMPOTENCY_CONFLICT");
            return new CommandAccepted(prior.get().getId(), prior.get().getModelVersionId(), project.getStatus().name());
        }

        DesignReferenceSetVersion latestReference = referenceVersions
                .findTopByReferenceSetIdAndOwnerUserIdOrderByVersionNumberDesc(refs.getReferenceSetId(), ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_REFERENCE_VERSION_NOT_FOUND"));
        if (!latestReference.getId().equals(refs.getId())) {
            throw new DesignProjectException("DESIGN_REFERENCE_VERSION_NOT_LATEST");
        }

        if (refs.getStatus() != DesignVersionStatus.APPROVED
                || referenceViews.findByReferenceVersionIdAndOwnerUserId(refs.getId(), ownerId).size() != 4
                || referenceApprovals.findTopByReferenceVersionIdAndOwnerUserIdOrderByCreatedAtDesc(refs.getId(), ownerId)
                    .map(approval -> approval.getDecision() != ApprovalDecision.APPROVED).orElse(true)) {
            throw new DesignProjectException("DESIGN_REFERENCES_NOT_APPROVED");
        }
        DesignModelVersion previous = modelVersions.findTopByProjectIdOrderByVersionNumberDesc(projectId).orElse(null);
        if ((project.getStatus() == DesignProjectStatus.MODEL_REVIEW || project.getStatus() == DesignProjectStatus.FAILED)
                && (previous == null || previous.getStatus() != DesignVersionStatus.CHANGES_REQUESTED)) {
            if (project.getStatus() != DesignProjectStatus.FAILED || previous == null
                    || previous.getStatus() != DesignVersionStatus.FAILED) {
                throw new DesignProjectException("DESIGN_MODEL_REVISION_NOT_ALLOWED");
            }
        }

        project.setStatus(stateMachine.enqueueModel(project.getStatus()));
        DesignModelVersion model = new DesignModelVersion();
        model.setId(UUID.randomUUID()); model.setProjectId(projectId); model.setOwnerUserId(ownerId);
        model.setReferenceVersionId(refs.getId()); model.setVersionNumber(previous == null ? 1 : previous.getVersionNumber() + 1);
        model.setSupersedesModelVersionId(previous == null ? null : previous.getId());
        model.setRevisionFeedback(revisionFeedback); model.setStatus(DesignVersionStatus.GENERATING);
        modelVersions.save(model);
        DesignPipelineJob job = newJob(project, ownerId, DesignStage.MODEL_MESH, null, model.getId(), null, idempotencyKey, fingerprint);
        jobs.save(job); usage.reserve(job.getId(), ownerId, usageUnit(DesignStage.MODEL_MESH)); projects.save(project);
        return new CommandAccepted(job.getId(), model.getId(), project.getStatus().name());
    }

    @Transactional
    public ApprovalResult approveModel(Integer ownerId, UUID projectId, UUID modelVersionId,
                                        ApprovalDecision decision, String feedback, String idempotencyKey) {
        requireKey(idempotencyKey);
        DesignProject project = ownedLocked(projectId, ownerId);
        DesignModelVersion model = modelVersions.findByIdAndOwnerUserId(modelVersionId, ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_MODEL_VERSION_NOT_FOUND"));
        if (!model.getProjectId().equals(projectId)) throw new DesignProjectException("DESIGN_MODEL_VERSION_NOT_FOUND");
        String fingerprint = fingerprint(modelVersionId.toString(), decision.name(), feedback);
        var prior = modelApprovals.findByOwnerUserIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (prior.isPresent()) {
            if (!prior.get().getRequestFingerprint().equals(fingerprint)) throw new DesignProjectException("DESIGN_IDEMPOTENCY_CONFLICT");
            return new ApprovalResult(prior.get().getId(), project.getStatus().name(), model.getStatus().name());
        }
        if (project.getStatus() != DesignProjectStatus.MODEL_REVIEW
                || model.getStatus() != DesignVersionStatus.READY_FOR_REVIEW || model.getArtifactKey() == null) {
            throw new DesignProjectException("DESIGN_APPROVAL_NOT_ALLOWED");
        }
        DesignModelApproval approval = new DesignModelApproval(); approval.setId(UUID.randomUUID());
        approval.setModelVersionId(modelVersionId); approval.setOwnerUserId(ownerId); approval.setDecision(decision);
        approval.setFeedback(feedback); approval.setIdempotencyKey(idempotencyKey); approval.setRequestFingerprint(fingerprint);
        modelApprovals.save(approval);
        model.setStatus(decision == ApprovalDecision.APPROVED ? DesignVersionStatus.APPROVED : DesignVersionStatus.CHANGES_REQUESTED);
        modelVersions.save(model);
        if (decision == ApprovalDecision.APPROVED) project.setStatus(stateMachine.approveModel(project.getStatus()));
        markProjectActivity(project);
        projects.save(project);
        return new ApprovalResult(approval.getId(), project.getStatus().name(), model.getStatus().name());
    }

    private DesignPipelineJob newJob(DesignProject project, Integer ownerId, DesignStage stage, UUID refVersion,
                                     UUID modelVersion, ReferenceViewSlot slot, String key, String fingerprint) {
        DesignPipelineJob job = new DesignPipelineJob(); job.setId(UUID.randomUUID()); job.setProjectId(project.getId());
        job.setOwnerUserId(ownerId); job.setStage(stage); job.setStatus(DesignPipelineJobStatus.PENDING);
        job.setReferenceVersionId(refVersion); job.setModelVersionId(modelVersion);
        job.setRequestedViewSlot(slot == null ? null : slot.apiValue()); job.setIdempotencyKey(key);
        job.setRequestFingerprint(fingerprint); job.setAttemptCount(0); job.setNextAttemptAt(Instant.now());
        return job;
    }

    private DesignProject ownedLocked(UUID projectId, Integer ownerId) {
        return projects.findLockedByIdAndOwner(projectId, ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_PROJECT_NOT_FOUND"));
    }

    private long usageUnit(DesignStage stage) {
        return stage == DesignStage.REFERENCE_IMAGE ? usageLimits.getReferenceJobUnits() : usageLimits.getModelJobUnits();
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) throw new DesignProjectException("DESIGN_INVALID_IDEMPOTENCY_KEY");
    }

    private void markProjectActivity(DesignProject project) {
        project.setUpdatedAt(Instant.now());
    }

    private String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                if (value == null) digest.update(new byte[]{-1,-1,-1,-1});
                else {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    digest.update(new byte[]{(byte)(bytes.length >>> 24), (byte)(bytes.length >>> 16), (byte)(bytes.length >>> 8), (byte)bytes.length});
                    digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
