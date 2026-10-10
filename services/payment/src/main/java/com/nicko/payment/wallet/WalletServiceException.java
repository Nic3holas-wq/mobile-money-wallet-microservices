package com.nicko.payment.wallet;

public class WalletServiceException extends RuntimeException {

    public WalletServiceException(String message) {
        super(message);
    }

    public WalletServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
