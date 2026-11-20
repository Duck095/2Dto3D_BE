package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.repository.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignProjectTimelineServiceTest {
    @Test
    void timelineUsesPersistedApprovalRevisionViewsAndJobSnapshotWithoutStorageKeys() {
        var projects = mock(DesignProjectRepository.class);
        var inputs = mock(DesignProjectInputRepository.class);
        var references = mock(DesignReferenceSetVersionRepository.class);
        var views = mock(DesignReferenceSetViewRepository.class);
        var images = mock(DesignReferenceImageVersionRepository.class);
        var refApprovals = mock(DesignReferenceApprovalRepository.class);
        var models = mock(DesignModelVersionRepository.class);
        var modelApprovals = mock(DesignModelApprovalRepository.class);
        var jobs = mock(DesignPipelineJobRepository.class);

        UUID projectId = UUID.randomUUID();
        UUID refId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID refApprovalId = UUID.randomUUID();
        UUID modelApprovalId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-18T12:00:00Z");
        DesignProject project = new DesignProject();
        project.setId(projectId); project.setOwnerUserId(17); project.setStatus(DesignProjectStatus.MODEL_REVIEW);
        project.setCreatedAt(now.minusSeconds(3600)); project.setUpdatedAt(now);
        when(projects.findByIdAndOwnerUserId(projectId, 17)).thenReturn(Optional.of(project));

        DesignProjectInput input = new DesignProjectInput();
        input.setId(UUID.randomUUID()); input.setCreatedAt(now); input.setViewHint("front");
        input.setMimeType("image/png"); input.setByteSize(12L); input.setSha256("a".repeat(64));
        when(inputs.findByProjectIdAndOwnerUserIdOrderByInputOrder(projectId, 17)).thenReturn(List.of(input));

        DesignReferenceSetVersion reference = new DesignReferenceSetVersion();
        reference.setId(refId); reference.setOwnerUserId(17); reference.setProjectId(projectId);
        reference.setCreatedAt(now.minusSeconds(30)); reference.setStatus(DesignVersionStatus.CHANGES_REQUESTED);
        reference.setRevisionFeedback("revise only front"); reference.setParentVersionId(UUID.randomUUID());
        when(references.findByProjectIdAndOwnerUserIdOrderByVersionNumberAsc(projectId, 17)).thenReturn(List.of(reference));
        DesignReferenceSetView view = new DesignReferenceSetView();
        view.setReferenceVersionId(refId); view.setOwnerUserId(17); view.setViewSlot("front"); view.setImageVersionId(imageId);
        when(views.findByReferenceVersionIdInAndOwnerUserId(List.of(refId), 17)).thenReturn(List.of(view));
        DesignReferenceImageVersion image = new DesignReferenceImageVersion();
        image.setId(imageId); image.setOwnerUserId(17); image.setViewSlot("front"); image.setVersionNumber(2);
        image.setSupersedesImageVersionId(UUID.randomUUID()); image.setArtifactKey("SECRET-storage-key");
        image.setMimeType("image/png"); image.setByteSize(100L); image.setSha256("b".repeat(64)); image.setCreatedAt(now);
        when(images.findByIdInAndOwnerUserId(List.of(imageId), 17)).thenReturn(List.of(image));

        DesignReferenceApproval refApproval = new DesignReferenceApproval();
        refApproval.setId(refApprovalId); refApproval.setOwnerUserId(17); refApproval.setReferenceVersionId(refId);
        refApproval.setDecision(ApprovalDecision.CHANGES_REQUESTED); refApproval.setFeedback("front is too dark");
        refApproval.setCreatedAt(now);
        when(refApprovals.findByReferenceVersionIdInAndOwnerUserIdOrderByCreatedAtAsc(List.of(refId), 17))
                .thenReturn(List.of(refApproval));

        DesignModelVersion model = new DesignModelVersion();
        model.setId(modelId); model.setOwnerUserId(17); model.setProjectId(projectId);
        model.setCreatedAt(now.plusSeconds(1)); model.setStatus(DesignVersionStatus.READY_FOR_REVIEW);
        model.setArtifactKey("SECRET-model-key"); model.setMimeType("model/gltf-binary");
        model.setByteSize(200L); model.setSha256("c".repeat(64));
        when(models.findByProjectIdAndOwnerUserIdOrderByVersionNumberAsc(projectId, 17)).thenReturn(List.of(model));
        DesignModelApproval modelApproval = new DesignModelApproval();
        modelApproval.setId(modelApprovalId); modelApproval.setOwnerUserId(17); modelApproval.setModelVersionId(modelId);
        modelApproval.setDecision(ApprovalDecision.APPROVED); modelApproval.setFeedback("approved"); modelApproval.setCreatedAt(now);
        when(modelApprovals.findByModelVersionIdInAndOwnerUserIdOrderByCreatedAtAsc(List.of(modelId), 17))
                .thenReturn(List.of(modelApproval));

        DesignPipelineJob job = new DesignPipelineJob();
        job.setId(jobId); job.setProjectId(projectId); job.setOwnerUserId(17); job.setStage(DesignStage.MODEL_MESH);
        job.setStatus(DesignPipelineJobStatus.PROCESSING); job.setModelVersionId(modelId); job.setUpdatedAt(now.plusSeconds(2));
        job.setProviderTaskRef("SECRET-provider-reference"); job.setSafeErrorCode("PROVIDER_SUBMISSION_STARTED");
        job.setAttemptCount(2);
        when(jobs.findByProjectIdAndOwnerUserIdOrderByCreatedAtAsc(projectId, 17)).thenReturn(List.of(job));

        var service = new DesignProjectTimelineService(projects, inputs, references, views, images, refApprovals,
                models, modelApprovals, jobs);
        var timeline = service.getForOwner(projectId, 17);
        var createdEvent = timeline.events().stream().filter(event -> event.eventType().equals("PROJECT_CREATED"))
                .findFirst().orElseThrow();
        var statusSnapshot = timeline.events().stream().filter(event -> event.eventType().equals("PROJECT_STATUS"))
                .findFirst().orElseThrow();
        assertThat(createdEvent.occurredAt()).isEqualTo(project.getCreatedAt());
        assertThat(statusSnapshot.occurredAt()).isEqualTo(project.getUpdatedAt());
        assertThat(createdEvent.eventId()).isNotEqualTo(statusSnapshot.eventId());
        var repeatedTimeline = service.getForOwner(projectId, 17);
        assertThat(repeatedTimeline.events()).filteredOn(event -> event.eventType().equals("PROJECT_CREATED")).hasSize(1);
        assertThat(repeatedTimeline.events().stream().filter(event -> event.eventType().equals("PROJECT_CREATED"))
                .findFirst().orElseThrow().eventId()).isEqualTo(createdEvent.eventId());

        assertThat(timeline.events()).extracting(DesignProjectTimelineResponse.Event::recordType)
                .contains("PROJECT_RECORD", "PROJECT_STATUS_SNAPSHOT", "PROJECT_INPUT_RECORD", "VERSION_STATUS_SNAPSHOT", "IMAGE_VERSION_RECORD",
                        "APPROVAL_EVENT", "JOB_STATUS_SNAPSHOT");
        var referenceEvent = timeline.events().stream().filter(event -> event.eventId().equals(refId)).findFirst().orElseThrow();
        assertThat(referenceEvent.parentId()).isEqualTo(reference.getParentVersionId());
        assertThat(referenceEvent.feedback()).isEqualTo("revise only front");
        assertThat(referenceEvent.referenceViews()).singleElement().satisfies(referenceView -> {
            assertThat(referenceView.viewSlot()).isEqualTo("front");
            assertThat(referenceView.imageVersionId()).isEqualTo(imageId);
            assertThat(referenceView.artifactUrl()).isEqualTo("/api/ai-studio/projects/" + projectId
                    + "/references/" + refId + "/views/front");
        });
        assertThat(timeline.events()).filteredOn(event -> event.eventId().equals(refApprovalId))
                .singleElement().satisfies(event -> {
                    assertThat(event.decision()).isEqualTo("CHANGES_REQUESTED");
                    assertThat(event.feedback()).isEqualTo("front is too dark");
                });
        var jobEvent = timeline.events().stream().filter(event -> event.eventId().equals(jobId)).findFirst().orElseThrow();
        assertThat(jobEvent.recordType()).isEqualTo("JOB_STATUS_SNAPSHOT");
        assertThat(jobEvent.status()).isEqualTo("PROCESSING");
        assertThat(timeline.events().stream().filter(event -> event.eventId().equals(modelApprovalId))).hasSize(1);
        assertThat(timeline.toString()).doesNotContain("SECRET-storage-key", "SECRET-model-key", "SECRET-provider-reference");
        verify(views, org.mockito.Mockito.times(2)).findByReferenceVersionIdInAndOwnerUserId(List.of(refId), 17);
        verify(images, org.mockito.Mockito.times(2)).findByIdInAndOwnerUserId(List.of(imageId), 17);
    }

    @Test
    void timelineDoesNotResolveProjectsOutsideTheRequestedOwnerScope() {
        var projects = mock(DesignProjectRepository.class);
        var inputs = mock(DesignProjectInputRepository.class);
        when(projects.findByIdAndOwnerUserId(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(17)))
                .thenReturn(Optional.empty());
        var service = new DesignProjectTimelineService(projects, inputs,
                mock(DesignReferenceSetVersionRepository.class), mock(DesignReferenceSetViewRepository.class),
                mock(DesignReferenceImageVersionRepository.class), mock(DesignReferenceApprovalRepository.class),
                mock(DesignModelVersionRepository.class), mock(DesignModelApprovalRepository.class),
                mock(DesignPipelineJobRepository.class));

        assertThatThrownBy(() -> service.getForOwner(UUID.randomUUID(), 17))
                .isInstanceOf(DesignProjectException.class).hasMessage("DESIGN_PROJECT_NOT_FOUND");
        verify(inputs, never()).findByProjectIdAndOwnerUserIdOrderByInputOrder(
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyInt());
    }
}
