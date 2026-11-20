package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.DesignProject;
import com.exe.morphia3d.repository.DesignModelVersionRepository;
import com.exe.morphia3d.repository.DesignProjectInputRepository;
import com.exe.morphia3d.repository.DesignProjectRepository;
import com.exe.morphia3d.repository.DesignReferenceSetRepository;
import com.exe.morphia3d.repository.DesignReferenceSetVersionRepository;
import com.exe.morphia3d.service.MediaValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignProjectHistoryServiceTest {
    @Test
    void returnsOwnerScopedHistoryWithStableUpdatedAtOrderingAndLatestVersions() {
        var projects = mock(DesignProjectRepository.class);
        var inputs = mock(DesignProjectInputRepository.class);
        var sets = mock(DesignReferenceSetRepository.class);
        var referenceVersions = mock(DesignReferenceSetVersionRepository.class);
        var modelVersions = mock(DesignModelVersionRepository.class);
        UUID projectId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        DesignProject project = project(projectId, Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-03T00:00:00Z"));
        project.setKeychainVariant("ordinary");
        project.setInputMode("multi");
        when(projects.findByOwnerUserId(eq(17), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(project), PageRequest.of(0, 1), 1));
        when(referenceVersions.findLatestByProjectIdsAndOwnerUserId(17, List.of(projectId)))
                .thenReturn(List.of(referenceVersion(projectId, referenceId)));
        when(modelVersions.findLatestByProjectIdsAndOwnerUserId(17, List.of(projectId)))
                .thenReturn(List.of(modelVersion(projectId, modelId)));

        var service = service(projects, inputs, sets, referenceVersions, modelVersions);
        var history = service.history(17, 0, 1);

        assertThat(history.content()).singleElement().satisfies(summary -> {
            assertThat(summary.projectId()).isEqualTo(projectId);
            assertThat(summary.productType()).isEqualTo("figure");
            assertThat(summary.keychainVariant()).isEqualTo("ordinary");
            assertThat(summary.styleId()).isEqualTo("cute");
            assertThat(summary.inputMode()).isEqualTo("multi");
            assertThat(summary.originalIdea()).isEqualTo("small lamp");
            assertThat(summary.latestReferenceVersionId()).isEqualTo(referenceId);
            assertThat(summary.latestModelVersionId()).isEqualTo(modelId);
        });
        assertThat(history.totalElements()).isEqualTo(1);
        org.mockito.ArgumentCaptor<Pageable> pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(projects).findByOwnerUserId(eq(17), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
        assertThat(pageable.getValue().getSort()).containsExactly(
                new Sort.Order(Sort.Direction.DESC, "updatedAt"), new Sort.Order(Sort.Direction.DESC, "id"));
        verify(inputs, never()).findByProjectIdAndOwnerUserIdOrderByInputOrder(any(), any());
    }

    @Test
    void emptyHistoryDoesNotIssueLatestVersionQueries() {
        var projects = mock(DesignProjectRepository.class);
        var inputs = mock(DesignProjectInputRepository.class);
        var sets = mock(DesignReferenceSetRepository.class);
        var referenceVersions = mock(DesignReferenceSetVersionRepository.class);
        var modelVersions = mock(DesignModelVersionRepository.class);
        when(projects.findByOwnerUserId(eq(17), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        var history = service(projects, inputs, sets, referenceVersions, modelVersions).history(17, 0, 20);

        assertThat(history.content()).isEmpty();
        assertThat(history.totalElements()).isZero();
        verify(referenceVersions, never()).findLatestByProjectIdsAndOwnerUserId(any(), any());
        verify(modelVersions, never()).findLatestByProjectIdsAndOwnerUserId(any(), any());
    }

    @Test
    void acceptsMaximumPageSizeOfOneHundred() {
        var projects = mock(DesignProjectRepository.class);
        var inputs = mock(DesignProjectInputRepository.class);
        var referenceVersions = mock(DesignReferenceSetVersionRepository.class);
        var modelVersions = mock(DesignModelVersionRepository.class);
        when(projects.findByOwnerUserId(eq(17), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        var history = service(projects, inputs, mock(DesignReferenceSetRepository.class), referenceVersions,
                modelVersions).history(17, 0, 100);

        assertThat(history.size()).isEqualTo(100);
        org.mockito.ArgumentCaptor<Pageable> pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(projects).findByOwnerUserId(eq(17), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void mapsProjectRowsAcrossHistoryStatusesAndInputModesWithoutExpandingVersions() {
        var projects = mock(DesignProjectRepository.class);
        var referenceVersions = mock(DesignReferenceSetVersionRepository.class);
        var modelVersions = mock(DesignModelVersionRepository.class);
        List<DesignProjectStatus> statuses = List.of(DesignProjectStatus.DRAFT, DesignProjectStatus.REFERENCES_QUEUED,
                DesignProjectStatus.REFERENCES_GENERATING, DesignProjectStatus.REFERENCES_REVIEW,
                DesignProjectStatus.REFERENCES_APPROVED, DesignProjectStatus.MODEL_QUEUED,
                DesignProjectStatus.MODEL_GENERATING, DesignProjectStatus.MODEL_REVIEW,
                DesignProjectStatus.COMPLETED, DesignProjectStatus.FAILED);
        List<String> modes = List.of("text", "image", "multi", "text", "image", "multi", "text", "image", "multi", "text");
        List<DesignProject> rows = java.util.stream.IntStream.range(0, statuses.size()).mapToObj(index -> {
            DesignProject row = project(UUID.randomUUID(), Instant.parse("2026-11-01T00:00:00Z"),
                    Instant.parse("2026-11-03T00:00:00Z"));
            row.setStatus(statuses.get(index));
            row.setInputMode(modes.get(index));
            row.setOriginalIdea("idea-" + index);
            if (index == 0) {
                row.setProductType("keychain");
                row.setKeychainVariant("click");
            }
            return row;
        }).toList();
        when(projects.findByOwnerUserId(eq(17), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 100), rows.size()));

        var history = service(projects, mock(DesignProjectInputRepository.class),
                mock(DesignReferenceSetRepository.class), referenceVersions, modelVersions).history(17, 0, 100);

        assertThat(history.content()).hasSize(10);
        assertThat(history.content()).extracting(DesignProjectSummaryResponse::status)
                .containsExactlyElementsOf(statuses.stream().map(Enum::name).toList());
        assertThat(history.content()).extracting(DesignProjectSummaryResponse::inputMode)
                .containsExactlyElementsOf(modes);
        assertThat(history.content()).extracting(DesignProjectSummaryResponse::originalIdea)
                .containsExactly("idea-0", "idea-1", "idea-2", "idea-3", "idea-4", "idea-5", "idea-6", "idea-7", "idea-8", "idea-9");
        assertThat(history.content().get(0).keychainVariant()).isEqualTo("click");
        assertThat(history.content().get(1).keychainVariant()).isNull();
    }

    @Test
    void rejectsInvalidPageAndSizeBoundaries() {
        var service = service(mock(DesignProjectRepository.class), mock(DesignProjectInputRepository.class),
                mock(DesignReferenceSetRepository.class), mock(DesignReferenceSetVersionRepository.class),
                mock(DesignModelVersionRepository.class));

        assertThatThrownBy(() -> service.history(17, -1, 20))
                .isInstanceOf(DesignProjectException.class).hasMessage("DESIGN_INVALID_PAGINATION");
        assertThatThrownBy(() -> service.history(17, 0, 0))
                .isInstanceOf(DesignProjectException.class).hasMessage("DESIGN_INVALID_PAGINATION");
        assertThatThrownBy(() -> service.history(17, 0, 101))
                .isInstanceOf(DesignProjectException.class).hasMessage("DESIGN_INVALID_PAGINATION");
    }

    private DesignProjectService service(DesignProjectRepository projects, DesignProjectInputRepository inputs,
            DesignReferenceSetRepository sets, DesignReferenceSetVersionRepository references,
            DesignModelVersionRepository models) {
        var catalog = new StyleProfileCatalog();
        return new DesignProjectService(projects, inputs, sets, references, models, catalog,
                new PromptNormalizationService(catalog), mock(MediaValidationService.class), new UnconfiguredDesignArtifactStore());
    }

    private DesignProject project(UUID id, Instant createdAt, Instant updatedAt) {
        DesignProject project = new DesignProject();
        project.setId(id); project.setOwnerUserId(17); project.setProductType("figure"); project.setStyleId("cute");
        project.setInputMode("text"); project.setOriginalIdea("small lamp"); project.setStatus(DesignProjectStatus.REFERENCES_REVIEW);
        project.setCreatedAt(createdAt); project.setUpdatedAt(updatedAt);
        return project;
    }

    private com.exe.morphia3d.entity.DesignReferenceSetVersion referenceVersion(UUID projectId, UUID id) {
        var version = new com.exe.morphia3d.entity.DesignReferenceSetVersion();
        version.setProjectId(projectId); version.setId(id); return version;
    }

    private com.exe.morphia3d.entity.DesignModelVersion modelVersion(UUID projectId, UUID id) {
        var version = new com.exe.morphia3d.entity.DesignModelVersion();
        version.setProjectId(projectId); version.setId(id); return version;
    }
}
