package com.exe.morphia3d.design;

import com.exe.morphia3d.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class DesignPipelineStatusService {
    private final DesignProjectRepository projects;
    private final DesignReferenceSetRepository sets;
    private final DesignReferenceSetVersionRepository referenceVersions;
    private final DesignReferenceSetViewRepository views;
    private final DesignModelVersionRepository modelVersions;
    private final DesignPipelineJobRepository jobs;
    public DesignPipelineStatusService(DesignProjectRepository projects, DesignReferenceSetRepository sets,
            DesignReferenceSetVersionRepository referenceVersions, DesignReferenceSetViewRepository views,
            DesignModelVersionRepository modelVersions, DesignPipelineJobRepository jobs) {
        this.projects=projects; this.sets=sets; this.referenceVersions=referenceVersions; this.views=views;
        this.modelVersions=modelVersions; this.jobs=jobs;
    }

    @Transactional(readOnly = true)
    public DesignPipelineStatusResponse getForOwner(UUID projectId, Integer ownerId) {
        var project = projects.findByIdAndOwnerUserId(projectId, ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_PROJECT_NOT_FOUND"));
        var set = sets.findByProjectIdAndOwnerUserId(projectId, ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_REFERENCE_SET_NOT_FOUND"));
        List<DesignPipelineStatusResponse.ReferenceVersion> refs = referenceVersions
                .findByReferenceSetIdAndOwnerUserIdOrderByVersionNumberAsc(set.getId(), ownerId).stream().map(version ->
                        new DesignPipelineStatusResponse.ReferenceVersion(version.getId(), version.getVersionNumber(),
                                version.getStatus().name(), views.findByReferenceVersionIdAndOwnerUserId(version.getId(), ownerId)
                                .stream().map(view -> view.getViewSlot()).sorted(java.util.Comparator.comparingInt(value ->
                                        ReferenceViewSlot.canonicalOrder().stream().map(ReferenceViewSlot::apiValue).toList().indexOf(value))).toList(),
                                version.getParentVersionId(), version.getCreatedAt())).toList();
        List<DesignPipelineStatusResponse.ModelVersion> modelList = modelVersions
                .findByProjectIdAndOwnerUserIdOrderByVersionNumberAsc(projectId, ownerId).stream().map(model ->
                        new DesignPipelineStatusResponse.ModelVersion(model.getId(), model.getVersionNumber(),
                                model.getReferenceVersionId(), model.getStatus().name(), model.getSupersedesModelVersionId(),
                                model.getArtifactKey() != null, model.getCreatedAt())).toList();
        List<DesignPipelineStatusResponse.PipelineJob> jobList = jobs
                .findByProjectIdAndOwnerUserIdOrderByCreatedAtAsc(projectId, ownerId).stream().map(job ->
                        new DesignPipelineStatusResponse.PipelineJob(job.getId(), job.getStage().name(), job.getStatus().name(),
                                job.getSafeErrorCode(), job.getAttemptCount(), job.getCreatedAt(), job.getUpdatedAt())).toList();
        return new DesignPipelineStatusResponse(projectId, project.getStatus().name(), refs, modelList, jobList);
    }
}
