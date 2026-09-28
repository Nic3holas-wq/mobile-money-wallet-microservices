package com.nicko.customer.dto;

import com.nicko.customer.customer.enums.ContactType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;

public record CustomerContactRequest(
        @NotNull ContactType contactType,
        @NotBlank @Size(max = 255) String contactValue,
        @NotNull Boolean primary,
        @Pattern(regexp = "[A-Z]{2}") String phoneRegion
) {
    @JsonIgnore
    @Email(message = "must be a valid email address")
    public String getEmailForValidation() {
        return contactType == ContactType.EMAIL && contactValue != null ? contactValue.strip() : null;
    }

}
