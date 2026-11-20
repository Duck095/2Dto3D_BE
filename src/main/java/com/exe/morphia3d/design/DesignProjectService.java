package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.DesignProject;
import com.exe.morphia3d.entity.DesignProjectInput;
import com.exe.morphia3d.entity.DesignReferenceSet;
import com.exe.morphia3d.repository.DesignProjectInputRepository;
import com.exe.morphia3d.repository.DesignProjectRepository;
import com.exe.morphia3d.repository.DesignReferenceSetVersionRepository;
import com.exe.morphia3d.repository.DesignReferenceSetRepository;
import com.exe.morphia3d.repository.DesignModelVersionRepository;
import com.exe.morphia3d.service.MediaValidationService;
import com.exe.morphia3d.service.ValidatedMedia;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class DesignProjectService {
    private final DesignProjectRepository projects;
    private final DesignProjectInputRepository inputs;
    private final DesignReferenceSetRepository referenceSets;
    private final DesignReferenceSetVersionRepository referenceVersions;
    private final DesignModelVersionRepository modelVersions;
    private final StyleProfileCatalog catalog;
    private final PromptNormalizationService normalizer;
    private final MediaValidationService mediaValidation;
    private final DesignArtifactStore artifacts;

    public DesignProjectService(DesignProjectRepository projects, DesignProjectInputRepository inputs,
                                DesignReferenceSetRepository referenceSets,
                                DesignReferenceSetVersionRepository referenceVersions,
                                DesignModelVersionRepository modelVersions, StyleProfileCatalog catalog,
                                PromptNormalizationService normalizer, MediaValidationService mediaValidation,
                                DesignArtifactStore artifacts) {
        this.projects = projects; this.inputs = inputs; this.referenceSets = referenceSets;
        this.referenceVersions = referenceVersions; this.modelVersions = modelVersions;
        this.catalog = catalog; this.normalizer = normalizer; this.mediaValidation = mediaValidation;
        this.artifacts = artifacts;
    }

    @Transactional
    public DesignProjectResponse create(Integer ownerId, CreateDesignProjectRequest request,
                                        List<MultipartFile> uploads, String idempotencyKey) {
        if (ownerId == null || ownerId <= 0) throw new DesignProjectException("DESIGN_AUTH_REQUIRED");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new DesignProjectException("DESIGN_INVALID_IDEMPOTENCY_KEY");
        }
        if (request == null) throw new DesignProjectException("DESIGN_INVALID_REQUEST");
        ProductType productType = ProductType.fromApiValue(request.productType());
        KeychainVariant variant = request.keychainVariant() == null || request.keychainVariant().isBlank()
                ? null : KeychainVariant.fromApiValue(request.keychainVariant());
        DesignInputMode mode = DesignInputMode.fromApiValue(request.inputMode());
        StyleProfile style = catalog.require(productType, variant, request.styleId());
        List<MultipartFile> files = uploads == null ? List.of() : List.copyOf(uploads);
        validateInputMode(mode, request.originalIdea(), files);
        List<ValidatedMedia> validated = files.isEmpty() ? List.of() : mediaValidation.validateImages(files);
        validateHints(request, files.size());
        List<byte[]> content = validated.stream().map(this::bytes).toList();
        String fingerprint = fingerprint(request, productType, variant, mode, style, validated, content);
        var existing = projects.findByOwnerUserIdAndCreateIdempotencyKey(ownerId, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestFingerprint().equals(fingerprint)) {
                throw new DesignProjectException("DESIGN_IDEMPOTENCY_CONFLICT");
            }
            return response(existing.get(), inputs.findByProjectIdAndOwnerUserIdOrderByInputOrder(existing.get().getId(), ownerId));
        }

        NormalizedPrompt normalized = normalizer.normalize(request.originalIdea(), productType, variant, style.styleId());
        UUID projectId = UUID.randomUUID();
        DesignProject project = new DesignProject();
        project.setId(projectId); project.setOwnerUserId(ownerId); project.setProductType(productType.apiValue());
        project.setKeychainVariant(variant == null ? null : variant.apiValue()); project.setStyleId(style.styleId());
        project.setInputMode(mode.apiValue()); project.setOriginalIdea(request.originalIdea());
        project.setNormalizedPrompt(normalized.normalizedText()); project.setPromptProfileVersion(normalized.profileVersion());
        project.setStatus(DesignProjectStatus.DRAFT); project.setCreateIdempotencyKey(idempotencyKey);
        project.setRequestFingerprint(fingerprint);

        List<String> storedKeys = new ArrayList<>();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        storedKeys.forEach(key -> { try { artifacts.delete(ownerId, projectId, key); } catch (IOException ignored) { } });
                    }
                }
            });
        }
        try {
            projects.save(project);
            DesignReferenceSet set = new DesignReferenceSet();
            set.setId(UUID.randomUUID()); set.setProjectId(projectId); set.setOwnerUserId(ownerId);
            referenceSets.save(set);
            for (int i = 0; i < validated.size(); i++) {
                ValidatedMedia media = validated.get(i);
                String mime = "image/" + (media.format().equals("jpg") ? "jpeg" : media.format());
                DesignArtifactDescriptor stored = artifacts.put(ownerId, projectId, mime, content.get(i));
                storedKeys.add(stored.artifactKey());
                DesignProjectInput input = new DesignProjectInput();
                input.setId(UUID.randomUUID()); input.setProjectId(projectId); input.setOwnerUserId(ownerId);
                input.setInputOrder(i); input.setArtifactKey(stored.artifactKey()); input.setMimeType(stored.mimeType());
                input.setByteSize(stored.byteSize()); input.setSha256(stored.sha256());
                input.setClientSlot(request.clientSlots() == null ? null : request.clientSlots().get(i));
                input.setViewHint(request.viewHints() == null ? null : request.viewHints().get(i));
                inputs.save(input);
            }
            return response(project, inputs.findByProjectIdAndOwnerUserIdOrderByInputOrder(projectId, ownerId));
        } catch (IOException failure) {
            storedKeys.forEach(key -> { try { artifacts.delete(ownerId, projectId, key); } catch (IOException ignored) { } });
            throw new DesignProjectException("DESIGN_ARTIFACT_STORE_UNAVAILABLE");
        } catch (RuntimeException failure) {
            storedKeys.forEach(key -> { try { artifacts.delete(ownerId, projectId, key); } catch (IOException ignored) { } });
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public DesignProjectResponse getForOwner(UUID projectId, Integer ownerId) {
        DesignProject project = projects.findByIdAndOwnerUserId(projectId, ownerId)
                .orElseThrow(() -> new DesignProjectException("DESIGN_PROJECT_NOT_FOUND"));
        return response(project, inputs.findByProjectIdAndOwnerUserIdOrderByInputOrder(projectId, ownerId));
    }

    @Transactional(readOnly = true)
    public DesignProjectSummaryResponse.Page history(Integer ownerId, int page, int size) {
        if (ownerId == null || ownerId <= 0) throw new DesignProjectException("DESIGN_AUTH_REQUIRED");
        if (page < 0 || size < 1 || size > 100) throw new DesignProjectException("DESIGN_INVALID_PAGINATION");
        Page<DesignProject> projectPage = projects.findByOwnerUserId(ownerId, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))));
        List<UUID> projectIds = projectPage.getContent().stream().map(DesignProject::getId).toList();
        java.util.Map<UUID, UUID> latestReferences = new java.util.HashMap<>();
        java.util.Map<UUID, UUID> latestModels = new java.util.HashMap<>();
        if (!projectIds.isEmpty()) {
            referenceVersions.findLatestByProjectIdsAndOwnerUserId(ownerId, projectIds)
                    .forEach(version -> latestReferences.put(version.getProjectId(), version.getId()));
            modelVersions.findLatestByProjectIdsAndOwnerUserId(ownerId, projectIds)
                    .forEach(version -> latestModels.put(version.getProjectId(), version.getId()));
        }
        List<DesignProjectSummaryResponse> content = projectPage.getContent().stream().map(project ->
                new DesignProjectSummaryResponse(project.getId(), project.getProductType(), project.getKeychainVariant(),
                        project.getStyleId(), project.getInputMode(), project.getOriginalIdea(), project.getStatus().name(),
                        project.getCreatedAt(), project.getUpdatedAt(),
                        latestReferences.get(project.getId()), latestModels.get(project.getId()))).toList();
        return new DesignProjectSummaryResponse.Page(content, projectPage.getNumber(), projectPage.getSize(),
                projectPage.getTotalElements(), projectPage.getTotalPages(), projectPage.isFirst(), projectPage.isLast());
    }

    private void validateInputMode(DesignInputMode mode, String idea, List<MultipartFile> files) {
        switch (mode) {
            case TEXT -> {
                if (idea == null || idea.isBlank() || !files.isEmpty()) throw new DesignProjectException("DESIGN_INPUT_MODE_MISMATCH");
            }
            case IMAGE -> { if (files.size() != 1) throw new DesignProjectException("DESIGN_INPUT_MODE_MISMATCH"); }
            case MULTI -> { if (files.isEmpty() || files.size() > 8) throw new DesignProjectException("DESIGN_INPUT_MODE_MISMATCH"); }
        }
        if (idea != null && idea.length() > 4000) throw new DesignProjectException("DESIGN_IDEA_TOO_LONG");
    }

    private void validateHints(CreateDesignProjectRequest request, int count) {
        if (request.clientSlots() != null && request.clientSlots().size() != count) throw new DesignProjectException("DESIGN_INPUT_METADATA_MISMATCH");
        if (request.viewHints() != null && request.viewHints().size() != count) throw new DesignProjectException("DESIGN_INPUT_METADATA_MISMATCH");
        if (request.clientSlots() != null && request.clientSlots().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new DesignProjectException("DESIGN_INPUT_METADATA_MISMATCH");
        }
        if (request.viewHints() != null) for (String value : request.viewHints()) {
            if (value != null) {
                if (value.isBlank()) throw new DesignProjectException("DESIGN_INPUT_METADATA_MISMATCH");
                ReferenceViewSlot.fromApiValue(value);
            }
        }
    }

    private byte[] bytes(ValidatedMedia media) {
        try { return media.file().getBytes(); }
        catch (IOException exception) { throw new DesignProjectException("DESIGN_INPUT_READ_FAILED"); }
    }

    private String fingerprint(CreateDesignProjectRequest request, ProductType productType, KeychainVariant variant,
                               DesignInputMode mode, StyleProfile style, List<ValidatedMedia> validated, List<byte[]> content) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, productType.apiValue()); write(out, variant == null ? null : variant.apiValue());
            write(out, style.styleId()); write(out, mode.apiValue()); write(out, request.originalIdea());
            for (int i = 0; i < validated.size(); i++) {
                write(out, request.clientSlots() == null ? null : request.clientSlots().get(i));
                write(out, request.viewHints() == null ? null : request.viewHints().get(i));
                write(out, sha256(content.get(i)));
            }
            out.flush(); return sha256(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private void write(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private DesignProjectResponse response(DesignProject project, List<DesignProjectInput> projectInputs) {
        List<DesignProjectResponse.InputSummary> summaries = projectInputs.stream().map(input ->
                new DesignProjectResponse.InputSummary(input.getId(), input.getInputOrder(), input.getMimeType(),
                        input.getByteSize(), input.getSha256(), input.getClientSlot(), input.getViewHint())).toList();
        return new DesignProjectResponse(project.getId(), project.getProductType(), project.getKeychainVariant(),
                project.getStyleId(), project.getInputMode(), project.getOriginalIdea(), project.getNormalizedPrompt(),
                project.getPromptProfileVersion(), project.getStatus().name(), project.getCreatedAt(), summaries);
    }
}
