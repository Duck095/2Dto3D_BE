package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.repository.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DesignPipelineCommandServiceTest {
    private static final int OWNER = 23;

    @Test
    void replaysPendingReferenceJobWithoutCreatingAnotherVersionOrReservation() {
        Fixture f = new Fixture(DesignProjectStatus.DRAFT);

        var first = f.service.enqueueReferences(OWNER, f.projectId, "reference-key", null, null);
        Instant replayBaseline = Instant.parse("2020-01-01T00:00:00Z");
        f.project.setUpdatedAt(replayBaseline);
        var replay = f.service.enqueueReferences(OWNER, f.projectId, "reference-key", null, null);

        assertThat(replay).isEqualTo(first);
        assertThat(f.project.getUpdatedAt()).isEqualTo(replayBaseline);
        assertThat(replay.versionId()).isEqualTo(f.savedReference.get().getId());
        assertThat(f.savedJob.get().getStatus()).isEqualTo(DesignPipelineJobStatus.PENDING);
        verify(f.jobs, times(1)).save(any());
        verify(f.referenceVersions, times(1)).save(any());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 4);
    }

    @Test
    void replaysReferenceJobAfterItReachesReview() {
        Fixture f = new Fixture(DesignProjectStatus.DRAFT);
        var first = f.service.enqueueReferences(OWNER, f.projectId, "reference-key", null, null);
        f.project.setStatus(DesignProjectStatus.REFERENCES_REVIEW);
        f.savedReference.get().setStatus(DesignVersionStatus.READY_FOR_REVIEW);

        var replay = f.service.enqueueReferences(OWNER, f.projectId, "reference-key", null, null);

        assertThat(replay.jobId()).isEqualTo(first.jobId());
        assertThat(replay.versionId()).isEqualTo(first.versionId());
        assertThat(replay.projectStatus()).isEqualTo("REFERENCES_REVIEW");
        verify(f.jobs, times(1)).save(any());
        verify(f.referenceVersions, times(1)).save(any());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 4);
    }

    @Test
    void replaysReferenceJobAfterApprovalAndProjectCompletion() {
        Fixture f = new Fixture(DesignProjectStatus.DRAFT);
        var first = f.service.enqueueReferences(OWNER, f.projectId, "reference-key", null, null);
        f.project.setStatus(DesignProjectStatus.COMPLETED);
        f.savedReference.get().setStatus(DesignVersionStatus.APPROVED);

        var replay = f.service.enqueueReferences(OWNER, f.projectId, "reference-key", null, null);

        assertThat(replay.jobId()).isEqualTo(first.jobId());
        assertThat(replay.versionId()).isEqualTo(first.versionId());
        assertThat(replay.projectStatus()).isEqualTo("COMPLETED");
        verify(f.jobs, times(1)).save(any());
        verify(f.referenceVersions, times(1)).save(any());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 4);
    }

    @Test
    void rejectsChangedReferencePayloadAsConflictBeforeRevisionStateChecks() {
        Fixture f = new Fixture(DesignProjectStatus.DRAFT);
        f.service.enqueueReferences(OWNER, f.projectId, "reference-key", null, null);
        f.project.setStatus(DesignProjectStatus.REFERENCES_REVIEW);
        f.savedReference.get().setStatus(DesignVersionStatus.READY_FOR_REVIEW);

        assertCode("DESIGN_IDEMPOTENCY_CONFLICT", () ->
                f.service.enqueueReferences(OWNER, f.projectId, "reference-key", "left", "change"));

        verify(f.jobs, times(1)).save(any());
        verify(f.referenceVersions, times(1)).save(any());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 4);
    }

    @Test
    void newReferenceKeyCannotSkipReviewApproval() {
        Fixture f = new Fixture(DesignProjectStatus.DRAFT);
        f.service.enqueueReferences(OWNER, f.projectId, "first-key", null, null);
        f.project.setStatus(DesignProjectStatus.REFERENCES_REVIEW);
        f.savedReference.get().setStatus(DesignVersionStatus.READY_FOR_REVIEW);

        assertCode("DESIGN_REFERENCE_REVISION_NOT_ALLOWED", () ->
                f.service.enqueueReferences(OWNER, f.projectId, "new-key", null, null));

        verify(f.jobs, times(1)).save(any());
        verify(f.referenceVersions, times(1)).save(any());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 4);
    }

    @Test
    void singleSlotRevisionCopiesTheThreeUnchangedImageVersionsToNewSnapshot() {
        Fixture f = new Fixture(DesignProjectStatus.COMPLETED);
        f.approvedReferences.setVersionNumber(7);
        f.savedReference.set(f.approvedReferences);
        var previousViews = List.of(f.view("front"), f.view("left"), f.view("right"), f.view("back"));
        when(f.views.findByReferenceVersionIdAndOwnerUserId(f.referenceId, OWNER)).thenReturn(previousViews);

        var accepted = f.service.enqueueReferences(OWNER, f.projectId, "revise-left", "left", "move handle");

        assertThat(accepted.versionId()).isEqualTo(f.savedReference.get().getId());
        assertThat(f.savedReference.get().getParentVersionId()).isEqualTo(f.approvedReferences.getId());
        assertThat(f.savedReference.get().getRevisionFeedback()).isEqualTo("move handle");
        var copied = org.mockito.ArgumentCaptor.forClass(DesignReferenceSetView.class);
        verify(f.views, times(3)).save(copied.capture());
        assertThat(copied.getAllValues()).extracting(DesignReferenceSetView::getViewSlot)
                .containsExactlyInAnyOrder("front", "right", "back");
        for (DesignReferenceSetView reused : copied.getAllValues()) {
            DesignReferenceSetView old = previousViews.stream()
                    .filter(view -> view.getViewSlot().equals(reused.getViewSlot())).findFirst().orElseThrow();
            assertThat(reused.getImageVersionId()).isEqualTo(old.getImageVersionId());
            assertThat(reused.getReferenceVersionId()).isEqualTo(accepted.versionId());
        }
        assertThat(previousViews).allSatisfy(view -> assertThat(view.getReferenceVersionId()).isEqualTo(f.referenceId));
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 4);
    }

    @Test
    void changesRequestedReferenceApprovalAdvancesProjectUpdatedAtWithoutChangingProjectStatus() {
        Fixture f = new Fixture(DesignProjectStatus.REFERENCES_REVIEW);
        f.project.setUpdatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        f.approvedReferences.setStatus(DesignVersionStatus.READY_FOR_REVIEW);

        f.service.approveReferences(OWNER, f.projectId, f.referenceId, ApprovalDecision.CHANGES_REQUESTED,
                "adjust front", "reference-feedback-key");

        assertThat(f.project.getStatus()).isEqualTo(DesignProjectStatus.REFERENCES_REVIEW);
        assertThat(f.project.getUpdatedAt()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void changesRequestedModelApprovalAdvancesProjectUpdatedAtWithoutChangingProjectStatus() {
        Fixture f = new Fixture(DesignProjectStatus.MODEL_REVIEW);
        Instant oldUpdatedAt = Instant.parse("2020-01-01T00:00:00Z");
        f.project.setUpdatedAt(oldUpdatedAt);
        DesignModelVersion model = new DesignModelVersion();
        model.setId(UUID.randomUUID()); model.setProjectId(f.projectId); model.setOwnerUserId(OWNER);
        model.setStatus(DesignVersionStatus.READY_FOR_REVIEW); model.setArtifactKey("test-artifact");
        when(f.modelVersions.findByIdAndOwnerUserId(model.getId(), OWNER)).thenReturn(Optional.of(model));

        f.service.approveModel(OWNER, f.projectId, model.getId(), ApprovalDecision.CHANGES_REQUESTED,
                "increase base", "model-feedback-key");

        assertThat(f.project.getStatus()).isEqualTo(DesignProjectStatus.MODEL_REVIEW);
        assertThat(f.project.getUpdatedAt()).isAfter(oldUpdatedAt);
    }

    @Test
    void referenceReplayStillRequiresProjectOwnership() {
        Fixture f = new Fixture(DesignProjectStatus.DRAFT);
        f.service.enqueueReferences(OWNER, f.projectId, "reference-key", null, null);
        clearInvocations(f.jobs);

        assertCode("DESIGN_PROJECT_NOT_FOUND", () ->
                f.service.enqueueReferences(OWNER + 1, f.projectId, "reference-key", null, null));

        verify(f.jobs, never()).findByOwnerUserIdAndStageAndIdempotencyKey(anyInt(), any(), anyString());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 4);
    }

    @Test
    void replaysModelJobAfterItReachesReview() {
        Fixture f = new Fixture(DesignProjectStatus.REFERENCES_APPROVED);
        var first = f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "model-key", null);
        f.project.setStatus(DesignProjectStatus.MODEL_REVIEW);
        f.savedModel.get().setStatus(DesignVersionStatus.READY_FOR_REVIEW);

        var replay = f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "model-key", null);

        assertThat(replay.jobId()).isEqualTo(first.jobId());
        assertThat(replay.versionId()).isEqualTo(first.versionId());
        assertThat(replay.projectStatus()).isEqualTo("MODEL_REVIEW");
        verify(f.jobs, times(1)).save(any());
        verify(f.modelVersions, times(1)).save(any());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 8);
    }

    @Test
    void replaysModelJobAfterApprovalAndProjectCompletion() {
        Fixture f = new Fixture(DesignProjectStatus.REFERENCES_APPROVED);
        var first = f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "model-key", null);
        f.project.setStatus(DesignProjectStatus.COMPLETED);
        f.savedModel.get().setStatus(DesignVersionStatus.APPROVED);

        var replay = f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "model-key", null);

        assertThat(replay.jobId()).isEqualTo(first.jobId());
        assertThat(replay.versionId()).isEqualTo(first.versionId());
        assertThat(replay.projectStatus()).isEqualTo("COMPLETED");
        verify(f.jobs, times(1)).save(any());
        verify(f.modelVersions, times(1)).save(any());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 8);
    }

    @Test
    void changedModelPayloadWithExistingKeyConflictsBeforeRevisionStateChecks() {
        Fixture f = new Fixture(DesignProjectStatus.REFERENCES_APPROVED);
        f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "model-key", null);
        f.project.setStatus(DesignProjectStatus.MODEL_REVIEW);
        f.savedModel.get().setStatus(DesignVersionStatus.READY_FOR_REVIEW);

        assertCode("DESIGN_IDEMPOTENCY_CONFLICT", () ->
                f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "model-key", "different"));

        verify(f.jobs, times(1)).save(any());
        verify(f.modelVersions, times(1)).save(any());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 8);
    }

    @Test
    void newModelKeyCannotCreateAnotherVersionBeforeCurrentModelIsApproved() {
        Fixture f = new Fixture(DesignProjectStatus.REFERENCES_APPROVED);
        f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "first-key", null);
        f.project.setStatus(DesignProjectStatus.MODEL_REVIEW);
        f.savedModel.get().setStatus(DesignVersionStatus.READY_FOR_REVIEW);

        assertCode("DESIGN_MODEL_REVISION_NOT_ALLOWED", () ->
                f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "new-key", null));

        verify(f.jobs, times(1)).save(any());
        verify(f.modelVersions, times(1)).save(any());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 8);
    }

    @Test
    void modelReplayStillRequiresProjectOwnership() {
        Fixture f = new Fixture(DesignProjectStatus.REFERENCES_APPROVED);
        f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "model-key", null);
        clearInvocations(f.jobs);

        assertCode("DESIGN_PROJECT_NOT_FOUND", () ->
                f.service.enqueueModel(OWNER + 1, f.projectId, f.referenceId, "model-key", null));

        verify(f.jobs, never()).findByOwnerUserIdAndStageAndIdempotencyKey(anyInt(), any(), anyString());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 8);
    }

    @Test
    void rejectsNewModelFromApprovedButSupersededReference() {
        Fixture f = new Fixture(DesignProjectStatus.REFERENCES_APPROVED);
        f.savedReference.set(referenceVersion(f, 2));

        assertCode("DESIGN_REFERENCE_VERSION_NOT_LATEST", () ->
                f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "new-v1-model", null));

        verify(f.jobs, never()).save(any());
        verify(f.modelVersions, never()).save(any());
        verify(f.usage, never()).reserve(any(), anyInt(), anyLong());
    }

    @Test
    void replaysExistingModelJobForSupersededReferenceBeforeLatestReferenceGuard() {
        Fixture f = new Fixture(DesignProjectStatus.REFERENCES_APPROVED);
        var original = f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "historical-model-key", null);
        f.project.setStatus(DesignProjectStatus.REFERENCES_APPROVED);
        f.savedReference.set(referenceVersion(f, 2));

        var replay = f.service.enqueueModel(OWNER, f.projectId, f.referenceId, "historical-model-key", null);

        assertThat(replay.jobId()).isEqualTo(original.jobId());
        assertThat(replay.versionId()).isEqualTo(original.versionId());
        assertThat(replay.projectStatus()).isEqualTo(DesignProjectStatus.REFERENCES_APPROVED.name());
        verify(f.jobs, times(1)).save(any());
        verify(f.modelVersions, times(1)).save(any());
        verify(f.usage, times(1)).reserve(f.savedJob.get().getId(), OWNER, 8);
    }

    private DesignReferenceSetVersion referenceVersion(Fixture fixture, int versionNumber) {
        DesignReferenceSetVersion latest = new DesignReferenceSetVersion();
        latest.setId(UUID.randomUUID()); latest.setReferenceSetId(fixture.setId); latest.setProjectId(fixture.projectId);
        latest.setOwnerUserId(OWNER); latest.setVersionNumber(versionNumber); latest.setStatus(DesignVersionStatus.APPROVED);
        return latest;
    }

    private static void assertCode(String expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DesignProjectException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(expected));
    }

    private static final class Fixture {
        private final DesignProjectRepository projects = mock(DesignProjectRepository.class);
        private final DesignReferenceSetRepository sets = mock(DesignReferenceSetRepository.class);
        private final DesignReferenceSetVersionRepository referenceVersions = mock(DesignReferenceSetVersionRepository.class);
        private final DesignReferenceSetViewRepository views = mock(DesignReferenceSetViewRepository.class);
        private final DesignModelVersionRepository modelVersions = mock(DesignModelVersionRepository.class);
        private final DesignReferenceApprovalRepository referenceApprovals = mock(DesignReferenceApprovalRepository.class);
        private final DesignModelApprovalRepository modelApprovals = mock(DesignModelApprovalRepository.class);
        private final DesignPipelineJobRepository jobs = mock(DesignPipelineJobRepository.class);
        private final DesignUsageReservationService usage = mock(DesignUsageReservationService.class);
        private final DesignUsageLimits limits = new DesignUsageLimits();
        private final UUID projectId = UUID.randomUUID();
        private final UUID setId = UUID.randomUUID();
        private final UUID referenceId = UUID.randomUUID();
        private final DesignProject project = new DesignProject();
        private final DesignReferenceSet set = new DesignReferenceSet();
        private final DesignReferenceSetVersion approvedReferences = new DesignReferenceSetVersion();
        private final AtomicReference<DesignReferenceSetVersion> savedReference = new AtomicReference<>();
        private final AtomicReference<DesignModelVersion> savedModel = new AtomicReference<>();
        private final AtomicReference<DesignPipelineJob> savedJob = new AtomicReference<>();
        private final DesignPipelineCommandService service;

        private Fixture(DesignProjectStatus projectStatus) {
            limits.setReferenceJobUnits(4);
            limits.setModelJobUnits(8);
            limits.setPerJobUnits(8);
            limits.setPerOwnerDailyUnits(100);
            limits.setGlobalDailyUnits(1000);

            project.setId(projectId);
            project.setOwnerUserId(OWNER);
            project.setStatus(projectStatus);
            project.setNormalizedPrompt("normalized prompt");
            project.setPromptProfileVersion("v1");
            set.setId(setId);
            set.setProjectId(projectId);
            set.setOwnerUserId(OWNER);
            approvedReferences.setId(referenceId);
            approvedReferences.setReferenceSetId(setId);
            approvedReferences.setProjectId(projectId);
            approvedReferences.setOwnerUserId(OWNER);
            approvedReferences.setStatus(DesignVersionStatus.APPROVED);
            if (projectStatus == DesignProjectStatus.REFERENCES_APPROVED) savedReference.set(approvedReferences);

            when(projects.findLockedByIdAndOwner(projectId, OWNER)).thenReturn(Optional.of(project));
            when(sets.findByProjectIdAndOwnerUserId(projectId, OWNER)).thenReturn(Optional.of(set));
            when(referenceVersions.findByIdAndOwnerUserId(referenceId, OWNER)).thenReturn(Optional.of(approvedReferences));
            when(referenceVersions.findTopByReferenceSetIdAndOwnerUserIdOrderByVersionNumberDesc(setId, OWNER))
                    .thenAnswer(call -> Optional.ofNullable(savedReference.get()));
            when(referenceVersions.save(any())).thenAnswer(call -> {
                DesignReferenceSetVersion version = call.getArgument(0);
                savedReference.set(version);
                return version;
            });
            when(views.findByReferenceVersionIdAndOwnerUserId(referenceId, OWNER)).thenReturn(List.of(
                    view("front"), view("left"), view("right"), view("back")));
            when(referenceApprovals.findTopByReferenceVersionIdAndOwnerUserIdOrderByCreatedAtDesc(referenceId, OWNER))
                    .thenReturn(Optional.of(approvedReferenceApproval()));
            when(modelVersions.findTopByProjectIdOrderByVersionNumberDesc(projectId))
                    .thenAnswer(call -> Optional.ofNullable(savedModel.get()));
            when(modelVersions.save(any())).thenAnswer(call -> {
                DesignModelVersion model = call.getArgument(0);
                savedModel.set(model);
                return model;
            });
            when(jobs.findByOwnerUserIdAndStageAndIdempotencyKey(eq(OWNER), any(DesignStage.class), anyString()))
                    .thenAnswer(call -> {
                        DesignPipelineJob job = savedJob.get();
                        return job != null && job.getStage() == call.getArgument(1)
                                && job.getIdempotencyKey().equals(call.getArgument(2))
                                ? Optional.of(job) : Optional.empty();
                    });
            when(jobs.save(any())).thenAnswer(call -> {
                DesignPipelineJob job = call.getArgument(0);
                savedJob.set(job);
                return job;
            });

            service = new DesignPipelineCommandService(projects, sets, referenceVersions, views, modelVersions,
                    referenceApprovals, modelApprovals, jobs, usage, limits);
        }

        private DesignReferenceSetView view(String slot) {
            DesignReferenceSetView view = new DesignReferenceSetView();
            view.setReferenceVersionId(referenceId);
            view.setReferenceSetId(setId);
            view.setProjectId(projectId);
            view.setViewSlot(slot);
            view.setImageVersionId(UUID.randomUUID());
            view.setOwnerUserId(OWNER);
            return view;
        }

        private DesignReferenceApproval approvedReferenceApproval() {
            DesignReferenceApproval approval = new DesignReferenceApproval();
            approval.setDecision(ApprovalDecision.APPROVED);
            return approval;
        }
    }
}
