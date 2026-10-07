# Software Requirements Specification (SRS)
## Mobile Money Platform — Wallet & Ledger Microservice (`wallet-service`)

---

### Document Control & Metadata

| Attribute | Details |
| :--- | :--- |
| **Document Version** | 1.0.0-PROD |
| **Status** | Approved / Baseline Architecture |
| **Author** | Senior Software Engineer (Amazon / FinTech Core Ledger) |
| **Reviewed By** | Principal Systems Architect, Staff Security Engineer, Head of Payments Engineering |
| **Target Service** | `wallet-service` (Mobile Money Core Ecosystem) |
| **Classification** | Restricted — Confidential (Core Financial Ledger) |
| **Last Updated** | October 2026 |

---

## 1. Introduction

### 1.1 Purpose
This Software Requirements Specification (SRS) establishes the authoritative functional, behavioral, and non-functional requirements for the **Wallet & Ledger Microservice (`wallet-service`)** within the mobile money platform. 

This document serves as the foundational engineering contract across:
- **Backend Core Engineers:** Guidance for implementing database transactions, locking hierarchies, API contracts, domain logic, and outbox messaging relays.
- **Quality Assurance & Automation Engineers:** Complete baseline for automated integration tests, concurrency fuzzing, performance benchmarking, chaos engineering, and BDD acceptance verification.
- **Site Reliability & Platform Engineers (SRE):** Definitions of operational SLAs, SLOs, latency budgets, disaster recovery targets (RPO/RTO), and infrastructure scaling topologies.
- **Compliance, Risk & Security Auditors:** Formal proof of double-entry ledger immutability, zero-knowledge PIN storage, regulatory compliance (Central Bank guidelines, PCI-DSS data boundary rules), and cryptographic step-up challenge flows.

### 1.2 Scope

#### 1.2.1 In-Scope Capabilities
The `wallet-service` is the sole source of financial truth for customer balances within the platform. It is responsible for:
1. **Automated Wallet Provisioning:** Subscribing to customer lifecycle events (`customer.created.v1`) to automatically initialize a sovereign, single-currency stored-value account with unique, collision-resistant wallet numbers.
2. **Double-Entry Financial Ledger:** Implementing strict, immutable double-entry accounting where every monetary movement is represented by paired `DEBIT` and `CREDIT` entries enforcing zero-sum conservation.
3. **Two-Phase Peer-to-Peer (P2P) Wallet Transfers:** Initiating balance reservations, validating customer eligibility via synchronous identity checks, enforcing strict customer PIN step-up verification, and executing atomic balance settlements.
4. **Step-Up Authentication & Credential Management:** Salted, PBKDF2-HMAC-SHA256 hashing of customer wallet PINs protected by a server-side HMAC secret; single-use, cryptographically secure step-up authorization token issuance; progressive failure lockout (3-strike circuit breaker).
5. **Background Transfer Expiration & Reservation Sweeping:** Deterministic background reconciliation tasks identifying expired reservation locks and releasing held funds without ledger contamination.
6. **Administrative Reversals:** Role-governed, non-destructive compensatory ledger postings executed by authorized compliance officers to reverse settled transfers.
7. **External Payment Partner Ingress/Egress (Two-Phase Settlement):** Providing internal idempotency-keyed endpoints for orchestrating reservations, commits, releases, and direct credits initiated by downstream payment gateways (e.g., M-Pesa, card acquirers).
8. **Transactional Outbox Event Relay:** Guaranteeing at-least-once asynchronous domain event publication to Apache Kafka utilizing database-level transaction coupling.

#### 1.2.2 Explicit Scope Exclusions
To maintain strict bounded contexts and prevent cross-domain contamination, the following capabilities are explicitly **excluded** from `wallet-service`:
- **Identity & KYC Lifecycle Management:** Customer KYC tiers, identity document uploads, sanction screening, and contact verification belong strictly to `customer-service`. `wallet-service` acts only as a consumer of verified eligibility state.
- **Payment Rail Protocol Translation:** Direct ISO 8583 message formatting, telecom Daraja API/M-Pesa STK push handshakes, card acquiring network handshakes, and banking host-to-host integrations belong exclusively to `payment-service`.
- **Foreign Exchange (FX) Conversion & Multi-Currency Arbitrage:** Currency conversion rates, spread calculations, and real-time FX trading logic are excluded. Each wallet is strictly single-currency (e.g., KES); cross-currency transfers must be mediated by an external orchestration layer.
- **Customer Authentication Token Issuance:** Issuing OIDC tokens, OAuth2 refresh workflows, and primary password authentication are handled by Keycloak. `wallet-service` only acts as an OAuth2 Resource Server validating JWT claims and managing wallet-specific secondary step-up PINs.
- **Customer Notification Dispatch:** Direct SMS, push notifications, or email dispatches are delegated to `notification-service` via asynchronous Kafka events.

### 1.3 Definitions, Acronyms, and Abbreviations

| Term / Acronym | Definition |
| :--- | :--- |
| **ACID** | Atomicity, Consistency, Isolation, Durability — transactional guarantees enforced at the PostgreSQL database layer. |
| **Available Balance** | The liquid funds immediately spendable by the wallet holder, defined mathematically as: $\text{Available Balance} = \text{Total Balance} - \text{Reserved Balance}$. |
| **Compensatory Entry** | An offsetting ledger transaction that neutralizes the financial effect of an earlier transaction without altering or deleting the original immutable ledger record. |
| **Deadlock Avoidance Lock Order** | Deterministic sorting of resource locks (e.g., `UUID_low < UUID_high`) prior to acquiring pessimistic row-level database locks (`SELECT ... FOR UPDATE`). |
| **Double-Entry Ledger** | An accounting standard where every financial transaction requires matching and equal debit and credit entries such that the fundamental accounting equation is preserved. |
| **HMAC** | Hash-based Message Authentication Code used as a pepper to protect salted PIN hashes and sign step-up tokens. |
| **Idempotency Key** | A unique, client-supplied string guaranteeing that retried HTTP requests perform the underlying financial mutation exactly once. |
| **Outbox Pattern** | Architectural pattern where domain entity mutations and outbound event messages are committed within the same relational database transaction. |
| **PBKDF2** | Password-Based Key Derivation Function 2 (RFC 2898), configured with HMAC-SHA256, 100,000 iterations, and random salt. |
| **Pessimistic Locking** | Database-level concurrency control (`SELECT ... FOR UPDATE`) acquiring row-level locks on wallet accounts during financial operations to eliminate race conditions. |
| **Reserved Balance** | Funds held in escrow for an in-flight, unconfirmed transaction (e.g., awaiting PIN step-up verification or payment gateway clearance). Reserved funds cannot be transferred or withdrawn. |
| **Step-Up Authentication** | Elevated challenge verification (wallet PIN) required before executing high-risk financial operations such as fund transfers. |
| **Total Balance** | The gross ledger balance legally attributed to the wallet. |
| **WORM** | Write Once, Read Many — immutable data persistence model enforced on the `wallet_ledger_entry` table via database triggers. |

