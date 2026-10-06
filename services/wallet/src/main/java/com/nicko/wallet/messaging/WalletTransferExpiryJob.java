package com.nicko.wallet.messaging;

import com.nicko.wallet.entity.WalletTransfer;
import com.nicko.wallet.repository.WalletTransferRepository;
import com.nicko.wallet.service.WalletTransferExpirationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class WalletTransferExpiryJob {

    private final WalletTransferRepository transferRepository;
    private final WalletTransferExpirationService expirationService;

    @Scheduled(fixedDelayString = "${app.stepup.expiry-poll-interval:PT30S}")
    public void expirePendingTransfers() {
        for (WalletTransfer transfer : transferRepository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                WalletTransfer.Status.PENDING_STEPUP, Instant.now())) {
            expirationService.expire(transfer.getId());
        }
    }
}
