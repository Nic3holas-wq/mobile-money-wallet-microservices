package com.nicko.wallet.controller;

import com.nicko.wallet.dto.PaymentOperationRequest;
import com.nicko.wallet.dto.PaymentOperationResponse;
import com.nicko.wallet.service.WalletPaymentOperationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/wallets/{walletId}")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PAYMENT_SERVICE')")
public class PaymentWalletOperationsController {

    private final WalletPaymentOperationService walletPaymentOperationService;

    @PostMapping("/reservations")
    public PaymentOperationResponse reserve(@PathVariable UUID walletId,
                                            @Valid @RequestBody PaymentOperationRequest request) {
        return walletPaymentOperationService.reserve(walletId, request);
    }

    @PostMapping("/reservations/{paymentReference}/commit")
    public PaymentOperationResponse commit(@PathVariable UUID walletId,
                                           @PathVariable String paymentReference) {
        return walletPaymentOperationService.commit(walletId, paymentReference);
    }

    @PostMapping("/reservations/{paymentReference}/release")
    public PaymentOperationResponse release(@PathVariable UUID walletId,
                                             @PathVariable String paymentReference) {
        return walletPaymentOperationService.release(walletId, paymentReference);
    }

    @PostMapping("/credits")
    public PaymentOperationResponse credit(@PathVariable UUID walletId,
                                           @Valid @RequestBody PaymentOperationRequest request) {
        return walletPaymentOperationService.credit(walletId, request);
    }
}
