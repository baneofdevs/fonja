package com.fonja.common.response;

import org.springframework.http.HttpStatus;

public enum ResponseCode {
    SUCCESS("00", "SUCCESSFUL", HttpStatus.OK),
    VALIDATION_ERROR("01", "Request validation failed", HttpStatus.BAD_REQUEST),
    NOT_FOUND("02", "Resource not found", HttpStatus.NOT_FOUND),
    UNAUTHORIZED("03", "Authentication required", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("04", "Not permitted", HttpStatus.FORBIDDEN),
    CONFLICT("05", "Request conflicts with current state", HttpStatus.CONFLICT),
    STORAGE_ERROR("20", "Document storage failure", HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR("99", "Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ResponseCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
