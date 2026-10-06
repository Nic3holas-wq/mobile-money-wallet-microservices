package com.nicko.wallet.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stepup_token")
@Getter
@Setter
@NoArgsConstructor
public class StepupToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false)
    private WalletTransfer walletTransfer;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @NotNull
    @Column(name = "token_hash", nullable = false, updatable = false, unique = true)

    @NotNull
    @Column(name = "status", nullable = false)
    private String status;

    @NotNull
    @Column(name = "attempts",nullable = false)
    private int attempts;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @NotNull
    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    @NotNull
    @ColumnDefault(("now()"))
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;


}
