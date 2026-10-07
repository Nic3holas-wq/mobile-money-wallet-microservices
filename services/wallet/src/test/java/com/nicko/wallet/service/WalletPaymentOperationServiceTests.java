package com.nicko.wallet.service;

import com.nicko.wallet.dto.PaymentOperationRequest;
import com.nicko.wallet.dto.PaymentOperationResponse;
import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.WalletLedgerEntry;
import com.nicko.wallet.entity.WalletPaymentOperation;
import com.nicko.wallet.entity.enums.LedgerDirection;
import com.nicko.wallet.entity.enums.LedgerEntryType;
import com.nicko.wallet.entity.enums.WalletStatus;
import com.nicko.wallet.repository.WalletLedgerEntryRepository;
import com.nicko.wallet.repository.WalletPaymentOperationRepository;
import com.nicko.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletPaymentOperationServiceTests {

    @Mock WalletRepository walletRepository;
    @Mock WalletPaymentOperationRepository operationRepository;
    @Mock WalletLedgerEntryRepository ledgerRepository;

    private final UUID walletDbId = UUID.randomUUID();
    private final UUID walletPublicId = UUID.randomUUID();
    private Wallet wallet;
    private WalletPaymentOperationService service;

    @BeforeEach
    void setUp() {
        service = new WalletPaymentOperationService(walletRepository, operationRepository, ledgerRepository);
        wallet = wallet(walletDbId, walletPublicId, "100.00", "10.00", WalletStatus.ACTIVE);
        when(walletRepository.findByPublicIdForUpdate(walletPublicId)).thenReturn(Optional.of(wallet));
    }

    @Test
    void reserveCreatesReservationAndReducesAvailableBalance() {
        PaymentOperationRequest request = request("pay-1", "25.00", "KES");
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "pay-1"))
                .thenReturn(Optional.empty());

        PaymentOperationResponse response = service.reserve(walletPublicId, request);

        assertThat(response.kind()).isEqualTo(WalletPaymentOperation.Kind.RESERVATION);
        assertThat(response.state()).isEqualTo(WalletPaymentOperation.State.RESERVED);
        assertThat(response.balance()).isEqualByComparingTo("100.00");
        assertThat(response.reservedBalance()).isEqualByComparingTo("35.00");
        assertThat(response.availableBalance()).isEqualByComparingTo("65.00");
        ArgumentCaptor<WalletPaymentOperation> operation = ArgumentCaptor.forClass(WalletPaymentOperation.class);
        verify(operationRepository).save(operation.capture());
        assertThat(operation.getValue().getPaymentReference()).isEqualTo("pay-1");
        assertThat(operation.getValue().getWallet()).isSameAs(wallet);
    }

    @Test
    void reserveIsIdempotentForSameOperationAndRejectsChangedDetails() {
        PaymentOperationRequest request = request("pay-1", "25.00", "KES");
        WalletPaymentOperation existing = operation("pay-1", WalletPaymentOperation.Kind.RESERVATION,
                WalletPaymentOperation.State.RESERVED, "25.00", "KES");
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "pay-1"))
                .thenReturn(Optional.of(existing));

        assertThat(service.reserve(walletPublicId, request).state()).isEqualTo(WalletPaymentOperation.State.RESERVED);
        assertThatThrownBy(() -> service.reserve(walletPublicId, request("pay-1", "26.00", "KES")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(operationRepository, never()).save(any());
    }

    @Test
    void reserveRejectsInsufficientFundsWrongCurrencyInactiveOrMissingWallet() {
        when(operationRepository.findByWalletIdAndPaymentReference(any(), anyString()))
                .thenReturn(Optional.empty());
        assertStatus(HttpStatus.CONFLICT,
                () -> service.reserve(walletPublicId, request("large", "1000.00", "KES")));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.reserve(walletPublicId, request("currency", "1.00", "USD")));

        wallet.setStatus(WalletStatus.FROZEN);
        assertStatus(HttpStatus.CONFLICT,
                () -> service.reserve(walletPublicId, request("frozen", "1.00", "KES")));

        when(walletRepository.findByPublicIdForUpdate(walletPublicId)).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND,
                () -> service.reserve(walletPublicId, request("missing", "1.00", "KES")));
    }

    @Test
    void commitDebitsBalanceReleasesReservationAndWritesWithdrawalLedger() {
        WalletPaymentOperation operation = operation("pay-1", WalletPaymentOperation.Kind.RESERVATION,
                WalletPaymentOperation.State.RESERVED, "25.00", "KES");
        wallet.setReservedBalance(new BigDecimal("35.00"));
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "pay-1"))
                .thenReturn(Optional.of(operation));

        PaymentOperationResponse response = service.commit(walletPublicId, "pay-1");

        assertThat(wallet.getBalance()).isEqualByComparingTo("75.00");
        assertThat(wallet.getReservedBalance()).isEqualByComparingTo("10.00");
        assertThat(operation.getState()).isEqualTo(WalletPaymentOperation.State.COMMITTED);
        assertThat(response.availableBalance()).isEqualByComparingTo("65.00");
        ArgumentCaptor<WalletLedgerEntry> ledger = ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(ledgerRepository).save(ledger.capture());
        assertThat(ledger.getValue().getEntryType()).isEqualTo(LedgerEntryType.WITHDRAWAL);
        assertThat(ledger.getValue().getDirection()).isEqualTo(LedgerDirection.DEBIT);
        assertThat(ledger.getValue().getBalanceBefore()).isEqualByComparingTo("100.00");
        assertThat(ledger.getValue().getBalanceAfter()).isEqualByComparingTo("75.00");

        assertThat(service.commit(walletPublicId, "pay-1").state())
                .isEqualTo(WalletPaymentOperation.State.COMMITTED);
        verify(ledgerRepository, times(1)).save(any());
    }

    @Test
    void releaseRestoresAvailableBalanceAndIsIdempotent() {
        WalletPaymentOperation operation = operation("pay-1", WalletPaymentOperation.Kind.RESERVATION,
                WalletPaymentOperation.State.RESERVED, "25.00", "KES");
        wallet.setReservedBalance(new BigDecimal("35.00"));
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "pay-1"))
                .thenReturn(Optional.of(operation));

        assertThat(service.release(walletPublicId, "pay-1").state())
                .isEqualTo(WalletPaymentOperation.State.RELEASED);
        assertThat(wallet.getBalance()).isEqualByComparingTo("100.00");
        assertThat(wallet.getReservedBalance()).isEqualByComparingTo("10.00");
        assertThat(service.release(walletPublicId, "pay-1").state())
                .isEqualTo(WalletPaymentOperation.State.RELEASED);
        verifyNoInteractions(ledgerRepository);
    }

    @Test
    void commitAndReleaseRejectMissingWrongKindAndInvalidState() {
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "missing"))
                .thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.commit(walletPublicId, "missing"));

        WalletPaymentOperation deposit = operation("deposit", WalletPaymentOperation.Kind.DEPOSIT,
                WalletPaymentOperation.State.POSTED, "1.00", "KES");
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "deposit"))
                .thenReturn(Optional.of(deposit));
        assertStatus(HttpStatus.CONFLICT, () -> service.release(walletPublicId, "deposit"));

        WalletPaymentOperation released = operation("released", WalletPaymentOperation.Kind.RESERVATION,
                WalletPaymentOperation.State.RELEASED, "1.00", "KES");
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "released"))
                .thenReturn(Optional.of(released));
        assertStatus(HttpStatus.CONFLICT, () -> service.commit(walletPublicId, "released"));
    }

    @Test
    void creditPostsDepositAndIsIdempotent() {
        PaymentOperationRequest request = request("deposit-1", "30.00", "KES");
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "deposit-1"))
                .thenReturn(Optional.empty());

        PaymentOperationResponse response = service.credit(walletPublicId, request);

        assertThat(wallet.getBalance()).isEqualByComparingTo("130.00");
        assertThat(response.state()).isEqualTo(WalletPaymentOperation.State.POSTED);
        ArgumentCaptor<WalletLedgerEntry> ledger = ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(ledgerRepository).save(ledger.capture());
        assertThat(ledger.getValue().getEntryType()).isEqualTo(LedgerEntryType.DEPOSIT);
        assertThat(ledger.getValue().getDirection()).isEqualTo(LedgerDirection.CREDIT);

        WalletPaymentOperation existing = operation("deposit-1", WalletPaymentOperation.Kind.DEPOSIT,
                WalletPaymentOperation.State.POSTED, "30.00", "KES");
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "deposit-1"))
                .thenReturn(Optional.of(existing));
        assertThat(service.credit(walletPublicId, request).state()).isEqualTo(WalletPaymentOperation.State.POSTED);
        verify(operationRepository).saveAndFlush(any());
        verify(ledgerRepository, times(1)).save(any());
    }

    @Test
    void creditRejectsSameReferenceWithDifferentKindOrDetails() {
        PaymentOperationRequest request = request("same-ref", "5.00", "KES");
        WalletPaymentOperation reservation = operation("same-ref", WalletPaymentOperation.Kind.RESERVATION,
                WalletPaymentOperation.State.RESERVED, "5.00", "KES");
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "same-ref"))
                .thenReturn(Optional.of(reservation));
        assertStatus(HttpStatus.CONFLICT, () -> service.credit(walletPublicId, request));

        WalletPaymentOperation deposit = operation("same-ref", WalletPaymentOperation.Kind.DEPOSIT,
                WalletPaymentOperation.State.POSTED, "4.00", "KES");
        when(operationRepository.findByWalletIdAndPaymentReference(walletDbId, "same-ref"))
                .thenReturn(Optional.of(deposit));
        assertStatus(HttpStatus.CONFLICT, () -> service.credit(walletPublicId, request));
    }

    private Wallet wallet(UUID id, UUID publicId, String balance, String reserved, WalletStatus status) {
        Wallet result = new Wallet();
        result.setId(id);
        result.setPublicId(publicId);
        result.setCustomerId(UUID.randomUUID());
        result.setWalletNumber("129123456789");
        result.setCurrency("KES");
        result.setBalance(new BigDecimal(balance));
        result.setReservedBalance(new BigDecimal(reserved));
        result.setStatus(status);
        return result;
    }

    private PaymentOperationRequest request(String reference, String amount, String currency) {
        return new PaymentOperationRequest(reference, new BigDecimal(amount), currency);
    }

    private WalletPaymentOperation operation(String reference, WalletPaymentOperation.Kind kind,
                                             WalletPaymentOperation.State state, String amount, String currency) {
        WalletPaymentOperation operation = new WalletPaymentOperation();
        operation.setId(UUID.randomUUID());
        operation.setWallet(wallet);
        operation.setPaymentReference(reference);
        operation.setKind(kind);
        operation.setState(state);
        operation.setAmount(new BigDecimal(amount));
        operation.setCurrency(currency);
        return operation;
    }

    private void assertStatus(HttpStatus status, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }
}
