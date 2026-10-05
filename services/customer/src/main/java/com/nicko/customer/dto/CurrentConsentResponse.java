package com.nicko.customer.dto;
import com.nicko.customer.entity.enums.ConsentType;
public record CurrentConsentResponse(ConsentType consentType, String requiredVersion, boolean mandatory, boolean accepted) {}
