package com.nicko.wallet.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stepup_token", indexes = {
        @Index(name = "idx_stepup_token_transfer_id", columnList = "transfer_id"),
        @Index(name = "idx_stepup_token_wallet_id", columnList = "wallet_id")
})
@Getter
@Setter
@NoArgsConstructor
public class StepupToken {

    public enum Status { PENDING, VERIFIED, CONSUMED, EXPIRED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false, updatable = false)
    private WalletTransfer walletTransfer;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    @NotNull
    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @NotNull
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private String channel;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "resend_count", nullable = false)
    private int resendCount = 0;

    @NotNull
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
