package com.nicko.wallet.service;

import com.nicko.wallet.entity.WalletPinCredential;
import com.nicko.wallet.repository.WalletPinCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletPinCredentialServiceTests {

    @Mock WalletPinCredentialRepository repository;

    private final UUID customerId = UUID.randomUUID();
    private WalletPinCrypto crypto;
    private WalletPinCredentialService service;

    @BeforeEach
    void setUp() {
        crypto = new WalletPinCrypto("k".repeat(32));
        service = new WalletPinCredentialService(repository, crypto);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
        ReflectionTestUtils.setField(service, "lockDuration", java.time.Duration.ofMinutes(15));
    }

    @Test
    void setOrChangeCreatesFirstCredentialAndVerifyAcceptsIt() {
        when(repository.findByCustomerIdForUpdate(customerId)).thenReturn(Optional.empty());

        assertThat(service.setOrChange(customerId, "123456", null))
                .isEqualTo(WalletPinCredentialService.Result.SUCCESS);

        ArgumentCaptor<WalletPinCredential> saved = ArgumentCaptor.forClass(WalletPinCredential.class);
        verify(repository).save(saved.capture());
        WalletPinCredential credential = saved.getValue();
        assertThat(credential.getCustomerId()).isEqualTo(customerId);
        assertThat(credential.getPinSalt()).isNotBlank();
        assertThat(credential.getPinHash()).doesNotContain("123456");
        assertThat(credential.getUpdatedAt()).isNotNull();

        when(repository.findByCustomerIdForUpdate(customerId)).thenReturn(Optional.of(credential));
        assertThat(service.verify(customerId, "123456")).isEqualTo(WalletPinCredentialService.Result.SUCCESS);
        assertThat(credential.getFailedAttempts()).isZero();
    }

    @Test
    void changingExistingPinRequiresAndValidatesCurrentPin() {
        WalletPinCredential credential = credential("123456");
        when(repository.findByCustomerIdForUpdate(customerId)).thenReturn(Optional.of(credential));

        assertThat(service.setOrChange(customerId, "654321", null))
                .isEqualTo(WalletPinCredentialService.Result.CURRENT_PIN_REQUIRED);
        assertThat(service.setOrChange(customerId, "654321", "000000"))
                .isEqualTo(WalletPinCredentialService.Result.INVALID_PIN);
        assertThat(credential.getFailedAttempts()).isEqualTo(1);

        assertThat(service.setOrChange(customerId, "654321", "123456"))
                .isEqualTo(WalletPinCredentialService.Result.SUCCESS);
        verify(repository, times(1)).save(credential);
        assertThat(crypto.matches(crypto.hashPin(customerId, "654321", credential.getPinSalt()),
                credential.getPinHash())).isTrue();
    }

    @Test
    void wrongPinLocksAtConfiguredAttemptLimitAndRejectsFurtherAttempts() {
        WalletPinCredential credential = credential("123456");
        when(repository.findByCustomerIdForUpdate(customerId)).thenReturn(Optional.of(credential));

        assertThat(service.verify(customerId, "000000")).isEqualTo(WalletPinCredentialService.Result.INVALID_PIN);
        assertThat(service.verify(customerId, "000000")).isEqualTo(WalletPinCredentialService.Result.INVALID_PIN);
        assertThat(service.verify(customerId, "000000")).isEqualTo(WalletPinCredentialService.Result.LOCKED);
        assertThat(credential.getFailedAttempts()).isEqualTo(3);
        assertThat(credential.getLockedUntil()).isAfter(Instant.now());

        assertThat(service.verify(customerId, "123456")).isEqualTo(WalletPinCredentialService.Result.LOCKED);
        assertThat(credential.getFailedAttempts()).isEqualTo(3);
    }

    @Test
    void expiredLockIsClearedAndSuccessfulVerificationResetsFailures() {
        WalletPinCredential credential = credential("123456");
        credential.setFailedAttempts(2);
        credential.setLockedUntil(Instant.now().minusSeconds(1));
        when(repository.findByCustomerIdForUpdate(customerId)).thenReturn(Optional.of(credential));

        assertThat(service.verify(customerId, "123456")).isEqualTo(WalletPinCredentialService.Result.SUCCESS);
        assertThat(credential.getFailedAttempts()).isZero();
        assertThat(credential.getLockedUntil()).isNull();
    }

    @Test
    void returnsMissingPinAndConfigurationResultsWithoutPersistence() {
        assertThat(service.verify(customerId, "123456")).isEqualTo(WalletPinCredentialService.Result.PIN_NOT_SET);
        verify(repository, never()).save(any());
        clearInvocations(repository);

        WalletPinCredentialService unconfigured = new WalletPinCredentialService(repository,
                new WalletPinCrypto("short"));
        assertThat(unconfigured.setOrChange(customerId, "123456", null))
                .isEqualTo(WalletPinCredentialService.Result.CONFIGURATION_MISSING);
        assertThat(unconfigured.verify(customerId, "123456"))
                .isEqualTo(WalletPinCredentialService.Result.CONFIGURATION_MISSING);
        verify(repository, never()).findByCustomerIdForUpdate(any());
    }

    private WalletPinCredential credential(String pin) {
        WalletPinCredential credential = new WalletPinCredential();
        credential.setCustomerId(customerId);
        credential.setPinSalt(crypto.newSalt());
        credential.setPinHash(crypto.hashPin(customerId, pin, credential.getPinSalt()));
        credential.setFailedAttempts(0);
        return credential;
    }
}
