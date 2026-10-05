package com.fonja.user.dto;

import com.fonja.user.entity.AccountIntent;
import jakarta.validation.constraints.Size;

/** Partial update; a null field is left untouched, a blank optional field is cleared. */
public record UpdateProfileRequest(
        @Size(min = 1, max = 100, message = "firstName must be 1 to 100 characters")
                String firstName,
        @Size(max = 100, message = "lastName must be at most 100 characters") String lastName,
        @Size(max = 32, message = "phone must be at most 32 characters") String phone,
        @Size(max = 500, message = "bio must be at most 500 characters") String bio,
        @Size(max = 120, message = "location must be at most 120 characters") String location,
        AccountIntent accountIntent) {}
