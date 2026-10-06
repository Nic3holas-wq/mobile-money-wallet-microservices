package com.nicko.wallet.exception;

public class CustomerServiceException extends RuntimeException {

    private final int status;

    public CustomerServiceException(String message) {
        this(message, 500, null);
    }

    public CustomerServiceException(String message, int status) {
        this(message, status, null);
    }

    public CustomerServiceException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}