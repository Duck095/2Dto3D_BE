package com.exe.morphia3d.design;

import java.io.IOException;
import java.util.UUID;

public class UnconfiguredDesignArtifactStore implements DesignArtifactStore {
    private IOException unavailable() { return new IOException("DESIGN_ARTIFACT_STORE_UNAVAILABLE"); }
    @Override public DesignArtifactDescriptor put(Integer ownerUserId, UUID projectId, String mimeType, byte[] content) throws IOException { throw unavailable(); }
    @Override public byte[] read(Integer ownerUserId, UUID projectId, String artifactKey) throws IOException { throw unavailable(); }
    @Override public void delete(Integer ownerUserId, UUID projectId, String artifactKey) throws IOException { throw unavailable(); }
}
