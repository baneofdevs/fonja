package com.fonja.common.exception;

import com.fonja.common.response.CustomResponse;
import com.fonja.common.response.ResponseCode;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Maps exceptions to {@link CustomResponse} error codes so no handler ever leaks a stack trace or a
 * raw Spring error body to a client.
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
    public ResponseEntity<CustomResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex) {
        String detail =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .collect(Collectors.joining("; "));
        return ResponseEntity.status(ResponseCode.VALIDATION_ERROR.getHttpStatus())
                .body(CustomResponse.error(ResponseCode.VALIDATION_ERROR, detail));
    }

    /**
     * A query or path parameter that could not be converted — most often an enum value that does
     * not exist, like {@code ?sort=TRENDING}. Client error, so 400 with the accepted values spelled
     * out; without this handler it would fall through to the 500 catch-all below.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CustomResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        String detail = ex.getName() + ": invalid value '" + ex.getValue() + "'";
        if (required != null && required.isEnum()) {
            detail +=
                    "; expected one of "
                            + Arrays.stream(required.getEnumConstants())
                                    .map(Object::toString)
                                    .collect(Collectors.joining(", "));
        }
        return ResponseEntity.status(ResponseCode.VALIDATION_ERROR.getHttpStatus())
                .body(CustomResponse.error(ResponseCode.VALIDATION_ERROR, detail));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<CustomResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException ex) {
        return ResponseEntity.status(ResponseCode.VALIDATION_ERROR.getHttpStatus())
                .body(
                        CustomResponse.error(
                                ResponseCode.VALIDATION_ERROR,
                                ex.getParameterName() + ": parameter is required"));
    }

    /** Malformed or missing JSON body. The parser's message is not echoed — it names internals. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CustomResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException ex) {
        return ResponseEntity.status(ResponseCode.VALIDATION_ERROR.getHttpStatus())
                .body(
                        CustomResponse.error(
                                ResponseCode.VALIDATION_ERROR,
                                "Request body is missing or is not valid JSON"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CustomResponse<Void>> handleUploadTooLarge(
            MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(ResponseCode.VALIDATION_ERROR.getHttpStatus())
                .body(
                        CustomResponse.error(
                                ResponseCode.VALIDATION_ERROR,
                                "Uploaded file exceeds the maximum allowed size"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<CustomResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(ResponseCode.FORBIDDEN.getHttpStatus())
                .body(
                        CustomResponse.error(
                                ResponseCode.FORBIDDEN, ResponseCode.FORBIDDEN.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CustomResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        // Never surface the exception message here — it can carry internals.
        return ResponseEntity.status(ResponseCode.INTERNAL_ERROR.getHttpStatus())
                .body(
                        CustomResponse.error(
                                ResponseCode.INTERNAL_ERROR,
                                ResponseCode.INTERNAL_ERROR.getMessage()));
    }
}
