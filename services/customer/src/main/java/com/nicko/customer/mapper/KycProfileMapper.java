package com.nicko.customer.mapper;

import com.nicko.customer.entity.KycProfile;
import com.nicko.customer.dto.KycProfileResponse;
import com.nicko.customer.dto.KycProfileRequest;
import org.springframework.stereotype.Component;

@Component
public class KycProfileMapper {
    public void update(KycProfile entity, KycProfileRequest request) {
        entity.setRequestedTier(request.requestedTier());
        entity.setOccupation(request.occupation());
        entity.setEmployerName(request.employerName());
        entity.setSourceOfFunds(request.sourceOfFunds());
        entity.setExpectedMonthlyVolume(request.expectedMonthlyVolume());
    }

    public KycProfileResponse toResponse(KycProfile entity) {
        return new KycProfileResponse(
                entity.getId(),
                entity.getStatus(),
                entity.getRequestedTier(),
                entity.getApprovedTier(),
                entity.getOccupation(),
                entity.getEmployerName(),
                entity.getSourceOfFunds(),
                entity.getExpectedMonthlyVolume(),
                entity.getSubmittedAt(),
                entity.getReviewedAt(),
                entity.getRejectionReason(),
                entity.getExpiresAt(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
