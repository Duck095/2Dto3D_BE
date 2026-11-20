package com.exe.morphia3d.design;

import java.time.Instant;
import java.util.UUID;

public record DesignPrintReadinessResponse(UUID projectId, UUID modelVersionId,
        String projectStatus, String modelStatus, boolean artifactAvailable, String artifactSha256,
        String printReadiness, String reasonCode, Double xMm, Double yMm, Double zMm,
        String dimensionUnit, String dimensionSource, String dimensionMethod,
        UUID measurementModelVersionId, String measurementArtifactSha256,
        Instant measuredAt, String provenanceId) { }
