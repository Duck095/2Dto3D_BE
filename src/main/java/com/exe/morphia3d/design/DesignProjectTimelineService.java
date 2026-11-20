package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.DesignReferenceImageVersion;
import com.exe.morphia3d.entity.DesignReferenceSetView;
import com.exe.morphia3d.repository.DesignModelApprovalRepository;
import com.exe.morphia3d.repository.DesignModelVersionRepository;
import com.exe.morphia3d.repository.DesignPipelineJobRepository;
import com.exe.morphia3d.repository.DesignProjectInputRepository;
import com.exe.morphia3d.repository.DesignProjectRepository;
import com.exe.morphia3d.repository.DesignReferenceApprovalRepository;
import com.exe.morphia3d.repository.DesignReferenceImageVersionRepository;
import com.exe.morphia3d.repository.DesignReferenceSetVersionRepository;
import com.exe.morphia3d.repository.DesignReferenceSetViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

/** Builds a read-only view of persisted design records; version/job statuses are snapshots, not transition events. */
@Service
public class DesignProjectTimelineService {
    private final DesignProjectRepository projects;
    private final DesignProjectInputRepository inputs;
    private final DesignReferenceSetVersionRepository referenceVersions;
    private final DesignReferenceSetViewRepository referenceViews;
    private final DesignReferenceImageVersionRepository imageVersions;
    private final DesignReferenceApprovalRepository referenceApprovals;
    private final DesignModelVersionRepository modelVersions;
    private final DesignModelApprovalRepository modelApprovals;
    private final DesignPipelineJobRepository jobs;

