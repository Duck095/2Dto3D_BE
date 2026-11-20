package com.exe.morphia3d.design;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DesignProjectResponse(UUID projectId, String productType, String keychainVariant, String styleId,
                                    String inputMode, String originalIdea, String normalizedPrompt,
                                    String promptProfileVersion, String status, Instant createdAt,
                                    List<InputSummary> inputs) {
    public record InputSummary(UUID inputId, int order, String mimeType, long byteSize, String sha256,
                               String clientSlot, String viewHint) { }
}
