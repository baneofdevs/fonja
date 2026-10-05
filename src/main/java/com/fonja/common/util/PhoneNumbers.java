package com.fonja.common.util;

import com.fonja.common.exception.ApiException;

/**
 * Nigerian mobile numbers, stored one way: E.164, {@code +234} and ten digits.
 *
 * <p>People type them every way — {@code 0803 123 4567}, {@code 803-123-4567}, {@code
 * +2348031234567}, {@code 2348031234567} — so input is stripped to digits and the country prefix or
 * trunk zero is dropped before checking what is left is ten digits starting 7, 8 or 9 (the mobile
 * ranges).
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /**
     * @return the number as {@code +234XXXXXXXXXX}
     * @throws ApiException badRequest if it is not a Nigerian mobile number
     */
    public static String normalizeNigerian(String input) {
        String digits = input == null ? "" : input.replaceAll("\\D", "");
        if (digits.startsWith("234")) {
            digits = digits.substring(3);
        }
        if (digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (!digits.matches("[789]\\d{9}")) {
            throw ApiException.badRequest(
                    "Enter a valid Nigerian phone number, e.g. 0803 123 4567");
        }
        return "+234" + digits;
    }
}
