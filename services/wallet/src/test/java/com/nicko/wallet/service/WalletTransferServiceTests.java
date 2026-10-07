package com.nicko.wallet.service;

import com.nicko.wallet.customer.CustomerClient;
import com.nicko.wallet.customer.CustomerDto;
import com.nicko.wallet.dto.WalletTransferRequest;
import com.nicko.wallet.dto.WalletTransferResponse;
import com.nicko.wallet.entity.StepupToken;
import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.WalletLedgerEntry;
import com.nicko.wallet.entity.WalletTransfer;
import com.nicko.wallet.entity.enums.LedgerDirection;
import com.nicko.wallet.entity.enums.LedgerEntryType;
import com.nicko.wallet.entity.enums.WalletStatus;
import com.nicko.wallet.repository.StepupTokenRepository;
import com.nicko.wallet.repository.WalletLedgerEntryRepository;
import com.nicko.wallet.repository.WalletRepository;
import com.nicko.wallet.repository.WalletTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletTransferServiceTests {

    @Mock CustomerClient customerClient;
    @Mock WalletRepository walletRepository;
    @Mock WalletTransferRepository transferRepository;
    @Mock WalletLedgerEntryRepository ledgerRepository;
    @Mock WalletOutboxEventService outboxService;
    @Mock StepupTokenRepository stepupTokenRepository;
    @Mock WalletPinCredentialService pinCredentialService;

    private final UUID customerId = UUID.randomUUID();
    private final UUID sourcePublicId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID destinationPublicId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID transferId = UUID.randomUUID();
    private final String authorization = "Bearer test-token";
    private final String rawStepupToken = "opaque-stepup-token";
    private Wallet source;
    private Wallet destination;
    private CustomerDto customer;
    private WalletPinCrypto pinCrypto;
    private WalletTransferService service;

    @BeforeEach
    void setUp() {
        source = wallet(sourcePublicId, customerId, "100.00", "0.00");
        destination = wallet(destinationPublicId, UUID.randomUUID(), "20.00", "0.00");
        customer = new CustomerDto(customerId, "Test", "Customer", "ACTIVE", true);
        pinCrypto = new WalletPinCrypto("k".repeat(32));
        service = new WalletTransferService(customerClient, walletRepository, transferRepository,
                ledgerRepository, outboxService, stepupTokenRepository, pinCredentialService, pinCrypto);
        ReflectionTestUtils.setField(service, "transferTtl", Duration.ofMinutes(15));
        when(customerClient.getCurrentCustomer(authorization)).thenReturn(customer);
    }

    @Test
    void initiatesTransferAndReservesFundsInStableWalletLockOrder() {
        stubLockedWallets();
        when(transferRepository.findBySourceWalletIdAndIdempotencyKey(source.getId(), "idem-1"))
                .thenReturn(Optional.empty());
        when(transferRepository.saveAndFlush(any(WalletTransfer.class))).thenAnswer(invocation -> {
            WalletTransfer transfer = invocation.getArgument(0);
            transfer.setId(transferId);
            return transfer;
        });

        WalletTransferResponse response = service.transfer(sourcePublicId, authorization, request("10.00", "idem-1"));

        assertThat(response.transferId()).isEqualTo(transferId);
        assertThat(response.status()).isEqualTo(WalletTransfer.Status.PENDING_STEPUP);
        assertThat(response.sourceWalletId()).isEqualTo(sourcePublicId);
        assertThat(response.destinationWalletId()).isEqualTo(destinationPublicId);
        assertThat(response.sourceReservedBalance()).isEqualByComparingTo("10.00");
        assertThat(response.sourceAvailableBalance()).isEqualByComparingTo("90.00");
        assertThat(response.expiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(14)));
        InOrder order = inOrder(walletRepository);
        order.verify(walletRepository).findByPublicIdForUpdate(sourcePublicId);
        order.verify(walletRepository).findByPublicIdForUpdate(destinationPublicId);
    }

    @Test
    void returnsExistingIdempotentTransferButRejectsChangedDetails() {
        stubLockedWallets();
        WalletTransfer existing = transfer(WalletTransfer.Status.PENDING_STEPUP,
                Instant.now().plusSeconds(300), "10.00");
        existing.setId(transferId);
        when(transferRepository.findBySourceWalletIdAndIdempotencyKey(source.getId(), "idem-1"))
                .thenReturn(Optional.of(existing));

        WalletTransferResponse response = service.transfer(sourcePublicId, authorization, request("10.00", "idem-1"));
        assertThat(response.transferId()).isEqualTo(transferId);
        assertThat(source.getReservedBalance()).isEqualByComparingTo("0.00");
        verify(transferRepository, never()).saveAndFlush(any());

        assertStatus(HttpStatus.CONFLICT,
                () -> service.transfer(sourcePublicId, authorization, request("11.00", "idem-1")));
    }

    @Test
    void rejectsIneligibleCustomerSameWalletNonOwnerAndInsufficientFunds() {
        when(customerClient.getCurrentCustomer(authorization))
                .thenReturn(new CustomerDto(customerId, "Test", "Customer", "PENDING", false));
        assertStatus(HttpStatus.FORBIDDEN,
                () -> service.transfer(sourcePublicId, authorization, request("10.00", "bad-customer")));

        when(customerClient.getCurrentCustomer(authorization)).thenReturn(customer);
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.transfer(sourcePublicId, authorization,
                        new WalletTransferRequest(sourcePublicId, "same", new BigDecimal("10.00"), "KES", null)));

        stubLockedWallets();
        when(transferRepository.findBySourceWalletIdAndIdempotencyKey(eq(source.getId()), anyString()))
                .thenReturn(Optional.empty());
        source.setCustomerId(UUID.randomUUID());
        assertStatus(HttpStatus.FORBIDDEN,
                () -> service.transfer(sourcePublicId, authorization, request("10.00", "not-owner")));

        source.setCustomerId(customerId);
        source.setBalance(new BigDecimal("5.00"));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.transfer(sourcePublicId, authorization, request("10.00", "no-funds")));
    }

    @Test
    void rejectsMissingWalletsInactiveWalletsAndCurrencyMismatch() {
        when(walletRepository.findByPublicIdForUpdate(sourcePublicId)).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND,
                () -> service.transfer(sourcePublicId, authorization, request("10.00", "missing")));

        stubLockedWallets();
        when(transferRepository.findBySourceWalletIdAndIdempotencyKey(any(), anyString()))
                .thenReturn(Optional.empty());
        destination.setCurrency("USD");
        assertStatus(HttpStatus.CONFLICT,
                () -> service.transfer(sourcePublicId, authorization, request("10.00", "currency")));

        destination.setCurrency("KES");
        destination.setStatus(WalletStatus.FROZEN);
        assertStatus(HttpStatus.CONFLICT,
                () -> service.transfer(sourcePublicId, authorization, request("10.00", "inactive")));
    }

    @Test
    void issuesStepupTokenAndExpiresPriorVerifiedToken() {
        WalletTransfer transfer = transfer(WalletTransfer.Status.PENDING_STEPUP,
                Instant.now().plusSeconds(300), "10.00");
        when(transferRepository.findByIdForUpdate(transferId)).thenReturn(Optional.of(transfer));
        when(pinCredentialService.verify(customerId, "123456"))
                .thenReturn(WalletPinCredentialService.Result.SUCCESS);
        StepupToken previous = new StepupToken();
        previous.setStatus(StepupToken.Status.VERIFIED);
        when(stepupTokenRepository.findByTransferAndStatusForUpdate(transferId, StepupToken.Status.VERIFIED))
                .thenReturn(Optional.of(previous));

        var response = service.acquireStepupToken(sourcePublicId, transferId, authorization, "123456");

        assertThat(response.transferId()).isEqualTo(transferId);
        assertThat(response.stepupToken()).isNotBlank().isNotEqualTo("123456");
        assertThat(response.expiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(4)));
        assertThat(previous.getStatus()).isEqualTo(StepupToken.Status.EXPIRED);
        ArgumentCaptor<StepupToken> saved = ArgumentCaptor.forClass(StepupToken.class);
        verify(stepupTokenRepository).save(saved.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo(pinCrypto.hashStepupToken(response.stepupToken()));
        assertThat(saved.getValue().getStatus()).isEqualTo(StepupToken.Status.VERIFIED);
        verify(stepupTokenRepository).saveAndFlush(previous);
    }

    @Test
    void rejectsStepupForMissingForeignExpiredOrCompletedTransfer() {
        when(transferRepository.findByIdForUpdate(transferId)).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND,
                () -> service.acquireStepupToken(sourcePublicId, transferId, authorization, "123456"));

        WalletTransfer transfer = transfer(WalletTransfer.Status.PENDING_STEPUP,
                Instant.now().plusSeconds(300), "10.00");
        transfer.getSourceWallet().setCustomerId(UUID.randomUUID());
        when(transferRepository.findByIdForUpdate(transferId)).thenReturn(Optional.of(transfer));
        assertStatus(HttpStatus.FORBIDDEN,
                () -> service.acquireStepupToken(sourcePublicId, transferId, authorization, "123456"));

        transfer.getSourceWallet().setCustomerId(customerId);
        transfer.setExpiresAt(Instant.now().minusSeconds(1));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.acquireStepupToken(sourcePublicId, transferId, authorization, "123456"));

        transfer.setExpiresAt(Instant.now().plusSeconds(300));
        transfer.setStatus(WalletTransfer.Status.COMPLETED);
        assertStatus(HttpStatus.CONFLICT,
                () -> service.acquireStepupToken(sourcePublicId, transferId, authorization, "123456"));
        verifyNoInteractions(stepupTokenRepository);
    }

    @Test
    void mapsPinVerificationResultsDuringStepup() {
        WalletTransfer transfer = transfer(WalletTransfer.Status.PENDING_STEPUP,
                Instant.now().plusSeconds(300), "10.00");
        when(transferRepository.findByIdForUpdate(transferId)).thenReturn(Optional.of(transfer));
        when(pinCredentialService.verify(customerId, "123456"))
                .thenReturn(WalletPinCredentialService.Result.PIN_NOT_SET,
                        WalletPinCredentialService.Result.CURRENT_PIN_REQUIRED,
                        WalletPinCredentialService.Result.INVALID_PIN,
                        WalletPinCredentialService.Result.LOCKED,
                        WalletPinCredentialService.Result.CONFIGURATION_MISSING);

        assertStatus(HttpStatus.CONFLICT,
                () -> service.acquireStepupToken(sourcePublicId, transferId, authorization, "123456"));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.acquireStepupToken(sourcePublicId, transferId, authorization, "123456"));
        assertStatus(HttpStatus.UNAUTHORIZED,
                () -> service.acquireStepupToken(sourcePublicId, transferId, authorization, "123456"));
        assertStatus(HttpStatus.TOO_MANY_REQUESTS,
                () -> service.acquireStepupToken(sourcePublicId, transferId, authorization, "123456"));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE,
                () -> service.acquireStepupToken(sourcePublicId, transferId, authorization, "123456"));
        verifyNoInteractions(stepupTokenRepository);
    }

    @Test
    void completesTransferUpdatesBalancesTokenLedgerAndOutbox() {
        WalletTransfer transfer = transfer(WalletTransfer.Status.PENDING_STEPUP,
                Instant.now().plusSeconds(300), "10.00");
        source.setBalance(new BigDecimal("100.00"));
        source.setReservedBalance(new BigDecimal("10.00"));
        destination.setBalance(new BigDecimal("20.00"));
        StepupToken token = token(transfer, StepupToken.Status.VERIFIED,
                Instant.now().plusSeconds(300), rawStepupToken);
        stubLockedWallets();
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        when(transferRepository.findByIdForUpdate(transferId)).thenReturn(Optional.of(transfer));
        when(stepupTokenRepository.findByTransferWalletAndTokenHashForUpdate(
                transferId, source.getId(), pinCrypto.hashStepupToken(rawStepupToken))).thenReturn(Optional.of(token));

        WalletTransferResponse response = service.complete(sourcePublicId, transferId, authorization, rawStepupToken);

        assertThat(response.status()).isEqualTo(WalletTransfer.Status.COMPLETED);
        assertThat(source.getBalance()).isEqualByComparingTo("90.00");
        assertThat(source.getReservedBalance()).isEqualByComparingTo("0.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("30.00");
        assertThat(transfer.getCompletedAt()).isNotNull();
        assertThat(token.getStatus()).isEqualTo(StepupToken.Status.CONSUMED);
        assertThat(token.getConsumedAt()).isNotNull();
        ArgumentCaptor<WalletLedgerEntry> entries = ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(ledgerRepository, times(2)).save(entries.capture());
        assertThat(entries.getAllValues()).extracting(WalletLedgerEntry::getDirection)
                .containsExactly(LedgerDirection.DEBIT, LedgerDirection.CREDIT);
        assertThat(entries.getAllValues()).extracting(WalletLedgerEntry::getEntryType)
                .containsOnly(LedgerEntryType.TRANSFER);
        verify(outboxService).recordWalletTransferCompleted(transfer);
    }

    @Test
    void completingAlreadyCompletedTransferIsIdempotent() {
        WalletTransfer transfer = transfer(WalletTransfer.Status.COMPLETED,
                Instant.now().minusSeconds(1), "10.00");
        transfer.setCompletedAt(Instant.now());
        StepupToken token = token(transfer, StepupToken.Status.CONSUMED,
                Instant.now().minusSeconds(1), rawStepupToken);
        stubLockedWallets();
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        when(transferRepository.findByIdForUpdate(transferId)).thenReturn(Optional.of(transfer));
        when(stepupTokenRepository.findByTransferWalletAndTokenHashForUpdate(
                transferId, source.getId(), pinCrypto.hashStepupToken(rawStepupToken))).thenReturn(Optional.of(token));

        assertThat(service.complete(sourcePublicId, transferId, authorization, rawStepupToken).status())
                .isEqualTo(WalletTransfer.Status.COMPLETED);
        verifyNoInteractions(ledgerRepository, outboxService);
    }

    @Test
    void completeRejectsMissingTransferWrongOwnerInvalidOrExpiredTokensAndBadState() {
        when(transferRepository.findById(transferId)).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND,
                () -> service.complete(sourcePublicId, transferId, authorization, rawStepupToken));

        WalletTransfer transfer = transfer(WalletTransfer.Status.PENDING_STEPUP,
                Instant.now().plusSeconds(300), "10.00");
        stubLockedWallets();
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        transfer.getSourceWallet().setCustomerId(UUID.randomUUID());
        assertStatus(HttpStatus.FORBIDDEN,
                () -> service.complete(sourcePublicId, transferId, authorization, rawStepupToken));
        transfer.getSourceWallet().setCustomerId(customerId);
        when(transferRepository.findByIdForUpdate(transferId)).thenReturn(Optional.of(transfer));

        when(stepupTokenRepository.findByTransferWalletAndTokenHashForUpdate(any(), any(), anyString()))
                .thenReturn(Optional.empty());
        assertStatus(HttpStatus.UNAUTHORIZED,
                () -> service.complete(sourcePublicId, transferId, authorization, rawStepupToken));

        StepupToken expired = token(transfer, StepupToken.Status.VERIFIED,
                Instant.now().minusSeconds(1), rawStepupToken);
        when(stepupTokenRepository.findByTransferWalletAndTokenHashForUpdate(
                transferId, source.getId(), pinCrypto.hashStepupToken(rawStepupToken))).thenReturn(Optional.of(expired));
        assertStatus(HttpStatus.UNAUTHORIZED,
                () -> service.complete(sourcePublicId, transferId, authorization, rawStepupToken));

        transfer.setStatus(WalletTransfer.Status.FAILED);
        StepupToken valid = token(transfer, StepupToken.Status.VERIFIED,
                Instant.now().plusSeconds(300), rawStepupToken);
        when(stepupTokenRepository.findByTransferWalletAndTokenHashForUpdate(
                transferId, source.getId(), pinCrypto.hashStepupToken(rawStepupToken))).thenReturn(Optional.of(valid));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.complete(sourcePublicId, transferId, authorization, rawStepupToken));
        verifyNoInteractions(ledgerRepository, outboxService);
    }

    private void stubLockedWallets() {
        when(walletRepository.findByPublicIdForUpdate(sourcePublicId)).thenReturn(Optional.of(source));
        when(walletRepository.findByPublicIdForUpdate(destinationPublicId)).thenReturn(Optional.of(destination));
    }

    private WalletTransferRequest request(String amount, String idempotencyKey) {
        return new WalletTransferRequest(destinationPublicId, idempotencyKey,
                new BigDecimal(amount), "KES", "test transfer");
    }

    private Wallet wallet(UUID publicId, UUID ownerId, String balance, String reserved) {
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        wallet.setPublicId(publicId);
        wallet.setCustomerId(ownerId);
        wallet.setWalletNumber("129123456789");
        wallet.setCurrency("KES");
        wallet.setBalance(new BigDecimal(balance));
        wallet.setReservedBalance(new BigDecimal(reserved));
        wallet.setStatus(WalletStatus.ACTIVE);
        return wallet;
    }

    private WalletTransfer transfer(WalletTransfer.Status status, Instant expiresAt, String amount) {
        WalletTransfer transfer = new WalletTransfer();
        transfer.setId(transferId);
        transfer.setReference("transfer-reference");
        transfer.setSourceWallet(source);
        transfer.setDestinationWallet(destination);
        transfer.setAmount(new BigDecimal(amount));
        transfer.setCurrency("KES");
        transfer.setStatus(status);
        transfer.setIdempotencyKey("idem-1");
        transfer.setExpiresAt(expiresAt);
        return transfer;
    }

    private StepupToken token(WalletTransfer transfer, StepupToken.Status status, Instant expiresAt, String raw) {
        StepupToken token = new StepupToken();
        token.setWalletTransfer(transfer);
        token.setWallet(source);
        token.setTokenHash(pinCrypto.hashStepupToken(raw));
        token.setChannel("PIN");
        token.setStatus(status);
        token.setExpiresAt(expiresAt);
        return token;
    }

    private void assertStatus(HttpStatus status, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }
}
