package com.nicko.payment.wallet;

import java.math.BigDecimal;

public record WalletCreditRequest(String paymentReference, BigDecimal amount, String currency) {
}
