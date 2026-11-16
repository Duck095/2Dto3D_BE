package com.exe.morphia3d.service.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.cloudinary.utils.ObjectUtils;
import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.enums.MediaProvider;
import com.exe.morphia3d.service.DeleteResult;
import com.exe.morphia3d.service.MediaAssetIdentity;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.service.MediaStorageProperties;
import com.exe.morphia3d.service.MediaStorageService;
import com.exe.morphia3d.service.MediaUploadIntent;
import com.exe.morphia3d.service.MediaUploadResult;
import com.exe.morphia3d.service.ValidatedMedia;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;

import static com.exe.morphia3d.service.MediaStorageException.Reason.IDENTITY_MISMATCH;
import static com.exe.morphia3d.service.MediaStorageException.Reason.INPUT_STREAM_FAILURE;
import static com.exe.morphia3d.service.MediaStorageException.Reason.INVALID_PROVIDER_RESPONSE;
import static com.exe.morphia3d.service.MediaStorageException.Reason.PROVIDER_UNAVAILABLE;
import static com.exe.morphia3d.service.MediaStorageException.Reason.UNSUPPORTED_IDENTITY;

@Service
@RequiredArgsConstructor
public class CloudinaryMediaStorageService implements MediaStorageService {

    private static final String CLOUDINARY_DELIVERY_HOST = "res.cloudinary.com";

    private final Cloudinary cloudinary;
    private final MediaStorageProperties properties;

    @Override
    public MediaUploadResult upload(MediaUploadIntent intent, ValidatedMedia media) {
        validateRequest(intent, media);
        properties.validate();

        InputStream multipartInput;
        try {
            multipartInput = media.file().getInputStream();
        } catch (IOException | RuntimeException exception) {
            throw new MediaStorageException(INPUT_STREAM_FAILURE);
        }

        try (InputStream input = new LocalMediaInputStream(multipartInput)) {
            Map<?, ?> response;
            try {
                response = cloudinary.uploader().upload(input, uploadOptions(intent));
            } catch (IOException exception) {
                if (hasLocalReadFailure(exception)) {
                    throw new MediaStorageException(INPUT_STREAM_FAILURE);
                }
                throw new MediaStorageException(PROVIDER_UNAVAILABLE);
            }
            return parseUploadResponse(intent, media, response);
        } catch (MediaStorageException exception) {
            throw exception;
        } catch (LocalMediaReadException exception) {
            throw new MediaStorageException(INPUT_STREAM_FAILURE);
        } catch (IOException exception) {
            // The wrapped stream is the only other checked-I/O source in this block.
            throw new MediaStorageException(INPUT_STREAM_FAILURE);
        } catch (RuntimeException exception) {
            throw new MediaStorageException(PROVIDER_UNAVAILABLE);
        }
    }

    @Override
    public DeleteResult delete(MediaAssetIdentity identity) {
        validateDeleteIdentity(identity);
        properties.validate();

        try {
            Map<?, ?> response = cloudinary.uploader().destroy(identity.publicId(), deleteOptions(identity));
            String result = stringValue(response == null ? null : response.get("result"));
            if ("ok".equalsIgnoreCase(result)) {
                return DeleteResult.DELETED;
            }
            if ("not found".equalsIgnoreCase(result)) {
                return DeleteResult.NOT_FOUND;
            }
            throw new MediaStorageException(INVALID_PROVIDER_RESPONSE);
        } catch (MediaStorageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MediaStorageException(PROVIDER_UNAVAILABLE);
        }
    }

    private void validateRequest(MediaUploadIntent intent, ValidatedMedia media) {
        if (intent == null || media == null || media.file() == null
                || intent.mediaKind() != media.mediaKind()
                || media.byteCount() <= 0 || media.file().getSize() != media.byteCount()
                || !isExpectedFormat(intent.mediaKind(), media.format())) {
            throw new MediaStorageException(UNSUPPORTED_IDENTITY);
        }
    }

    private Map<String, Object> uploadOptions(MediaUploadIntent intent) {
        return ObjectUtils.asMap(
                "public_id", intent.publicId(),
                "resource_type", intent.resourceType(),
                "type", intent.deliveryType(),
                "overwrite", false,
                "use_filename", false,
                "unique_filename", false,
                "connect_timeout", properties.getConnectTimeoutMillis(),
                "connection_request_timeout", properties.getConnectionRequestTimeoutMillis(),
                "timeout", properties.getRequestTimeoutMillis());
    }

    private MediaUploadResult parseUploadResponse(MediaUploadIntent intent, ValidatedMedia media, Map<?, ?> response) {
        if (response == null) {
            throw new MediaStorageException(INVALID_PROVIDER_RESPONSE);
        }

        String providerAssetId = requiredString(response, "asset_id");
        String publicId = requiredString(response, "public_id");
        String resourceType = requiredString(response, "resource_type");
        String deliveryType = requiredString(response, "type");
        String format = requiredString(response, "format").toLowerCase(Locale.ROOT);
        long byteCount = requiredLong(response, "bytes");
        long version = requiredLong(response, "version");
        String secureUrl = requiredString(response, "secure_url");

        String responseCloudName = stringValue(response.get("cloud_name"));
        if (responseCloudName != null && !cloudName().equals(responseCloudName)) {
            throw new MediaStorageException(IDENTITY_MISMATCH);
        }
        if (!intent.publicId().equals(publicId)
                || !intent.resourceType().equals(resourceType)
                || !intent.deliveryType().equals(deliveryType)
                || !formatsMatch(media.format(), format, intent.mediaKind())
                || byteCount != media.byteCount()
                || version <= 0) {
            throw new MediaStorageException(IDENTITY_MISMATCH);
        }
        validateSecureUrl(secureUrl, intent, resourceType, deliveryType, format, version);

        return new MediaUploadResult(MediaProvider.CLOUDINARY, cloudName(), providerAssetId, publicId,
                intent.mediaKind(), resourceType, deliveryType, format, byteCount, version, secureUrl);
    }

