package com.nicko.wallet.service;

import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.event.CustomerWalletCreationRequestedEvent;
import com.nicko.wallet.repository.WalletRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletNumberGenerator walletNumberGenerator;
    private final WalletOutboxEventService walletOutboxEventService;

    @Transactional
    public void createWallet(
            CustomerWalletCreationRequestedEvent event
    ) {

        UUID customerId = event.customerId();

        if (walletRepository.existsByCustomerId(customerId)) {
            return;
        }

        Wallet wallet = new Wallet();

        wallet.setCustomerId(customerId);
        wallet.setWalletNumber(walletNumberGenerator.generate());
        wallet.setCurrency(extractCurrency(event));
        wallet.setBalance(BigDecimal.ZERO);

        Wallet savedWallet = walletRepository.save(wallet);

        walletOutboxEventService.recordWalletCreated(savedWallet);
    }

    private String extractCurrency(
            CustomerWalletCreationRequestedEvent event
    ) {

        Object currency = event.data().get("currency");

        if (currency == null) {
            return "KES";
        }

        return currency.toString();
    }
}