package com.exe.morphia3d.controller;

import com.exe.morphia3d.design.DesignProjectException;
import com.exe.morphia3d.design.DesignQuotaException;
import com.exe.morphia3d.design.ProviderUnavailableException;
import com.exe.morphia3d.feature.FeatureDisabledException;
import com.exe.morphia3d.service.MediaValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.MissingRequestHeaderException;

@RestControllerAdvice(assignableTypes = {
        DesignProjectController.class, DesignPipelineController.class, DesignArtifactController.class,
        DesignPrintReadinessController.class
})
public class DesignProjectExceptionHandler {
    public record ErrorResponse(String code, String message) { }

    @ExceptionHandler(DesignProjectException.class)
    public ResponseEntity<ErrorResponse> project(DesignProjectException failure) {
        HttpStatus status = switch (failure.getCode()) {
            case "DESIGN_PROJECT_NOT_FOUND", "DESIGN_REFERENCE_SET_NOT_FOUND", "DESIGN_REFERENCE_VERSION_NOT_FOUND",
                    "DESIGN_MODEL_VERSION_NOT_FOUND", "DESIGN_ARTIFACT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DESIGN_IDEMPOTENCY_CONFLICT", "DESIGN_REFERENCE_VERSION_NOT_LATEST" -> HttpStatus.CONFLICT;
            case "DESIGN_APPROVAL_NOT_ALLOWED", "DESIGN_REFERENCE_REVISION_NOT_ALLOWED",
                    "DESIGN_MODEL_REVISION_NOT_ALLOWED", "DESIGN_REFERENCES_NOT_APPROVED",
                    "DESIGN_REFERENCE_SNAPSHOT_INCOMPLETE" -> HttpStatus.CONFLICT;
            case "DESIGN_AUTH_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            case "DESIGN_ARTIFACT_STORE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(failure.getCode(), "The design project request could not be completed."));
    }

    @ExceptionHandler(DesignQuotaException.class)
    public ResponseEntity<ErrorResponse> quota(DesignQuotaException failure) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse(failure.getMessage(), "AI Studio usage is not available within the configured limits."));
    }

    @ExceptionHandler(ProviderUnavailableException.class)
    public ResponseEntity<ErrorResponse> provider(ProviderUnavailableException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DESIGN_PROVIDER_UNAVAILABLE", "The design provider is not configured."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> forbidden(AccessDeniedException failure) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("DESIGN_ACCESS_DENIED", "You do not have access to this design project."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(new ErrorResponse("DESIGN_INVALID_REQUEST", "The design project request is invalid."));
    }

    @ExceptionHandler({BindException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<ErrorResponse> invalidRequest(Exception failure) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("DESIGN_INVALID_REQUEST", "The design project request is invalid."));
    }

    @ExceptionHandler(FeatureDisabledException.class)
    public ResponseEntity<ErrorResponse> unavailable(FeatureDisabledException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DESIGN_SERVICE_UNAVAILABLE", "The design service is temporarily unavailable."));
    }

    @ExceptionHandler(MediaValidationException.class)
    public ResponseEntity<ErrorResponse> mediaValidation(MediaValidationException failure) {
        HttpStatus status = switch (failure.getReason()) {
            case CONTENT_MISMATCH, UNSUPPORTED_EXTENSION, UNSUPPORTED_MIME_TYPE, INVALID_GLB ->
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case TOO_LARGE, TOO_MANY_IMAGES, IMAGE_DIMENSIONS_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            case MISSING_FILE, MALFORMED_IMAGE, MALFORMED_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        String code = switch (failure.getReason()) {
            case CONTENT_MISMATCH -> "DESIGN_MEDIA_CONTENT_MISMATCH";
            case UNSUPPORTED_EXTENSION -> "DESIGN_MEDIA_UNSUPPORTED_EXTENSION";
            case UNSUPPORTED_MIME_TYPE -> "DESIGN_MEDIA_UNSUPPORTED_MIME_TYPE";
            case INVALID_GLB -> "DESIGN_MEDIA_INVALID_GLB";
            case TOO_LARGE -> "DESIGN_MEDIA_TOO_LARGE";
            case TOO_MANY_IMAGES -> "DESIGN_MEDIA_TOO_MANY_IMAGES";
            case IMAGE_DIMENSIONS_EXCEEDED -> "DESIGN_MEDIA_IMAGE_DIMENSIONS_EXCEEDED";
            case MISSING_FILE -> "DESIGN_MEDIA_MISSING_FILE";
            case MALFORMED_IMAGE -> "DESIGN_MEDIA_MALFORMED_IMAGE";
            case MALFORMED_REQUEST -> "DESIGN_MEDIA_MALFORMED_REQUEST";
        };
        String message = switch (failure.getReason()) {
            case CONTENT_MISMATCH -> "The uploaded image content does not match its declared format.";
            case UNSUPPORTED_EXTENSION, UNSUPPORTED_MIME_TYPE, INVALID_GLB ->
                    "The uploaded media format is not supported.";
            case TOO_LARGE, TOO_MANY_IMAGES, IMAGE_DIMENSIONS_EXCEEDED ->
                    "The uploaded media exceeds the configured limits.";
            case MISSING_FILE -> "A required upload file is missing.";
            case MALFORMED_IMAGE -> "The uploaded image is malformed or unreadable.";
            case MALFORMED_REQUEST -> "The multipart upload is malformed.";
        };
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }

}
