package com.exe.morphia3d.design;

import com.exe.morphia3d.repository.DesignModelVersionRepository;
import com.exe.morphia3d.repository.DesignProjectInputRepository;
import com.exe.morphia3d.repository.DesignReferenceImageVersionRepository;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class JpaLegacyDesignArtifactProjectAuthorizer implements LegacyDesignArtifactProjectAuthorizer {
    private final DesignProjectInputRepository inputs;
    private final DesignReferenceImageVersionRepository references;
    private final DesignModelVersionRepository models;

    public JpaLegacyDesignArtifactProjectAuthorizer(DesignProjectInputRepository inputs,
            DesignReferenceImageVersionRepository references, DesignModelVersionRepository models) {
        this.inputs = inputs; this.references = references; this.models = models;
    }

    @Override public boolean isLinked(Integer ownerUserId, UUID projectId, String artifactKey) {
        return ownerUserId != null && projectId != null && artifactKey != null
                && (inputs.existsByOwnerUserIdAndProjectIdAndArtifactKey(ownerUserId, projectId, artifactKey)
                || references.existsByOwnerUserIdAndProjectIdAndArtifactKey(ownerUserId, projectId, artifactKey)
                || models.existsByOwnerUserIdAndProjectIdAndArtifactKey(ownerUserId, projectId, artifactKey));
    }
}
