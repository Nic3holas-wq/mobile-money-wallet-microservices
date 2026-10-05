package com.nicko.customer.service;

import com.nicko.customer.config.DocumentProtection;
import com.nicko.customer.entity.KycDocument;
import com.nicko.customer.entity.enums.DocumentVerificationStatus;
import com.nicko.customer.dto.*;
import com.nicko.customer.mapper.KycDocumentMapper;
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
public class KycDocumentService {
    private final CustomerOwnership ownership;
    private final KycAccess access;
    private final KycDocumentRepository repository;
    private final KycDocumentMapper mapper;
    private final DocumentProtection protection;
    private final CustomerAuditService audit;

    @Transactional
    public KycDocumentResponse create(UUID userId, KycDocumentRequest request) {
        return save(userId, null, request);
    }

    @Transactional
    public KycDocumentResponse update(UUID userId, UUID id, KycDocumentRequest request) {
        return save(userId, id, request);
    }

    private KycDocumentResponse save(UUID userId, UUID id, KycDocumentRequest request) {
        var profile = access.require(ownership.lock(userId).getId());
        access.requireEditable(profile);
        var document = id == null ? new KycDocument() : find(profile.getId(), id);
        if (request.issuedAt() != null && request.expiresAt() != null && !request.expiresAt().isAfter(request.issuedAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document expiry must be after issue date");
        }
        String hash = protection.hash(request.documentType().name(), request.issuingCountry(), request.documentNumber());
        if (id == null ? repository.existsByDocumentNumberHash(hash) : repository.existsByDocumentNumberHashAndIdNot(hash, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Document is already registered");
        }
        mapper.update(document, request);
        document.setKycProfile(profile);
        document.setDocumentNumberHash(hash);
        document.setDocumentNumberEncrypted(protection.encrypt(request.documentNumber()));
        document.setVerificationStatus(DocumentVerificationStatus.PENDING);
        document.setVerificationProvider(null);
        document.setProviderReference(null);
        document.setFailureReason(null);
        document.setVerifiedAt(null);
        return mapper.toResponse(repository.saveAndFlush(document));
    }

    @Transactional(readOnly = true)
    public PageResponse<KycDocumentResponse> list(UUID userId, int page, int size) {
        return listFor(ownership.require(userId).getId(), page, size);
    }

    @Transactional(readOnly = true)
    public KycDocumentResponse get(UUID userId, UUID id) {
        return mapper.toResponse(find(access.require(ownership.require(userId).getId()).getId(), id));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        var profile = access.require(ownership.lock(userId).getId());
        access.requireEditable(profile);
        repository.delete(find(profile.getId(), id));
        repository.flush();
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<KycDocumentResponse> listForReview(UUID customerId, int page, int size) {
        ownership.requireById(customerId);
        return listFor(customerId, page, size);
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional
    public KycDocumentResponse review(UUID customerId, UUID id, UUID reviewer, KycDocumentReviewRequest request) {
        var customer = ownership.lockById(customerId);
        if (customer.getKeycloakUserId().equals(reviewer)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff cannot review their own documents");
        }
        var profile = access.require(customerId);
        access.requireSubmitted(profile);
        var document = find(profile.getId(), id);
        if (request.decision() != DocumentVerificationStatus.VERIFIED && request.decision() != DocumentVerificationStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision must be VERIFIED or REJECTED");
        }
        if (request.decision() == DocumentVerificationStatus.VERIFIED && document.getExpiresAt() != null
                && !document.getExpiresAt().isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An expired document cannot be verified");
        }
        if (request.decision() == DocumentVerificationStatus.REJECTED
                && (request.failureReason() == null || request.failureReason().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A failure reason is required");
        }
        var before = AuditSnapshots.document(document);
        document.setVerificationStatus(request.decision());
        document.setVerificationProvider(request.verificationProvider().strip());
        document.setProviderReference(request.providerReference());
        document.setFailureReason(request.decision() == DocumentVerificationStatus.REJECTED ? request.failureReason().strip() : null);
        document.setVerifiedAt(request.decision() == DocumentVerificationStatus.VERIFIED ? Instant.now() : null);
        repository.saveAndFlush(document);
        audit.record(customerId, reviewer, "KYC_DOCUMENT_" + request.decision().name(), "KYC_DOCUMENT", id,
                before, AuditSnapshots.document(document));
        return mapper.toResponse(document);
    }

    private PageResponse<KycDocumentResponse> listFor(UUID customerId, int page, int size) {
        return PageResponse.from(repository.findByKycProfileId(access.require(customerId).getId(), ApiPages.of(page, size))
                .map(mapper::toResponse));
    }
    private KycDocument find(UUID profileId, UUID id) {
        return repository.findByIdAndKycProfileId(id, profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "KYC document not found"));
    }
}