    public DesignProjectTimelineService(DesignProjectRepository projects, DesignProjectInputRepository inputs,
            DesignReferenceSetVersionRepository referenceVersions, DesignReferenceSetViewRepository referenceViews,
            DesignReferenceImageVersionRepository imageVersions, DesignReferenceApprovalRepository referenceApprovals,
            DesignModelVersionRepository modelVersions, DesignModelApprovalRepository modelApprovals,
            DesignPipelineJobRepository jobs) {
        this.projects = projects;
        this.inputs = inputs;
        this.referenceVersions = referenceVersions;
        this.referenceViews = referenceViews;
        this.imageVersions = imageVersions;
        this.referenceApprovals = referenceApprovals;
        this.modelVersions = modelVersions;
        this.modelApprovals = modelApprovals;
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public DesignProjectTimelineResponse getForOwner(UUID projectId, Integer ownerId) {
        var project = projects.findByIdAndOwnerUserId(projectId, ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_PROJECT_NOT_FOUND"));
        var projectInputs = inputs.findByProjectIdAndOwnerUserIdOrderByInputOrder(projectId, ownerId);
        var references = referenceVersions.findByProjectIdAndOwnerUserIdOrderByVersionNumberAsc(projectId, ownerId);
        var models = modelVersions.findByProjectIdAndOwnerUserIdOrderByVersionNumberAsc(projectId, ownerId);
        List<UUID> referenceIds = references.stream().map(value -> value.getId()).toList();
        List<UUID> modelIds = models.stream().map(value -> value.getId()).toList();

        List<DesignReferenceSetView> viewRows = referenceIds.isEmpty() ? List.of()
                : referenceViews.findByReferenceVersionIdInAndOwnerUserId(referenceIds, ownerId);
        Map<UUID, List<DesignReferenceSetView>> viewsByReference = viewRows.stream()
                .collect(Collectors.groupingBy(DesignReferenceSetView::getReferenceVersionId));
        List<UUID> imageIds = viewRows.stream().map(DesignReferenceSetView::getImageVersionId).distinct().toList();
        List<DesignReferenceImageVersion> images = imageIds.isEmpty() ? List.of()
                : imageVersions.findByIdInAndOwnerUserId(imageIds, ownerId);
        Map<UUID, DesignReferenceImageVersion> imagesById = images.stream()
                .collect(Collectors.toMap(DesignReferenceImageVersion::getId, value -> value));
        var refApprovals = referenceIds.isEmpty() ? List.<com.exe.morphia3d.entity.DesignReferenceApproval>of()
                : referenceApprovals.findByReferenceVersionIdInAndOwnerUserIdOrderByCreatedAtAsc(referenceIds, ownerId);
        var modelApprovals = modelIds.isEmpty() ? List.<com.exe.morphia3d.entity.DesignModelApproval>of()
                : this.modelApprovals.findByModelVersionIdInAndOwnerUserIdOrderByCreatedAtAsc(modelIds, ownerId);
        var pipelineJobs = jobs.findByProjectIdAndOwnerUserIdOrderByCreatedAtAsc(projectId, ownerId);

        List<DesignProjectTimelineResponse.Event> events = new ArrayList<>();
        events.add(event(projectCreatedEventId(project.getId()), "PROJECT_CREATED", "PROJECT_RECORD", project.getCreatedAt(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, List.of()));
        events.add(event(project.getId(), "PROJECT_STATUS", "PROJECT_STATUS_SNAPSHOT", project.getUpdatedAt(),
                null, project.getStatus().name(), null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of()));
        for (var input : projectInputs) {
            events.add(event(input.getId(), "PROJECT_INPUT_ADDED", "PROJECT_INPUT_RECORD", input.getCreatedAt(),
                    null, null, null, null, null, null, input.getViewHint(), null,
                    "/api/ai-studio/projects/" + projectId + "/inputs/" + input.getId() + "/content",
                    input.getMimeType(), input.getByteSize(), input.getSha256(), null, null, null, List.of()));
        }
        for (var version : references) {
            List<DesignProjectTimelineResponse.ReferenceView> referenceViewMetadata = viewsByReference
                    .getOrDefault(version.getId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(view -> ReferenceViewSlot.canonicalOrder().stream()
                            .map(ReferenceViewSlot::apiValue).toList().indexOf(view.getViewSlot())))
                    .map(view -> imagesById.get(view.getImageVersionId()))
                    .filter(java.util.Objects::nonNull)
                    .map(image -> new DesignProjectTimelineResponse.ReferenceView(image.getViewSlot(), image.getId(),
                            image.getVersionNumber(), image.getSupersedesImageVersionId(),
                            "/api/ai-studio/projects/" + projectId + "/references/" + version.getId()
                                    + "/views/" + image.getViewSlot(),
                            image.getMimeType(), image.getByteSize(), image.getSha256(), image.getCreatedAt()))
                    .toList();
            events.add(event(version.getId(), "REFERENCE_VERSION", "VERSION_STATUS_SNAPSHOT", version.getCreatedAt(),
                    version.getId(), version.getStatus().name(), null, version.getRevisionFeedback(),
                    version.getParentVersionId(), null, null, null, null, null, null, null, null, null, null,
                    referenceViewMetadata));
        }
        for (var image : images) {
            events.add(event(image.getId(), "REFERENCE_IMAGE_VERSION", "IMAGE_VERSION_RECORD", image.getCreatedAt(),
                    image.getId(), null, null, null, null, image.getSupersedesImageVersionId(), image.getViewSlot(),
                    image.getId(), null, image.getMimeType(), image.getByteSize(), image.getSha256(), null, null,
                    null, List.of()));
        }
        for (var approval : refApprovals) {
            events.add(event(approval.getId(), "REFERENCE_APPROVAL", "APPROVAL_EVENT", approval.getCreatedAt(),
                    approval.getReferenceVersionId(), null, approval.getDecision().name(), approval.getFeedback(),
                    null, null, null, null, null, null, null, null, null, null, null, List.of()));
        }
        for (var model : models) {
            String artifactUrl = model.getArtifactKey() == null ? null
                    : "/api/ai-studio/projects/" + projectId + "/models/" + model.getId() + "/artifact";
            events.add(event(model.getId(), "MODEL_VERSION", "VERSION_STATUS_SNAPSHOT", model.getCreatedAt(),
                    model.getId(), model.getStatus().name(), null, model.getRevisionFeedback(), null,
                    model.getSupersedesModelVersionId(), null, null, artifactUrl, model.getMimeType(),
                    model.getByteSize(), model.getSha256(), null, null, null, List.of()));
        }
        for (var approval : modelApprovals) {
            events.add(event(approval.getId(), "MODEL_APPROVAL", "APPROVAL_EVENT", approval.getCreatedAt(),
                    approval.getModelVersionId(), null, approval.getDecision().name(), approval.getFeedback(),
                    null, null, null, null, null, null, null, null, null, null, null, List.of()));
        }
        for (var job : pipelineJobs) {
            events.add(event(job.getId(), "PIPELINE_JOB_STATUS", "JOB_STATUS_SNAPSHOT", job.getUpdatedAt(),
                    job.getReferenceVersionId() == null ? job.getModelVersionId() : job.getReferenceVersionId(),
                    job.getStatus().name(), null, null, null, null, job.getRequestedViewSlot(), null,
                    null, null, null, null, job.getStage().name(), job.getAttemptCount(), job.getSafeErrorCode(), List.of()));
        }
        events.sort(Comparator.comparing(DesignProjectTimelineResponse.Event::occurredAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(event -> event.eventId().toString()));
        return new DesignProjectTimelineResponse(projectId, List.copyOf(events));
    }

    private UUID projectCreatedEventId(UUID projectId) {
        return UUID.nameUUIDFromBytes(("ai-studio:" + projectId + ":PROJECT_CREATED").getBytes(StandardCharsets.UTF_8));
    }

    private DesignProjectTimelineResponse.Event event(UUID id, String type, String recordType,
            java.time.Instant occurredAt, UUID versionId, String status, String decision, String feedback,
            UUID parentId, UUID supersedesId, String viewSlot, UUID imageVersionId, String artifactUrl,
            String mimeType, Long byteSize, String sha256, String stage, Integer attemptCount,
            String safeErrorCode, List<DesignProjectTimelineResponse.ReferenceView> referenceViews) {
        return new DesignProjectTimelineResponse.Event(id, type, recordType, occurredAt, versionId, status,
                decision, feedback, parentId, supersedesId, viewSlot, imageVersionId, artifactUrl, mimeType,
                byteSize, sha256, stage, attemptCount, safeErrorCode, referenceViews);
    }
}
