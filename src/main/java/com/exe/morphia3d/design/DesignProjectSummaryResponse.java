package com.exe.morphia3d.design;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DesignProjectSummaryResponse(UUID projectId, String productType, String keychainVariant, String styleId,
        String inputMode, String originalIdea, String status, Instant createdAt, Instant updatedAt,
        UUID latestReferenceVersionId, UUID latestModelVersionId) {
    public record Page(List<DesignProjectSummaryResponse> content, int page, int size, long totalElements,
                       int totalPages, boolean first, boolean last) { }
}
