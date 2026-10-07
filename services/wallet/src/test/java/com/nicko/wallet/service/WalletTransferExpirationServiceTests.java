package com.nicko.wallet.service;

import com.nicko.wallet.entity.StepupToken;
import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.WalletTransfer;
import com.nicko.wallet.repository.StepupTokenRepository;
import com.nicko.wallet.repository.WalletRepository;
import com.nicko.wallet.repository.WalletTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletTransferExpirationServiceTests {
    @Mock WalletRepository walletRepository;
    @Mock WalletTransferRepository transferRepository;
    @Mock StepupTokenRepository tokenRepository;
    @Mock WalletOutboxEventService outbox;

    WalletTransferExpirationService service;
    Wallet source;
    Wallet destination;
    WalletTransfer transfer;
    UUID transferId;

    @BeforeEach
    void setUp() {
        service = new WalletTransferExpirationService(walletRepository, transferRepository, tokenRepository, outbox);
        source = wallet("source");
        destination = wallet("destination");
        transferId = UUID.randomUUID();
        transfer = new WalletTransfer();
        transfer.setId(transferId);
        transfer.setSourceWallet(source);
        transfer.setDestinationWallet(destination);
        transfer.setAmount(new BigDecimal("12.00"));
        transfer.setStatus(WalletTransfer.Status.PENDING_STEPUP);
        transfer.setExpiresAt(Instant.now().minusSeconds(1));
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        UUID low = source.getPublicId().compareTo(destination.getPublicId()) < 0 ? source.getPublicId() : destination.getPublicId();
        UUID high = low.equals(source.getPublicId()) ? destination.getPublicId() : source.getPublicId();
        when(walletRepository.findByPublicIdForUpdate(low)).thenReturn(Optional.of(source.getPublicId().equals(low) ? source : destination));
        when(walletRepository.findByPublicIdForUpdate(high)).thenReturn(Optional.of(source.getPublicId().equals(high) ? source : destination));
        when(transferRepository.findByIdForUpdate(transferId)).thenReturn(Optional.of(transfer));
    }

    @Test
    void expiresTransferReleasesReservationAndExpiresVerifiedToken() {
        source.setReservedBalance(new BigDecimal("20.00"));
        StepupToken token = new StepupToken();
        token.setStatus(StepupToken.Status.VERIFIED);
        when(tokenRepository.findByTransferAndStatusForUpdate(transferId, StepupToken.Status.VERIFIED)).thenReturn(Optional.of(token));

        service.expire(transferId);

        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("8.00"), source.getReservedBalance());
        org.junit.jupiter.api.Assertions.assertEquals(WalletTransfer.Status.FAILED, transfer.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("STEPUP_EXPIRED", transfer.getFailureReason());
        org.junit.jupiter.api.Assertions.assertEquals(StepupToken.Status.EXPIRED, token.getStatus());
        verify(outbox).recordWalletTransferFailed(transfer);
    }

    @Test
    void recordsReservationMismatchAndStillFailsTransfer() {
        source.setReservedBalance(new BigDecimal("2.00"));
        when(tokenRepository.findByTransferAndStatusForUpdate(transferId, StepupToken.Status.VERIFIED)).thenReturn(Optional.empty());

        service.expire(transferId);

        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("2.00"), source.getReservedBalance());
        org.junit.jupiter.api.Assertions.assertEquals("STEPUP_RESERVATION_MISMATCH", transfer.getFailureReason());
        org.junit.jupiter.api.Assertions.assertEquals(WalletTransfer.Status.FAILED, transfer.getStatus());
        verify(outbox).recordWalletTransferFailed(transfer);
    }

    @Test
    void ignoresMissingOrNotYetExpiredTransfer() {
        when(transferRepository.findById(transferId)).thenReturn(Optional.empty());
        service.expire(transferId);
        verifyNoInteractions(walletRepository, tokenRepository, outbox);

        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        transfer.setExpiresAt(Instant.now().plusSeconds(60));
        service.expire(transferId);
        verify(tokenRepository, never()).findByTransferAndStatusForUpdate(any(), any());
        verifyNoInteractions(outbox);
    }

    private static Wallet wallet(String label) {
        Wallet wallet = new Wallet();
        wallet.setPublicId(UUID.randomUUID());
        wallet.setReservedBalance(BigDecimal.ZERO);
        return wallet;
    }
}
