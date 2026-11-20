package com.exe.morphia3d.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DesignProjectMultipartExceptionHandler {
    private static final String PROJECT_CREATE_PATH = "/api/ai-studio/projects";

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<DesignProjectExceptionHandler.ErrorResponse> unsupportedRequestMediaType(
            HttpMediaTypeNotSupportedException failure, HttpServletRequest request)
            throws HttpMediaTypeNotSupportedException {
        if (!isProjectCreateRequest(request)) throw failure;
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new DesignProjectExceptionHandler.ErrorResponse("DESIGN_UNSUPPORTED_REQUEST_MEDIA_TYPE",
                        "The design project endpoint requires multipart form data."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<DesignProjectExceptionHandler.ErrorResponse> uploadTooLarge(
            MaxUploadSizeExceededException failure, HttpServletRequest request)
            throws MaxUploadSizeExceededException {
        if (!isProjectCreateRequest(request)) throw failure;
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new DesignProjectExceptionHandler.ErrorResponse("DESIGN_UPLOAD_TOO_LARGE",
                        "The multipart upload exceeds the configured size limit."));
    }

    private boolean isProjectCreateRequest(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && request.getRequestURI() != null
                && request.getRequestURI().endsWith(PROJECT_CREATE_PATH);
    }
}
