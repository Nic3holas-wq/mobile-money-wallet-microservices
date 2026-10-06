package com.nicko.wallet.service;

import com.nicko.wallet.customer.CustomerClient;
import com.nicko.wallet.customer.CustomerDto;
import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.enums.WalletStatus;
import com.nicko.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletPinService {

    private final CustomerClient customerClient;
    private final WalletRepository walletRepository;
    private final WalletPinCredentialService credentialService;

    public void setOrChange(UUID walletPublicId, String newPin, String currentPin) {
        CustomerDto customer = customerClient.getCurrentCustomer();
        Wallet wallet = walletRepository.findByPublicId(walletPublicId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found"));
        if (!customer.id().equals(wallet.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wallet does not belong to this customer");
        }
        if (!"ACTIVE".equals(customer.customerStatus()) || !customer.walletEligible()
                || wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer is not eligible to set a wallet PIN");
        }
        mapResult(credentialService.setOrChange(customer.id(), newPin, currentPin));
    }

    private void mapResult(WalletPinCredentialService.Result result) {
        switch (result) {
            case SUCCESS -> { }
            case PIN_NOT_SET -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Set a wallet PIN first");
            case CURRENT_PIN_REQUIRED -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Current PIN is required");
            case INVALID_PIN -> throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid current PIN");
            case LOCKED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PIN entry is temporarily locked");
            case CONFIGURATION_MISSING -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Wallet PIN verification is not configured");
        }
    }
}
