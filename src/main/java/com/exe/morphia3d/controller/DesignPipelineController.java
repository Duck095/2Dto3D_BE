package com.exe.morphia3d.controller;

import com.exe.morphia3d.design.*;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai-studio/projects/{projectId}")
public class DesignPipelineController {
    private final DesignPipelineCommandService commands;
    private final DesignPipelineStatusService statusService;
    private final FeatureGate featureGate;
    public DesignPipelineController(DesignPipelineCommandService commands, DesignPipelineStatusService statusService, FeatureGate featureGate) {
        this.commands = commands; this.statusService = statusService; this.featureGate = featureGate;
    }

    @GetMapping("/pipeline")
    public DesignPipelineStatusResponse status(@PathVariable UUID projectId,
                                               @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal);
        return statusService.getForOwner(projectId, principal.getId());
    }

    @PostMapping("/references/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DesignPipelineCommandService.CommandAccepted generateReferences(@PathVariable UUID projectId,
            @Valid @RequestBody(required = false) ReferenceGenerationCommand command,
            @RequestHeader("Idempotency-Key") String key, @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal);
        ReferenceGenerationCommand value = command == null ? new ReferenceGenerationCommand(null, null) : command;
        return commands.enqueueReferences(principal.getId(), projectId, key, value.viewSlot(), value.revisionFeedback());
    }

    @PostMapping("/references/{versionId}/approval")
    public DesignPipelineCommandService.ApprovalResult approveReferences(@PathVariable UUID projectId,
            @PathVariable UUID versionId, @Valid @RequestBody DesignApprovalCommand command,
            @RequestHeader("Idempotency-Key") String key, @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal);
        return commands.approveReferences(principal.getId(), projectId, versionId, command.decision(), command.feedback(), key);
    }

    @PostMapping("/models/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DesignPipelineCommandService.CommandAccepted generateModel(@PathVariable UUID projectId,
            @Valid @RequestBody ModelGenerationCommand command, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal);
        return commands.enqueueModel(principal.getId(), projectId, command.referenceVersionId(), key, command.revisionFeedback());
    }

    @PostMapping("/models/{modelVersionId}/approval")
    public DesignPipelineCommandService.ApprovalResult approveModel(@PathVariable UUID projectId,
            @PathVariable UUID modelVersionId, @Valid @RequestBody DesignApprovalCommand command,
            @RequestHeader("Idempotency-Key") String key, @AuthenticationPrincipal UserPrincipal principal) {
        requireCustomer(principal);
        return commands.approveModel(principal.getId(), projectId, modelVersionId, command.decision(), command.feedback(), key);
    }

    private void requireCustomer(UserPrincipal principal) {
        if (principal == null || principal.getUser() == null
                || (principal.getUser().getRole() != Role.USER && principal.getUser().getRole() != Role.ADMIN)) {
            throw new AccessDeniedException("AI Studio is limited to customer and administrator accounts.");
        }
        featureGate.requireEnabled(FeatureFamily.AI);
    }
}
