package com.nicko.wallet.service;

import com.nicko.wallet.customer.CustomerClient;
import com.nicko.wallet.customer.CustomerDto;
import com.nicko.wallet.dto.WalletTransferRequest;
import com.nicko.wallet.dto.WalletTransferResponse;
import com.nicko.wallet.dto.StepupTokenResponse;
import com.nicko.wallet.entity.StepupToken;
import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.WalletLedgerEntry;
import com.nicko.wallet.entity.WalletTransfer;
import com.nicko.wallet.entity.enums.LedgerDirection;
import com.nicko.wallet.entity.enums.LedgerEntryType;
import com.nicko.wallet.entity.enums.WalletStatus;
import com.nicko.wallet.repository.WalletLedgerEntryRepository;
import com.nicko.wallet.repository.WalletRepository;
import com.nicko.wallet.repository.WalletTransferRepository;
import com.nicko.wallet.repository.StepupTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;

@Service
@RequiredArgsConstructor
public class WalletTransferService {

    private static final Duration STEPUP_TOKEN_TTL = Duration.ofMinutes(5);

    private final CustomerClient customerClient;
    private final WalletRepository walletRepository;
    private final WalletTransferRepository transferRepository;
    private final WalletLedgerEntryRepository ledgerEntryRepository;
    private final WalletOutboxEventService outboxEventService;
    private final StepupTokenRepository stepupTokenRepository;
    private final WalletPinCredentialService pinCredentialService;
    private final WalletPinCrypto pinCrypto;

    @Value("${app.stepup.transfer-ttl:PT15M}")
    private Duration transferTtl;

