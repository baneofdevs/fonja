package com.fonja.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Reuses audit-central's wrapper shape: flat {@code "00"}/{@code "SUCCESSFUL"} on success, a code
 * from {@link ResponseCode} otherwise. Every controller in this service returns this type so
 * clients only ever parse one envelope.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomResponse<T> {

    private String responseCode;
    private String responseMessage;
    private T responseData;

    public CustomResponse() {
        // Jackson
    }

    public CustomResponse(String responseCode, String responseMessage, T responseData) {
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.responseData = responseData;
    }

    public static <T> CustomResponse<T> success(T data) {
        return new CustomResponse<>(
                ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getMessage(), data);
    }

    public static <T> CustomResponse<T> success(String message, T data) {
        return new CustomResponse<>(ResponseCode.SUCCESS.getCode(), message, data);
    }

    public static <T> CustomResponse<T> error(ResponseCode code, String message) {
        return new CustomResponse<>(code.getCode(), message, null);
    }

    public String getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(String responseCode) {
        this.responseCode = responseCode;
    }

    public String getResponseMessage() {
        return responseMessage;
    }

    public void setResponseMessage(String responseMessage) {
        this.responseMessage = responseMessage;
    }

    public T getResponseData() {
        return responseData;
    }

    public void setResponseData(T responseData) {
        this.responseData = responseData;
    }
}
