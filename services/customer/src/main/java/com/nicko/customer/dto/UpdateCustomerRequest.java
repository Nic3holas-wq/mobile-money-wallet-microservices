package com.nicko.customer.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCustomerRequest(@Size(max = 255) String preferredName,
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String preferredLanguage) {
    @JsonAnySetter
    public void rejectProtectedField(String name, Object value) {
        throw new IllegalArgumentException("Only preferredName and preferredLanguage can be updated");
    }
}
