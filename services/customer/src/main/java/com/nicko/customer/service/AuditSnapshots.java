package com.nicko.customer.service;

import com.nicko.customer.entity.*;
import java.util.LinkedHashMap;
import java.util.Map;

// Explicit allowlists: never serialize entities, document numbers, hashes, ciphertext or file references.
final class AuditSnapshots {
    private AuditSnapshots() {}
    private static Map<String, Object> fields(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            Object value = fields[i + 1];
            if (value != null) { result.put((String) fields[i], value.toString()); }
        }
        return result;
    }
    static Map<String, Object> profile(KycProfile p) {
        return fields("status", p.getStatus(), "requestedTier", p.getRequestedTier(),
                "approvedTier", p.getApprovedTier(), "pepStatus", p.getPepStatus(),
                "sanctionsStatus", p.getSanctionsStatus(), "riskRating", p.getRiskRating(),
                "submittedAt", p.getSubmittedAt(), "reviewedAt", p.getReviewedAt(),
                "reviewedBy", p.getReviewedByKeycloakId(), "rejectionReason", p.getRejectionReason(),
                "expiresAt", p.getExpiresAt());
    }
    static Map<String, Object> document(KycDocument d) {
        return fields("documentId", d.getId(), "profileId", d.getKycProfile().getId(),
                "documentType", d.getDocumentType(), "issuingCountry", d.getIssuingCountry(),
                "issuedAt", d.getIssuedAt(), "expiresAt", d.getExpiresAt(),
                "status", d.getVerificationStatus(), "verificationProvider", d.getVerificationProvider(),
                "providerReference", d.getProviderReference(), "failureReason", d.getFailureReason(),
                "verifiedAt", d.getVerifiedAt());
    }
    static Map<String, Object> limit(CustomerLimit l) {
        return fields("transactionType", l.getTransactionType(), "currency", l.getCurrency(),
                "perTransactionLimit", l.getPerTransactionLimit(), "dailyLimit", l.getDailyLimit(),
                "monthlyLimit", l.getMonthlyLimit(), "dailyCountLimit", l.getDailyCountLimit(),
                "effectiveFrom", l.getEffectiveFrom(), "effectiveUntil", l.getEffectiveUntil(), "reason", l.getReason());
    }
}
