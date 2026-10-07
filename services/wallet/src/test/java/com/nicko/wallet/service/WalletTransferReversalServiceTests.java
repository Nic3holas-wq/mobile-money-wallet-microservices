package com.nicko.wallet.service;

import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.WalletTransfer;
import com.nicko.wallet.entity.WalletTransferReversal;
import com.nicko.wallet.entity.enums.WalletStatus;
import com.nicko.wallet.repository.WalletLedgerEntryRepository;
import com.nicko.wallet.repository.WalletRepository;
import com.nicko.wallet.repository.WalletTransferRepository;
import com.nicko.wallet.repository.WalletTransferReversalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletTransferReversalServiceTests {
    @Mock WalletRepository walletRepository;
    @Mock WalletTransferRepository transferRepository;
    @Mock WalletTransferReversalRepository reversalRepository;
    @Mock WalletLedgerEntryRepository ledgerRepository;
    @Mock WalletOutboxEventService outboxEventService;

    private WalletTransferReversalService service;
    private Wallet originalSource;
    private Wallet originalDestination;
    private WalletTransfer original;
    private UUID transferId;

    @BeforeEach
    void setUp() {
        service = new WalletTransferReversalService(walletRepository, transferRepository, reversalRepository,
                ledgerRepository, outboxEventService);
        originalSource = wallet(UUID.fromString("00000000-0000-0000-0000-000000000001"), "40.00", "0.00");
        originalDestination = wallet(UUID.fromString("00000000-0000-0000-0000-000000000002"), "60.00", "5.00");
        original = new WalletTransfer();
        transferId = UUID.randomUUID();
        original.setId(transferId);
        original.setReference("original-ref");
        original.setSourceWallet(originalSource);
        original.setDestinationWallet(originalDestination);
        original.setAmount(new BigDecimal("10.00"));
        original.setCurrency("KES");
        original.setIdempotencyKey("original-idem");
        original.setStatus(WalletTransfer.Status.COMPLETED);
        original.setExpiresAt(Instant.now());
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(original));
        when(transferRepository.findByIdForUpdate(transferId)).thenReturn(Optional.of(original));
        when(reversalRepository.findByOriginalTransferId(transferId)).thenReturn(Optional.empty());
        when(walletRepository.findByPublicIdForUpdate(any())).thenAnswer(invocation -> {
            UUID publicId = invocation.getArgument(0);
            return Optional.of(publicId.equals(originalSource.getPublicId()) ? originalSource : originalDestination);
        });
        when(transferRepository.saveAndFlush(any(WalletTransfer.class))).thenAnswer(invocation -> {
            WalletTransfer reversal = invocation.getArgument(0);
            reversal.setId(UUID.randomUUID());
            return reversal;
        });
    }

    @Test
    void reversesCompletedTransferWithOppositeLedgerEntriesAndAuditEvent() {
        var response = service.reverse(transferId, "admin-subject", "Customer dispute");

        assertThat(response.status()).isEqualTo(WalletTransfer.Status.COMPLETED);
        assertThat(response.sourceWalletId()).isEqualTo(originalDestination.getPublicId());
        assertThat(response.destinationWalletId()).isEqualTo(originalSource.getPublicId());
        assertThat(originalDestination.getBalance()).isEqualByComparingTo("50.00");
        assertThat(originalSource.getBalance()).isEqualByComparingTo("50.00");
        assertThat(originalDestination.getReservedBalance()).isEqualByComparingTo("5.00");
        ArgumentCaptor<WalletTransferReversal> audit = ArgumentCaptor.forClass(WalletTransferReversal.class);
        verify(reversalRepository).save(audit.capture());
        assertThat(audit.getValue().getOriginalTransfer()).isSameAs(original);
        assertThat(audit.getValue().getReversalTransfer().getId()).isEqualTo(response.transferId());
        assertThat(audit.getValue().getAdminSubject()).isEqualTo("admin-subject");
        assertThat(audit.getValue().getReason()).isEqualTo("Customer dispute");
        verify(ledgerRepository, times(2)).save(any());
        verify(outboxEventService).recordWalletTransferReversed(original, audit.getValue().getReversalTransfer(),
                "admin-subject", "Customer dispute");
    }

    @Test
    void rejectsNonCompletedAlreadyReversedAndInsufficientAvailableFunds() {
        original.setStatus(WalletTransfer.Status.FAILED);
        assertStatus(HttpStatus.CONFLICT);
        original.setStatus(WalletTransfer.Status.COMPLETED);
        when(reversalRepository.findByOriginalTransferId(transferId))
                .thenReturn(Optional.of(new WalletTransferReversal()));
        assertStatus(HttpStatus.CONFLICT);
        when(reversalRepository.findByOriginalTransferId(transferId)).thenReturn(Optional.empty());
        originalDestination.setBalance(new BigDecimal("14.99"));
        originalDestination.setReservedBalance(new BigDecimal("5.00"));
        assertStatus(HttpStatus.CONFLICT);
        verify(transferRepository, never()).saveAndFlush(any());
    }

    private void assertStatus(HttpStatus status) {
        assertThatThrownBy(() -> service.reverse(transferId, "admin-subject", "reason"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(status);
    }

    private Wallet wallet(UUID publicId, String balance, String reserved) {
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        wallet.setPublicId(publicId);
        wallet.setCustomerId(UUID.randomUUID());
        wallet.setWalletNumber(UUID.randomUUID().toString().substring(0, 12));
        wallet.setCurrency("KES");
        wallet.setBalance(new BigDecimal(balance));
        wallet.setReservedBalance(new BigDecimal(reserved));
        wallet.setStatus(WalletStatus.ACTIVE);
        return wallet;
    }
}
