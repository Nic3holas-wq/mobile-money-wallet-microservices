package com.nicko.wallet.controller;

import com.nicko.wallet.dto.PaymentWalletAccountResponse;
import com.nicko.wallet.service.WalletPaymentOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/payment-accounts")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PAYMENT_SERVICE')")
public class PaymentWalletAccountController {

    private final WalletPaymentOperationService walletPaymentOperationService;

    @GetMapping("/{customerId}")
    public PaymentWalletAccountResponse getByCustomerId(@PathVariable UUID customerId) {
        return walletPaymentOperationService.getPaymentAccount(customerId);
    }
}
