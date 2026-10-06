package com.nicko.wallet.service;

import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

public class BusinessCorrelation {

    static final Object KEY = new Object();

    private BusinessCorrelation() {
    }

    static UUID current() {

        String requestId = MDC.get("requestId");

        if (requestId != null) {
            try {
                return UUID.fromString(requestId);
            } catch (IllegalArgumentException ignored) {
            }
        }

        UUID existing =
                (UUID) TransactionSynchronizationManager
                        .getResource(KEY);

        if (existing != null) {
            return existing;
        }

        UUID id = UUID.randomUUID();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {

            TransactionSynchronizationManager.bindResource(KEY, id);

            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {

                        @Override
                        public void suspend() {
                            TransactionSynchronizationManager
                                    .unbindResourceIfPossible(KEY);
                        }

                        @Override
                        public void resume() {
                            TransactionSynchronizationManager
                                    .bindResource(KEY, id);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager
                                    .unbindResourceIfPossible(KEY);
                        }
                    }
            );
        }

        return id;
    }

    public static void set(UUID correlationId) {
        TransactionSynchronizationManager.bindResource(KEY, correlationId);
    }

    public static void clear() {
        TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
    }
}
