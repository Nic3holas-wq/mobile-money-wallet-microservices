package com.nicko.wallet.repository;

import com.nicko.wallet.entity.WalletPaymentOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletPaymentOperationRepository extends JpaRepository<WalletPaymentOperation, UUID> {
    Optional<WalletPaymentOperation> findByWalletIdAndPaymentReference(UUID walletId, String paymentReference);
}
