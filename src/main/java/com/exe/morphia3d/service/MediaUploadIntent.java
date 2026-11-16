package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.MediaKind;

import java.util.Objects;
import java.util.UUID;

/** Server-only upload intent. Provider identity is derived from persisted IDs, never accepted from a client. */
public record MediaUploadIntent(long mediaAssetId, UUID operationId, UUID leaseToken, MediaKind mediaKind) {

    public MediaUploadIntent {
        if (mediaAssetId <= 0) {
            throw new IllegalArgumentException("media asset ID must be positive");
        }
        Objects.requireNonNull(operationId, "operation ID is required");
        Objects.requireNonNull(leaseToken, "lease token is required");
        Objects.requireNonNull(mediaKind, "media kind is required");
    }

    public static MediaUploadIntent create(long mediaAssetId, UUID operationId, UUID leaseToken, MediaKind mediaKind) {
        return new MediaUploadIntent(mediaAssetId, operationId, leaseToken, mediaKind);
    }

    public String publicId() {
        String folder = mediaKind == MediaKind.IMAGE ? "images" : "models";
        return "product-media/" + folder + "/" + operationId + "/" + mediaAssetId;
    }

    public String resourceType() {
        return "image";
    }

    public String deliveryType() {
        return "upload";
    }
}
