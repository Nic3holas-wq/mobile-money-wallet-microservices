package com.nicko.customer.mapper;

import com.nicko.customer.customer.KycDocument;
import com.nicko.customer.dto.KycDocumentResponse;
import com.nicko.customer.dto.KycDocumentRequest;
import org.springframework.stereotype.Component;

@Component
public class KycDocumentMapper {
    public void update(KycDocument entity, KycDocumentRequest request) {
        entity.setDocumentType(request.documentType());
        entity.setIssuingCountry(request.issuingCountry());
        entity.setIssuedAt(request.issuedAt());
        entity.setExpiresAt(request.expiresAt());
        entity.setFrontFileReference(request.frontFileReference().strip());
        entity.setBackFileReference(request.backFileReference());
    }

    public KycDocumentResponse toResponse(KycDocument entity) {
        return new KycDocumentResponse(
                entity.getId(),
                entity.getDocumentType(),
                entity.getIssuingCountry(),
                entity.getIssuedAt(),
                entity.getExpiresAt(),
                entity.getFrontFileReference(),
                entity.getBackFileReference(),
                entity.getVerificationStatus(),
                entity.getFailureReason(),
                entity.getVerifiedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
