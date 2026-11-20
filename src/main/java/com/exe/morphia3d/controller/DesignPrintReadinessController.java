package com.exe.morphia3d.controller;

import com.exe.morphia3d.design.DesignPrintReadinessResponse;
import com.exe.morphia3d.design.DesignPrintReadinessService;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.security.UserPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai-studio/projects/{projectId}/models/{modelVersionId}/print-readiness")
public class DesignPrintReadinessController {
    private final DesignPrintReadinessService readiness;
    private final FeatureGate featureGate;
    public DesignPrintReadinessController(DesignPrintReadinessService readiness, FeatureGate featureGate) {
        this.readiness=readiness; this.featureGate=featureGate;
    }
    @GetMapping
    public ResponseEntity<DesignPrintReadinessResponse> get(@PathVariable UUID projectId, @PathVariable UUID modelVersionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || principal.getUser() == null
                || (principal.getUser().getRole() != Role.USER && principal.getUser().getRole() != Role.ADMIN)) {
            throw new AccessDeniedException("AI Studio is limited to customer and administrator accounts.");
        }
        featureGate.requireEnabled(FeatureFamily.AI);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .body(readiness.get(principal.getId(), projectId, modelVersionId));
    }
}
