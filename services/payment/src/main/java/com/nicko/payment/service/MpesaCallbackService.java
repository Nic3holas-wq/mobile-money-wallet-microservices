package com.nicko.payment.service;

import com.nicko.payment.dto.MpesaCallbackResult;
import com.nicko.payment.entity.MpesaTransaction;
import com.nicko.payment.entity.Payment;
import com.nicko.payment.entity.PaymentAttempt;
import com.nicko.payment.entity.PaymentCallback;
import com.nicko.payment.entity.enums.CallbackProcessingStatus;
import com.nicko.payment.entity.enums.PaymentAttemptStatus;
import com.nicko.payment.entity.enums.PaymentProvider;
import com.nicko.payment.entity.enums.PaymentStatus;
import com.nicko.payment.repository.MpesaTransactionRepository;
import com.nicko.payment.repository.PaymentCallbackRepository;
import com.nicko.payment.repository.PaymentRepository;
import com.nicko.payment.wallet.WalletCreditRequest;
import com.nicko.payment.wallet.WalletServiceClient;
import com.nicko.payment.wallet.WalletServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MpesaCallbackService {

    private static final int MAX_RETRIES = 5;
    private static final DateTimeFormatter MPESA_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final PaymentCallbackRepository callbackRepository;
    private final MpesaTransactionRepository mpesaTransactionRepository;
    private final PaymentRepository paymentRepository;
    private final WalletServiceClient walletServiceClient;
    private final PaymentEventFactory eventFactory;

    @Value("${app.mpesa.callback-token:}")
    private String callbackToken;

    @Transactional
    public MpesaCallbackResult receive(String suppliedToken, Map<String, Object> payload) {
        verifyCallbackToken(suppliedToken);
        Map<?, ?> stkCallback = stkCallback(payload);
        String checkoutRequestId = text(stkCallback.get("CheckoutRequestID"));
        if (checkoutRequestId == null || checkoutRequestId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "M-Pesa callback has no checkout request ID");
        }

        PaymentCallback callback = new PaymentCallback();
        callback.setProvider(PaymentProvider.MPESA);
        callback.setCallbackType("STK_CALLBACK");
        callback.setProviderReference(checkoutRequestId);
        callback.setPayload(payload);
        callback.setReceivedAt(Instant.now());
        callback.setProcessingStatus(CallbackProcessingStatus.RECEIVED);
        callback = callbackRepository.saveAndFlush(callback);

        MpesaTransaction transaction = mpesaTransactionRepository.findByCheckoutRequestId(checkoutRequestId)
                .orElse(null);
        if (transaction == null) {
            markFailed(callback, "No payment attempt matched this checkout request");
            return new MpesaCallbackResult("FAILED");
        }
        return process(callback, transaction, stkCallback);
    }

    @Transactional
    public void retryFailed(UUID callbackId) {
        PaymentCallback callback = callbackRepository.findByIdForUpdate(callbackId).orElse(null);
        if (callback == null || callback.getProcessingStatus() != CallbackProcessingStatus.FAILED
                || callback.getRetryCount() >= MAX_RETRIES) return;

        callback.setRetryCount(callback.getRetryCount() + 1);
        Map<?, ?> stkCallback;
        try {
            stkCallback = stkCallback(callback.getPayload());
        } catch (ResponseStatusException exception) {
            markFailed(callback, exception.getReason());
            return;
        }
        MpesaTransaction transaction = mpesaTransactionRepository.findByCheckoutRequestId(callback.getProviderReference())
                .orElse(null);
        if (transaction == null) {
            markFailed(callback, "No payment attempt matched this checkout request");
            return;
        }
        process(callback, transaction, stkCallback);
    }

    private MpesaCallbackResult process(PaymentCallback callback, MpesaTransaction transaction, Map<?, ?> stkCallback) {
        PaymentAttempt attempt = transaction.getPaymentAttempt();
        Payment payment = paymentRepository.findByIdForUpdate(attempt.getPayment().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        callback.setPayment(payment);

        if (isFinal(payment.getStatus())) {
            callback.setProcessingStatus(CallbackProcessingStatus.IGNORED);
            callback.setProcessedAt(Instant.now());
            callback.setNextRetryAt(null);
            callback.setErrorMessage("Payment already has a final status");
            return new MpesaCallbackResult("IGNORED");
        }

        Integer resultCode = integer(stkCallback.get("ResultCode"));
        transaction.setMerchantRequestId(text(stkCallback.get("MerchantRequestID")));
        transaction.setCheckoutRequestId(text(stkCallback.get("CheckoutRequestID")));
        transaction.setResultCode(resultCode);
        transaction.setResultDescription(text(stkCallback.get("ResultDesc")));

        if (resultCode == null) {
            markFailed(callback, "Callback is missing ResultCode");
            return new MpesaCallbackResult("FAILED");
        }
        if (resultCode != 0) {
            payment.setStatus(PaymentStatus.FAILED);
            attempt.setStatus(PaymentAttemptStatus.FAILED);
            attempt.setResponseTimestamp(Instant.now());
            attempt.setErrorCode(String.valueOf(resultCode));
            attempt.setErrorMessage(transaction.getResultDescription());
            markProcessed(callback);
            eventFactory.record(payment, "payment.deposit.failed.v1");
            return new MpesaCallbackResult("PROCESSED");
        }

        Map<String, Object> metadata = callbackMetadata(stkCallback);
        BigDecimal amount = decimal(metadata.get("Amount"));
        String receiptNumber = text(metadata.get("MpesaReceiptNumber"));
        String phoneNumber = text(metadata.get("PhoneNumber"));
        if (amount == null || amount.compareTo(payment.getAmount()) != 0 || receiptNumber == null
                || receiptNumber.isBlank()) {
            markFailed(callback, "Successful callback is missing a matching amount or M-Pesa receipt number");
            return new MpesaCallbackResult("FAILED");
        }
        if (phoneNumber != null && !normalizePhoneNumber(phoneNumber).equals(transaction.getPhoneNumber())) {
            markFailed(callback, "Callback phone number does not match the deposit request");
            return new MpesaCallbackResult("FAILED");
        }

        transaction.setMpesaReceiptNumber(receiptNumber);
        transaction.setTransactionDate(mpesaDate(metadata.get("TransactionDate")));
        mpesaTransactionRepository.saveAndFlush(transaction);
        callbackRepository.saveAndFlush(callback);

        try {
            walletServiceClient.credit(payment.getWalletId(),
                    new WalletCreditRequest(payment.getReference(), payment.getAmount(), payment.getCurrency()));
        } catch (WalletServiceException exception) {
            markFailed(callback, exception.getMessage());
            return new MpesaCallbackResult("FAILED");
        }

        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setCompletedAt(Instant.now());
        attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);
        attempt.setResponseTimestamp(Instant.now());
        markProcessed(callback);
        eventFactory.record(payment, "payment.deposit.completed.v1");
        return new MpesaCallbackResult("PROCESSED");
    }

    private void markFailed(PaymentCallback callback, String reason) {
        callback.setProcessingStatus(CallbackProcessingStatus.FAILED);
        callback.setErrorMessage(reason);
        callback.setProcessedAt(Instant.now());
        callback.setNextRetryAt(callback.getRetryCount() < MAX_RETRIES
                ? Instant.now().plusSeconds(Math.min(300, 5L << callback.getRetryCount())) : null);
    }

    private static void markProcessed(PaymentCallback callback) {
        callback.setProcessingStatus(CallbackProcessingStatus.PROCESSED);
        callback.setProcessedAt(Instant.now());
        callback.setNextRetryAt(null);
        callback.setErrorMessage(null);
    }

    private void verifyCallbackToken(String suppliedToken) {
        if (callbackToken.isBlank() || suppliedToken == null
                || !MessageDigest.isEqual(callbackToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static Map<?, ?> stkCallback(Map<String, Object> payload) {
        Map<?, ?> callback = asMap(asMap(payload.get("Body")).get("stkCallback"));
        if (callback.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid M-Pesa callback payload");
        }
        return callback;
    }

    private static boolean isFinal(PaymentStatus status) {
        return status == PaymentStatus.COMPLETED || status == PaymentStatus.FAILED
                || status == PaymentStatus.CANCELLED || status == PaymentStatus.REVERSED;
    }

    private static Map<String, Object> callbackMetadata(Map<?, ?> callback) {
        Map<?, ?> metadata = asMap(callback.get("CallbackMetadata"));
        Object items = metadata.get("Item");
        if (!(items instanceof List<?> list)) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Object item : list) {
            Map<?, ?> entry = asMap(item);
            String name = text(entry.get("Name"));
            if (name != null) values.put(name, entry.get("Value"));
        }
        return values;
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BigDecimal decimal(Object value) {
        try {
            return value == null ? null : new BigDecimal(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Instant mpesaDate(Object value) {
        String text = text(value);
        if (text == null || !text.matches("[0-9]{14}")) return null;
        return LocalDateTime.parse(text, MPESA_DATE).atZone(ZoneId.of("Africa/Nairobi")).toInstant();
    }

    private static String normalizePhoneNumber(String phoneNumber) {
        String normalized = phoneNumber.replaceAll("[+\\s-]", "");
        if (normalized.startsWith("0")) normalized = "254" + normalized.substring(1);
        return normalized;
    }
}
