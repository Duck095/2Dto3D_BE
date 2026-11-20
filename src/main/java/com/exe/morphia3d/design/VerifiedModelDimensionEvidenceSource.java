package com.exe.morphia3d.design;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Trusted, read-only physical measurement lookup. Production has no backing source yet. */
public interface VerifiedModelDimensionEvidenceSource {
    Optional<Evidence> find(UUID modelVersionId, String artifactSha256);

    record Evidence(UUID modelVersionId, String artifactSha256, double xMm, double yMm, double zMm,
                    String dimensionUnit, String dimensionSource, String dimensionMethod,
                    Instant measuredAt, String provenanceId, boolean superseded, boolean conflicting) { }
}
