package com.nicko.customer.service;

import com.nicko.customer.customer.KycProfile;
import com.nicko.customer.customer.enums.*;
import com.nicko.customer.dto.*;
import com.nicko.customer.mapper.KycProfileMapper;
import com.nicko.customer.repository.KycProfileRepository;
import com.nicko.customer.repository.KycDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KycProfileService {
    private final CustomerOwnership ownership;
    private final KycAccess access;
    private final KycProfileRepository repository;
    private final KycDocumentRepository documents;
    private final KycProfileMapper mapper;
    private final OutboxEventService outbox;
    private final CustomerAuditService audit;

    @Transactional
    public KycProfileResponse create(UUID userId, KycProfileRequest request) {
        var customer = ownership.lock(userId);
        if (repository.findByCustomerId(customer.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KYC profile already exists");
        }
        var profile = new KycProfile();
        profile.setCustomer(customer);
        profile.setStatus(KycStatus.NOT_STARTED);
        profile.setPepStatus(PepStatus.NOT_SCREENED);
        profile.setSanctionsStatus(SanctionsStatus.NOT_SCREENED);
        // Conservative initial value until staff assessment; never treated as clearance.
        profile.setRiskRating(RiskRating.HIGH);
        apply(profile, request);
        return mapper.toResponse(repository.saveAndFlush(profile));
    }

    @Transactional(readOnly = true)
    public KycProfileResponse get(UUID userId) {
        return mapper.toResponse(access.require(ownership.require(userId).getId()));
    }

    @Transactional
    public KycProfileResponse update(UUID userId, KycProfileRequest request) {
        var profile = access.require(ownership.lock(userId).getId());
        access.requireEditable(profile);
        apply(profile, request);
        return mapper.toResponse(repository.saveAndFlush(profile));
    }

    @Transactional
    public KycProfileResponse submit(UUID userId) {
        var customer = ownership.lock(userId);
        var profile = access.require(customer.getId());
        access.requireEditable(profile);
        var before = AuditSnapshots.profile(profile);
        var evidence = documents.findByKycProfileId(profile.getId());
        if (evidence.isEmpty()) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Add at least one KYC document first"); }
        before.put("documents", evidence.stream().map(AuditSnapshots::document).toList());
        for (var document : evidence) {
            if (document.getExpiresAt() != null && !document.getExpiresAt().isAfter(LocalDate.now(ZoneOffset.UTC))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Replace expired documents before submission");
            }
            document.setVerificationStatus(DocumentVerificationStatus.PENDING);
            document.setVerifiedAt(null);
            document.setVerificationProvider(null);
            document.setProviderReference(null);
            document.setFailureReason(null);
        }
        profile.setStatus(KycStatus.PENDING);
        profile.setSubmittedAt(Instant.now());
        profile.setApprovedTier(null);
        profile.setReviewedAt(null);
        profile.setReviewedByKeycloakId(null);
        profile.setRejectionReason(null);
        profile.setExpiresAt(null);
        profile.setPepStatus(PepStatus.NOT_SCREENED);
        profile.setSanctionsStatus(SanctionsStatus.NOT_SCREENED);
        profile.setRiskRating(RiskRating.HIGH);
        customer.setKycStatus(KycStatus.PENDING);
        customer.setWalletEligible(false);
        repository.saveAndFlush(profile);
        var after = AuditSnapshots.profile(profile);
        after.put("documents", evidence.stream().map(AuditSnapshots::document).toList());
        audit.record(customer.getId(), userId, "KYC_SUBMITTED", "KYC_PROFILE", profile.getId(), before, after);
        outbox.record(customer, "customer.kyc.submitted.v1", profile.getId());
        return mapper.toResponse(profile);
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional(readOnly = true)
    public KycProfileResponse getForReview(UUID customerId) {
        ownership.requireById(customerId);
        return mapper.toResponse(access.require(customerId));
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional
    public KycProfileResponse review(UUID customerId, UUID reviewer, KycReviewRequest request) {
        var customer = ownership.lockById(customerId);
        if (customer.getKeycloakUserId().equals(reviewer)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff cannot review their own KYC");
        }
        var profile = access.require(customerId);
        access.requireSubmitted(profile);
        var before = AuditSnapshots.profile(profile);
        if (request.decision() != KycStatus.APPROVED && request.decision() != KycStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision must be APPROVED or REJECTED");
        }
        if (request.decision() == KycStatus.APPROVED) {
            if (request.approvedTier() == null || request.approvedTier() == KycTier.TIER_0
                    || request.approvedTier().compareTo(profile.getRequestedTier()) > 0 || request.expiresAt() == null
                    || request.pepStatus() != PepStatus.NOT_PEP || request.sanctionsStatus() != SanctionsStatus.CLEAR
                    || request.riskRating() == RiskRating.HIGH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval requires an eligible tier, expiry and cleared screening");
            }
            var evidence = documents.findByKycProfileId(profile.getId());
            if (evidence.isEmpty() || evidence.stream().anyMatch(document ->
                    document.getVerificationStatus() != DocumentVerificationStatus.VERIFIED
                    || (document.getExpiresAt() != null && !document.getExpiresAt().isAfter(LocalDate.now(ZoneOffset.UTC))))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "All submitted documents must be verified and unexpired");
            }
            profile.setApprovedTier(request.approvedTier());
            profile.setExpiresAt(request.expiresAt());
            profile.setRejectionReason(null);
            customer.setKycTier(request.approvedTier());
        } else {
            if (request.rejectionReason() == null || request.rejectionReason().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rejection reason is required");
            }
            profile.setApprovedTier(null);
            profile.setExpiresAt(null);
            profile.setRejectionReason(request.rejectionReason().strip());
            customer.setKycTier(KycTier.TIER_0);
        }
        profile.setStatus(request.decision());
        profile.setPepStatus(request.pepStatus());
        profile.setSanctionsStatus(request.sanctionsStatus());
        profile.setRiskRating(request.riskRating());
        profile.setReviewedAt(Instant.now());
        profile.setReviewedByKeycloakId(reviewer);
        customer.setKycStatus(request.decision());
        // Wallet activation requires a separate eligibility policy (verified contacts, consent, etc.).
        customer.setWalletEligible(false);
        repository.saveAndFlush(profile);
        var after = AuditSnapshots.profile(profile);
        after.put("verificationMethod", "STAFF_REVIEW");
        after.put("documents", documents.findByKycProfileId(profile.getId()).stream().map(AuditSnapshots::document).toList());
        audit.record(customerId, reviewer, "KYC_" + request.decision().name(), "KYC_PROFILE", profile.getId(), before, after);
        outbox.record(customer, request.decision() == KycStatus.APPROVED
                ? "customer.kyc.verified.v1" : "customer.kyc.rejected.v1", profile.getId());
        return mapper.toResponse(profile);
    }

    private void apply(KycProfile profile, KycProfileRequest request) {
        if (request.requestedTier() == KycTier.TIER_0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request a tier above TIER_0");
        }
        mapper.update(profile, request);
    }
}
