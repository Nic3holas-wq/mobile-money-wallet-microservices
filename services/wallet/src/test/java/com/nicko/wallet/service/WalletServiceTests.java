package com.nicko.wallet.service;

import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.enums.WalletStatus;
import com.nicko.wallet.event.CustomerWalletCreationRequestedEvent;
import com.nicko.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTests {

    @Mock WalletRepository walletRepository;
    @Mock WalletNumberGenerator numberGenerator;
    @Mock WalletOutboxEventService outboxService;

    private final UUID customerId = UUID.randomUUID();
    private WalletService service;

    @BeforeEach
    void setUp() {
        service = new WalletService(walletRepository, numberGenerator, outboxService);
        when(numberGenerator.generate()).thenReturn("129123456789");
    }

    @Test
    void createsActiveWalletWithEventCurrencyAndRecordsWalletCreatedEvent() {
        when(walletRepository.existsByCustomerId(customerId)).thenReturn(false);
        when(walletRepository.save(any(Wallet.class))).thenAnswer(invocation -> {
            Wallet wallet = invocation.getArgument(0);
            wallet.setId(UUID.randomUUID());
            return wallet;
        });
        CustomerWalletCreationRequestedEvent event = event(Map.of("currency", "USD"));

        service.createWallet(event);

        ArgumentCaptor<Wallet> saved = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).save(saved.capture());
        Wallet wallet = saved.getValue();
        assertThat(wallet.getCustomerId()).isEqualTo(customerId);
        assertThat(wallet.getWalletNumber()).isEqualTo("129123456789");
        assertThat(wallet.getCurrency()).isEqualTo("USD");
        assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        verify(outboxService).recordWalletCreated(wallet);
    }

    @Test
    void defaultsCurrencyAndSkipsDuplicateCustomerWallets() {
        when(walletRepository.existsByCustomerId(customerId)).thenReturn(false);
        when(walletRepository.save(any(Wallet.class))).thenAnswer(invocation -> {
            Wallet wallet = invocation.getArgument(0);
            wallet.setId(UUID.randomUUID());
            return wallet;
        });
        service.createWallet(event(Map.of()));
        ArgumentCaptor<Wallet> saved = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).save(saved.capture());
        assertThat(saved.getValue().getCurrency()).isEqualTo("KES");

        reset(walletRepository, numberGenerator, outboxService);
        when(walletRepository.existsByCustomerId(customerId)).thenReturn(true);
        service = new WalletService(walletRepository, numberGenerator, outboxService);
        service.createWallet(event(Map.of("currency", "USD")));
        verify(walletRepository, never()).save(any());
        verifyNoInteractions(numberGenerator, outboxService);
    }

    private CustomerWalletCreationRequestedEvent event(Map<String, Object> data) {
        return new CustomerWalletCreationRequestedEvent(UUID.randomUUID(), "customer.wallet.creation.requested.v1",
                1, "2026-01-01T00:00:00Z", customerId, UUID.randomUUID(), data);
    }
}
