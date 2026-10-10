package com.nicko.payment.provider.mpesa;

public class MpesaProviderException extends RuntimeException {

    private final boolean outcomeUnknown;

    public MpesaProviderException(String message) {
        super(message);
        this.outcomeUnknown = false;
    }

    public MpesaProviderException(String message, Throwable cause) {
        super(message, cause);
        this.outcomeUnknown = false;
    }

    public MpesaProviderException(String message, Throwable cause, boolean outcomeUnknown) {
        super(message, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}
