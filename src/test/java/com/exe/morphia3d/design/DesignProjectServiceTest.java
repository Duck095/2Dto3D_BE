package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.DesignProject;
import com.exe.morphia3d.repository.DesignProjectInputRepository;
import com.exe.morphia3d.repository.DesignProjectRepository;
import com.exe.morphia3d.repository.DesignReferenceSetVersionRepository;
import com.exe.morphia3d.repository.DesignModelVersionRepository;
import com.exe.morphia3d.repository.DesignReferenceSetRepository;
import com.exe.morphia3d.service.MediaValidationService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignProjectServiceTest {
    @Test
    void createsCanonicalTextProjectAndStoresOriginalIdeaVerbatim() {
        var projects = mock(DesignProjectRepository.class);
        var inputs = mock(DesignProjectInputRepository.class);
        var sets = mock(DesignReferenceSetRepository.class);
        when(projects.findByOwnerUserIdAndCreateIdempotencyKey(17, "once")).thenReturn(Optional.empty());
        when(projects.save(any())).thenAnswer(call -> call.getArgument(0));
        when(inputs.findByProjectIdAndOwnerUserIdOrderByInputOrder(any(), any())).thenReturn(List.of());
        var service = new DesignProjectService(projects, inputs, sets, mock(DesignReferenceSetVersionRepository.class),
                mock(DesignModelVersionRepository.class), new StyleProfileCatalog(),
                new PromptNormalizationService(new StyleProfileCatalog()), mock(MediaValidationService.class),
                new UnconfiguredDesignArtifactStore());
        String idea = "  Bé Mèo — xanh ngọc, resin mờ, chữ ‘Mây’  ";

        DesignProjectResponse response = service.create(17, new CreateDesignProjectRequest("keychain", "ordinary",
                "cute-mascot", "text", idea, null, null), List.of(), "once");

        assertThat(response.productType()).isEqualTo("keychain");
        assertThat(response.keychainVariant()).isEqualTo("ordinary");
        assertThat(response.originalIdea()).isEqualTo(idea);
        assertThat(response.normalizedPrompt()).contains(idea);
        assertThat(response.status()).isEqualTo("DRAFT");
        verify(sets).save(any());
    }

    @Test
    void rejectsTextModeWithFilesBeforeStorageAndDetectsIdempotencyConflicts() {
        var projects = mock(DesignProjectRepository.class);
        var inputs = mock(DesignProjectInputRepository.class);
        var sets = mock(DesignReferenceSetRepository.class);
        var media = mock(MediaValidationService.class);
        var artifacts = mock(DesignArtifactStore.class);
        var service = new DesignProjectService(projects, inputs, sets, mock(DesignReferenceSetVersionRepository.class),
                mock(DesignModelVersionRepository.class), new StyleProfileCatalog(),
                new PromptNormalizationService(new StyleProfileCatalog()), media, artifacts);

        assertThatThrownBy(() -> service.create(17, new CreateDesignProjectRequest("figure", null,
                "chibi-collectible", "text", "idea", null, null), List.of(mock(org.springframework.web.multipart.MultipartFile.class)), "key"))
                .isInstanceOf(DesignProjectException.class);
        verify(projects, never()).save(any());
        verify(media, never()).validateImages(any());

        DesignProject prior = new DesignProject(); prior.setRequestFingerprint("0".repeat(64));
        when(projects.findByOwnerUserIdAndCreateIdempotencyKey(17, "same")).thenReturn(Optional.of(prior));
        assertThatThrownBy(() -> service.create(17, new CreateDesignProjectRequest("figure", null,
                "chibi-collectible", "text", "different idea", null, null), List.of(), "same"))
                .isInstanceOf(DesignProjectException.class)
                .hasMessage("DESIGN_IDEMPOTENCY_CONFLICT");
    }
}
