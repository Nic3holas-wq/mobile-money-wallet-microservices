package com.nicko.wallet.event;

public final class WalletEventTypes {

    private WalletEventTypes() {
    }

    public static final String CUSTOMER_WALLET_CREATION_REQUESTED =
            "CUSTOMER_WALLET_CREATION_REQUESTED";

    public static final String WALLET_CREATED =
            "WALLET_CREATED";

    public static final String WALLET_TRANSFER_COMPLETED = "wallet.transfer.completed.v1";
    public static final String WALLET_TRANSFER_FAILED = "wallet.transfer.failed.v1";
    public static final String WALLET_TRANSFER_REVERSED = "wallet.transfer.reversed.v1";
}