### 1.4 References & Regulatory Frameworks
1. **RFC 6749 / RFC 7519:** The OAuth 2.0 Authorization Framework and JSON Web Token (JWT) Specifications.
2. **RFC 2898:** PKCS #5: Password-Based Cryptography Specification Version 2.0 (PBKDF2).
3. **ISO 4217:** Codes for the representation of currencies and funds (KES, USD, EUR).
4. **National Payment System (NPS) Act & Central Bank of Kenya (CBK) Guidelines:** Legal frameworks governing electronic retail payments, customer fund segregation, and audit trails.
5. **PCI-DSS v4.0:** Payment Card Industry Data Security Standard (Requirement 3: Protect Cardholder Data; Requirement 10: Log and Monitor all system and financial accesses).
6. **Core Mobile Money Enterprise System Architecture:** Document Ref `ARCH-CORE-2026-v2`.

---

## 2. Overall Description

### 2.1 Product Perspective & System Ecosystem Architecture
The `wallet-service` represents the financial core of the mobile money platform. It functions as an autonomous, decoupled microservice within a distributed event-driven mesh.

```
                              ┌─────────────────────────────────────────┐
                              │            Client Application           │
                              │       (Mobile App / Web Portal)         │
                              └────────────────────┬────────────────────┘
                                                   │ HTTPS / Bearer JWT
                                                   ▼
                                      ┌─────────────────────────┐
                                      │   API Gateway (Nginx /  │
                                      │  Spring Cloud Gateway)  │
                                      └────────────┬────────────┘
                                                   │
                  ┌────────────────────────────────┼────────────────────────────────┐
                  │                                │                                │
                  ▼                                ▼                                ▼
       ┌─────────────────────┐          ┌─────────────────────┐          ┌─────────────────────┐
       │  Customer Service   │          │   Wallet Service    │          │   Payment Service   │
       │   (Port: 8081)      │          │    (Port: 8082)     │◀─────────┤   (Port: 8083)      │
       └──────────┬──────────┘          └──────────┬──────────┘ Internal └──────────┬──────────┘
                  │                                │      ▲       REST/mTLS         │
                  │ customer.created.v1            │      │                         │
                  ▼                                │      │ Synchronous KYC Check   │
       ┌─────────────────────┐                     │      │ via OpenFeign           │
       │    Apache Kafka     │◀────────────────────┤      └─────────────────────────┘
       │  Distributed Mesh   │  wallet.transfer.   │
       └─────────────────────┘  completed.v1       ▼
                                            ┌──────────────┐
                                            │  PostgreSQL  │
                                            │ (wallet_db)  │
                                            └──────────────┘
```

- **Upstream:** Mobile clients access wallet APIs via the API Gateway using Keycloak-issued bearer tokens.
- **Downstream Sync:** `wallet-service` performs synchronous OpenFeign calls to `customer-service` (`/api/v1/customers/me`) protected by Resilience4j circuit breakers to verify customer status (`ACTIVE`) and wallet eligibility (`walletEligible == true`).
- **Downstream Internal Services:** `payment-service` directly calls internal endpoints (`/internal/v1/wallets/{walletId}/*`) authenticated via service-to-service mTLS and scoped Keycloak JWT tokens containing the `PAYMENT_SERVICE` role.
- **Asynchronous Bus:** Apache Kafka manages reliable, distributed integration:
  - Inbound: Consumes `CustomerWalletCreationRequestedEvent` from `customer-service`.
  - Outbound: Emits `wallet.created.v1`, `wallet.transfer.completed.v1`, `wallet.transfer.failed.v1`, and `wallet.transfer.reversed.v1`.

### 2.2 User Classes and System Actors

