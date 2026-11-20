package com.exe.morphia3d.design;

import java.util.List;
import java.util.UUID;

public interface ReferenceImageProvider {
    List<GeneratedReferenceImage> generate(ReferenceImageRequest request);
    record InputArtifact(String artifactKey, String mimeType, long byteSize, String sha256, byte[] content) {
        public InputArtifact { content = content == null ? null : content.clone(); }
        @Override public byte[] content() { return content == null ? null : content.clone(); }
    }
    record ReferenceImageRequest(UUID jobId, String idempotencyKey, String normalizedPrompt,
                                 String profileVersion, List<InputArtifact> inputs, List<ReferenceViewSlot> slots) { }
    record GeneratedReferenceImage(ReferenceViewSlot slot, String mimeType, byte[] content, Long reportedUsageUnits) {
        public GeneratedReferenceImage { content = content == null ? null : content.clone(); }
        @Override public byte[] content() { return content == null ? null : content.clone(); }
    }
}
