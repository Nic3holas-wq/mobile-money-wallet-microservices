package com.nicko.customer.dto;

import com.nicko.customer.customer.enums.AddressType;
import java.time.Instant;
import java.util.UUID;

public record CustomerAddressResponse(UUID id, AddressType addressType, String countryCode,
        String county, String cityOrTown, String postalCode, String addressLine1,
        String addressLine2, boolean primary, Instant createdAt, Instant updatedAt) {}
