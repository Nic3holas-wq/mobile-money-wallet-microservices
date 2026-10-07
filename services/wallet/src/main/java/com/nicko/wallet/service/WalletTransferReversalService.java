package com.nicko.wallet.service;

import com.nicko.wallet.dto.WalletTransferResponse;
import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.WalletLedgerEntry;
import com.nicko.wallet.entity.WalletTransfer;
import com.nicko.wallet.entity.WalletTransferReversal;
import com.nicko.wallet.entity.enums.LedgerDirection;
import com.nicko.wallet.entity.enums.LedgerEntryType;
import com.nicko.wallet.entity.enums.WalletStatus;
import com.nicko.wallet.repository.WalletLedgerEntryRepository;
import com.nicko.wallet.repository.WalletRepository;
import com.nicko.wallet.repository.WalletTransferRepository;
import com.nicko.wallet.repository.WalletTransferReversalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletTransferReversalService {
    private final WalletRepository walletRepository;
    private final WalletTransferRepository transferRepository;
    private final WalletTransferReversalRepository reversalRepository;
    private final WalletLedgerEntryRepository ledgerRepository;
    private final WalletOutboxEventService outboxEventService;

    @Transactional
    public WalletTransferResponse reverse(UUID transferId, String adminSubject, String reason) {
        WalletTransfer observed = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        Wallet source = observed.getDestinationWallet();
        Wallet destination = observed.getSourceWallet();
        lockWallets(source.getPublicId(), destination.getPublicId());

        WalletTransfer original = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        if (reversalRepository.findByOriginalTransferId(transferId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer has already been reversed");
        }
        if (original.getStatus() != WalletTransfer.Status.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed transfers can be reversed");
        }

        Wallet reversingSource = original.getDestinationWallet();
        Wallet reversingDestination = original.getSourceWallet();
        requireActive(reversingSource);
        requireActive(reversingDestination);
        if (!reversingSource.getCurrency().equals(original.getCurrency())
                || !reversingDestination.getCurrency().equals(original.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer wallets must retain the original currency");
        }
        BigDecimal available = reversingSource.getBalance().subtract(reversingSource.getReservedBalance());
        if (available.compareTo(original.getAmount()) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Destination wallet has insufficient available funds to reverse this transfer");
        }

        BigDecimal sourceBefore = reversingSource.getBalance();
        BigDecimal destinationBefore = reversingDestination.getBalance();
        reversingSource.setBalance(sourceBefore.subtract(original.getAmount()));
        reversingDestination.setBalance(destinationBefore.add(original.getAmount()));

        WalletTransfer reversal = new WalletTransfer();
        reversal.setReference(UUID.randomUUID().toString());
        reversal.setSourceWallet(reversingSource);
        reversal.setDestinationWallet(reversingDestination);
        reversal.setAmount(original.getAmount());
        reversal.setCurrency(original.getCurrency());
        reversal.setStatus(WalletTransfer.Status.COMPLETED);
        reversal.setIdempotencyKey("REVERSAL-" + original.getId());
        reversal.setDescription("Reversal of transfer " + original.getReference());
        reversal.setExpiresAt(Instant.now());
        reversal.setCompletedAt(Instant.now());
        reversal = transferRepository.saveAndFlush(reversal);

        ledgerRepository.save(ledgerEntry(reversingSource, reversal, LedgerDirection.DEBIT,
                sourceBefore, reversingSource.getBalance()));
        ledgerRepository.save(ledgerEntry(reversingDestination, reversal, LedgerDirection.CREDIT,
                destinationBefore, reversingDestination.getBalance()));

        WalletTransferReversal audit = new WalletTransferReversal();
        audit.setOriginalTransfer(original);
        audit.setReversalTransfer(reversal);
        audit.setAdminSubject(adminSubject);
        audit.setReason(reason);
        reversalRepository.save(audit);
        outboxEventService.recordWalletTransferReversed(original, reversal, adminSubject, reason);
        return response(reversal);
    }

    private void lockWallets(UUID first, UUID second) {
        UUID lowId = Comparator.<UUID>naturalOrder().compare(first, second) < 0 ? first : second;
        UUID highId = lowId.equals(first) ? second : first;
        walletRepository.findByPublicIdForUpdate(lowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found"));
        walletRepository.findByPublicIdForUpdate(highId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found"));
    }

    private void requireActive(Wallet wallet) {
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Both wallets must be active");
        }
    }

    private WalletLedgerEntry ledgerEntry(Wallet wallet, WalletTransfer transfer, LedgerDirection direction,
                                          BigDecimal before, BigDecimal after) {
        WalletLedgerEntry entry = new WalletLedgerEntry();
        entry.setWallet(wallet);
        entry.setWalletTransfer(transfer);
        entry.setEntryType(LedgerEntryType.TRANSFER);
        entry.setDirection(direction);
        entry.setAmount(transfer.getAmount());
        entry.setBalanceBefore(before);
        entry.setBalanceAfter(after);
        entry.setCurrency(transfer.getCurrency());
        entry.setDescription(transfer.getDescription());
        return entry;
    }

    private WalletTransferResponse response(WalletTransfer transfer) {
        Wallet source = transfer.getSourceWallet();
        Wallet destination = transfer.getDestinationWallet();
        return new WalletTransferResponse(transfer.getId(), transfer.getReference(), source.getPublicId(),
                destination.getPublicId(), transfer.getAmount(), transfer.getCurrency(), transfer.getStatus(),
                source.getBalance(), source.getReservedBalance(), source.getBalance().subtract(source.getReservedBalance()),
                destination.getBalance(), transfer.getCreatedAt(), transfer.getCompletedAt(), transfer.getExpiresAt());
    }
}
