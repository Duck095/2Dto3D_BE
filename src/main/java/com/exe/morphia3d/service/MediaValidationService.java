package com.exe.morphia3d.service;

import com.exe.morphia3d.enums.MediaKind;
import com.exe.morphia3d.service.ai.ValidatedGlb;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.exe.morphia3d.service.MediaValidationException.Reason.CONTENT_MISMATCH;
import static com.exe.morphia3d.service.MediaValidationException.Reason.IMAGE_DIMENSIONS_EXCEEDED;
import static com.exe.morphia3d.service.MediaValidationException.Reason.INVALID_GLB;
import static com.exe.morphia3d.service.MediaValidationException.Reason.MALFORMED_IMAGE;
import static com.exe.morphia3d.service.MediaValidationException.Reason.MISSING_FILE;
import static com.exe.morphia3d.service.MediaValidationException.Reason.TOO_LARGE;
import static com.exe.morphia3d.service.MediaValidationException.Reason.TOO_MANY_IMAGES;
import static com.exe.morphia3d.service.MediaValidationException.Reason.UNSUPPORTED_EXTENSION;
import static com.exe.morphia3d.service.MediaValidationException.Reason.UNSUPPORTED_MIME_TYPE;

@Service
@RequiredArgsConstructor
public class MediaValidationService {

    private static final Map<String, ImageType> IMAGE_TYPES = Map.of(
            "jpg", new ImageType("jpeg", "image/jpeg", Signature.JPEG),
            "jpeg", new ImageType("jpeg", "image/jpeg", Signature.JPEG),
            "png", new ImageType("png", "image/png", Signature.PNG),
            "webp", new ImageType("webp", "image/webp", Signature.WEBP));

    private static final int GLB_HEADER_BYTES = 12;
    private static final String GLB_MIME_TYPE = "model/gltf-binary";
    private static final String GENERIC_BINARY_MIME_TYPE = "application/octet-stream";

    private final MediaUploadProperties properties;

    public List<ValidatedMedia> validateImages(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new MediaValidationException(MISSING_FILE);
        }
        if (files.size() > properties.getMaxImages()) {
            throw new MediaValidationException(TOO_MANY_IMAGES);
        }