    @Transactional
    public WalletTransferResponse transfer(UUID sourceWalletPublicId, String authorization,
                                           WalletTransferRequest request) {
        CustomerDto customer = customerClient.getCurrentCustomer(authorization);
        requireEligible(customer);
        if (sourceWalletPublicId.equals(request.destinationWalletId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source and destination wallets must differ");
        }

        Wallet[] lockedWallets = lockWallets(sourceWalletPublicId, request.destinationWalletId());
        Wallet source = sourceWalletPublicId.equals(lockedWallets[0].getPublicId())
                ? lockedWallets[0] : lockedWallets[1];
        Wallet destination = request.destinationWalletId().equals(lockedWallets[0].getPublicId())
                ? lockedWallets[0] : lockedWallets[1];

        if (!customer.id().equals(source.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source wallet does not belong to this customer");
        }

        WalletTransfer existing = transferRepository
                .findBySourceWalletIdAndIdempotencyKey(source.getId(), request.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.getDestinationWallet().getId().equals(destination.getId())
                    || existing.getAmount().compareTo(request.amount()) != 0
                    || !existing.getCurrency().equals(request.currency())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key was already used with different transfer details");
            }
            return response(existing);
        }

        requireActive(source);
        requireActive(destination);
        if (!source.getCurrency().equals(request.currency())
                || !destination.getCurrency().equals(request.currency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer currency must match both wallets");
        }

        BigDecimal available = source.getBalance().subtract(source.getReservedBalance());
        if (available.compareTo(request.amount()) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient available funds");
        }

        var transfer = new WalletTransfer();
        transfer.setReference(UUID.randomUUID().toString());
        transfer.setSourceWallet(source);
        transfer.setDestinationWallet(destination);
        transfer.setAmount(request.amount());
        transfer.setCurrency(request.currency());
        transfer.setStatus(WalletTransfer.Status.PENDING_STEPUP);
        transfer.setIdempotencyKey(request.idempotencyKey());
        transfer.setDescription(request.description());
        transfer.setExpiresAt(Instant.now().plus(transferTtl));
        source.setReservedBalance(source.getReservedBalance().add(request.amount()));
        transferRepository.saveAndFlush(transfer);
        return response(transfer);
    }

    @Transactional
    public StepupTokenResponse acquireStepupToken(UUID sourceWalletPublicId, UUID transferId,
                                                  String authorization, String pin) {
        CustomerDto customer = currentEligibleCustomer(authorization);
        WalletTransfer transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        if (!sourceWalletPublicId.equals(transfer.getSourceWallet().getPublicId())
                || !customer.id().equals(transfer.getSourceWallet().getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Transfer does not belong to this customer");
        }
        if (transfer.getStatus() != WalletTransfer.Status.PENDING_STEPUP
                || !transfer.getExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer is no longer awaiting step-up");
        }

        requirePinResult(pinCredentialService.verify(customer.id(), pin));
        stepupTokenRepository.findByTransferAndStatusForUpdate(transferId, StepupToken.Status.VERIFIED)
                .ifPresent(previous -> {
                    previous.setStatus(StepupToken.Status.EXPIRED);
                    previous.setUpdatedAt(Instant.now());
                    stepupTokenRepository.saveAndFlush(previous);
                });

        String rawToken = pinCrypto.newStepupToken();
        var stepupToken = new StepupToken();
        stepupToken.setWalletTransfer(transfer);
        stepupToken.setWallet(transfer.getSourceWallet());
        stepupToken.setTokenHash(pinCrypto.hashStepupToken(rawToken));
        stepupToken.setChannel("PIN");
        stepupToken.setStatus(StepupToken.Status.VERIFIED);
        stepupToken.setExpiresAt(Instant.now().plus(STEPUP_TOKEN_TTL));
        stepupToken.setUpdatedAt(Instant.now());
        stepupTokenRepository.save(stepupToken);
        return new StepupTokenResponse(transferId, rawToken, stepupToken.getExpiresAt());
    }

    @Transactional
    public WalletTransferResponse complete(UUID sourceWalletPublicId, UUID transferId,
                                           String authorization, String rawToken) {
        CustomerDto customer = currentEligibleCustomer(authorization);
        WalletTransfer observed = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        if (!sourceWalletPublicId.equals(observed.getSourceWallet().getPublicId())
                || !customer.id().equals(observed.getSourceWallet().getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Transfer does not belong to this customer");
        }

        Wallet[] lockedWallets = lockWallets(observed.getSourceWallet().getPublicId(),
                observed.getDestinationWallet().getPublicId());
        Wallet source = sourceWalletPublicId.equals(lockedWallets[0].getPublicId())
                ? lockedWallets[0] : lockedWallets[1];
        Wallet destination = observed.getDestinationWallet().getPublicId().equals(lockedWallets[0].getPublicId())
                ? lockedWallets[0] : lockedWallets[1];
        WalletTransfer transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        StepupToken token = stepupTokenRepository.findByTransferWalletAndTokenHashForUpdate(
                        transferId, source.getId(), pinCrypto.hashStepupToken(rawToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid step-up token"));

        if (transfer.getStatus() == WalletTransfer.Status.COMPLETED
                && token.getStatus() == StepupToken.Status.CONSUMED) {
            return response(transfer);
        }
        if (transfer.getStatus() != WalletTransfer.Status.PENDING_STEPUP
                || !transfer.getExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer is no longer awaiting step-up");
        }
        if (token.getStatus() != StepupToken.Status.VERIFIED
                || !token.getExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Step-up token is expired or already used");
        }
        requireActive(source);
        requireActive(destination);
        if (source.getReservedBalance().compareTo(transfer.getAmount()) < 0
                || source.getBalance().compareTo(transfer.getAmount()) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reserved funds are no longer available");
        }

        BigDecimal sourceBefore = source.getBalance();
        BigDecimal destinationBefore = destination.getBalance();
        source.setReservedBalance(source.getReservedBalance().subtract(transfer.getAmount()));
        source.setBalance(sourceBefore.subtract(transfer.getAmount()));
        destination.setBalance(destinationBefore.add(transfer.getAmount()));
        transfer.setStatus(WalletTransfer.Status.COMPLETED);
        transfer.setCompletedAt(Instant.now());
        token.setStatus(StepupToken.Status.CONSUMED);
        token.setConsumedAt(Instant.now());
        token.setUpdatedAt(Instant.now());

        ledgerEntryRepository.save(ledgerEntry(source, transfer, LedgerDirection.DEBIT,
                sourceBefore, source.getBalance()));
        ledgerEntryRepository.save(ledgerEntry(destination, transfer, LedgerDirection.CREDIT,
                destinationBefore, destination.getBalance()));
        outboxEventService.recordWalletTransferCompleted(transfer);
        return response(transfer);
    }

    private Wallet[] lockWallets(UUID firstId, UUID secondId) {
        UUID lowId = Comparator.<UUID>naturalOrder().compare(firstId, secondId) < 0 ? firstId : secondId;
        UUID highId = lowId.equals(firstId) ? secondId : firstId;
        Wallet low = walletRepository.findByPublicIdForUpdate(lowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found"));
        Wallet high = walletRepository.findByPublicIdForUpdate(highId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found"));
        return new Wallet[]{low, high};
    }

    private void requireActive(Wallet wallet) {
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Both wallets must be active");
        }
    }

    private CustomerDto currentEligibleCustomer(String authorization) {
        CustomerDto customer = customerClient.getCurrentCustomer(authorization);
        requireEligible(customer);
        return customer;
    }

    private void requireEligible(CustomerDto customer) {
        if (!"ACTIVE".equals(customer.customerStatus()) || !customer.walletEligible()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer is not eligible to make wallet transfers");
        }
    }

    private void requirePinResult(WalletPinCredentialService.Result result) {
        switch (result) {
            case SUCCESS -> { }
            case PIN_NOT_SET -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Set a wallet PIN first");
            case CURRENT_PIN_REQUIRED -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Current PIN is required");
            case INVALID_PIN -> throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PIN");
            case LOCKED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PIN entry is temporarily locked");
            case CONFIGURATION_MISSING -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Wallet PIN verification is not configured");
        }
    }

    private WalletLedgerEntry ledgerEntry(Wallet wallet, WalletTransfer transfer,
                                          LedgerDirection direction, BigDecimal before, BigDecimal after) {
        var entry = new WalletLedgerEntry();
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
        return new WalletTransferResponse(transfer.getId(), transfer.getReference(),
                transfer.getSourceWallet().getPublicId(), transfer.getDestinationWallet().getPublicId(),
                transfer.getAmount(), transfer.getCurrency(), transfer.getStatus(),
                transfer.getSourceWallet().getBalance(), transfer.getSourceWallet().getReservedBalance(),
                transfer.getSourceWallet().getBalance().subtract(transfer.getSourceWallet().getReservedBalance()),
                transfer.getDestinationWallet().getBalance(), transfer.getCreatedAt(), transfer.getCompletedAt(),
                transfer.getExpiresAt());
    }
}
