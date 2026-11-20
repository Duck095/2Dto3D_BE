package com.exe.morphia3d.design;

import java.util.UUID;

@FunctionalInterface
public interface LegacyDesignArtifactProjectAuthorizer {
    boolean isLinked(Integer ownerUserId, UUID projectId, String artifactKey);
}
