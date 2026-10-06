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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletPaymentOperationService {

    private final WalletRepository walletRepository;
    private final WalletPaymentOperationRepository operationRepository;
    private final WalletLedgerEntryRepository ledgerEntryRepository;

    @Transactional
    public PaymentOperationResponse reserve(UUID walletPublicId, PaymentOperationRequest request) {
        Wallet wallet = findActiveWalletForUpdate(walletPublicId);
        WalletPaymentOperation operation = findExisting(wallet, request, WalletPaymentOperation.Kind.RESERVATION);
        if (operation != null) {
            return response(wallet, operation);
        }

        BigDecimal available = wallet.getBalance().subtract(wallet.getReservedBalance());
        if (available.compareTo(request.amount()) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient available funds");
        }

        operation = newOperation(wallet, request, WalletPaymentOperation.Kind.RESERVATION,
                WalletPaymentOperation.State.RESERVED);
        operationRepository.save(operation);
        wallet.setReservedBalance(wallet.getReservedBalance().add(request.amount()));
        return response(wallet, operation);
    }

    @Transactional
    public PaymentOperationResponse commit(UUID walletPublicId, String paymentReference) {
        Wallet wallet = findWalletForUpdate(walletPublicId);
        WalletPaymentOperation operation = findOperation(wallet, paymentReference);
        requireKind(operation, WalletPaymentOperation.Kind.RESERVATION);
        if (operation.getState() == WalletPaymentOperation.State.COMMITTED) {
            return response(wallet, operation);
        }
        if (operation.getState() != WalletPaymentOperation.State.RESERVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a reserved payment can be committed");
        }

        BigDecimal before = wallet.getBalance();
        wallet.setReservedBalance(wallet.getReservedBalance().subtract(operation.getAmount()));
        wallet.setBalance(before.subtract(operation.getAmount()));
        operation.setState(WalletPaymentOperation.State.COMMITTED);
        operation.touch();
        ledgerEntryRepository.save(createLedgerEntry(wallet, operation, LedgerEntryType.WITHDRAWAL,
                LedgerDirection.DEBIT, before, wallet.getBalance()));
        return response(wallet, operation);
    }

    @Transactional
    public PaymentOperationResponse release(UUID walletPublicId, String paymentReference) {
        Wallet wallet = findWalletForUpdate(walletPublicId);
        WalletPaymentOperation operation = findOperation(wallet, paymentReference);
        requireKind(operation, WalletPaymentOperation.Kind.RESERVATION);
        if (operation.getState() == WalletPaymentOperation.State.RELEASED) {
            return response(wallet, operation);
        }
        if (operation.getState() != WalletPaymentOperation.State.RESERVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a reserved payment can be released");
        }

        wallet.setReservedBalance(wallet.getReservedBalance().subtract(operation.getAmount()));
        operation.setState(WalletPaymentOperation.State.RELEASED);
        operation.touch();
        return response(wallet, operation);
    }

    @Transactional
    public PaymentOperationResponse credit(UUID walletPublicId, PaymentOperationRequest request) {
        Wallet wallet = findActiveWalletForUpdate(walletPublicId);
        WalletPaymentOperation operation = findExisting(wallet, request, WalletPaymentOperation.Kind.DEPOSIT);
        if (operation != null) {
            return response(wallet, operation);
        }

        BigDecimal before = wallet.getBalance();
        wallet.setBalance(before.add(request.amount()));
        operation = newOperation(wallet, request, WalletPaymentOperation.Kind.DEPOSIT,
                WalletPaymentOperation.State.POSTED);
        operationRepository.saveAndFlush(operation);
        ledgerEntryRepository.save(createLedgerEntry(wallet, operation, LedgerEntryType.DEPOSIT,
                LedgerDirection.CREDIT, before, wallet.getBalance()));
        return response(wallet, operation);
    }

    private Wallet findActiveWalletForUpdate(UUID publicId) {
        Wallet wallet = findWalletForUpdate(publicId);
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wallet is not active");
        }
        return wallet;
    }

    private Wallet findWalletForUpdate(UUID publicId) {
        return walletRepository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found"));
    }

    private WalletPaymentOperation findExisting(Wallet wallet, PaymentOperationRequest request,
                                                 WalletPaymentOperation.Kind expectedKind) {
        WalletPaymentOperation existing = operationRepository
                .findByWalletIdAndPaymentReference(wallet.getId(), request.paymentReference()).orElse(null);
        if (existing == null) {
            requireCurrency(wallet, request.currency());
            return null;
        }
        requireKind(existing, expectedKind);
        if (existing.getAmount().compareTo(request.amount()) != 0
                || !existing.getCurrency().equals(request.currency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment reference was already used with different operation details");
        }
        return existing;
    }

    private WalletPaymentOperation findOperation(Wallet wallet, String paymentReference) {
        return operationRepository.findByWalletIdAndPaymentReference(wallet.getId(), paymentReference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment operation not found"));
    }

    private void requireCurrency(Wallet wallet, String currency) {
        if (!wallet.getCurrency().equals(currency)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Currency does not match wallet currency");
        }
    }

    private void requireKind(WalletPaymentOperation operation, WalletPaymentOperation.Kind kind) {
        if (operation.getKind() != kind) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment reference belongs to a different operation");
        }
    }

    private WalletPaymentOperation newOperation(Wallet wallet, PaymentOperationRequest request,
                                                WalletPaymentOperation.Kind kind,
                                                WalletPaymentOperation.State state) {
        var operation = new WalletPaymentOperation();
        operation.setWallet(wallet);
        operation.setPaymentReference(request.paymentReference());
        operation.setKind(kind);
        operation.setState(state);
        operation.setAmount(request.amount());
        operation.setCurrency(request.currency());
        return operation;
    }

    private WalletLedgerEntry createLedgerEntry(Wallet wallet, WalletPaymentOperation operation,
                                                LedgerEntryType type, LedgerDirection direction,
                                                BigDecimal before, BigDecimal after) {
        var entry = new WalletLedgerEntry();
        entry.setWallet(wallet);
        entry.setPaymentOperation(operation);
        entry.setEntryType(type);
        entry.setDirection(direction);
        entry.setAmount(operation.getAmount());
        entry.setBalanceBefore(before);
        entry.setBalanceAfter(after);
        entry.setCurrency(operation.getCurrency());
        entry.setDescription(operation.getPaymentReference());
        return entry;
    }

    private PaymentOperationResponse response(Wallet wallet, WalletPaymentOperation operation) {
        return new PaymentOperationResponse(operation.getId(), wallet.getPublicId(),
                operation.getPaymentReference(), operation.getKind(), operation.getState(), operation.getAmount(),
                operation.getCurrency(), wallet.getBalance(), wallet.getReservedBalance(),
                wallet.getBalance().subtract(wallet.getReservedBalance()));
    }
}
