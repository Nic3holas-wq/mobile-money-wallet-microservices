package com.nicko.payment.messaging;

import com.nicko.payment.entity.enums.CallbackProcessingStatus;
import com.nicko.payment.repository.PaymentCallbackRepository;
import com.nicko.payment.service.MpesaCallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class PaymentCallbackRetryJob {

    private static final int MAX_RETRIES = 5;

    private final PaymentCallbackRepository callbackRepository;
    private final MpesaCallbackService callbackService;

    @Scheduled(fixedDelayString = "${app.mpesa.callback-retry-interval-ms:5000}")
    public void retryFailedCallbacks() {
        callbackRepository.findTop25ByProcessingStatusAndRetryCountLessThanAndNextRetryAtBeforeOrderByReceivedAtAsc(
                        CallbackProcessingStatus.FAILED, MAX_RETRIES, Instant.now())
                .forEach(callback -> callbackService.retryFailed(callback.getId()));
    }
}
