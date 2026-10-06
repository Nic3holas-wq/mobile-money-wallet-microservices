# Wallet transfers and step-up PIN

Wallet-to-wallet transfers require the customer to own the source wallet, be
active and wallet-eligible, and pass a PIN check. The transfer reserves funds
when initiated. A successful step-up token completes it; an unfinished transfer
releases its reservation after 15 minutes.

## Configure the PIN hashing key

Set a stable secret of at least 32 bytes before starting the wallet container:

```sh
export WALLET_STEPUP_HMAC_KEY="$(openssl rand -hex 32)"
docker compose -f services/docker-compose.yml up -d --build wallet-service
```

Keep the key in your secret manager or local environment and preserve it across
restarts. Rotating it makes existing PIN hashes and outstanding step-up tokens
unverifiable; customers will need to set their PIN again. Without this key, PIN
endpoints respond with `503`.

## Transfer flow

All requests use the customer's bearer token through the gateway.

1. Set a six-digit PIN with `PUT /api/v1/wallets/{walletId}/pin` and body
   `{"newPin":"<six digits>"}`. Changing a PIN requires `currentPin`.
2. Initiate a transfer with
   `POST /api/v1/wallets/{sourceWalletId}/transfers` and body containing
   `destinationWalletId`, `idempotencyKey`, `amount`, and `currency`. This
   returns a `PENDING_STEPUP` transfer and reserves the funds.
3. Acquire a token with
   `POST /api/v1/wallets/{sourceWalletId}/transfers/{transferId}/stepup-token`
   and body `{"pin":"<six digits>"}`. The opaque token is returned once and
   expires exactly five minutes after issuance.
4. Complete the transfer with
   `POST /api/v1/wallets/{sourceWalletId}/transfers/{transferId}/complete` and
   body `{"stepupToken":"<returned token>"}`. The token is consumed once.

PINs are stored as salted PBKDF2-HMAC-SHA256 hashes, protected by the configured
server-side HMAC key. Three failed PIN checks lock PIN actions for 15 minutes. Completed and expired
transfers are written to the wallet outbox in the same transaction as their
balance and ledger changes, then published to `wallet.transfer.completed.v1`
or `wallet.transfer.failed.v1`.
