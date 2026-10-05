package com.fonja.user.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "firstName is required")
                @Size(max = 100, message = "firstName must be at most 100 characters")
                String firstName,
        @NotBlank(message = "lastName is required")
                @Size(max = 100, message = "lastName must be at most 100 characters")
                String lastName,
        @NotBlank(message = "email is required")
                @Email(message = "email must be a valid email address")
                @Size(max = 255, message = "email must be at most 255 characters")
                String email,
        @NotBlank(message = "phone is required")
                @Size(max = 32, message = "phone must be at most 32 characters")
                String phone,
        // 72 is BCrypt's input limit; anything past it would be silently ignored.
        @NotBlank(message = "password is required")
                @Size(min = 8, max = 72, message = "password must be 8 to 72 characters")
                String password,
        @AssertTrue(message = "You must accept the Terms & Conditions and Privacy Policy")
                boolean acceptedTerms) {}
