package com.nicko.customer.service;

import com.nicko.customer.entity.CustomerConsent;
import com.nicko.customer.dto.*;
import com.nicko.customer.mapper.CustomerConsentMapper;
import com.nicko.customer.repository.CustomerConsentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetAddress;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerConsentService {
    private final CustomerOwnership ownership;
    private final CustomerConsentRepository repository;
    private final CustomerConsentMapper mapper;
    private final ConsentPolicyService policies;
    private final CustomerAuditService audit;
    private final java.time.Clock clock;

    @Transactional
    public CustomerConsentResponse accept(UUID userId, CustomerConsentRequest request, InetAddress ip, String userAgent) {
        var customer = ownership.lock(userId);
        String version = request.documentVersion().strip();
        policies.validate(request.consentType(), version);
        if (repository.existsByCustomerIdAndConsentTypeAndDocumentVersionAndAcceptedTrueAndWithdrawnAtIsNull(
                customer.getId(), request.consentType(), version)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Consent already recorded for this version");
        }
        var consent = new CustomerConsent();
        consent.setCustomer(customer);
        consent.setConsentType(request.consentType());
        consent.setDocumentVersion(version);
        consent.setChannel("API");
        consent.setAccepted(true);
        consent.setAcceptedAt(clock.instant());
        consent.setIpAddress(ip);
        consent.setUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length())));
        repository.saveAndFlush(consent);
        audit.record(customer.getId(), userId, "CONSENT_ACCEPTED", "CUSTOMER_CONSENT", consent.getId(),
                java.util.Map.of(), java.util.Map.of("consentType", consent.getConsentType().name(), "version", version, "channel", "API"));
        return mapper.toResponse(consent);
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerConsentResponse> list(UUID userId, int page, int size) {
        return PageResponse.from(repository.findByCustomerId(ownership.require(userId).getId(), ApiPages.of(page, size))
                .map(mapper::toResponse));
    }

    @Transactional(readOnly = true)
    public CustomerConsentResponse get(UUID userId, UUID id) {
        return mapper.toResponse(find(ownership.require(userId).getId(), id));
    }

    @Transactional
    public CustomerConsentResponse withdraw(UUID userId, UUID id) {
        var customer = ownership.lock(userId);
        var consent = find(customer.getId(), id);
        if (consent.getWithdrawnAt() == null) {
            consent.setWithdrawnAt(clock.instant());
            if (consent.getConsentType() != com.nicko.customer.entity.enums.ConsentType.MARKETING) {
                customer.setWalletEligible(false);
            }
            audit.record(customer.getId(), userId, "CONSENT_WITHDRAWN", "CUSTOMER_CONSENT", consent.getId(),
                    java.util.Map.of("accepted", true), java.util.Map.of("withdrawnAt", consent.getWithdrawnAt().toString(),
                            "consentType", consent.getConsentType().name(), "version", consent.getDocumentVersion()));
        }
        // accepted/acceptedAt preserve the original acceptance evidence.
        return mapper.toResponse(repository.saveAndFlush(consent));
    }

    @Transactional(readOnly = true)
    public java.util.List<CurrentConsentResponse> current(UUID userId) {
        return policies.current(ownership.require(userId).getId());
    }

    private CustomerConsent find(UUID customerId, UUID id) {
        return repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Consent not found"));
    }
}
