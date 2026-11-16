package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.DesignerModelRequest;
import com.exe.morphia3d.dto.response.ModelResponse;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DesignerModelUploadServiceTest {

    @Mock private MediaValidationService validation;
    @Mock private MediaStorageService storage;
    @Mock private MediaAssetLifecycleService lifecycle;
    @Mock private MediaUploadHeartbeat heartbeat;
    @Mock private DesignerModelPersistenceService persistence;

    private DesignerModelUploadService service;
    private UserPrincipal principal;
    private DesignerModelRequest metadata;
    private List<MultipartFile> images;
    private MultipartFile model;
    private ValidatedMedia validatedImage;
    private ValidatedMedia validatedModel;
    private MediaAssetOperationLease operation;
    private MediaUploadIntent modelIntent;
    private MediaUploadIntent firstImageIntent;
    private MediaUploadIntent secondImageIntent;
    private MediaUploadResult modelResult;
    private MediaUploadResult firstImageResult;
    private MediaUploadResult secondImageResult;
    private final AtomicLong providerCalls = new AtomicLong();

    @BeforeEach
    void setUp() {
        service = new DesignerModelUploadService(validation, storage, lifecycle, heartbeat, persistence);
        principal = new UserPrincipal(User.builder().id(42).role(Role.DESIGNER).build());
        metadata = new DesignerModelRequest();
        metadata.setDesignerId(999);
        metadata.setName("Desk lamp");
        metadata.setSlug("desk-lamp");
        metadata.setFilePath3d("client/model-path");
        metadata.setImages(List.of(com.exe.morphia3d.dto.request.ModelImageRequest.builder()
                .imagePath("client/image-path").isPrimary(true).build()));
        images = List.of(file("first.png", "image/png", new byte[]{1}),
                file("second.webp", "image/webp", new byte[]{2}));
        model = file("model.glb", "model/gltf-binary", new byte[]{3});
        validatedImage = new ValidatedMedia(images.get(0), MediaKind.IMAGE, "png", 1, 2, 2);
        validatedModel = new ValidatedMedia(model, MediaKind.MODEL_3D, "glb", 1, null, null);
        operation = new MediaAssetOperationLease(UUID.randomUUID(), UUID.randomUUID(), "upload-owner",
                List.of(101L, 102L, 103L));
        modelIntent = intent(101, MediaKind.MODEL_3D);
        firstImageIntent = intent(102, MediaKind.IMAGE);
        secondImageIntent = intent(103, MediaKind.IMAGE);
        modelResult = result(modelIntent, "raw", "glb");
        firstImageResult = result(firstImageIntent, "image", "png");
        secondImageResult = result(secondImageIntent, "image", "webp");

        when(validation.validateImages(images)).thenReturn(List.of(validatedImage,
                new ValidatedMedia(images.get(1), MediaKind.IMAGE, "webp", 1, 2, 2)));
        when(validation.validateModel(model)).thenReturn(validatedModel);
        when(lifecycle.createUploadIntents(any(), anyList())).thenReturn(operation);
        when(lifecycle.claimUpload(operation, 101L)).thenReturn(modelIntent);
        when(lifecycle.claimUpload(operation, 102L)).thenReturn(firstImageIntent);
        when(lifecycle.claimUpload(operation, 103L)).thenReturn(secondImageIntent);
        when(heartbeat.during(any(), any())).thenAnswer(invocation -> {
            providerCalls.incrementAndGet();
            return ((java.util.function.Supplier<?>) invocation.getArgument(1)).get();
        });
        when(storage.upload(modelIntent, validatedModel)).thenReturn(modelResult);
        when(storage.upload(firstImageIntent, validatedImage)).thenReturn(firstImageResult);
        when(storage.upload(secondImageIntent, new ValidatedMedia(images.get(1), MediaKind.IMAGE, "webp", 1, 2, 2)))
                .thenReturn(secondImageResult);
        when(persistence.persist(principal, metadata, operation)).thenReturn(ModelResponse.builder().id(700).build());
    }

    @Test
    void validatesAllFilesBeforeFirstUpload() {
        when(validation.validateModel(model)).thenThrow(new MediaValidationException(
                MediaValidationException.Reason.INVALID_GLB));

        assertThatThrownBy(() -> service.create(principal, metadata, images, model))
                .isInstanceOf(MediaValidationException.class);

        assertThat(providerCalls).hasValue(0);
        verifyNoInteractions(storage, lifecycle, heartbeat, persistence);
    }

    @Test
    void uploadsOnlyAfterValidationAndPersistsEveryTrustedProviderResult() {
        ModelResponse expected = ModelResponse.builder().id(700).build();
        when(persistence.persist(principal, metadata, operation)).thenReturn(expected);

        assertThat(service.create(principal, metadata, images, model)).isSameAs(expected);

        var order = inOrder(validation, lifecycle, storage, heartbeat, persistence);
        order.verify(validation).validateImages(images);
        order.verify(validation).validateModel(model);
        order.verify(lifecycle).createUploadIntents(any(), anyList());
        order.verify(storage).upload(modelIntent, validatedModel);
        order.verify(lifecycle).recordUpload(modelIntent, modelResult);
        order.verify(storage).upload(firstImageIntent, validatedImage);
        order.verify(lifecycle).recordUpload(firstImageIntent, firstImageResult);
        order.verify(storage).upload(eq(secondImageIntent), any());
        order.verify(lifecycle).recordUpload(eq(secondImageIntent), eq(secondImageResult));
        order.verify(persistence).persist(principal, metadata, operation);
        assertThat(providerCalls.get()).isEqualTo(3);
    }

    @Test
    void legacyDesignerRequestWithoutSlugStillUploadsAndPersists() {
        metadata.setSlug(null);

        ModelResponse response = service.create(principal, metadata, images, model);

        assertThat(response.getId()).isEqualTo(700);
        verify(lifecycle).createUploadIntents(any(), anyList());
        verify(storage).upload(modelIntent, validatedModel);
        verify(persistence).persist(principal, metadata, operation);
    }

    @Test
    void compensatesModelAfterSecondImageUploadFails() {
        RuntimeException providerFailure = new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
        when(storage.upload(eq(secondImageIntent), any())).thenThrow(providerFailure);

        assertThatThrownBy(() -> service.create(principal, metadata, images, model))
                .isSameAs(providerFailure);

        verify(lifecycle).requestCleanup(operation, operation.mediaAssetIds());
        verify(lifecycle, never()).attach(any(), anyCollection());
        verify(persistence, never()).persist(any(), any(), any());
    }

    @Test
    void compensatesNewAssetsAfterDatabaseSaveFailure() {
        MediaPersistenceException databaseFailure = MediaPersistenceException.rolledBack(
                new IllegalStateException("database save failed"));
        when(persistence.persist(principal, metadata, operation)).thenThrow(databaseFailure);

        assertThatThrownBy(() -> service.create(principal, metadata, images, model)).isSameAs(databaseFailure);

        verify(lifecycle).requestCleanup(operation, operation.mediaAssetIds());
    }

    @Test
    void doesNotDeleteWhenCommitOutcomeIsAmbiguous() {
        MediaPersistenceException uncertainCommit = MediaPersistenceException.outcomeUnknown(
                new IllegalStateException("commit acknowledgment lost"));
        when(persistence.persist(principal, metadata, operation)).thenThrow(uncertainCommit);

        assertThatThrownBy(() -> service.create(principal, metadata, images, model)).isSameAs(uncertainCommit);

        verify(lifecycle, never()).requestCleanup(any(), anyCollection());
    }

    @Test
    void cleanupFailureKeepsOriginalUploadError() {
        RuntimeException uploadFailure = new MediaStorageException(MediaStorageException.Reason.PROVIDER_UNAVAILABLE);
        doThrow(uploadFailure).when(heartbeat).during(eq(modelIntent), any());
        doThrow(new IllegalStateException("lease state unavailable"))
                .when(lifecycle).requestCleanup(operation, operation.mediaAssetIds());

        assertThatThrownBy(() -> service.create(principal, metadata, images, model)).isSameAs(uploadFailure);
    }

    @Test
    void compensatesLocalStreamFailureWithoutReplacingTheOriginalError() {
        RuntimeException streamFailure = new MediaStorageException(MediaStorageException.Reason.INPUT_STREAM_FAILURE);
        when(storage.upload(modelIntent, validatedModel)).thenThrow(streamFailure);

        assertThatThrownBy(() -> service.create(principal, metadata, images, model)).isSameAs(streamFailure);

        verify(lifecycle).requestCleanup(operation, operation.mediaAssetIds());
        verify(persistence, never()).persist(any(), any(), any());
    }

    @Test
    void staleUploadClaimCannotReachProviderAndCleanupFailureDoesNotReviveIt() {
        when(lifecycle.claimUpload(operation, 101L)).thenReturn(null);
        RuntimeException cleanupFailure = new IllegalStateException("cleanup state unavailable");
        doThrow(cleanupFailure).when(lifecycle).requestCleanup(operation, operation.mediaAssetIds());

        assertThatThrownBy(() -> service.create(principal, metadata, images, model))
                .isInstanceOf(MediaUploadLeaseException.class);

        verifyNoInteractions(storage, heartbeat, persistence);
        verify(lifecycle).requestCleanup(operation, operation.mediaAssetIds());
    }

    private MultipartFile file(String filename, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", filename, contentType, bytes);
    }

    private MediaUploadIntent intent(long id, MediaKind kind) {
        return new MediaUploadIntent(id, operation.operationId(), operation.leaseToken(), kind);
    }

    private MediaUploadResult result(MediaUploadIntent intent, String resourceType, String format) {
        String suffix = MediaKind.MODEL_3D == intent.mediaKind() ? "" : "." + format;
        String url = "https://res.cloudinary.com/test-cloud/" + resourceType + "/upload/v1/"
                + intent.publicId() + suffix;
        return new MediaUploadResult(MediaProvider.CLOUDINARY, "test-cloud", "asset-" + intent.mediaAssetId(),
                intent.publicId(), intent.mediaKind(), resourceType, "upload", format, 1L, 1L, url);
    }
}
