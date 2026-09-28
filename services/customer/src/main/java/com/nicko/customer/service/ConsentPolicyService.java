package com.nicko.customer.service;

import com.nicko.customer.customer.enums.ConsentType;
import com.nicko.customer.dto.*;
import com.nicko.customer.repository.CustomerConsentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

@Service
public class ConsentPolicyService {
    private final CustomerConsentRepository repository;
    private final List<ConsentPolicyResponse> policies;
    public ConsentPolicyService(CustomerConsentRepository repository,
            @Value("${app.consent.terms-version:v1}") String terms,
            @Value("${app.consent.privacy-version:v1}") String privacy,
            @Value("${app.consent.marketing-version:v1}") String marketing) {
        this.repository = repository;
        for (String version : List.of(terms, privacy, marketing)) {
            if (version.isBlank() || version.length() > 255) { throw new IllegalArgumentException("Invalid consent policy version configuration"); }
        }
        policies = List.of(new ConsentPolicyResponse(ConsentType.TERMS_AND_CONDITIONS, terms, true),
                new ConsentPolicyResponse(ConsentType.PRIVACY_POLICY, privacy, true),
                new ConsentPolicyResponse(ConsentType.MARKETING, marketing, false));
    }
    public List<ConsentPolicyResponse> policies() { return policies; }
    public void validate(ConsentType type, String version) {
        if (policies.stream().noneMatch(p -> p.consentType() == type && p.documentVersion().equals(version))) {
            throw new FieldValidationException("documentVersion", "must match the currently published policy version");
        }
    }
    public List<CurrentConsentResponse> current(UUID customerId) {
        return policies.stream().map(p -> new CurrentConsentResponse(p.consentType(), p.documentVersion(), p.mandatory(),
                repository.existsByCustomerIdAndConsentTypeAndDocumentVersionAndAcceptedTrueAndWithdrawnAtIsNull(
                        customerId, p.consentType(), p.documentVersion()))).toList();
    }
    public boolean mandatorySatisfied(UUID customerId) {
        return current(customerId).stream().filter(CurrentConsentResponse::mandatory).allMatch(CurrentConsentResponse::accepted);
    }
}
