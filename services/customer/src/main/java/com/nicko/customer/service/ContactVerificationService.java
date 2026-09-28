package com.nicko.customer.service;

import com.nicko.customer.customer.*;
import com.nicko.customer.customer.enums.VerificationSource;
import com.nicko.customer.dto.*;
import com.nicko.customer.mapper.CustomerContactMapper;
import com.nicko.customer.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContactVerificationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CustomerOwnership ownership;
    private final CustomerContactRepository contacts;
    private final ContactVerificationRepository challenges;
    private final VerificationCodeProtection protection;
    private final VerificationDelivery delivery;
    private final CustomerContactMapper mapper;
    private final CustomerAuditService audit;
    private final OutboxEventService outbox;
    private final Clock clock;

    @Transactional
    public VerificationChallengeResponse request(UUID userId, UUID contactId) {
        var customer = ownership.lock(userId);
        var contact = contact(customer.getId(), contactId);
        if (contact.isVerified()) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Contact is already verified"); }
        var now = clock.instant();
        var latest = challenges.findFirstByContactIdOrderByCreatedAtDesc(contactId).orElse(null);
        if ((latest != null && now.isBefore(latest.getCreatedAt().plusSeconds(60)))
                || challenges.countByCustomerIdAndCreatedAtAfter(customer.getId(), now.minusSeconds(3600)) >= 5) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Verification request limit reached; retry later");
        }
        // Invalidate older challenges; an old code cannot succeed after resend.
        challenges.findByContactIdAndConsumedAtIsNull(contactId).forEach(c -> c.setConsumedAt(now));
        var challenge = new ContactVerificationChallenge();
        challenge.setId(UUID.randomUUID());
        challenge.setCustomerId(customer.getId());
        challenge.setContactId(contactId);
        challenge.setContactFingerprint(fingerprint(contact));
        String code = String.format(java.util.Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        challenge.setCodeHash(protection.hash(challenge.getId() + ":" + code));
        challenge.setCreatedAt(now);
        challenge.setExpiresAt(now.plusSeconds(300));
        challenges.saveAndFlush(challenge);
        // Bounded local delivery. Any failure rolls back the challenge and prior invalidations.
        delivery.send(challenge.getId(), contact.getContactType(), contact.getContactValue(), code);
        return new VerificationChallengeResponse(challenge.getId(), challenge.getExpiresAt(), now.plusSeconds(60), "PENDING");
    }

    @Transactional(readOnly = true)
    public VerificationChallengeResponse get(UUID userId, UUID contactId, UUID challengeId) {
        var customer = ownership.require(userId);
        contact(customer.getId(), contactId);
        var challenge = challenges.findByIdAndCustomerIdAndContactId(challengeId, customer.getId(), contactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verification challenge not found"));
        String status = challenge.getConsumedAt() != null ? "CLOSED"
                : !challenge.getExpiresAt().isAfter(clock.instant()) ? "EXPIRED" : "PENDING";
        return new VerificationChallengeResponse(challenge.getId(), challenge.getExpiresAt(), challenge.getCreatedAt().plusSeconds(60), status);
    }

    @Transactional(noRollbackFor = VerificationRejectedException.class)
    public CustomerContactResponse confirm(UUID userId, UUID contactId, UUID challengeId, ConfirmContactRequest request) {
        var customer = ownership.lock(userId);
        var contact = contact(customer.getId(), contactId);
        var challenge = challenges.findByIdAndCustomerIdAndContactId(challengeId, customer.getId(), contactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verification challenge not found"));
        var now = clock.instant();
        if (challenge.getConsumedAt() != null || !challenge.getExpiresAt().isAfter(now) || challenge.getAttempts() >= 5
                || !challenge.getContactFingerprint().equals(fingerprint(contact))) { throw new VerificationRejectedException(); }
        challenge.setAttempts(challenge.getAttempts() + 1);
        boolean valid = MessageDigest.isEqual(challenge.getCodeHash().getBytes(StandardCharsets.UTF_8),
                protection.hash(challengeId + ":" + request.code()).getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            if (challenge.getAttempts() >= 5) { challenge.setConsumedAt(now); }
            challenges.saveAndFlush(challenge);
            throw new VerificationRejectedException();
        }
        challenge.setConsumedAt(now);
        contact.setVerified(true);
        contact.setVerifiedAt(now);
        contact.setVerificationSource(VerificationSource.OTP);
        contacts.saveAndFlush(contact);
        audit.record(customer.getId(), userId, "CONTACT_VERIFIED", "CUSTOMER_CONTACT", contactId,
                Map.of("verified", false), Map.of("verified", true, "method", "OTP"));
        outbox.record(customer, "customer.contact.verified.v1", "CUSTOMER_CONTACT", contactId,
                Map.of("contactId", contactId.toString(), "contactType", contact.getContactType().name()));
        return mapper.toResponse(contact);
    }
    private String fingerprint(CustomerContact contact) {
        return protection.hash(contact.getContactType().name() + ":" + contact.getContactValue());
    }
    private CustomerContact contact(UUID customerId, UUID id) {
        return contacts.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
    }
}
