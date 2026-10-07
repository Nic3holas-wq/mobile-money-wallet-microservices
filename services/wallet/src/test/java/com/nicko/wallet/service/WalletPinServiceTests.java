package com.nicko.wallet.service;

import com.nicko.wallet.customer.CustomerClient;
import com.nicko.wallet.customer.CustomerDto;
import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.enums.WalletStatus;
import com.nicko.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletPinServiceTests {

    @Mock CustomerClient customerClient;
    @Mock WalletRepository walletRepository;
    @Mock WalletPinCredentialService credentialService;

    private final UUID customerId = UUID.randomUUID();
    private final UUID walletPublicId = UUID.randomUUID();
    private final String authorization = "Bearer token";
    private WalletPinService service;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        service = new WalletPinService(customerClient, walletRepository, credentialService);
        wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        wallet.setPublicId(walletPublicId);
        wallet.setCustomerId(customerId);
        wallet.setStatus(WalletStatus.ACTIVE);
    }

    @Test
    void setsPinForOwningActiveEligibleCustomer() {
        when(customerClient.getCurrentCustomer(authorization)).thenReturn(customer(true, "ACTIVE"));
        when(walletRepository.findByPublicId(walletPublicId)).thenReturn(Optional.of(wallet));
        when(credentialService.setOrChange(customerId, "123456", null))
                .thenReturn(WalletPinCredentialService.Result.SUCCESS);

        service.setOrChange(walletPublicId, authorization, "123456", null);

        verify(customerClient).getCurrentCustomer(authorization);
        verify(credentialService).setOrChange(customerId, "123456", null);
    }

    @Test
    void rejectsUnknownWalletAndWalletOwnedByAnotherCustomer() {
        when(customerClient.getCurrentCustomer(authorization)).thenReturn(customer(true, "ACTIVE"));
        when(walletRepository.findByPublicId(walletPublicId)).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.setOrChange(walletPublicId, authorization, "123456", null));

        wallet.setCustomerId(UUID.randomUUID());
        when(walletRepository.findByPublicId(walletPublicId)).thenReturn(Optional.of(wallet));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.setOrChange(walletPublicId, authorization, "123456", null));
        verifyNoInteractions(credentialService);
    }

    @Test
    void rejectsIneligibleOrInactiveCustomerAndInactiveWallet() {
        when(walletRepository.findByPublicId(walletPublicId)).thenReturn(Optional.of(wallet));
        when(customerClient.getCurrentCustomer(authorization)).thenReturn(customer(false, "ACTIVE"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.setOrChange(walletPublicId, authorization, "123456", null));

        when(customerClient.getCurrentCustomer(authorization)).thenReturn(customer(true, "PENDING"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.setOrChange(walletPublicId, authorization, "123456", null));

        when(customerClient.getCurrentCustomer(authorization)).thenReturn(customer(true, "ACTIVE"));
        wallet.setStatus(WalletStatus.FROZEN);
        when(walletRepository.findByPublicId(walletPublicId)).thenReturn(Optional.of(wallet));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.setOrChange(walletPublicId, authorization, "123456", null));
        verifyNoInteractions(credentialService);
    }

    @Test
    void mapsCredentialResultsToExpectedHttpStatuses() {
        when(customerClient.getCurrentCustomer(authorization)).thenReturn(customer(true, "ACTIVE"));
        when(walletRepository.findByPublicId(walletPublicId)).thenReturn(Optional.of(wallet));

        assertStatus(HttpStatus.CONFLICT, withCredentialResult(WalletPinCredentialService.Result.PIN_NOT_SET));
        assertStatus(HttpStatus.CONFLICT, withCredentialResult(WalletPinCredentialService.Result.CURRENT_PIN_REQUIRED));
        assertStatus(HttpStatus.UNAUTHORIZED, withCredentialResult(WalletPinCredentialService.Result.INVALID_PIN));
        assertStatus(HttpStatus.TOO_MANY_REQUESTS, withCredentialResult(WalletPinCredentialService.Result.LOCKED));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
                withCredentialResult(WalletPinCredentialService.Result.CONFIGURATION_MISSING));
    }

    private Runnable withCredentialResult(WalletPinCredentialService.Result result) {
        when(credentialService.setOrChange(customerId, "123456", null)).thenReturn(result);
        return () -> service.setOrChange(walletPublicId, authorization, "123456", null);
    }

    private CustomerDto customer(boolean eligible, String status) {
        return new CustomerDto(customerId, "Test", "Customer", status, eligible);
    }

    private void assertStatus(HttpStatus status, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ResponseStatusException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ResponseStatusException.class))
                .extracting(ResponseStatusException::getStatusCode)
                .isEqualTo(status);
    }
}
