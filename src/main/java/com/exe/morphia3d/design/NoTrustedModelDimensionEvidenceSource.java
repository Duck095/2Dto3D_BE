package com.exe.morphia3d.design;

import java.util.Optional;
import java.util.UUID;

/** Fail-closed production default until an independently reviewed trusted source exists. */
public final class NoTrustedModelDimensionEvidenceSource implements VerifiedModelDimensionEvidenceSource {
    @Override public Optional<Evidence> find(UUID modelVersionId, String artifactSha256) { return Optional.empty(); }
}
