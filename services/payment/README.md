# Payment Service

Spring Boot payment microservice, built with Maven. The first flow is a customer initiated M-Pesa Express deposit.

## Deposit API

`POST /api/v1/payments/deposits` requires a bearer token whose subject is the customer UUID, an `Idempotency-Key` header, and this request body:

```json
{
  "amount": 100,
  "phoneNumber": "254712345678",
  "description": "Wallet deposit"
}
```

The service resolves the customer's active wallet, creates the payment and provider attempt, then requests an M-Pesa Express prompt. The response is asynchronous; poll `GET /api/v1/payments/{paymentId}` for its current status. M-Pesa success callbacks credit the wallet with the payment reference as the wallet operation's idempotency key.

## Local configuration

Set these in `services/.env` before starting the stack:

```dotenv
MPESA_CONSUMER_KEY=
MPESA_CONSUMER_SECRET=
MPESA_BUSINESS_SHORT_CODE=
MPESA_PASSKEY=
MPESA_PUBLIC_BASE_URL=https://your-public-callback-host
MPESA_CALLBACK_TOKEN=use-a-long-random-value
PAYMENT_WALLET_CLIENT_SECRET=
```

`MPESA_PUBLIC_BASE_URL` must be reachable by Safaricom over HTTPS for real callback delivery. `MPESA_BASE_URL` defaults to the Safaricom sandbox. Do not commit these credentials.

The Keycloak `payment-service` client must be confidential, have service accounts enabled, and be configured with the `payment-service` realm role plus a `wallet-service` audience. Use that client's secret for `PAYMENT_WALLET_CLIENT_SECRET`; the wallet service checks both the audience and role on its internal payment endpoints. Customer access tokens also need the `payment-service` audience.

Run locally with `./mvnw spring-boot:run`. The outbox publisher sends payment events to `payment.events.v1` when Kafka is enabled.
