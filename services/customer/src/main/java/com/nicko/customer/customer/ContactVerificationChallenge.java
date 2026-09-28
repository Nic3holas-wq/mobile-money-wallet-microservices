package com.nicko.customer.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contact_verification_challenge")
@Getter @Setter
public class ContactVerificationChallenge {
    @Id private UUID id;
    @Column(nullable = false) private UUID customerId;
    @Column(nullable = false) private UUID contactId;
    @Column(nullable = false, length = 64) private String contactFingerprint;
    @Column(nullable = false, length = 64) private String codeHash;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant expiresAt;
    @Column(nullable = false) private int attempts;
    private Instant consumedAt;
}
