package com.nicko.customer.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record RegisterCustomerRequest(
        @NotBlank @Size(max = 255) String firstName,
        @Size(max = 255) String middleName,
        @NotBlank @Size(max = 255) String lastName,
        @NotNull @Past LocalDate dateOfBirth,
        @Size(max = 255) String gender,
        @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "must be a two-letter uppercase country code") String nationality,
        @NotBlank @Size(max = 255) String preferredLanguage
) {}
