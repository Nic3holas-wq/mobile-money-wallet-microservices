package com.nicko.customer.dto;

import com.nicko.customer.customer.enums.ContactType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;

public record CustomerContactRequest(
        @NotNull ContactType contactType,
        @NotBlank @Size(max = 255) String contactValue,
        @NotNull Boolean primary
) {
    @JsonIgnore
    @Email(message = "must be a valid email address")
    public String getEmailForValidation() {
        return contactType == ContactType.EMAIL && contactValue != null ? contactValue.strip() : null;
    }

    @JsonIgnore
    @AssertTrue(message = "phone must use international format, for example +254712345678")
    public boolean isPhoneNumberValid() {
        return contactType != ContactType.PHONE || contactValue == null
                || contactValue.strip().matches("\\+[1-9][0-9]{1,14}");
    }
}
