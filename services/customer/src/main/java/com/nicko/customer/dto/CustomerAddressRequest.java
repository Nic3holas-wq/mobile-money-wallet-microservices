package com.nicko.customer.dto;

import com.nicko.customer.customer.enums.AddressType;
import jakarta.validation.constraints.*;

public record CustomerAddressRequest(
        @NotNull AddressType addressType,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
        @NotBlank @Size(max = 255) String county,
        @NotBlank @Size(max = 255) String cityOrTown,
        @Size(max = 255) String postalCode,
        @NotBlank @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @NotNull Boolean primary
) {}
