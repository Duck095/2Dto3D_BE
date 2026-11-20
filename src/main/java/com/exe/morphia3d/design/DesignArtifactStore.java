package com.exe.morphia3d.design;

import java.io.IOException;
import java.util.UUID;

public interface DesignArtifactStore {
    DesignArtifactDescriptor put(Integer ownerUserId, UUID projectId, String mimeType, byte[] content) throws IOException;
    byte[] read(Integer ownerUserId, UUID projectId, String artifactKey) throws IOException;
    void delete(Integer ownerUserId, UUID projectId, String artifactKey) throws IOException;
}
