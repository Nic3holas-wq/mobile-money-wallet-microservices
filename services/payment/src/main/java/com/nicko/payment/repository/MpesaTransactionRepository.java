package com.nicko.payment.repository;

import com.nicko.payment.entity.MpesaTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MpesaTransactionRepository extends JpaRepository<MpesaTransaction, UUID> {

    Optional<MpesaTransaction> findByCheckoutRequestId(String checkoutRequestId);

    Optional<MpesaTransaction> findByPaymentAttempt_Payment_Id(java.util.UUID paymentId);
}
