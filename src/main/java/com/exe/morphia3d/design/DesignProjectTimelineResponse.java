package com.exe.morphia3d.design;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DesignProjectTimelineResponse(UUID projectId, List<Event> events) {
    public record Event(UUID eventId, String eventType, String recordType, Instant occurredAt, UUID versionId,
            String status, String decision, String feedback, UUID parentId, UUID supersedesId,
            String viewSlot, UUID imageVersionId, String artifactUrl, String mimeType, Long byteSize,
            String sha256, String stage, Integer attemptCount, String safeErrorCode,
            List<ReferenceView> referenceViews) { }

    public record ReferenceView(String viewSlot, UUID imageVersionId, int imageVersionNumber, UUID supersedesImageVersionId,
            String artifactUrl, String mimeType, long byteSize, String sha256, Instant createdAt) { }
}
