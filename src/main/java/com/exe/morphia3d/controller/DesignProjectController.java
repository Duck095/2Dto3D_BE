package com.exe.morphia3d.controller;

import com.exe.morphia3d.design.CreateDesignProjectRequest;
import com.exe.morphia3d.design.DesignProjectResponse;
import com.exe.morphia3d.design.DesignProjectService;
import com.exe.morphia3d.design.DesignProjectTimelineService;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai-studio/projects")
public class DesignProjectController {
    private final DesignProjectService projects;
    private final DesignProjectTimelineService timeline;
    private final FeatureGate featureGate;
    public DesignProjectController(DesignProjectService projects, DesignProjectTimelineService timeline, FeatureGate featureGate) {
        this.projects = projects; this.timeline = timeline; this.featureGate = featureGate;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DesignProjectResponse create(@Valid @RequestPart("data") CreateDesignProjectRequest request,
                                        @RequestPart(value = "images", required = false) List<MultipartFile> images,
                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal); featureGate.requireEnabled(FeatureFamily.AI);
        return projects.create(principal.getId(), request, images, idempotencyKey);
    }

    @GetMapping("/{projectId}")
    public DesignProjectResponse get(@PathVariable UUID projectId,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal); featureGate.requireEnabled(FeatureFamily.AI);
        return projects.getForOwner(projectId, principal.getId());
    }

    @GetMapping
    public com.exe.morphia3d.design.DesignProjectSummaryResponse.Page history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal); featureGate.requireEnabled(FeatureFamily.AI);
        return projects.history(principal.getId(), page, size);
    }

    @GetMapping("/{projectId}/timeline")
    public com.exe.morphia3d.design.DesignProjectTimelineResponse timeline(@PathVariable UUID projectId,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal); featureGate.requireEnabled(FeatureFamily.AI);
        return timeline.getForOwner(projectId, principal.getId());
    }

    private void requireCustomer(UserPrincipal principal) {
        if (principal == null || principal.getUser() == null
                || (principal.getUser().getRole() != Role.USER && principal.getUser().getRole() != Role.ADMIN)) {
            throw new AccessDeniedException("AI Studio is limited to customer and administrator accounts.");
        }
    }
}
