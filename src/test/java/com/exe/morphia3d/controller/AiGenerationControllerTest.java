package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.request.CreateAiGenerationRequest;
import com.exe.morphia3d.dto.response.AiGenerationJobResponse;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.AiGenerationStatus;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.feature.FeatureFamily;
import com.exe.morphia3d.feature.FeatureGate;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.ai.AiGenerationJobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGenerationControllerTest {
    @Mock private AiGenerationJobService jobs;
    @Mock private FeatureGate featureGate;

    @Test
    void acceptsCreateOnlyThroughServiceAndReturnsPendingJob() {
        UserPrincipal owner = owner();
        CreateAiGenerationRequest request = new CreateAiGenerationRequest("a lamp", "modern", "text");
        UUID id = UUID.randomUUID();
        AiGenerationJobResponse expected = response(id, AiGenerationStatus.PENDING);
        when(jobs.create(eq(owner), eq(request), eq(null), eq("idem-1"))).thenReturn(expected);
        AiGenerationController controller = new AiGenerationController(jobs, featureGate);

        ResponseEntity<AiGenerationJobResponse> result = controller.create(request, null, "idem-1", owner);

        assertThat(result.getStatusCode().value()).isEqualTo(202);
        assertThat(result.getBody()).isEqualTo(expected);
        verify(featureGate).requireEnabled(FeatureFamily.AI);
    }

    @Test
    void historyAndStatusAlwaysPassPrincipalOwnerIdToService() {
        UserPrincipal owner = owner();
        AiGenerationController controller = new AiGenerationController(jobs, featureGate);
        UUID id = UUID.randomUUID();
        when(jobs.findForOwner(id, owner.getId())).thenReturn(response(id, AiGenerationStatus.PENDING));
        when(jobs.history(eq(owner.getId()), any())).thenReturn(new PageImpl<>(
                List.of(response(id, AiGenerationStatus.PENDING)), PageRequest.of(0, 10), 1));

        controller.get(id, owner);
        controller.history(0, 10, owner);

        verify(jobs).findForOwner(id, owner.getId());
        verify(jobs).history(eq(owner.getId()), any());
    }

    private UserPrincipal owner() {
        return new UserPrincipal(User.builder().id(41).role(Role.USER).build());
    }

    private AiGenerationJobResponse response(UUID id, AiGenerationStatus status) {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        return new AiGenerationJobResponse(id, status, now, now, null, null, null, null, null, null);
    }
}
