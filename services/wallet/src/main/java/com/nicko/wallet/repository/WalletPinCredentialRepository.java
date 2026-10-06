package com.nicko.wallet.repository;

import com.nicko.wallet.entity.WalletPinCredential;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletPinCredentialRepository extends JpaRepository<WalletPinCredential, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from WalletPinCredential p where p.customerId = :customerId")
    Optional<WalletPinCredential> findByCustomerIdForUpdate(@Param("customerId") UUID customerId);
}
