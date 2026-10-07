package com.nicko.wallet.repository;

import com.nicko.wallet.entity.WalletTransferReversal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletTransferReversalRepository extends JpaRepository<WalletTransferReversal, UUID> {
    Optional<WalletTransferReversal> findByOriginalTransferId(UUID originalTransferId);
}
