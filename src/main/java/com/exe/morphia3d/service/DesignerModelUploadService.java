package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.DesignerModelRequest;
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

/** Validates, uploads, and coordinates compensation without holding a database transaction over provider I/O. */
@Service
@RequiredArgsConstructor
public class DesignerModelUploadService {

    private static final Logger log = LoggerFactory.getLogger(DesignerModelUploadService.class);

    private final MediaValidationService validation;
    private final MediaStorageService storage;
    private final MediaAssetLifecycleService lifecycle;
    private final MediaUploadHeartbeat heartbeat;
    private final DesignerModelPersistenceService persistence;

    public ModelResponse create(UserPrincipal principal,
                                DesignerModelRequest metadata,
                                List<MultipartFile> imageFiles,
                                MultipartFile modelFile) {
        validateRequest(principal, metadata);
        List<ValidatedMedia> validatedImages = validation.validateImages(imageFiles);
        ValidatedMedia validatedModel = validation.validateModel(modelFile);

        List<ValidatedMedia> orderedMedia = new ArrayList<>(validatedImages.size() + 1);
        orderedMedia.add(validatedModel);
        orderedMedia.addAll(validatedImages);
        List<MediaAssetIntentSpec> specifications = orderedMedia.stream()
                .map(media -> new MediaAssetIntentSpec(media.mediaKind(), media.format(), media.byteCount()))
                .toList();

        MediaAssetOperationLease operation = lifecycle.createUploadIntents(UUID.randomUUID(), specifications);
        if (operation.mediaAssetIds().size() != orderedMedia.size()) {
            throw new MediaUploadLeaseException("Media upload operation returned an invalid asset set");
        }

        try {
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

    private void validateRequest(UserPrincipal principal, DesignerModelRequest metadata) {
        if (principal == null || principal.getId() == null || metadata == null
                || (metadata.getName() != null && metadata.getName().length() > 150)
                || (metadata.getCategory() != null && metadata.getCategory().length() > 100)
                || (metadata.getModelParts() != null && metadata.getModelParts().stream().anyMatch(part ->
                    part == null || part.getPartName() == null || part.getPartName().length() > 100
                            || part.getPartKey() == null || part.getPartKey().length() > 100))) {
            throw new MediaValidationException(MediaValidationException.Reason.MALFORMED_REQUEST);
        }
    }

    private void requestCleanupSafely(MediaAssetOperationLease operation, RuntimeException originalFailure) {
        try {
            lifecycle.requestCleanup(operation, operation.mediaAssetIds());
        } catch (RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
            log.warn("Upload compensation was deferred (operationId={})", operation.operationId());
        }
    }
}