        List<ValidatedMedia> validated = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            validated.add(validateImage(file));
        }
        return List.copyOf(validated);
    }

    /** Upstream model-upload contract: validate GLB content and retain supported STL uploads. */
    public ValidatedMedia validateModel(MultipartFile file) {
        requirePresent(file);
        if (file.getSize() > properties.getModelMaxBytes()) {
            throw new MediaValidationException(TOO_LARGE);
        }

        String modelExtension = extension(file.getOriginalFilename());
        if ("glb".equals(modelExtension)) {
            return validateGlb(file);
        }
        if (!"stl".equals(modelExtension)) {
            throw new MediaValidationException(UNSUPPORTED_EXTENSION);
        }

        String mimeType = normalizedMimeType(file.getContentType());
        if (!GENERIC_BINARY_MIME_TYPE.equals(mimeType)
                && !"application/sla".equals(mimeType)
                && !"text/plain".equals(mimeType)
                && !mimeType.isEmpty()) {
            throw new MediaValidationException(UNSUPPORTED_MIME_TYPE);
        }
        return new ValidatedMedia(file, MediaKind.MODEL_3D, "stl", file.getSize(), null, null);
    }

    public ValidatedMedia validateGlb(MultipartFile file) {
        requirePresent(file);
        validateGlbMetadata(file.getOriginalFilename(), file.getContentType(), file.getSize());
        try (InputStream input = file.getInputStream()) {
            validateGlbContent(input, file.getSize());
        } catch (IOException exception) {
            throw new MediaValidationException(INVALID_GLB, exception);
        }

        return new ValidatedMedia(file, MediaKind.MODEL_3D, "glb", file.getSize(), null, null);
    }

    public ValidatedGlb validateGlb(byte[] bytes, String filename, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new MediaValidationException(MISSING_FILE);
        }
        validateGlbMetadata(filename, contentType, bytes.length);
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            validateGlbContent(input, bytes.length);
        } catch (IOException exception) {
            throw new MediaValidationException(INVALID_GLB, exception);
        }
        return new ValidatedGlb(bytes);
    }

    private void validateGlbMetadata(String filename, String contentType, long size) {
        if (size > properties.getModelMaxBytes()) {
            throw new MediaValidationException(TOO_LARGE);
        }
        if (!"glb".equals(extension(filename))) {
            throw new MediaValidationException(UNSUPPORTED_EXTENSION);
        }
        String mimeType = normalizedMimeType(contentType);
        if (!GLB_MIME_TYPE.equals(mimeType) && !GENERIC_BINARY_MIME_TYPE.equals(mimeType)) {
            throw new MediaValidationException(UNSUPPORTED_MIME_TYPE);
        }
        if (size < GLB_HEADER_BYTES) {
            throw new MediaValidationException(INVALID_GLB);
        }
    }

    private void validateGlbContent(InputStream input, long expectedSize) throws IOException {
        byte[] header = input.readNBytes(GLB_HEADER_BYTES);
        if (header.length != GLB_HEADER_BYTES
                || header[0] != 'g' || header[1] != 'l' || header[2] != 'T' || header[3] != 'F') {
            throw new MediaValidationException(INVALID_GLB);
        }
        long version = unsignedLittleEndianInt(header, 4);
        long declaredLength = unsignedLittleEndianInt(header, 8);
        if (version != 2) {
            throw new MediaValidationException(INVALID_GLB);
        }

        long actualLength = header.length;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            actualLength += read;
            if (actualLength > properties.getModelMaxBytes()) {
                throw new MediaValidationException(TOO_LARGE);
            }
        }
        if (declaredLength != actualLength || expectedSize != actualLength) {
            throw new MediaValidationException(INVALID_GLB);
        }
    }

    private ValidatedMedia validateImage(MultipartFile file) {
        requirePresent(file);
        if (file.getSize() > properties.getImageMaxBytes()) {
            throw new MediaValidationException(TOO_LARGE);
        }

        String extension = extension(file.getOriginalFilename());
        ImageType expectedType = IMAGE_TYPES.get(extension);
        if (expectedType == null) {
            throw new MediaValidationException(UNSUPPORTED_EXTENSION);
        }
        if (!expectedType.mimeType().equals(normalizedMimeType(file.getContentType()))) {
            throw new MediaValidationException(UNSUPPORTED_MIME_TYPE);
        }

        try {
            verifySignature(file, expectedType.signature());
            return decodeAndValidateDimensions(file, expectedType);
        } catch (MediaValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MediaValidationException(MALFORMED_IMAGE, exception);
        }
    }

    private void verifySignature(MultipartFile file, Signature expected) throws IOException {
        try (InputStream input = file.getInputStream()) {
            byte[] prefix = input.readNBytes(expected.minimumBytes());
            if (!expected.matches(prefix)) {
                throw new MediaValidationException(CONTENT_MISMATCH);
            }
        }
    }

    private ValidatedMedia decodeAndValidateDimensions(MultipartFile file, ImageType expectedType) throws IOException {
        try (InputStream input = file.getInputStream(); ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw new MediaValidationException(MALFORMED_IMAGE);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new MediaValidationException(MALFORMED_IMAGE);
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                String decodedFormat = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!expectedType.format().equals(decodedFormat)
                        && !(expectedType.format().equals("jpeg") && decodedFormat.equals("jpg"))) {
                    throw new MediaValidationException(CONTENT_MISMATCH);
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                        || (long) width * (long) height > properties.getDecodedPixelMax()) {
                    throw new MediaValidationException(IMAGE_DIMENSIONS_EXCEEDED);
                }

                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw new MediaValidationException(MALFORMED_IMAGE);
                }
                decoded.flush();
                return new ValidatedMedia(file, MediaKind.IMAGE, expectedType.format(), file.getSize(), width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private void requirePresent(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getOriginalFilename() == null) {
            throw new MediaValidationException(MISSING_FILE);
        }
    }

    private String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int dot = filename.lastIndexOf('.');
        if (dot <= separator || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizedMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int parameters = mimeType.indexOf(';');
        return (parameters < 0 ? mimeType : mimeType.substring(0, parameters)).trim().toLowerCase(Locale.ROOT);
    }

    private long unsignedLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private record ImageType(String format, String mimeType, Signature signature) {
    }

    private enum Signature {
        JPEG(3) {
            @Override
            boolean matches(byte[] bytes) {
                return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                        && (bytes[2] & 0xff) == 0xff;
            }
        },
        PNG(8) {
            @Override
            boolean matches(byte[] bytes) {
                byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
                return bytes.length >= signature.length && java.util.Arrays.equals(signature, bytes);
            }
        },
        WEBP(12) {
            @Override
            boolean matches(byte[] bytes) {
                return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                        && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            }
        };

        private final int minimumBytes;

        Signature(int minimumBytes) {
            this.minimumBytes = minimumBytes;
        }

        int minimumBytes() {
            return minimumBytes;
        }

        abstract boolean matches(byte[] bytes);
    }
}
