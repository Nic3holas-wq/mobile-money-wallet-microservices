package com.nicko.wallet.service;

import com.nicko.wallet.entity.StepupToken;
import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.WalletTransfer;
import com.nicko.wallet.repository.StepupTokenRepository;
import com.nicko.wallet.repository.WalletRepository;
import com.nicko.wallet.repository.WalletTransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletTransferExpirationService {

    private final WalletRepository walletRepository;
    private final WalletTransferRepository transferRepository;
    private final StepupTokenRepository stepupTokenRepository;
    private final WalletOutboxEventService outboxEventService;

    @Transactional
    public void expire(UUID transferId) {
        WalletTransfer observed = transferRepository.findById(transferId).orElse(null);
        if (observed == null) {
            return;
        }
        UUID sourcePublicId = observed.getSourceWallet().getPublicId();
        UUID destinationPublicId = observed.getDestinationWallet().getPublicId();
        UUID lowId = sourcePublicId.compareTo(destinationPublicId) < 0 ? sourcePublicId : destinationPublicId;
        UUID highId = lowId.equals(sourcePublicId) ? destinationPublicId : sourcePublicId;
        Wallet low = walletRepository.findByPublicIdForUpdate(lowId).orElse(null);
        Wallet high = walletRepository.findByPublicIdForUpdate(highId).orElse(null);
        if (low == null || high == null) {
            return;
        }

        WalletTransfer transfer = transferRepository.findByIdForUpdate(transferId).orElse(null);
        if (transfer == null || transfer.getStatus() != WalletTransfer.Status.PENDING_STEPUP
                || transfer.getExpiresAt().isAfter(Instant.now())) {
            return;
        }

        Wallet source = transfer.getSourceWallet();
        if (source.getReservedBalance().compareTo(transfer.getAmount()) >= 0) {
            source.setReservedBalance(source.getReservedBalance().subtract(transfer.getAmount()));
            transfer.setFailureReason("STEPUP_EXPIRED");
        } else {
            transfer.setFailureReason("STEPUP_RESERVATION_MISMATCH");
            log.error("Wallet transfer {} expired with an inconsistent reserved balance", transferId);
        }
        transfer.setStatus(WalletTransfer.Status.FAILED);
        stepupTokenRepository.findByTransferAndStatusForUpdate(transferId, StepupToken.Status.VERIFIED)
                .ifPresent(token -> {
                    token.setStatus(StepupToken.Status.EXPIRED);
                    token.setUpdatedAt(Instant.now());
                });
        outboxEventService.recordWalletTransferFailed(transfer);
    }
}
