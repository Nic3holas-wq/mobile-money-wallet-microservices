package com.nicko.customer.dto;

import com.nicko.customer.customer.enums.*;
import jakarta.validation.constraints.*;

public record CustomerConsentRequest(@NotNull ConsentType consentType,
        @NotBlank @Size(max = 255) String documentVersion) {}
