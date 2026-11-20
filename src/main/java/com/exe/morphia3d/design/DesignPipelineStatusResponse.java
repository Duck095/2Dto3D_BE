package com.exe.morphia3d.design;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DesignPipelineStatusResponse(UUID projectId, String projectStatus,
        List<ReferenceVersion> referenceVersions, List<ModelVersion> modelVersions, List<PipelineJob> jobs) {
    public record ReferenceVersion(UUID versionId, int versionNumber, String status, List<String> availableViews,
                                   UUID parentVersionId, Instant createdAt) { }
    public record ModelVersion(UUID modelVersionId, int versionNumber, UUID referenceVersionId, String status,
                               UUID supersedesModelVersionId, boolean artifactAvailable, Instant createdAt) { }
    public record PipelineJob(UUID jobId, String stage, String status, String safeErrorCode,
                              int attemptCount, Instant createdAt, Instant updatedAt) { }
}
