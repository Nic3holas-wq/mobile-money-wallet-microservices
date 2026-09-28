package com.nicko.customer.dto;
import com.nicko.customer.customer.enums.ConsentType;
public record ConsentPolicyResponse(ConsentType consentType, String documentVersion, boolean mandatory) {}
