package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.AIModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Validates and uploads AI model media, leaving all database attachment to a separate transaction. */
@Service
@RequiredArgsConstructor
public class AIModelUploadService {

    private static final Logger log = LoggerFactory.getLogger(AIModelUploadService.class);

    private final MediaValidationService validation;
    private final MediaStorageService storage;
    private final MediaAssetLifecycleService lifecycle;
    private final MediaUploadHeartbeat heartbeat;
    private final AIModelPersistenceService persistence;

    public ModelResponse create(UserPrincipal principal,
                                AIModelRequest metadata,
                                List<MultipartFile> imageFiles,
                                MultipartFile modelFile) {
        validateMetadata(principal, metadata);
        List<ValidatedMedia> validatedImages = validation.validateImages(imageFiles);
        ValidatedMedia validatedModel = validation.validateModel(modelFile);

        List<ValidatedMedia> orderedMedia = new ArrayList<>(validatedImages.size() + 1);
        orderedMedia.add(validatedModel);
        orderedMedia.addAll(validatedImages);
        List<MediaAssetIntentSpec> specifications = orderedMedia.stream()
                .map(media -> new MediaAssetIntentSpec(media.mediaKind(), media.format(), media.byteCount()))
                .toList();

        MediaAssetOperationLease operation = lifecycle.createUploadIntents(UUID.randomUUID(), specifications);
        try {
            if (operation == null || operation.mediaAssetIds().size() != orderedMedia.size()) {
                throw new MediaUploadLeaseException("Media upload operation returned an invalid asset set");
            }
            for (int index = 0; index < orderedMedia.size(); index++) {
                long assetId = operation.mediaAssetIds().get(index);
                MediaUploadIntent intent = lifecycle.claimUpload(operation, assetId);
                if (intent == null) {
                    throw new MediaUploadLeaseException("Media upload lease is no longer active");
                }
                ValidatedMedia media = orderedMedia.get(index);
                MediaUploadResult result = heartbeat.during(intent, () -> storage.upload(intent, media));
                lifecycle.recordUpload(intent, result);
            }
        } catch (RuntimeException uploadFailure) {
            requestCleanupSafely(operation, uploadFailure);
            throw uploadFailure;
        }

        try {
            return persistence.persist(principal, metadata, operation);
        } catch (MediaPersistenceException persistenceFailure) {
            if (persistenceFailure.isSafeToCompensate()) {
                requestCleanupSafely(operation, persistenceFailure);
            }
            throw persistenceFailure;
        }
    }

    private void validateMetadata(UserPrincipal principal, AIModelRequest metadata) {
        if (principal == null || principal.getId() == null || principal.getId() < 1 || metadata == null
                || exceeds(metadata.getName(), 150)
                || exceeds(metadata.getSlug(), 255)
                || exceeds(metadata.getDescription(), 255)
                || exceeds(metadata.getCategory(), 100)
                || (metadata.getModelParts() != null && metadata.getModelParts().stream().anyMatch(part ->
                    part == null || blankOrExceeds(part.getPartName(), 100)
                            || blankOrExceeds(part.getPartKey(), 100)))) {
            throw new MediaValidationException(MediaValidationException.Reason.MALFORMED_REQUEST);
        }
    }

    private boolean exceeds(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private boolean blankOrExceeds(String value, int maxLength) {
        return value == null || value.isBlank() || exceeds(value, maxLength);
    }

    private void requestCleanupSafely(MediaAssetOperationLease operation, RuntimeException originalFailure) {
        if (operation == null) return;
        try {
            lifecycle.requestCleanup(operation, operation.mediaAssetIds());
        } catch (RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
            log.warn("AI media compensation was deferred (operationId={})", operation.operationId());
        }
    }
}
