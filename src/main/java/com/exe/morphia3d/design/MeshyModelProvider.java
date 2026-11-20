package com.exe.morphia3d.design;

import java.util.List;
import java.util.UUID;

public interface MeshyModelProvider {
    String submit(ModelRequest request);
    TaskStatus status(String providerTaskReference);
    GeneratedModel result(String providerTaskReference);
    record ApprovedReference(String slot, String artifactKey, String sha256, String mimeType, byte[] content) {
        public ApprovedReference { content = content == null ? null : content.clone(); }
        public ApprovedReference(String slot, String artifactKey, String sha256, byte[] content) {
            this(slot, artifactKey, sha256, "image/png", content);
        }
        @Override public byte[] content() { return content == null ? null : content.clone(); }
    }
    record ModelRequest(UUID jobId, String idempotencyKey, String normalizedPrompt,
                        List<ApprovedReference> approvedReferences, String revisionFeedback) { }
    enum TaskStatus { PENDING, PROCESSING, SUCCEEDED, FAILED, UNKNOWN }
    record GeneratedModel(String mimeType, byte[] content, Long reportedUsageUnits) { }
}
