package com.nicko.wallet.repository;

import com.nicko.wallet.entity.StepupToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StepupTokenRepository extends JpaRepository<StepupToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from StepupToken t where t.walletTransfer.id = :transferId and t.status = :status")
    Optional<StepupToken> findByTransferAndStatusForUpdate(@Param("transferId") UUID transferId,
                                                           @Param("status") StepupToken.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from StepupToken t where t.walletTransfer.id = :transferId and t.wallet.id = :walletId and t.tokenHash = :tokenHash")
    Optional<StepupToken> findByTransferWalletAndTokenHashForUpdate(@Param("transferId") UUID transferId,
                                                                    @Param("walletId") UUID walletId,
                                                                    @Param("tokenHash") String tokenHash);
}
