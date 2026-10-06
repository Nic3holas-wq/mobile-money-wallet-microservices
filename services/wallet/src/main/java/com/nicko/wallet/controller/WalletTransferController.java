package com.nicko.wallet.controller;

import com.nicko.wallet.dto.WalletTransferRequest;
import com.nicko.wallet.dto.WalletTransferResponse;
import com.nicko.wallet.dto.AcquireStepupTokenRequest;
import com.nicko.wallet.dto.CompleteWalletTransferRequest;
import com.nicko.wallet.dto.StepupTokenResponse;
import com.nicko.wallet.service.WalletTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets/{sourceWalletId}/transfers")
@RequiredArgsConstructor
public class WalletTransferController {

    private final WalletTransferService walletTransferService;

    @PostMapping
    public WalletTransferResponse transfer(@PathVariable UUID sourceWalletId,
                                           @Valid @RequestBody WalletTransferRequest request) {
        return walletTransferService.transfer(sourceWalletId, request);
    }

    @PostMapping("/{transferId}/stepup-token")
    public StepupTokenResponse acquireStepupToken(@PathVariable UUID sourceWalletId,
                                                  @PathVariable UUID transferId,
                                                  @Valid @RequestBody AcquireStepupTokenRequest request) {
        return walletTransferService.acquireStepupToken(sourceWalletId, transferId, request.pin());
    }

    @PostMapping("/{transferId}/complete")
    public WalletTransferResponse complete(@PathVariable UUID sourceWalletId,
                                           @PathVariable UUID transferId,
                                           @Valid @RequestBody CompleteWalletTransferRequest request) {
        return walletTransferService.complete(sourceWalletId, transferId, request.stepupToken());
    }
}
