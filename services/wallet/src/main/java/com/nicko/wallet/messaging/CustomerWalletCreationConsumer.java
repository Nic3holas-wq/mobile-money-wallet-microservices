package com.nicko.wallet.messaging;

import com.nicko.wallet.event.CustomerWalletCreationRequestedEvent;
import com.nicko.wallet.service.BusinessCorrelation;
import com.nicko.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class CustomerWalletCreationConsumer {

    private final WalletService walletService;

    @KafkaListener(
            topics = "customer.wallet.creation.requested.v1",
            groupId = "wallet-service"
    )
    @Transactional
    public void consume(
            CustomerWalletCreationRequestedEvent event
    ) {

        establishCorrelation(event);

        walletService.createWallet(event);
    }

    private void establishCorrelation(
            CustomerWalletCreationRequestedEvent event
    ) {

        if (event.correlationId() == null) {
            return;
        }

        BusinessCorrelation.set(event.correlationId());

        if (TransactionSynchronizationManager.isSynchronizationActive()) {

            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {

                        @Override
                        public void afterCompletion(int status) {
                            BusinessCorrelation.clear();
                        }
                    }
            );
        }
    }
}