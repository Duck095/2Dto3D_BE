package com.exe.morphia3d.controller;

import com.exe.morphia3d.design.*;
import com.exe.morphia3d.entity.DesignModelVersion;
import com.exe.morphia3d.entity.DesignProjectInput;
import com.exe.morphia3d.entity.DesignReferenceImageVersion;
import com.exe.morphia3d.entity.DesignReferenceSetVersion;
import com.exe.morphia3d.entity.DesignReferenceSetView;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.repository.*;
import com.exe.morphia3d.security.UserPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai-studio/projects/{projectId}")
public class DesignArtifactController {
    private final DesignProjectRepository projects;
    private final DesignProjectInputRepository inputs;
    private final DesignReferenceSetRepository referenceSets;
    private final DesignReferenceSetVersionRepository referenceVersions;
    private final DesignReferenceSetViewRepository views;
    private final DesignReferenceImageVersionRepository imageVersions;
    private final DesignModelVersionRepository models;
    private final DesignArtifactStore artifacts;
    private final FeatureGate featureGate;

    public DesignArtifactController(DesignProjectRepository projects, DesignProjectInputRepository inputs,
            DesignReferenceSetRepository referenceSets, DesignReferenceSetVersionRepository referenceVersions,
            DesignReferenceSetViewRepository views, DesignReferenceImageVersionRepository imageVersions,
            DesignModelVersionRepository models, DesignArtifactStore artifacts, FeatureGate featureGate) {
        this.projects=projects; this.inputs=inputs; this.referenceSets=referenceSets; this.referenceVersions=referenceVersions;
        this.views=views; this.imageVersions=imageVersions; this.models=models; this.artifacts=artifacts; this.featureGate=featureGate;
    }

    @GetMapping("/inputs/{inputId}/content")
    public ResponseEntity<byte[]> input(@PathVariable UUID projectId, @PathVariable UUID inputId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal); ownedProject(projectId, principal.getId());
        DesignProjectInput input = inputs.findByIdAndProjectIdAndOwnerUserId(inputId, projectId, principal.getId())
                .orElseThrow(() -> notFound());
        return privateBytes(principal.getId(), projectId, input.getArtifactKey(), input.getMimeType(), "input-" + input.getId());
    }

    @GetMapping("/references/{versionId}/views/{slot}")
    public ResponseEntity<byte[]> reference(@PathVariable UUID projectId, @PathVariable UUID versionId,
                                            @PathVariable String slot, @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal); ownedProject(projectId, principal.getId());
        ReferenceViewSlot viewSlot = ReferenceViewSlot.fromApiValue(slot);
        DesignReferenceSetVersion version = referenceVersions.findByIdAndOwnerUserId(versionId, principal.getId())
                .orElseThrow(DesignArtifactController::notFound);
        UUID setId = referenceSets.findByProjectIdAndOwnerUserId(projectId, principal.getId())
                .map(com.exe.morphia3d.entity.DesignReferenceSet::getId).orElseThrow(DesignArtifactController::notFound);
        if (!setId.equals(version.getReferenceSetId())) throw notFound();
        DesignReferenceSetView view = views.findById(new DesignReferenceSetView.Key(versionId, viewSlot.apiValue()))
                .orElseThrow(DesignArtifactController::notFound);
        DesignReferenceImageVersion image = imageVersions.findByIdAndOwnerUserId(view.getImageVersionId(), principal.getId())
                .orElseThrow(DesignArtifactController::notFound);
        if (!projectId.equals(image.getProjectId())) throw notFound();
        return privateBytes(principal.getId(), projectId, image.getArtifactKey(), image.getMimeType(), "reference-" + image.getId());
    }

    @GetMapping("/models/{modelVersionId}/artifact")
    public ResponseEntity<byte[]> model(@PathVariable UUID projectId, @PathVariable UUID modelVersionId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal); ownedProject(projectId, principal.getId());
        DesignModelVersion model = models.findByIdAndOwnerUserId(modelVersionId, principal.getId())
                .filter(value -> value.getProjectId().equals(projectId) && value.getArtifactKey() != null)
                .orElseThrow(DesignArtifactController::notFound);
        return privateBytes(principal.getId(), projectId, model.getArtifactKey(), "model/gltf-binary", "design-" + model.getId() + ".glb");
    }

    private ResponseEntity<byte[]> privateBytes(Integer ownerId, UUID projectId, String key, String mime, String filename) {
        try {
            MediaType mediaType = MediaType.parseMediaType(mime);
            return ResponseEntity.ok().contentType(mediaType).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .body(artifacts.read(ownerId, projectId, key));
        } catch (NoSuchFileException | FileNotFoundException missing) {
            throw notFound();
        } catch (DesignArtifactNotFoundException denied) {
            throw notFound();
        } catch (IOException unavailable) {
            throw new DesignProjectException("DESIGN_ARTIFACT_STORE_UNAVAILABLE");
        } catch (IllegalArgumentException invalidArtifact) {
            throw notFound();
        }
    }

    private void ownedProject(UUID id, Integer ownerId) {
        if (!projects.findByIdAndOwnerUserId(id, ownerId).isPresent()) throw notFound();
    }

    private void requireCustomer(UserPrincipal principal) {
        if (principal == null || principal.getUser() == null
                || (principal.getUser().getRole() != Role.USER && principal.getUser().getRole() != Role.ADMIN)) {
            throw new AccessDeniedException("AI Studio is limited to customer and administrator accounts.");
        }
        featureGate.requireEnabled(FeatureFamily.AI);
    }

    private static DesignProjectException notFound() { return new DesignProjectException("DESIGN_ARTIFACT_NOT_FOUND"); }
}
