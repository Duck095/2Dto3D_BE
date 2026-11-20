package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.DesignModelApproval;
import com.exe.morphia3d.entity.DesignModelVersion;
import com.exe.morphia3d.entity.DesignProject;
import com.exe.morphia3d.repository.DesignModelApprovalRepository;
import com.exe.morphia3d.repository.DesignModelVersionRepository;
import com.exe.morphia3d.repository.DesignProjectRepository;
import com.exe.morphia3d.service.MediaValidationService;
import com.exe.morphia3d.service.glb.GlbStructure;
import com.exe.morphia3d.service.glb.GlbStructureParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class DesignPrintReadinessService {
    private final DesignProjectRepository projects;
    private final DesignModelVersionRepository models;
    private final DesignModelApprovalRepository approvals;
    private final DesignArtifactStore artifacts;
    private final MediaValidationService mediaValidation;
    private final GlbStructureParser glbStructureParser;
    private final VerifiedModelDimensionEvidenceSource evidenceSource;
    private final Double minDimensionMm;
    private final Double maxDimensionMm;

    public DesignPrintReadinessService(DesignProjectRepository projects, DesignModelVersionRepository models,
            DesignModelApprovalRepository approvals, DesignArtifactStore artifacts, MediaValidationService mediaValidation,
            GlbStructureParser glbStructureParser, VerifiedModelDimensionEvidenceSource evidenceSource,
            @Value("${ai-studio.print-readiness.min-dimension-mm:}") Double minDimensionMm,
            @Value("${ai-studio.print-readiness.max-dimension-mm:}") Double maxDimensionMm) {
        this.projects=projects; this.models=models; this.approvals=approvals; this.artifacts=artifacts;
        this.mediaValidation=mediaValidation; this.glbStructureParser=glbStructureParser; this.evidenceSource=evidenceSource;
        this.minDimensionMm=minDimensionMm; this.maxDimensionMm=maxDimensionMm;
    }

    public DesignPrintReadinessResponse get(Integer ownerId, UUID projectId, UUID modelVersionId) {
        DesignProject project = projects.findByIdAndOwnerUserId(projectId, ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_PROJECT_NOT_FOUND"));
        DesignModelVersion model = models.findByIdAndOwnerUserId(modelVersionId, ownerId)
                .filter(value -> projectId.equals(value.getProjectId()))
                .orElseThrow(() -> new DesignProjectException("DESIGN_MODEL_VERSION_NOT_FOUND"));
        String projectStatus = project.getStatus().name();
        String modelStatus = model.getStatus().name();
        if (project.getStatus() != DesignProjectStatus.COMPLETED) return notReady(projectId, model, projectStatus, modelStatus, "PROJECT_NOT_COMPLETED", false, null);
        if (models.findTopByProjectIdAndOwnerUserIdOrderByVersionNumberDesc(projectId, ownerId)
                .map(latest -> !latest.getId().equals(modelVersionId)).orElse(true)) {
            return notReady(projectId, model, projectStatus, modelStatus, "MODEL_VERSION_SUPERSEDED", false, null);
        }
        DesignModelApproval approval = approvals.findTopByModelVersionIdAndOwnerUserIdOrderByCreatedAtDesc(modelVersionId, ownerId).orElse(null);
        if (model.getStatus() != DesignVersionStatus.APPROVED || approval == null || approval.getDecision() != ApprovalDecision.APPROVED) {
            return notReady(projectId, model, projectStatus, modelStatus, "MODEL_NOT_APPROVED", false, null);
        }
        if (model.getArtifactKey() == null || model.getSha256() == null || model.getByteSize() == null) {
            return notReady(projectId, model, projectStatus, modelStatus, "ARTIFACT_MISSING", false, null);
        }
        byte[] content;
        try { content = artifacts.read(ownerId, projectId, model.getArtifactKey()); }
        catch (IOException | RuntimeException unavailable) {
            return notReady(projectId, model, projectStatus, modelStatus, "ARTIFACT_UNREADABLE", false, null);
        }
        GlbStructure structure;
        try {
            mediaValidation.validateGlb(content, "model.glb", model.getMimeType());
            structure = glbStructureParser.parse(content);
            if (!hasSceneGeometry(structure)) {
                return notReady(projectId, model, projectStatus, modelStatus, "ARTIFACT_INVALID", false, null);
            }
        } catch (RuntimeException invalid) {
            return notReady(projectId, model, projectStatus, modelStatus, "ARTIFACT_INVALID", false, null);
        }
        String digest = sha256(content);
        if (!digest.equalsIgnoreCase(model.getSha256()) || model.getByteSize() != content.length) {
            return notReady(projectId, model, projectStatus, modelStatus, "ARTIFACT_CHECKSUM_MISMATCH", true, digest);
        }
        var evidence = evidenceSource.find(modelVersionId, digest).orElse(null);
        if (evidence == null) return notReady(projectId, model, projectStatus, modelStatus, "DIMENSION_EVIDENCE_MISSING", true, digest);
        if (!modelVersionId.equals(evidence.modelVersionId()) || !digest.equalsIgnoreCase(evidence.artifactSha256()))
            return notReady(projectId, model, projectStatus, modelStatus, "DIMENSION_EVIDENCE_MISMATCH", true, digest);
        if (evidence.superseded() || evidence.conflicting()) return notReady(projectId, model, projectStatus, modelStatus, "DIMENSION_EVIDENCE_CONFLICT", true, digest);
        if (!validLimits() || !"mm".equals(evidence.dimensionUnit()) || !finiteBounded(evidence.xMm())
                || !finiteBounded(evidence.yMm()) || !finiteBounded(evidence.zMm())
                || blank(evidence.dimensionSource()) || blank(evidence.dimensionMethod())
                || blank(evidence.provenanceId()) || evidence.measuredAt() == null
                || evidence.measuredAt().isAfter(java.time.Instant.now())) {
            return notReady(projectId, model, projectStatus, modelStatus, "DIMENSION_EVIDENCE_INVALID", true, digest);
        }
        return new DesignPrintReadinessResponse(projectId, modelVersionId, projectStatus, modelStatus, true, digest,
                "READY", null, evidence.xMm(), evidence.yMm(), evidence.zMm(),
                evidence.dimensionUnit(), evidence.dimensionSource(), evidence.dimensionMethod(), evidence.modelVersionId(),
                evidence.artifactSha256(), evidence.measuredAt(), evidence.provenanceId());
    }

    private boolean validLimits() { return minDimensionMm != null && maxDimensionMm != null && Double.isFinite(minDimensionMm)
            && Double.isFinite(maxDimensionMm) && minDimensionMm > 0 && maxDimensionMm >= minDimensionMm; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean hasSceneGeometry(GlbStructure structure) {
        if (structure == null || structure.defaultSceneIndex() < 0
                || structure.defaultSceneIndex() >= structure.scenes().size() || structure.nodes().isEmpty()
                || structure.meshes().isEmpty() || structure.primitiveOccurrences().isEmpty()) return false;
        var activeOccurrences = structure.primitiveOccurrences().stream()
                .filter(occurrence -> occurrence.nodeIndex() >= 0 && occurrence.nodeIndex() < structure.nodes().size()
                        && structure.nodes().get(occurrence.nodeIndex()).sceneIndices()
                                .contains(structure.defaultSceneIndex()))
                .toList();
        if (activeOccurrences.isEmpty()) return false;
        return activeOccurrences.stream().allMatch(occurrence -> {
            if (occurrence.meshIndex() < 0 || occurrence.meshIndex() >= structure.meshes().size()) return false;
            var mesh = structure.meshes().get(occurrence.meshIndex());
            if (occurrence.primitiveIndex() < 0 || occurrence.primitiveIndex() >= mesh.primitives().size()) return false;
            var primitive = mesh.primitives().get(occurrence.primitiveIndex());
            if (!primitive.attributeNames().contains("POSITION") || primitive.positionCount() < 3
                    || !createsSurface(primitive.mode()) || !primitive.verifiedSurface()) return false;
            long elementCount = primitive.indexCount() == null ? primitive.positionCount() : primitive.indexCount();
            return elementCount >= 3 && (primitive.mode() != 4 || elementCount % 3 == 0);
        });
    }
    private static boolean createsSurface(int mode) {
        return mode == 4 || mode == 5 || mode == 6;
    }
    private boolean finiteBounded(double value) { return Double.isFinite(value) && value >= minDimensionMm && value <= maxDimensionMm; }
    private DesignPrintReadinessResponse notReady(UUID projectId, DesignModelVersion model, String projectStatus,
            String modelStatus, String reason, boolean available, String digest) {
        return new DesignPrintReadinessResponse(projectId, model.getId(), projectStatus, modelStatus, available, digest,
                "NOT_READY", reason, null, null, null, null, null, null, null, null, null, null);
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
