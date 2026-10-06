package com.nicko.wallet.entity.enums;

/**
 * The core of double-entry bookkeeping: every WalletLedgerEntry is either
 * a DEBIT or a CREDIT. A Wallet is treated as a liability account (money
 * the platform owes the customer) - so DEBIT decreases the wallet's
 * balance and CREDIT increases it, consistent with standard liability
 * account conventions.
 */
public enum LedgerDirection {
    DEBIT,
    CREDIT
}
