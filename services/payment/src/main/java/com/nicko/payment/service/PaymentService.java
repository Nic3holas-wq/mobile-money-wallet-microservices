package com.nicko.payment.service;

import com.nicko.payment.dto.DepositRequest;
import com.nicko.payment.dto.PaymentResponse;
import com.nicko.payment.entity.MpesaTransaction;
import com.nicko.payment.entity.Payment;
import com.nicko.payment.entity.PaymentAttempt;
import com.nicko.payment.entity.enums.PaymentAttemptStatus;
import com.nicko.payment.entity.enums.PaymentProvider;
import com.nicko.payment.entity.enums.PaymentStatus;
import com.nicko.payment.entity.enums.PaymentType;
import com.nicko.payment.provider.mpesa.MpesaExpressClient;
import com.nicko.payment.provider.mpesa.MpesaProviderException;
import com.nicko.payment.provider.mpesa.MpesaPushResponse;
import com.nicko.payment.repository.MpesaTransactionRepository;
import com.nicko.payment.repository.PaymentAttemptRepository;
import com.nicko.payment.repository.PaymentRepository;
import com.nicko.payment.wallet.WalletAccount;
import com.nicko.payment.wallet.WalletServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final MpesaTransactionRepository mpesaTransactionRepository;
    private final WalletServiceClient walletServiceClient;
    private final MpesaExpressClient mpesaExpressClient;
    private final PaymentEventFactory eventFactory;

    public PaymentResponse initiateDeposit(UUID customerId, String idempotencyKey, DepositRequest request) {
        Payment existing = paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            String existingPhone = mpesaTransactionRepository.findByPaymentAttempt_Payment_Id(existing.getId())
                    .map(MpesaTransaction::getPhoneNumber).orElse(null);
            if (existing.getType() != PaymentType.DEPOSIT || existing.getAmount().compareTo(request.amount()) != 0
                    || !normalizePhoneNumber(request.phoneNumber()).equals(existingPhone)
                    || !java.util.Objects.equals(existing.getDescription(), request.description())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency-Key was already used for a different payment request");
            }
            return response(existing, "Returning the existing payment for this idempotency key");
        }

        WalletAccount account = walletServiceClient.findAccount(customerId);
        if (account == null || !customerId.equals(account.customerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No active wallet is available for this customer");
        }
        if (!"KES".equals(account.currency())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "M-Pesa deposits currently support KES wallets only");
        }

        Payment payment = new Payment();
        payment.setPublicId(UUID.randomUUID());
        payment.setReference("DEP-" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
        payment.setCustomerId(customerId);
        payment.setWalletId(account.walletId());
        payment.setProvider(PaymentProvider.MPESA);
        payment.setType(PaymentType.DEPOSIT);
        payment.setAmount(request.amount());
        payment.setCurrency(account.currency());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setIdempotencyKey(idempotencyKey);
        payment.setDescription(request.description());
        payment = paymentRepository.saveAndFlush(payment);

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setPayment(payment);
        attempt.setAttemptNumber(1);
        attempt.setStatus(PaymentAttemptStatus.PROCESSING);
        attempt.setRequestTimestamp(Instant.now());
        attempt = attemptRepository.saveAndFlush(attempt);

        MpesaTransaction mpesaTransaction = new MpesaTransaction();
        mpesaTransaction.setPaymentAttempt(attempt);
        mpesaTransaction.setPhoneNumber(normalizePhoneNumber(request.phoneNumber()));
        mpesaTransactionRepository.save(mpesaTransaction);

        try {
            MpesaPushResponse providerResponse = mpesaExpressClient.initiate(
                    mpesaTransaction.getPhoneNumber(), request.amount().longValueExact(),
                    payment.getReference().substring(0, Math.min(payment.getReference().length(), 12)),
                    request.description());
            attempt.setResponseTimestamp(Instant.now());
            if (providerResponse != null) {
                mpesaTransaction.setMerchantRequestId(providerResponse.merchantRequestId());
                mpesaTransaction.setCheckoutRequestId(providerResponse.checkoutRequestId());
                payment.setProviderReference(providerResponse.checkoutRequestId());
            }
            if (providerResponse == null || !"0".equals(providerResponse.responseCode())
                    || providerResponse.checkoutRequestId() == null || providerResponse.checkoutRequestId().isBlank()) {
                String message = providerResponse == null ? "M-Pesa returned an empty response"
                        : providerResponse.responseDescription();
                payment.setStatus(PaymentStatus.FAILED);
                attempt.setStatus(PaymentAttemptStatus.FAILED);
                attempt.setErrorCode(providerResponse == null ? "EMPTY_RESPONSE" : providerResponse.responseCode());
                attempt.setErrorMessage(message);
                paymentRepository.save(payment);
                attemptRepository.save(attempt);
                mpesaTransactionRepository.save(mpesaTransaction);
                eventFactory.record(payment, "payment.deposit.failed.v1");
                return response(payment, "M-Pesa did not accept the deposit request");
            }

            payment.setStatus(PaymentStatus.PROCESSING);
            attempt.setStatus(PaymentAttemptStatus.PROCESSING);
            paymentRepository.save(payment);
            attemptRepository.save(attempt);
            mpesaTransactionRepository.save(mpesaTransaction);
            eventFactory.record(payment, "payment.deposit.initiated.v1");
            return response(payment, providerResponse.customerMessage());
        } catch (MpesaProviderException exception) {
            boolean outcomeUnknown = exception.outcomeUnknown();
            payment.setStatus(outcomeUnknown ? PaymentStatus.PROCESSING : PaymentStatus.FAILED);
            attempt.setStatus(outcomeUnknown ? PaymentAttemptStatus.UNKNOWN : PaymentAttemptStatus.FAILED);
            attempt.setResponseTimestamp(Instant.now());
            attempt.setErrorCode(outcomeUnknown ? "MPESA_OUTCOME_UNKNOWN" : "MPESA_REQUEST_REJECTED");
            attempt.setErrorMessage(exception.getMessage());
            paymentRepository.save(payment);
            attemptRepository.save(attempt);
            mpesaTransactionRepository.save(mpesaTransaction);
            eventFactory.record(payment, outcomeUnknown ? "payment.deposit.initiated.v1" : "payment.deposit.failed.v1");
            return response(payment, outcomeUnknown
                    ? "The provider response is pending; use the payment status before retrying"
                    : "M-Pesa could not accept the deposit request");
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID customerId, UUID publicId) {
        Payment payment = paymentRepository.findByPublicIdAndCustomerId(publicId, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        return response(payment, null);
    }

    private static PaymentResponse response(Payment payment, String message) {
        return new PaymentResponse(payment.getPublicId(), payment.getReference(), payment.getType(),
                payment.getAmount(), payment.getCurrency(), payment.getStatus(), payment.getProvider().name(),
                payment.getProviderReference(), payment.getCreatedAt(), payment.getCompletedAt(), message);
    }

    private static String normalizePhoneNumber(String phoneNumber) {
        String normalized = phoneNumber.replaceAll("[+\\s-]", "");
        if (normalized.startsWith("0")) normalized = "254" + normalized.substring(1);
        return normalized;
    }
}