1. **End Customer (Authenticated User):**
   - Accesses standard wallet functionalities (querying balances, configuring wallet PINs, initiating P2P transfers, providing step-up authorization).
   - Identity authenticated via JWT (`sub` claim matching the wallet's `customer_id`).
2. **Payment Service Worker (System Principal):**
   - Microservice executing external mobile money or card payments.
   - Authorized via JWT possessing authority `PAYMENT_SERVICE`. Permitted to execute internal fund reservations, commits, releases, and credits.
3. **Wallet Administrator / Compliance Officer (Staff Actor):**
   - Operations officer auditing suspicious activity or resolving transaction disputes.
   - Authorized via JWT possessing authority `WALLET_ADMIN`. Permitted to invoke transaction reversal endpoints.
4. **Internal Background Expiration Engine:**
   - Autonomous Spring Scheduled cron process (`WalletTransferExpiryJob`) polling for stalled `PENDING_STEPUP` transfers and running compensating reservation unlocks.

### 2.3 Operating Environment & Deployment Topology

| Component | Standard Specification |
| :--- | :--- |
| **Runtime Platform** | Linux (Alpine / Red Hat UBI containerized image), OpenJDK 21 LTS |
| **Framework** | Spring Boot 3.3.x, Spring Cloud 2023.x (OpenFeign, Resilience4j) |
| **Database Tier** | PostgreSQL 16+ with ACID compliant write-ahead logging (WAL) |
| **Connection Pool** | HikariCP (configured with bounded connections, aggressive leak detection, 30s timeout) |
| **Message Broker** | Apache Kafka 3.6+ cluster (min.insync.replicas = 2, acks = all) |
| **Identity Provider** | Keycloak 24+ (OpenID Connect / OAuth 2.0 Provider) |
| **Observability** | Micrometer, Prometheus metrics exposition, OpenTelemetry / Zipkin tracing |

### 2.4 Assumptions and System Dependencies

1. **Identity Integrity:** Assumes Keycloak is high-availability and enforces valid digital signatures on all JWTs. Claims `sub` and `aud` (`wallet-service`) are unconditionally validated.
2. **Customer Service Contract:** Assumes `customer-service` adheres to strict response latencies ($p99 < 150\text{ ms}$). Fallback circuit breaking rejects transfers if `customer-service` is unreachable to avoid unauthorized transactions.
3. **Cryptographic Key Persistence:** Assumes `WALLET_STEPUP_HMAC_KEY` is provisioned via an external secret management store (HashiCorp Vault or AWS Secrets Manager) and preserved across application lifecycle restarts.
4. **PostgreSQL Clustered Topology:** Assumes PostgreSQL runs with synchronized streaming replication across multiple availability zones with zero transaction loss configurations (`synchronous_commit = on`).

---

## 3. Functional Requirements

```
                       TRANSFER STATE MACHINE & STEP-UP LIFECYCLE
                       
  [Client]              [Transfer Service]           [Database]             [Background Job]
     │                          │                         │                         │
     │ 1. Initiate Transfer     │                         │                         │
     ├─────────────────────────▶│                         │                         │
     │                          │ Lock Wallets (Pess.)    │                         │
     │                          ├────────────────────────▶│                         │
     │                          │ Reserve Balance         │                         │
     │                          │ Status: PENDING_STEPUP  │                         │
     │ 2. Return Transfer ID    │◀────────────────────────┤                         │
     │◀─────────────────────────┤                         │                         │
     │                          │                         │                         │
     │ 3. Submit PIN            │                         │                         │
     ├─────────────────────────▶│                         │                         │
     │                          │ Verify PBKDF2 Hash      │                         │
     │                          │ Issue Step-Up Token     │                         │
     │ 4. Return Step-Up Token  │ (TTL: 5 Minutes)        │                         │
     │◀─────────────────────────┤                         │                         │
     │                          │                         │                         │
     │ 5. Complete Transfer     │                         │                         │
     ├─────────────────────────▶│ Lock Wallets & Token    │                         │
     │                          ├────────────────────────▶│                         │
     │                          │ Settle Balances         │                         │
     │                          │ Append Ledger Rows      │                         │
     │                          │ Status: COMPLETED       │                         │
     │ 6. Transfer COMPLETED    │ Insert Outbox Event     │                         │
     │◀─────────────────────────┤◀────────────────────────┤                         │
     │                          │                         │                         │
     │                          │                         │ 7. TTL Expired (15 min) │
     │                          │                         │    No PIN Submitted     │
     │                          │                         │◀────────────────────────┤
     │                          │                         │ Release Reservation     │
     │                          │                         │ Status: FAILED          │
     │                          │                         │ Insert Outbox Event     │
```

---

### FR-01: Asynchronous Wallet Provisioning (Customer Onboarding)
- **Description:** Consumes verified onboarding events emitted when a customer registration is finalized. Automatically provisions an isolated stored-value account with initial zero balances.
- **Trigger:** Inbound Kafka event on topic `customer.wallet-creation.requested.v1` (or internal event `CustomerWalletCreationRequestedEvent`).
- **Inputs:**
  ```json
  {
    "eventId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "customerId": "d3b07384-d113-4944-9c8e-a9b0e149bc68",
    "timestamp": "2026-10-07T12:00:00Z",
    "data": {
      "currency": "KES"
    }
  }
  ```
- **Preconditions:**
  - `customerId` must be a valid, unique UUID.
  - The customer must not already possess an existing wallet record.
- **Postconditions:**
  - A new `wallet` row is created with `balance = 0.0000`, `reserved_balance = 0.0000`, `status = 'ACTIVE'`, and unique 10-digit numeric `wallet_number`.
  - Transactional `outbox_event` created for topic `wallet.created.v1`.
- **Error Conditions & Edge Cases:**
  - *Duplicate Consumer Delivery:* If `existsByCustomerId(customerId) == true`, consumer detects idempotent condition, acknowledges offset without error, and ignores duplicate creation.

---

### FR-02: Wallet PIN Setup and Modification
- **Endpoint:** `PUT /api/v1/wallets/{walletId}/pin`
- **Description:** Establishes or rotates the customer's 6-digit cryptographic PIN used for step-up verification. Protects credentials against brute-force attacks via rate-limiting and temporary account lockouts.
- **Inputs:**
  - Path Parameter: `walletId` (UUID, Public ID of the wallet)
  - Headers: `Authorization: Bearer <JWT>`
  - Request Body:
    ```json
    {
      "currentPin": "123456", // Optional on first setup; mandatory on change
      "newPin": "654321"       // Exactly 6 numeric digits [0-9]{6}
    }
    ```
- **Preconditions:**
  - Authenticated JWT subject matches `wallet.customer_id`.
  - Caller status is `ACTIVE` and `walletEligible == true` via `customer-service`.
  - `WALLET_STEPUP_HMAC_KEY` is present in system environment.
- **Postconditions:**
  - Generates secure random 16-byte salt.
  - Computes PBKDF2-HMAC-SHA256 hash using salt and server HMAC key pepper.
  - Persists hash, salt, and resets `failed_attempts = 0`, `locked_until = null`.
- **Error Conditions:**
  - Missing server pepper $\rightarrow$ `503 Service Unavailable`.
  - Non-numeric or invalid length PIN $\rightarrow$ `400 Bad Request`.
  - Missing `currentPin` when PIN is already established $\rightarrow$ `409 Conflict`.
  - Invalid `currentPin` $\rightarrow$ Increments `failed_attempts`; returns `401 Unauthorized`.
  - 3 consecutive failures reached $\rightarrow$ Sets `locked_until = NOW() + 15 minutes`; returns `429 Too Many Requests`.

---

### FR-03: Two-Phase Wallet Transfer Initiation & Reservation
- **Endpoint:** `POST /api/v1/wallets/{sourceWalletId}/transfers`
- **Description:** First phase of peer-to-peer transfer. Validates business constraints, locks wallets in ordered sequence to prevent deadlocks, holds the requested amount in `reserved_balance`, and creates a transfer record in `PENDING_STEPUP` status.
- **Inputs:**
  - Path Parameter: `sourceWalletId` (UUID)
  - Headers: `Authorization: Bearer <JWT>`
  - Request Body:
    ```json
    {
      "destinationWalletId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "amount": 2500.00,
      "currency": "KES",
      "idempotencyKey": "tx-client-req-90412-abcdef",
      "description": "Payment for lunch"
    }
    ```
- **Preconditions:**
  - Authenticated customer owns `sourceWalletId`.
  - Customer is `ACTIVE` and `walletEligible == true`.
  - `sourceWalletId != destinationWalletId`.
  - Both wallets have status `ACTIVE`.
  - Currencies of both wallets match request currency.
  - Source wallet available funds: $(\text{balance} - \text{reserved\_balance}) \ge \text{amount}$.
- **Postconditions:**
  - Source wallet `reserved_balance` incremented by `amount`.
  - `wallet_transfer` created with status `PENDING_STEPUP`, `reference = UUID`, and `expires_at = NOW() + 15 minutes`.
  - HTTP `200 OK` returning transfer details.
- **Error Conditions:**
  - Source equals destination $\rightarrow$ `400 Bad Request`.
  - Customer unverified / ineligible $\rightarrow$ `403 Forbidden`.
  - Insufficient available liquid balance $\rightarrow$ `409 Conflict`.
  - Non-matching currencies $\rightarrow$ `409 Conflict`.
  - Inactive wallet status $\rightarrow$ `409 Conflict`.
  - *Idempotency Conflict:* Duplicate `(source_wallet_id, idempotency_key)` with identical payload returns existing transfer; different payload $\rightarrow$ `409 Conflict`.

---

### FR-04: Step-Up Authentication & Challenge Token Issuance
- **Endpoint:** `POST /api/v1/wallets/{sourceWalletId}/transfers/{transferId}/stepup-token`
- **Description:** Verifies customer's 6-digit wallet PIN against stored PBKDF2 hash. Upon success, generates a single-use, cryptographically random, 5-minute time-to-live (TTL) step-up authorization token.
- **Inputs:**
  - Path Parameters: `sourceWalletId` (UUID), `transferId` (UUID)
  - Headers: `Authorization: Bearer <JWT>`
  - Request Body:
    ```json
    {
      "pin": "654321"
    }
    ```
- **Preconditions:**
  - Transfer exists in status `PENDING_STEPUP` and `expires_at > NOW()`.
  - Authenticated customer owns the transfer source wallet.
  - Credential is not currently locked (`locked_until == null` or past).
- **Postconditions:**
  - Generates 32-byte secure random token string.
  - Computes SHA-256 token hash and inserts/updates `stepup_token` row with `status = 'VERIFIED'` and `expires_at = NOW() + 5 minutes`.
  - Resets PIN `failed_attempts` to 0.
  - Returns raw token string in response (never logged or persisted in cleartext).
- **Error Conditions:**
  - Transfer expired or not in `PENDING_STEPUP` $\rightarrow$ `409 Conflict`.
  - PIN not established $\rightarrow$ `409 Conflict`.
  - Incorrect PIN $\rightarrow$ Increments `failed_attempts`; returns `401 Unauthorized`.
  - PIN locked $\rightarrow$ `429 Too Many Requests`.

---

### FR-05: Wallet Transfer Completion & Double-Entry Ledger Posting
- **Endpoint:** `POST /api/v1/wallets/{sourceWalletId}/transfers/{transferId}/complete`
- **Description:** Executes atomic balance settlement. Consumes the step-up token, decrements source balance & reserved balance, increments destination balance, writes balancing append-only double-entry ledger rows, and enqueues an outbox event.
- **Inputs:**
  - Path Parameters: `sourceWalletId` (UUID), `transferId` (UUID)
  - Headers: `Authorization: Bearer <JWT>`
  - Request Body:
    ```json
    {
      "stepupToken": "raw_opaque_crypto_token_string"
    }
    ```
- **Preconditions:**
  - Transfer exists, `status == 'PENDING_STEPUP'`, and `expires_at > NOW()`.
  - Step-up token matches hash, belongs to transfer and source wallet, has `status == 'VERIFIED'`, and has not expired.
  - Both wallets are `ACTIVE`.
- **Postconditions (Atomic DB Transaction):**
  - Source wallet: `reserved_balance -= amount`, `balance -= amount`.
  - Destination wallet: `balance += amount`.
  - Transfer status updated to `COMPLETED`, `completed_at = NOW()`.
  - Step-up token updated to `status = 'CONSUMED'`, `consumed_at = NOW()`.
  - Two rows written to `wallet_ledger_entry`:
    - DEBIT entry on source wallet: `balance_before`, `balance_after = balance_before - amount`.
    - CREDIT entry on destination wallet: `balance_before`, `balance_after = balance_before + amount`.
  - Outbox event written with `event_type = 'wallet.transfer.completed.v1'`.
- **Error Conditions:**
  - Invalid, already consumed, or expired token $\rightarrow$ `401 Unauthorized`.
  - Transfer already expired $\rightarrow$ `409 Conflict`.
  - Database row locked / concurrent modification $\rightarrow$ Automatic retry or `500 Internal Error` with complete rollback.

---

### FR-06: Automated Transfer Expiration & Reservation Sweeper
- **Component:** Background Scheduled Task (`WalletTransferExpiryJob`)
- **Description:** Background process executing every minute (configurable) scanning for transfers in `PENDING_STEPUP` state whose `expires_at` timestamp has passed.
- **Preconditions:**
  - `wallet_transfer.status == 'PENDING_STEPUP'` and `wallet_transfer.expires_at <= NOW()`.
- **Postconditions (Per Expired Transfer):**
  - Acquires pessimistic locks on source and destination wallets in canonical ID order.
  - Decrements `source_wallet.reserved_balance` by `transfer.amount`.
  - Sets `wallet_transfer.status = 'FAILED'`, `failure_reason = 'STEPUP_EXPIRED'`.
  - Marks active `stepup_token` as `EXPIRED`.
  - Records `outbox_event` with type `wallet.transfer.failed.v1`.
- **Guarantees:**
  - Zero ledger entries generated (no funds changed net ownership).
  - Reserved balances fully restored to customer's available pool.

---

### FR-07: Administrative Transfer Reversal & Compensatory Ledger Posting
- **Endpoint:** `POST /api/v1/admin/transfers/{transferId}/reversal`
- **Description:** Reverses a previously `COMPLETED` transfer upon compliance or dispute approval. Implements a full compensatory financial transaction reversing debtor and creditor roles.
- **Inputs:**
  - Path Parameter: `transferId` (UUID)
  - Headers: `Authorization: Bearer <AdminJWT>` (Requires authority `WALLET_ADMIN`)
  - Request Body:
    ```json
    {
      "reason": "Court order dispute resolution case #99482"
    }
    ```
- **Preconditions:**
  - Caller principal holds `WALLET_ADMIN` role.
  - Original transfer `status == 'COMPLETED'`.
  - Transfer has not been previously reversed (enforced via unique constraint on `wallet_transfer_reversal.original_transfer_id`).
  - Destination wallet has sufficient available liquid funds to cover reversal: $(\text{balance} - \text{reserved\_balance}) \ge \text{original\_amount}$.
  - Both wallets are `ACTIVE`.
- **Postconditions:**
  - Deducts `amount` from original destination wallet.
  - Credits `amount` to original source wallet.
  - Creates new compensatory `wallet_transfer` record (`status = 'COMPLETED'`, `idempotency_key = 'REVERSAL-{originalId}'`).
  - Appends two new `wallet_ledger_entry` records with `entry_type = 'TRANSFER_REVERSAL'`.
  - Inserts audit row in `wallet_transfer_reversal` linking original and reversal transfer IDs.
  - Emits `wallet.transfer.reversed.v1` outbox event.
- **Error Conditions:**
  - Non-admin caller $\rightarrow$ `403 Forbidden`.
  - Already reversed $\rightarrow$ `409 Conflict`.
  - Insufficient funds in destination wallet $\rightarrow$ `409 Conflict` (Prevents turning destination wallet negative).

---

### FR-08 to FR-11: Internal Payment Gateway Two-Phase Operations
Internal APIs restricted exclusively to the `PAYMENT_SERVICE` caller for handling top-ups (cash-in) and external withdrawals (cash-out via M-Pesa/Bank):

- **FR-08: Reserve Payment (`POST /internal/v1/wallets/{walletId}/reservations`):**
  - Creates an in-flight reservation holding funds for an external payment payout.
  - Increases `reserved_balance`. Creates `wallet_payment_operation` with `kind = 'RESERVATION'`, `state = 'RESERVED'`.
- **FR-09: Commit Payment (`POST /internal/v1/wallets/{walletId}/reservations/{paymentReference}/commit`):**
  - Confirms payout was executed by external rail. Decrements both `balance` and `reserved_balance`.
  - Transitions operation state to `COMMITTED`. Writes immutable `DEBIT` ledger row (`entry_type = 'WITHDRAWAL'`).
- **FR-10: Release Payment (`POST /internal/v1/wallets/{walletId}/reservations/{paymentReference}/release`):**
  - Payment rail rejected payout. Releases `reserved_balance` back to available balance.
  - Transitions operation state to `RELEASED`. Zero ledger entries created.
- **FR-11: Credit Payment (`POST /internal/v1/wallets/{walletId}/credits`):**
  - Cash-in/Deposit from external rail.
  - Increments `balance`. Transitions operation state to `POSTED`. Writes immutable `CREDIT` ledger row (`entry_type = 'DEPOSIT'`).

---

### FR-12: Transactional Outbox Event Publishing
- **Description:** Asynchronous event relay engine ensuring guaranteed message delivery to Kafka without distributed transactions (2PC).
- **Mechanism:**
  - Scheduled worker polling `outbox_event` table for rows where `status = 'PENDING'`.
  - Relays event payload to target topic with Kafka publisher idempotence enabled (`acks = all`).
  - Upon broker ACK, updates outbox record to `status = 'PUBLISHED'` and records `published_at`.
  - On failure, increments `attempt_count` with exponential backoff and persists `last_error`.

---

## 4. Non-Functional Requirements (NFR)

```
                            DISTRIBUTED LATENCY BUDGET (p99 SLA)
                            
  [Client]                [API Gateway]             [Wallet Svc]            [PostgreSQL]
     │                          │                         │                       │
     │─── HTTP Call (15ms) ────▶│                         │                       │
     │                          │─── Route & JWT (10ms) ─▶│                       │
     │                          │                         │─── Feign KYC (50ms) ─▶│ (Customer Svc)
     │                          │                         │─── Ordered Lock (30ms)│
     │                          │                         │─── Ledger Write (25ms)│
     │                          │                         │─── Outbox Ins. (10ms)─▶│
     │                          │◀── Response (10ms) ─────│◀── Commit (15ms) ─────│
     │◀── HTTP Res (15ms) ──────│                         │                       │
     
  TOTAL END-TO-END SLA BUDGET: 180ms (Within Target: p99 < 200ms)
```

### 4.1 Performance & Latency Budgets
1. **Response Time SLA/SLO (Measured at Service Boundary):**
   - **P2P Transfer Initiation (`POST /transfers`):**
     - $p50 < 45\text{ ms}$
     - $p95 < 120\text{ ms}$
     - $p99 < 200\text{ ms}$
   - **Step-Up Verification (`POST /stepup-token`):**
     - $p50 < 30\text{ ms}$
     - $p99 < 80\text{ ms}$ (including PBKDF2 compute budget)
   - **Transfer Completion & Ledger Settlement (`POST /complete`):**
     - $p50 < 40\text{ ms}$
     - $p95 < 100\text{ ms}$
     - $p99 < 180\text{ ms}$
   - **Internal Payment Operations (`reserve`, `commit`, `credit`):**
     - $p99 < 75\text{ ms}$
2. **Database Execution Time:** Maximum allowed duration for any financial mutation transaction is **50 ms** under peak load. Any transaction taking longer than **250 ms** triggers a severity warning.

### 4.2 Scalability & Concurrency Model
1. **Target Load Capacities:**
   - Launch Target: **500 concurrent financial transactions per second (TPS)** with sustained durability.
   - 12-Month Target: Auto-scale linearly to **5,000 TPS** via database read replicas and partitioned sharding.
2. **Concurrency & Deadlock Prevention Mechanism:**
   - Multi-account transfers touching two distinct wallets (Source and Destination) are subject to high-concurrency race conditions (e.g., Alice sends to Bob while Bob sends to Alice).
   - **Strict Lock Ordering Hierarchy:** All operations acquiring pessimistic locks on two wallets **MUST** sort the wallet UUIDs in natural lexicographical order (`UUID.compareTo`) and acquire locks sequentially:
     $$\text{First Lock} = \min(\text{UUID}_A, \text{UUID}_B), \quad \text{Second Lock} = \max(\text{UUID}_A, \text{UUID}_B)$$
   - This mathematical order eliminates circular lock acquisition graphs, guaranteeing **$0\%$ deadlock occurrence** across concurrent bidirectional transfers.
3. **Database Connection Pool Sizing:**
   - HikariCP pool sized according to the Postgres connection sizing formula:
     $$\text{PoolSize} = (\text{CoreCount} \times 2) + \text{SpindleCount}$$
   - Configured with `maximum-pool-size: 25`, `connection-timeout: 30000ms`, `max-lifetime: 1800000ms`.

### 4.3 Availability, Fault Tolerance & Graceful Degradation
1. **Uptime SLA:** $99.99\%$ monthly availability ($\le 4.38\text{ minutes}$ of unplanned downtime per month).
2. **Graceful Degradation & Circuit Breaking:**
   - Feign client communication with `customer-service` is wrapped in Resilience4j circuit breakers:
     - Failure rate threshold: $50\%$ over a sliding window of 20 calls.
     - Slow call threshold: $500\text{ ms}$.
     - When open, circuit breaker fails fast with `503 Service Unavailable`, rejecting transfer initiation to guarantee unverified customers cannot move funds.
3. **Kafka Broker Disconnection:**
   - If Apache Kafka is unreachable, the API continues operating without customer degradation.
   - The Transactional Outbox pattern guarantees events accumulate safely in the relational database. Once the broker recovers, the relay worker resumes dispatching events without message loss.

### 4.4 Consistency & Concurrency Guarantees

| Operation Domain | Consistency Model | Mechanism / Boundary |
| :--- | :--- | :--- |
| **Balance Checks & Reservation** | **Strict Immediate Consistency** | Enforced within single ACID transaction via PostgreSQL `SELECT ... FOR UPDATE` row locks. |
| **Double-Entry Ledger Writing** | **Strict Immediate Consistency** | Committed atomically with wallet balance updates in the exact same database transaction. |
| **Transfer Completion** | **Strict Immediate Consistency** | Single-use token invalidation and balance debits/credits share atomic commit boundaries. |
| **Kafka Event Notification** | **Eventual Consistency** | At-least-once asynchronous delivery via Transactional Outbox poller ($< 1\text{ second}$ lag). |
| **Customer Profile Verification** | **Read-Time Strong Consistency** | Synchronous REST verification with `customer-service` before reservation lock is established. |

### 4.5 Security Architecture & Cryptographic Controls
1. **OAuth2 / OIDC Token Verification:**
   - Stateless JWT verification using Keycloak JWKS public keys.
   - Rejection of tokens missing `aud: wallet-service` or where expiration time has elapsed.
2. **Step-Up PIN Cryptographic Invariants:**
   - **Salt Generation:** Cryptographically secure 16-byte random salt per credential (`SecureRandom`).
   - **Key Derivation:** PBKDF2 with HMAC-SHA256, 100,000 iterations.
   - **Pepper Protection:** Server-side HMAC secret (`WALLET_STEPUP_HMAC_KEY`) applied during hashing to defeat offline precomputation attacks if database tables are compromised.
   - **Cleartext Hygiene:** PINs are never logged, serialized into diagnostics, cached in Redis, or stored in application memory beyond the immediate request lifecycle.
3. **Opaque Step-Up Challenge Tokens:**
   - Generated as 32-byte cryptographically secure random alphanumeric strings.
   - Stored in database exclusively as SHA-256 hashes (`token_hash`).
   - Bound to specific `transfer_id`, `wallet_id`, with strict 5-minute single-use validity.

### 4.6 Auditability, Traceability & Immutability
1. **Immutable Double-Entry Ledger Protection (WORM):**
   - The `wallet_ledger_entry` table has an explicit PostgreSQL database trigger (`trg_ledger_immutable`) executing:
     ```sql
     CREATE TRIGGER trg_ledger_immutable
         BEFORE UPDATE OR DELETE ON wallet_ledger_entry
         FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();
     ```
   - Any SQL `UPDATE` or `DELETE` statement targeting financial ledger rows fails unconditionally with a database exception, even if initiated by application database users.
2. **Double-Entry Mathematical Invariants:**
   - Enforced by database check constraints:
     $$\text{ck\_ledger\_balance}: \begin{cases} 
     \text{DEBIT} \implies \text{balance\_after} = \text{balance\_before} - \text{amount} \\
     \text{CREDIT} \implies \text{balance\_after} = \text{balance\_before} + \text{amount}
     \end{cases}$$
3. **Distributed Correlation & Traceability:**
   - Every inbound request requires or generates an `X-Correlation-ID` / `requestId`.
   - Propagated through MDC logging contexts into all console logs, outbox payloads, and downstream Feign calls.

### 4.7 Disaster Recovery & Business Continuity
1. **Recovery Point Objective (RPO):** $\text{RPO} = 0$ for all committed financial ledger records. PostgreSQL WAL logs are continuously shipped to encrypted immutable object storage.
2. **Recovery Time Objective (RTO):** $\text{RTO} < 15\text{ minutes}$ for complete service restoration during catastrophic availability zone loss.
3. **Point-In-Time Recovery (PITR):** Capable of restoring the ledger database to any specific second within the preceding 35 calendar days.

---

## 5. External Interface Requirements

### 5.1 RESTful API Contracts
All APIs conform to JSON over HTTPS standards. Complete API specifications are published in OpenAPI 3.0 / Swagger format at `/swagger-ui/index.html`.

#### Summary of Public Endpoints
- `PUT /api/v1/wallets/{walletId}/pin`: Setup or update 6-digit wallet PIN.
- `POST /api/v1/wallets/{sourceWalletId}/transfers`: Initiate transfer and reserve balance.
- `POST /api/v1/wallets/{sourceWalletId}/transfers/{transferId}/stepup-token`: Acquire step-up authorization token via PIN verification.
- `POST /api/v1/wallets/{sourceWalletId}/transfers/{transferId}/complete`: Settle transfer using step-up token.

#### Summary of Internal & Admin Endpoints
- `POST /api/v1/admin/transfers/{transferId}/reversal`: Reverse completed transfer (Requires `WALLET_ADMIN`).
- `POST /internal/v1/wallets/{walletId}/reservations`: Hold funds for external payment.
- `POST /internal/v1/wallets/{walletId}/reservations/{paymentReference}/commit`: Finalize debit for completed external payout.
- `POST /internal/v1/wallets/{walletId}/reservations/{paymentReference}/release`: Unlock held funds for failed external payout.
- `POST /internal/v1/wallets/{walletId}/credits`: Deposit funds from external rail.

### 5.2 Event Streaming Interface (Apache Kafka)

#### Inbound Subscriptions

| Topic Name | Producer | Event Class | Handling Strategy |
| :--- | :--- | :--- | :--- |
| `customer.wallet-creation.requested.v1` | `customer-service` | `CustomerWalletCreationRequestedEvent` | Idempotent creation of wallet account with default currency. |

#### Outbound Publications

| Topic Name | Schema / Event Type | Trigger | Partition Key |
| :--- | :--- | :--- | :--- |
| `wallet.created.v1` | `WalletCreatedEvent` | Wallet record successfully initialized. | `customerId` |
| `wallet.transfer.completed.v1` | `WalletTransferCompletedEvent` | Transfer successfully settled. | `sourceWalletId` |
| `wallet.transfer.failed.v1` | `WalletTransferFailedEvent` | Transfer expired or rejected. | `sourceWalletId` |
| `wallet.transfer.reversed.v1` | `WalletTransferReversedEvent` | Administrative reversal posted. | `originalTransferId` |

### 5.3 Upstream Synchronous Dependencies

```
[wallet-service] ──(HTTP GET /api/v1/customers/me)──▶ [customer-service]
                  ├── Bearer Token Forwarding
                  ├── Timeout: Connect 2000ms, Read 3000ms
                  └── Resilience4j Circuit Breaker Protection
```

- **Degradation Policy:** If `customer-service` responds with `5xx` or times out, the circuit breaker opens. The transfer request is rejected with `503 Service Unavailable`. Under no circumstances will funds be reserved or transferred without confirmed eligibility.

---

## 6. Data Requirements

### 6.1 Logical Data Model & ERD Mapping

```
  ┌─────────────────────────────────────────────────────────┐
  │                         WALLET                          │
  ├─────────────────────────────────────────────────────────┤
  │ id : UUID (PK)                                          │
  │ public_id : UUID (UQ)                                   │
  │ customer_id : UUID (UQ)                                 │
  │ wallet_number : VARCHAR(20) (UQ)                        │
  │ currency : VARCHAR(3)                                   │
  │ balance : DECIMAL(19,4)                                 │
  │ reserved_balance : DECIMAL(19,4)                        │
  │ status : VARCHAR(20) [ACTIVE, SUSPENDED, FROZEN, CLOSED]│
  │ version : BIGINT                                        │
  │ created_at : TIMESTAMP                                  │
  │ updated_at : TIMESTAMP                                  │
  └───────────┬─────────────────────────────────┬───────────┘
              │ 1                               │ 1
              │                                 │
              │ *                               │ *
  ┌───────────▼─────────────┐       ┌───────────▼─────────────┐
  │     WALLET_TRANSFER     │       │ WALLET_PAYMENT_OPERATION│
  ├─────────────────────────┤       ├─────────────────────────┤
  │ id : UUID (PK)          │       │ id : UUID (PK)          │
  │ reference : VARCHAR(50) │       │ wallet_id : UUID (FK)   │
  │ source_wallet_id : FK   │       │ payment_ref : VARCHAR   │
  │ destination_wallet_id:FK│       │ kind : VARCHAR(20)      │
  │ amount : DECIMAL(19,4)  │       │ state : VARCHAR(20)     │
  │ status : VARCHAR(20)    │       │ amount : DECIMAL(19,4)  │
  │ idempotency_key : VAR   │       │ currency : VARCHAR(3)   │
  │ expires_at : TIMESTAMP  │       │ created_at : TIMESTAMP  │
  │ completed_at : TIMESTAMP│       └───────────┬─────────────┘
  └───────────┬─────────────┘                   │ 1
              │ 1                               │
              │                                 │
              ├───────────────────┐             │
              │ *                 │ 1           │ *
  ┌───────────▼─────────────┐ ┌───▼─────────────▼─────────────┐
  │      STEPUP_TOKEN       │ │      WALLET_LEDGER_ENTRY      │
  ├─────────────────────────┤ ├───────────────────────────────┤
  │ id : UUID (PK)          │ │ id : UUID (PK)                │
  │ transfer_id : UUID (FK) │ │ wallet_id : UUID (FK)         │
  │ token_hash : VARCHAR    │ │ transfer_id : UUID (FK, NULL) │
  │ status : VARCHAR(20)    │ │ payment_op_id : FK (NULL)     │
  │ expires_at : TIMESTAMP  │ │ entry_type : VARCHAR(20)      │
  │ consumed_at : TIMESTAMP │ │ direction : VARCHAR(6)        │
  └─────────────────────────┘ │ amount : DECIMAL(19,4)        │
                              │ balance_before : DECIMAL      │
                              │ balance_after : DECIMAL       │
                              │ created_at : TIMESTAMP        │
                              │ [TRIGGER: IMMUTABLE WORM]     │
                              └───────────────────────────────┘
```

### 6.2 Data Retention & Archival Policies

| Data Entity | Retention Period | Storage Tier | Archival Policy |
| :--- | :--- | :--- | :--- |
| **`wallet_ledger_entry`** | **10 Years (Indefinite)** | Primary Online PostgreSQL (3 yrs) $\rightarrow$ Encrypted Read-Only S3 Parquet (7 yrs) | Strict regulatory compliance; zero deletion permitted under any circumstances. |
| **`wallet_transfer`** | **7 Years** | Primary PostgreSQL (1 yr) $\rightarrow$ Cold Archive Partition (6 yrs) | Partitioned by `created_at` yearly. |
| **`stepup_token`** | **90 Days** | Primary PostgreSQL | Truncated / purged after 90 days via vacuum maintenance script. |
| **`outbox_event`** | **30 Days post-publish** | Primary PostgreSQL | Rows with `status = 'PUBLISHED'` purged after 30 days to bound table bloat. |

### 6.3 Database Migration Strategy
- Managed exclusively through **Flyway Versioned Migrations** (`db/migration/V1__*.sql` through `V4__*.sql`).
- Hibernate configuration enforced to `ddl-auto: validate` in production environments; all schema modifications require peer-reviewed SQL scripts executed during automated deployment pipelines.

---

## 7. System Constraints

### 7.1 Architectural & Technology Stack Mandates
1. **Storage Engine:** Relational PostgreSQL is mandatory; document/NoSQL stores are prohibited for core ledgering to ensure serializable transaction safety.
2. **Monetary Precision:** All balance fields must use Java `BigDecimal` mapped to PostgreSQL `DECIMAL(19, 4)`. **Floating point types (`float`, `double`) are strictly prohibited** to prevent binary rounding inaccuracies.
3. **Rounding Rules:** All monetary calculations follow `RoundingMode.HALF_EVEN` (Banker's Rounding) with zero precision truncation.

### 7.2 Regulatory & Banking Constraints
1. **Fund Segregation:** Customer wallet funds must strictly reconcile against the platform's custodial trust bank accounts at all times.
2. **Anti-Money Laundering (AML) Ceiling:** Single-transaction transfers exceeding configured central bank thresholds (e.g., KES 150,000) are blocked or flagged for compliance review.
3. **Data Residency:** All databases, encryption keys, and Kafka messaging partitions must reside within sovereign national borders in compliance with national cloud computing data protection legislation.

---

## 8. Acceptance Criteria (BDD Given-When-Then Traceability)

### AC-01: Transfer Initiation with Balance Reservation (Traces to FR-03)
```gherkin
Scenario: Customer successfully initiates transfer with sufficient liquid balance
  Given Customer "C-100" owns active wallet "W-SRC" with balance 1000.00 and reserved_balance 0.00
    And Customer "C-200" owns active wallet "W-DST" with balance 500.00
    And Customer "C-100" is verified as ACTIVE and walletEligible by Customer Service
  When Customer "C-100" sends POST /api/v1/wallets/W-SRC/transfers with amount 300.00 and currency "KES"
  Then the response status is 200 OK
    And the returned transfer status is "PENDING_STEPUP"
    And wallet "W-SRC" has balance 1000.00 and reserved_balance 300.00 (available 700.00)
    And no ledger entries have been written to "wallet_ledger_entry"
```

### AC-02: Prevent Transfer with Insufficient Available Balance (Traces to FR-03)
```gherkin
Scenario: Customer attempts transfer exceeding available liquid funds
  Given Customer "C-100" owns active wallet "W-SRC" with balance 1000.00 and reserved_balance 800.00 (available 200.00)
  When Customer "C-100" sends POST /api/v1/wallets/W-SRC/transfers with amount 300.00
  Then the response status is 409 CONFLICT
    And the error message states "Insufficient available funds"
    And wallet "W-SRC" reserved_balance remains unchanged at 800.00
```

### AC-03: PIN Verification & Single-Use Step-Up Issuance (Traces to FR-04)
```gherkin
Scenario: Customer verifies PIN and receives opaque step-up token
  Given Transfer "T-500" exists in status "PENDING_STEPUP" on wallet "W-SRC"
    And Customer "C-100" has configured wallet PIN "123456"
  When Customer "C-100" sends POST /api/v1/wallets/W-SRC/transfers/T-500/stepup-token with pin "123456"
  Then the response status is 200 OK
    And the response contains a 32-byte opaque "token"
    And a stepup_token record is saved with status "VERIFIED" and expires_at 5 minutes in the future
```

### AC-04: PIN Lockout after Three Failed Attempts (Traces to FR-02, FR-04)
```gherkin
Scenario: Customer enters incorrect PIN 3 consecutive times
  Given Customer "C-100" has failed PIN entry 2 times
  When Customer "C-100" enters incorrect PIN for the 3rd time
  Then the response status is 429 TOO_MANY_REQUESTS
    And the customer credential is locked for 15 minutes
    And subsequent PIN verification attempts immediately return 429 without checking hash
```

### AC-05: Atomic Transfer Completion & Balanced Ledger Creation (Traces to FR-05)
```gherkin
Scenario: Completing transfer with valid step-up token
  Given Transfer "T-500" for amount 300.00 is in status "PENDING_STEPUP"
    And wallet "W-SRC" has balance 1000.00 and reserved_balance 300.00
    And wallet "W-DST" has balance 500.00 and reserved_balance 0.00
    And step-up token "TOK-99" is valid and VERIFIED
  When Customer sends POST /api/v1/wallets/W-SRC/transfers/T-500/complete with token "TOK-99"
  Then the response status is 200 OK
    And transfer "T-500" status is "COMPLETED"
    And wallet "W-SRC" balance is 700.00 and reserved_balance is 0.00
    And wallet "W-DST" balance is 800.00 and reserved_balance is 0.00
    And exactly two rows are inserted into "wallet_ledger_entry":
      | Direction | Wallet | Amount | Balance Before | Balance After |
      | DEBIT     | W-SRC  | 300.00 | 1000.00        | 700.00        |
      | CREDIT    | W-DST  | 300.00 | 500.00         | 800.00        |
    And an outbox event "wallet.transfer.completed.v1" is persisted
```

### AC-06: Automatic Transfer Expiration Unlocks Reservations (Traces to FR-06)
```gherkin
Scenario: Background sweeper cleans up stalled transfers
  Given Transfer "T-800" for amount 150.00 has status "PENDING_STEPUP" and expires_at in the past
    And wallet "W-SRC" has reserved_balance 150.00
  When the "WalletTransferExpiryJob" runs
  Then transfer "T-800" status transitions to "FAILED" with failure_reason "STEPUP_EXPIRED"
    And wallet "W-SRC" reserved_balance is reduced to 0.00
    And wallet "W-SRC" balance remains unchanged
    And outbox event "wallet.transfer.failed.v1" is inserted
```

### AC-07: Administrative Reversal Execution (Traces to FR-07)
```gherkin
Scenario: Admin reverses settled transfer
  Given Completed transfer "T-500" transferred 300.00 from "W-SRC" to "W-DST"
    And wallet "W-DST" has balance 800.00 and reserved_balance 0.00
    And caller holds authority "WALLET_ADMIN"
  When Admin posts reversal to POST /api/v1/admin/transfers/T-500/reversal with reason "Fraud Dispute"
  Then the response status is 200 OK
    And wallet "W-DST" balance is debited to 500.00
    And wallet "W-SRC" balance is credited to 1000.00
    And compensatory ledger entries of type "TRANSFER_REVERSAL" are appended
    And an audit row is written to "wallet_transfer_reversal"
```

---

## 9. Appendices

### 9.1 Comprehensive Domain Glossary
- **Available Balance:** Spendable customer funds, computed dynamically as gross balance minus reserved balance.
- **Circuit Breaker:** Design pattern preventing cascade failures by failing fast when downstream services are unavailable.
- **Idempotency Key:** Client-generated identifier used to prevent duplicate executions of identical mutations.
- **PBKDF2-HMAC-SHA256:** Password hashing function designed to resist GPU-based brute-force cracking.
- **Pessimistic Locking:** Database lock preventing concurrent reads/writes on a row until the active transaction completes.
- **Outbox Pattern:** Architectural approach guaranteeing atomic persistence and decoupled publication of domain events.
- **WORM Storage:** Write Once, Read Many storage policy guaranteeing financial immutability.

### 9.2 Open Questions & Architectural Risks

| # | Item / Risk Description | Severity | Mitigation Plan |
| :--- | :--- | :--- | :--- |
| **R-01** | High-concurrency hot wallets (e.g., merchant accounts receiving hundreds of simultaneous payments) may experience thread contention on row-level locks. | Medium | Evaluate transaction striping or micro-batch ledger queues in Q1 2027 if merchant transfer velocity exceeds 200 TPS per single account. |
| **R-02** | Master HMAC pepper secret rotation policy. Rotating `WALLET_STEPUP_HMAC_KEY` invalidates existing PIN hashes. | High | Implement dual-key verification window (Primary / Secondary pepper keys) in v2.0 before key rotation schedule. |
| **R-03** | Disaster recovery cross-region replication lag causing split-brain ledger risk. | Critical | Enforce strict synchronous commit on primary database cluster; standby cross-region nodes run in asynchronous read-only mode with manual failover governance. |