    private void validateSecureUrl(String value, MediaUploadIntent intent, String resourceType,
                                   String deliveryType, String format, long version) {
        try {
            URI uri = new URI(value);
            String publicIdPath = intent.mediaKind() == MediaKind.MODEL_3D
                    ? intent.publicId() : intent.publicId() + "." + format;
            String expectedPath = "/" + cloudName() + "/" + resourceType + "/" + deliveryType
                    + "/v" + version + "/" + publicIdPath;
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !CLOUDINARY_DELIVERY_HOST.equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !expectedPath.equals(uri.getPath())) {
                throw new MediaStorageException(IDENTITY_MISMATCH);
            }
        } catch (URISyntaxException exception) {
            throw new MediaStorageException(INVALID_PROVIDER_RESPONSE);
        }
    }

    private Map<String, Object> deleteOptions(MediaAssetIdentity identity) {
        return ObjectUtils.asMap(
                "resource_type", identity.resourceType(),
                "type", identity.deliveryType(),
                "connect_timeout", properties.getConnectTimeoutMillis(),
                "connection_request_timeout", properties.getConnectionRequestTimeoutMillis(),
                "timeout", properties.getRequestTimeoutMillis());
    }

    private void validateDeleteIdentity(MediaAssetIdentity identity) {
        if (identity == null || identity.provider() != MediaProvider.CLOUDINARY
                || !cloudName().equals(identity.cloudName())
                || !expectedResourceType(identity.mediaKind()).equals(identity.resourceType())
                || !isSupportedDeliveryType(identity.deliveryType())
                || !isServerGeneratedPublicId(identity.publicId(), identity.mediaKind())) {
            throw new MediaStorageException(UNSUPPORTED_IDENTITY);
        }
    }

    private boolean isServerGeneratedPublicId(String publicId, MediaKind kind) {
        String[] segments = publicId.split("/", -1);
        String expectedFolder = kind == MediaKind.IMAGE ? "images" : "models";
        if (segments.length != 4 || !"product-media".equals(segments[0])
                || !expectedFolder.equals(segments[1])) {
            return false;
        }
        try {
            if (!segments[2].equals(java.util.UUID.fromString(segments[2]).toString())) {
                return false;
            }
        } catch (IllegalArgumentException exception) {
            return false;
        }

        String assetId = segments[3];
        if (kind == MediaKind.MODEL_3D) {
            if (assetId.endsWith(".glb")) {
                assetId = assetId.substring(0, assetId.length() - ".glb".length());
            } else if (assetId.endsWith(".stl")) {
                assetId = assetId.substring(0, assetId.length() - ".stl".length());
            } else {
                return false;
            }
        } else if (assetId.contains(".")) {
            return false;
        }
        try {
            return Long.parseLong(assetId) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean isExpectedFormat(MediaKind kind, String format) {
        if (format == null) {
            return false;
        }
        String normalized = normalizeFormat(format);
        if (kind == MediaKind.MODEL_3D) {
            return "glb".equals(normalized) || "stl".equals(normalized);
        }
        return "jpeg".equals(normalized) || "png".equals(normalized) || "webp".equals(normalized);
    }

    private boolean formatsMatch(String expected, String returned, MediaKind kind) {
        if (!isExpectedFormat(kind, expected) || !isExpectedFormat(kind, returned)) {
            return false;
        }
        return normalizeFormat(expected).equals(normalizeFormat(returned));
    }

    private String normalizeFormat(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "jpg".equals(normalized) ? "jpeg" : normalized;
    }

    private String expectedResourceType(MediaKind kind) {
        return "image";
    }

    private boolean isSupportedDeliveryType(String type) {
        return "upload".equals(type) || "private".equals(type) || "authenticated".equals(type);
    }

    private String requiredString(Map<?, ?> response, String key) {
        String value = stringValue(response.get(key));
        if (value == null) {
            throw new MediaStorageException(INVALID_PROVIDER_RESPONSE);
        }
        return value;
    }

    private String stringValue(Object value) {
        if (!(value instanceof String string) || string.isBlank()) {
            return null;
        }
        return string;
    }

    private long requiredLong(Map<?, ?> response, String key) {
        Object value = response.get(key);
        if (!(value instanceof Number number)) {
            throw new MediaStorageException(INVALID_PROVIDER_RESPONSE);
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new MediaStorageException(INVALID_PROVIDER_RESPONSE);
        }
    }

    private String cloudName() {
        String configured = cloudinary.config.cloudName;
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Cloudinary cloud name is not configured");
        }
        return configured;
    }

    private boolean hasLocalReadFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof LocalMediaReadException) {
                return true;
            }
        }
        return false;
    }

    private static final class LocalMediaInputStream extends FilterInputStream {

        private LocalMediaInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (IOException exception) {
                throw new LocalMediaReadException();
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                return super.read(bytes, offset, length);
            } catch (IOException exception) {
                throw new LocalMediaReadException();
            }
        }

        @Override
        public long skip(long count) throws IOException {
            try {
                return super.skip(count);
            } catch (IOException exception) {
                throw new LocalMediaReadException();
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } catch (IOException exception) {
                throw new LocalMediaReadException();
            }
        }
    }

    private static final class LocalMediaReadException extends IOException {
        private LocalMediaReadException() {
            super("local media stream failed");
        }
    }
}
