package com.exe.morphia3d.controller;

import com.exe.morphia3d.dto.response.ProductApiErrorResponse;
import com.exe.morphia3d.service.MediaPersistenceException;
import com.exe.morphia3d.service.MediaStorageException;
import com.exe.morphia3d.service.MediaUploadLeaseException;
import com.exe.morphia3d.service.MediaValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice(assignableTypes = Model3dController.class)
public class MediaUploadExceptionHandler {

    @ExceptionHandler(MediaValidationException.class)
    ResponseEntity<ProductApiErrorResponse> handleValidation(MediaValidationException exception) {
        HttpStatus status = switch (exception.getReason()) {
            case TOO_LARGE, TOO_MANY_IMAGES, IMAGE_DIMENSIONS_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            case UNSUPPORTED_EXTENSION, UNSUPPORTED_MIME_TYPE, CONTENT_MISMATCH, INVALID_GLB ->
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case MISSING_FILE, MALFORMED_IMAGE, MALFORMED_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        String message = switch (status) {
            case PAYLOAD_TOO_LARGE -> "The upload exceeds the configured size or image limits";
            case UNSUPPORTED_MEDIA_TYPE -> "Only supported JPEG, PNG, WebP, GLB, and STL content is accepted";
            default -> "The upload request or a required file is invalid";
        };
        return response(status, message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProductApiErrorResponse> handlePayloadLimit(MaxUploadSizeExceededException exception) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "The multipart request exceeds the configured size limit");
    }

    @ExceptionHandler(MediaStorageException.class)
    ResponseEntity<ProductApiErrorResponse> handleStorage(MediaStorageException exception) {
        HttpStatus status = switch (exception.getReason()) {
            case PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INPUT_STREAM_FAILURE -> HttpStatus.BAD_REQUEST;
            case INVALID_PROVIDER_RESPONSE, IDENTITY_MISMATCH, UNSUPPORTED_IDENTITY -> HttpStatus.BAD_GATEWAY;
        };
        String message = status == HttpStatus.SERVICE_UNAVAILABLE
                ? "Media storage is temporarily unavailable"
                : status == HttpStatus.BAD_REQUEST
                    ? "The uploaded media could not be read"
                    : "Media storage returned an invalid response";
        return response(status, message);
    }

    @ExceptionHandler(MediaUploadLeaseException.class)
    ResponseEntity<ProductApiErrorResponse> handleLease(MediaUploadLeaseException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "The media upload could not safely continue");
    }

    @ExceptionHandler(MediaPersistenceException.class)
    ResponseEntity<ProductApiErrorResponse> handlePersistence(MediaPersistenceException exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "The model could not be saved");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProductApiErrorResponse> handleMalformedRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "The multipart request is malformed or incomplete");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProductApiErrorResponse> handleUnsupportedRequestType(HttpMediaTypeNotSupportedException exception) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The multipart request content type is not supported");
    }

    private ResponseEntity<ProductApiErrorResponse> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ProductApiErrorResponse(
                status.value(), status.getReasonPhrase(), message));
    }
}
