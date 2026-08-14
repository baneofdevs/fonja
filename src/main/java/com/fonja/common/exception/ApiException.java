package com.fonja.common.exception;

import com.fonja.common.response.ResponseCode;

/**
 * Base for every exception this service raises deliberately. Carries the
 * {@link ResponseCode} so {@link GlobalExceptionHandler} never has to guess at
 * a status from the exception type.
 */
public class ApiException extends RuntimeException {

    private final ResponseCode responseCode;

    public ApiException(ResponseCode responseCode, String message) {
        super(message);
        this.responseCode = responseCode;
    }

    public ApiException(ResponseCode responseCode, String message, Throwable cause) {
        super(message, cause);
        this.responseCode = responseCode;
    }

    public ResponseCode getResponseCode() {
        return responseCode;
    }

    public static ApiException notFound(String message) {
        return new ApiException(ResponseCode.NOT_FOUND, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(ResponseCode.VALIDATION_ERROR, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ResponseCode.CONFLICT, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ResponseCode.FORBIDDEN, message);
    }

    public static ApiException verificationRequired(String message) {
        return new ApiException(ResponseCode.VERIFICATION_REQUIRED, message);
    }
}
