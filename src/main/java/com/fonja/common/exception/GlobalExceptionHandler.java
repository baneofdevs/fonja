package com.fonja.common.exception;

import com.fonja.common.response.CustomResponse;
import com.fonja.common.response.ResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * Maps exceptions to {@link CustomResponse} error codes so no handler ever
 * leaks a stack trace or a raw Spring error body to a client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<CustomResponse<Void>> handleApiException(ApiException ex) {
        ResponseCode code = ex.getResponseCode();
        // Deliberate, expected failures — logged at warn, without a stack trace.
        log.warn("API exception [{}]: {}", code.getCode(), ex.getMessage());
        return ResponseEntity.status(code.getHttpStatus())
                .body(CustomResponse.error(code, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CustomResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(ResponseCode.VALIDATION_ERROR.getHttpStatus())
                .body(CustomResponse.error(ResponseCode.VALIDATION_ERROR, detail));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CustomResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(ResponseCode.VALIDATION_ERROR.getHttpStatus())
                .body(CustomResponse.error(ResponseCode.VALIDATION_ERROR, "Uploaded file exceeds the maximum allowed size"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<CustomResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(ResponseCode.FORBIDDEN.getHttpStatus())
                .body(CustomResponse.error(ResponseCode.FORBIDDEN, ResponseCode.FORBIDDEN.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CustomResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        // Never surface the exception message here — it can carry internals.
        return ResponseEntity.status(ResponseCode.INTERNAL_ERROR.getHttpStatus())
                .body(CustomResponse.error(ResponseCode.INTERNAL_ERROR, ResponseCode.INTERNAL_ERROR.getMessage()));
    }
}
