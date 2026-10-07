package com.nicko.wallet.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet_transfer_reversal")
@Getter
@Setter
@NoArgsConstructor
public class WalletTransferReversal {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_transfer_id", nullable = false, unique = true, updatable = false)
    private WalletTransfer originalTransfer;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reversal_transfer_id", nullable = false, unique = true, updatable = false)
    private WalletTransfer reversalTransfer;

    @NotBlank
    @Size(max = 100)
    @Column(name = "admin_subject", nullable = false, updatable = false, length = 100)
    private String adminSubject;

    @NotBlank
    @Size(max = 255)
    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
