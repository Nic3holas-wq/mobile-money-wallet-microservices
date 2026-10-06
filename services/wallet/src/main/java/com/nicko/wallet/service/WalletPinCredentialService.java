package com.nicko.wallet.service;

import com.nicko.wallet.entity.WalletPinCredential;
import com.nicko.wallet.repository.WalletPinCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletPinCredentialService {

    public enum Result { SUCCESS, PIN_NOT_SET, CURRENT_PIN_REQUIRED, INVALID_PIN, LOCKED, CONFIGURATION_MISSING }

    private final WalletPinCredentialRepository repository;
    private final WalletPinCrypto crypto;

    @Value("${app.stepup.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.stepup.pin-lock-duration:PT15M}")
    private Duration lockDuration;

    @Transactional
    public Result setOrChange(UUID customerId, String newPin, String currentPin) {
        if (!crypto.isConfigured()) {
            return Result.CONFIGURATION_MISSING;
        }
        WalletPinCredential credential = repository.findByCustomerIdForUpdate(customerId).orElse(null);
        if (credential != null) {
            if (isLocked(credential)) {
                return Result.LOCKED;
            }
            if (currentPin == null) {
                return Result.CURRENT_PIN_REQUIRED;
            }
            if (!validPin(credential, currentPin)) {
                recordFailedAttempt(credential);
                return isLocked(credential) ? Result.LOCKED : Result.INVALID_PIN;
            }
        } else {
            credential = new WalletPinCredential();
            credential.setCustomerId(customerId);
        }

        credential.setPinSalt(crypto.newSalt());
        credential.setPinHash(crypto.hashPin(customerId, newPin, credential.getPinSalt()));
        credential.setFailedAttempts(0);
        credential.setLockedUntil(null);
        credential.setUpdatedAt(Instant.now());
        repository.save(credential);
        return Result.SUCCESS;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result verify(UUID customerId, String pin) {
        if (!crypto.isConfigured()) {
            return Result.CONFIGURATION_MISSING;
        }
        WalletPinCredential credential = repository.findByCustomerIdForUpdate(customerId).orElse(null);
        if (credential == null) {
            return Result.PIN_NOT_SET;
        }
        if (isLocked(credential)) {
            return Result.LOCKED;
        }
        if (!validPin(credential, pin)) {
            recordFailedAttempt(credential);
            return isLocked(credential) ? Result.LOCKED : Result.INVALID_PIN;
        }
        credential.setFailedAttempts(0);
        credential.setLockedUntil(null);
        credential.setUpdatedAt(Instant.now());
        return Result.SUCCESS;
    }

    private boolean validPin(WalletPinCredential credential, String pin) {
        return crypto.matches(crypto.hashPin(credential.getCustomerId(), pin, credential.getPinSalt()),
                credential.getPinHash());
    }

    private boolean isLocked(WalletPinCredential credential) {
        Instant now = Instant.now();
        if (credential.getLockedUntil() == null) {
            return false;
        }
        if (credential.getLockedUntil().isAfter(now)) {
            return true;
        }
        credential.setLockedUntil(null);
        credential.setFailedAttempts(0);
        return false;
    }

    private void recordFailedAttempt(WalletPinCredential credential) {
        int attempts = credential.getFailedAttempts() + 1;
        credential.setFailedAttempts(attempts);
        if (attempts >= maxAttempts) {
            credential.setLockedUntil(Instant.now().plus(lockDuration));
        }
        credential.setUpdatedAt(Instant.now());
    }
}
