# Software Design Specification (SDS)
## Mobile Money Core Platform — Wallet & Ledger Microservice (`wallet-service`)

---

## 1. Document Information

| Attribute | Specification Details |
| :--- | :--- |
| **Project / Service Name** | Mobile Money Core Platform / `wallet-service` |
| **Document Version** | 1.0.0-PROD |
| **Author** | Nicholas Murimi(Software Engineer) |
| **Date** | October 2026 |
| **Status** | Approved / Baseline Architecture Design |
| **Classification** | Restricted — Confidential (Core Financial Ledger) |
| **Related Specifications** | [Wallet Microservice Requirements Specification (SRS)](./Wallet_Microservice_Requirements_Specification.md) |

---

## 2. Introduction

### 2.1 Purpose
This Software Design Specification (SDS) provides the definitive technical blueprint, architecture, domain decomposition, and implementation details for the **Wallet & Ledger Microservice (`wallet-service`)**. It documents the low-level mechanical design of the service's double-entry accounting engine, cryptographic step-up challenge flows, concurrency control models, and distributed event streaming topologies.

### 2.2 Scope
This specification covers:
- The internal layered component architecture, including controllers, application services, domain entities, repositories, and asynchronous messaging engines.
- Physical database design in PostgreSQL 16, relational data models, append-only Write-Once-Read-Many (WORM) database triggers, and check constraints.
- RESTful HTTP API contracts, request/response DTO schemas, and RFC 7807 error formats.
- Apache Kafka event topologies, Transactional Outbox mechanics, partition hashing, and at-least-once delivery guarantees.
- Concurrency, locking invariants, deadlock elimination proofs, and ACID isolation boundary definitions.
- Multi-layered security, Keycloak JWT claims mapping, PBKDF2-HMAC-SHA256 password hashing with server peppers, and brute-force lockouts.
- Fault tolerance, Resilience4j circuit breakers, automated background reservation cleanup, and operational telemetry.

### 2.3 Intended Audience
- **Core Backend Engineers:** Implementation guide for domain logic, database operations, and event listeners.
- **Platform & SRE Teams:** Configuration reference for container deployment, Kafka topologies, and JVM runtime tuning.
- **QA & Test Automation Engineers:** Baseline for designing integration suites, concurrency fuzzing, and contract tests.
- **Security & Compliance Auditors:** Technical evidence of cryptographic isolation, tamper-evident ledgers, and zero cleartext PIN persistence.

### 2.4 References
1. **RFC 6749 / RFC 7519:** The OAuth 2.0 Authorization Framework and JSON Web Token (JWT) Specifications.
2. **RFC 2898:** PKCS #5: Password-Based Cryptography Specification Version 2.0 (PBKDF2).
3. **RFC 7807:** Problem Details for HTTP APIs.
4. **PostgreSQL 16 Documentation:** Concurrency Control, Explicit Locking, and Trigger Procedures.
5. **National Payment System (NPS) Guidelines & CBK Regulations:** Electronic stored-value accounts, fund segregation, and audit trails.

### 2.5 Definitions and Acronyms
- **ACID:** Atomicity, Consistency, Isolation, Durability.
- **Available Balance:** Liquid funds spendable by the customer ($\text{Balance} - \text{Reserved Balance}$).
- **Compensatory Entry:** An offsetting ledger entry neutralizing a prior financial movement without modifying past records.
- **DLT / DLQ:** Dead-Letter Topic / Dead-Letter Queue.
- **HMAC:** Hash-based Message Authentication Code.
- **MDC:** Mapped Diagnostic Context (SLF4J thread-local diagnostic logging).
- **Outbox Pattern:** Persistence design coupling domain entity mutations with outbound message creation in a single DB transaction.
- **Pessimistic Locking:** Database-level row locking (`SELECT ... FOR UPDATE`) preventing concurrent conflicting reads/writes.
- **Reserved Balance:** Funds locked in escrow for pending transfers or external payments.
- **Step-Up Authentication:** Secondary authorization challenge (6-digit PIN) required before executing fund movements.
- **WORM:** Write Once, Read Many (immutable append-only persistence).

---

## 3. Service Overview

### 3.1 Service Purpose
The `wallet-service` is the authoritative custodian of customer funds and stored-value accounts within the platform. It tracks monetary balances and ensures financial integrity using double-entry bookkeeping, where no money can be created or destroyed without balancing debit and credit entries.

### 3.2 Responsibilities
1. **Account Provisioning:** Automatically initializes single-currency stored-value wallets upon customer onboarding events.
2. **Two-Phase P2P Transfers:** Executes peer-to-peer transfers using a two-phase protocol (Fund Reservation $\to$ Step-Up Challenge $\to$ Atomic Settlement).
3. **Immutable Ledger Maintenance:** Generates balanced, append-only `DEBIT` and `CREDIT` records protected by database-level triggers.
4. **PIN Lifecycle & Challenge Issuance:** Manages customer wallet PINs and validates step-up credentials using PBKDF2-HMAC-SHA256 with server-side HMAC peppering.
5. **Background Stalled Reservation Reclamation:** Periodically sweeps expired in-flight transfers and restores reserved balances.
6. **Administrative Reversals:** Executes non-destructive compensatory ledger transfers initiated by compliance officers.
7. **External Payment Gateway Integration:** Exposes internal two-phase endpoints (`reserve`, `commit`, `release`, and direct `credit`) for downstream payment gateways.
8. **Reliable Domain Event Publication:** Guarantees at-least-once event delivery to Apache Kafka using a Transactional Outbox poller.

### 3.3 Boundaries and Scope Exclusions
- **Identity & KYC:** Customer onboarding, document verification, and tier assignments belong to `customer-service`.
- **Payment Rail Integrations:** Raw telecom Daraja/M-Pesa STK push and card gateway integrations belong to `payment-service`.
- **Foreign Exchange (FX):** Multi-currency exchange rate conversions are managed by an upstream orchestration service.
- **Primary Auth & Token Minting:** Handled exclusively by Keycloak (OIDC/OAuth2).
- **Customer Notifications:** SMS, Push, and Email messaging are handled by `notification-service` via asynchronous Kafka consumption.

### 3.4 Relationship to Other Services
- **`customer-service`:** Upstream provider of KYC eligibility verified synchronously via OpenFeign; upstream emitter of `customer.wallet.creation.requested.v1`.
- **`payment-service`:** Internal caller orchestrating reservations and deposits for external cash-in and cash-out flows.
- **`keycloak`:** Identity provider whose public signing keys validate incoming Bearer JWT tokens.
- **`notification-service`:** Downstream consumer of `wallet.transfer.completed.v1`, `wallet.transfer.failed.v1`, and `wallet.transfer.reversed.v1`.

---

## 4. Architectural Design

### 4.1 Architectural Style
The service implements a **Hexagonal (Ports and Adapters)** architectural pattern with strict boundary separation between business logic and infrastructure adapters:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          INBOUND TRANSPORT ADAPTERS                         │
│                                                                             │
│  [REST Controllers]           [Kafka Consumer]         [Scheduled Jobs]     │
│  - WalletTransferController   - CustomerWalletConsumer - ExpiryJob          │
│  - PaymentOpsController                                - OutboxPublisher    │
│  - WalletPinController                                                      │
│  - AdminTransferController                                                  │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Request DTOs / Method Invocations
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          APPLICATION SERVICES LAYER                         │
│                                                                             │
│  - WalletTransferService          - WalletPaymentOperationService           │
│  - WalletPinService               - WalletTransferReversalService           │
│  - WalletPinCredentialService     - WalletTransferExpirationService         │
│  - WalletService                  - WalletOutboxEventService                │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Domain Mutations & Locking Protocols
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            DOMAIN & CRYPTO CORE                             │
│                                                                             │
│  [Domain Entities]            [Cryptographic Engines]  [Value Generators]   │
│  - Wallet, WalletTransfer     - WalletPinCrypto        - WalletNumberGen    │
│  - WalletLedgerEntry          - (PBKDF2 / HMAC)                             │
│  - StepupToken, OutboxEvent                                                 │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Repository & Messaging Ports
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         OUTBOUND INFRASTRUCTURE ADAPTERS                    │
│                                                                             │
│  [Spring Data JPA / Postgres] [OpenFeign HTTP Client]   [Apache Kafka]      │
│  - PostgreSQL 16 Hikari Pool   - CustomerClient (KYC)   - KafkaTemplate     │
│  - WORM Database Triggers      - Resilience4j Breaker   - Outbox Poller     │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Internal Component Architecture
- **Web Layer:** Spring MVC REST controllers performing request validation (`jakarta.validation`), extracting authenticated JWT claims, and delegating to application services.
- **Service Layer:** Transactional boundaries (`@Transactional`), business rules, balance validations, and ordered pessimistic locking coordination.
- **Data Access Layer:** Spring Data JPA repositories executing explicit locking queries (`@Lock(LockModeType.PESSIMISTIC_WRITE)`).
- **Messaging Layer:** Spring Kafka template publishing outbox events, and container listeners consuming inbound provisioning requests.

### 4.3 Service Dependencies and Communication Patterns

| Target System | Protocol | Communication Pattern | Purpose |
| :--- | :--- | :--- | :--- |
| **`customer-service`** | HTTP/REST (JSON) | Synchronous / Protected by Circuit Breaker | Real-time KYC status and wallet eligibility verification. |
| **`PostgreSQL 16`** | JDBC / TCP | Synchronous / ACID Transactional | Core persistence, row locking, and append-only ledgering. |
| **`Apache Kafka`** | TCP (Kafka Binary) | Asynchronous / Fire-and-Forget (Buffered by Outbox) | Domain event publication and lifecycle message consumption. |
| **`Keycloak`** | HTTP/REST | Asynchronous Cache / Public Key Retrieval | JWKS certificate endpoint for stateless JWT validation. |

---

## 5. Component Design

### 5.1 Component Inventory

#### 5.1.1 Controllers (`com.nicko.wallet.controller`)
1. **`WalletTransferController`:**
   - `transfer(sourceWalletId, jwt, request)` $\to$ `POST /api/v1/wallets/{sourceWalletId}/transfers`
   - `acquireStepupToken(sourceWalletId, transferId, jwt, request)` $\to$ `POST /api/v1/wallets/{sourceWalletId}/transfers/{transferId}/stepup-token`
   - `complete(sourceWalletId, transferId, jwt, request)` $\to$ `POST /api/v1/wallets/{sourceWalletId}/transfers/{transferId}/complete`
2. **`PaymentWalletOperationsController`:**
   - Pre-authorized for `hasAuthority('PAYMENT_SERVICE')`.
   - `reserve(walletId, request)` $\to$ `POST /internal/v1/wallets/{walletId}/reservations`
   - `commit(walletId, paymentReference)` $\to$ `POST /internal/v1/wallets/{walletId}/reservations/{paymentReference}/commit`
   - `release(walletId, paymentReference)` $\to$ `POST /internal/v1/wallets/{walletId}/reservations/{paymentReference}/release`
   - `credit(walletId, request)` $\to$ `POST /internal/v1/wallets/{walletId}/credits`
3. **`WalletPinController`:**
   - `setOrChangePin(walletId, jwt, request)` $\to$ `PUT /api/v1/wallets/{walletId}/pin`
4. **`AdminWalletTransferController`:**
   - Pre-authorized for `hasAuthority('WALLET_ADMIN')`.
   - `reverse(transferId, jwt, request)` $\to$ `POST /api/v1/admin/transfers/{transferId}/reversal`

#### 5.1.2 Application Services (`com.nicko.wallet.service`)
1. **`WalletTransferService`:** Orchestrates transfer initiation, fund reservation, ordered locking, step-up token verification, balance mutation, and ledger posting.
2. **`WalletPaymentOperationService`:** Manages internal payment reservations, commits, releases, and direct credits.
3. **`WalletPinService` & `WalletPinCredentialService`:** Coordinates PIN hashing, salt generation, verification attempts, and brute-force lockout tracking.
4. **`WalletTransferExpirationService`:** Handles compensation for expired transfers by unlocking held funds and marking transfers failed.
5. **`WalletTransferReversalService`:** Executes administrative reversals by posting compensatory debits and credits.
6. **`WalletService`:** Handles account creation upon onboarding events.
7. **`WalletOutboxEventService`:** Persists domain events into the `outbox_event` table within active transactions.
8. **`WalletPinCrypto`:** Cryptographic engine managing PBKDF2-HMAC-SHA256 derivations, random salt creation, and token hashing.
9. **`WalletNumberGenerator`:** Generates collision-resistant, 10-digit human-readable account numbers.

#### 5.1.3 Repositories (`com.nicko.wallet.repository`)
- `WalletRepository`: Provides `findByPublicIdForUpdate` with pessimistic write locks.
- `WalletTransferRepository`: Provides `findByIdForUpdate` and idempotent lookups.
- `WalletLedgerEntryRepository`: Append-only persistence for double-entry records.
- `StepupTokenRepository`: Token lookups with row-level locks.
- `WalletPaymentOperationRepository`: Internal operation tracking.
- `WalletPinCredentialRepository`: Salt and hash credential persistence.
- `WalletTransferReversalRepository`: Reversal audit tracking.
- `OutboxEventRepository`: Polling interface for pending events.

#### 5.1.4 Domain Entities (`com.nicko.wallet.entity`)
- `Wallet`: Account aggregate root containing balance, reserved balance, currency, and status.
- `WalletTransfer`: P2P transfer entity with status, expiration, and idempotency key.
- `WalletLedgerEntry`: Immutable double-entry financial record.
- `StepupToken`: Challenge token bound to transfer and wallet.
- `WalletPaymentOperation`: Internal two-phase payment operation record.
- `WalletPinCredential`: Customer PIN credential entity.
- `WalletTransferReversal`: Audit entity linking original and reversal transfers.
- `OutboxEvent`: Transactional outbox event entity.

---

## 6. Data Design

### 6.1 Database Technology
- **Engine:** PostgreSQL 16+
- **Driver:** PostgreSQL JDBC Driver with HikariCP connection pooling.
- **Migration Framework:** Flyway (`src/main/resources/db/migration`).
- **Hibernate Dialect:** `org.hibernate.dialect.PostgreSQLDialect` with `ddl-auto: validate`.

### 6.2 Entity-Relationship Diagram (ERD)

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
              │ * (source / dest)               │ *
  ┌───────────▼─────────────┐       ┌───────────▼─────────────┐
  │     WALLET_TRANSFER     │       │ WALLET_PAYMENT_OPERATION│
  ├─────────────────────────┤       ├─────────────────────────┤
  │ id : UUID (PK)          │       │ id : UUID (PK)          │
  │ reference : VARCHAR(50) │       │ wallet_id : UUID (FK)   │
  │ source_wallet_id : FK   │       │ payment_ref : VARCHAR   │
  │ destination_wallet_id:FK│       │ kind : VARCHAR(20)      │
  │ amount : DECIMAL(19,4)  │       │ state : VARCHAR(20)     │
  │ currency : VARCHAR(3)   │       │ amount : DECIMAL(19,4)  │
  │ status : VARCHAR(20)    │       │ currency : VARCHAR(3)   │
  │ idempotency_key : VAR   │       │ created_at : TIMESTAMP  │
  │ expires_at : TIMESTAMP  │       │ updated_at : TIMESTAMP  │
  │ completed_at : TIMESTAMP│       │ version : BIGINT        │
  └───────────┬─────────────┘       └───────────┬─────────────┘
              │ 1                               │ 1
              │                                 │
              ├───────────────────┐             │
              │ *                 │ 1           │ *
  ┌───────────▼─────────────┐ ┌───▼─────────────▼─────────────┐
  │      STEPUP_TOKEN       │ │      WALLET_LEDGER_ENTRY      │
  ├─────────────────────────┤ ├───────────────────────────────┤
  │ id : UUID (PK)          │ │ id : UUID (PK)                │
  │ transfer_id : UUID (FK) │ │ wallet_id : UUID (FK)         │
  │ wallet_id : UUID (FK)   │ │ transfer_id : UUID (FK, NULL) │
  │ token_hash : VARCHAR    │ │ payment_op_id : FK (NULL)     │
  │ status : VARCHAR(20)    │ │ entry_type : VARCHAR(20)      │
  │ expires_at : TIMESTAMP  │ │ direction : VARCHAR(6)        │
  │ consumed_at : TIMESTAMP │ │ amount : DECIMAL(19,4)        │
  │ attempts : INTEGER      │ │ balance_before : DECIMAL      │
  │ resend_count : INTEGER  │ │ balance_after : DECIMAL       │
  └─────────────────────────┘ │ currency : VARCHAR(3)         │
                              │ created_at : TIMESTAMP        │
                              │ [TRIGGER: IMMUTABLE WORM]     │
                              └───────────────────────────────┘
```

### 6.3 Database Constraints and Integrity Rules
- **Non-Negative Balance:** `CONSTRAINT ck_wallet_balance CHECK (balance >= 0)`.
- **Reserved Balance Ceiling:** `CONSTRAINT ck_wallet_reserved_balance CHECK (reserved_balance >= 0 AND reserved_balance <= balance)`.
- **Double-Entry Arithmetic Verification:**
  ```sql
  CONSTRAINT ck_ledger_balance CHECK (
      (direction = 'DEBIT'  AND balance_after = balance_before - amount) OR
      (direction = 'CREDIT' AND balance_after = balance_before + amount)
  )
  ```
- **Single-Origin Constraint:** `CONSTRAINT ck_wallet_ledger_entry_origin CHECK ((transfer_id IS NOT NULL AND payment_operation_id IS NULL) OR (transfer_id IS NULL AND payment_operation_id IS NOT NULL))`.
- **Idempotency Uniqueness:** `CONSTRAINT uc_transfer_wallet_idem UNIQUE (source_wallet_id, idempotency_key)`.
- **WORM Immutability Trigger:** Trigger `trg_ledger_immutable` fires on `BEFORE UPDATE OR DELETE` of `wallet_ledger_entry` and raises a fatal PL/pgSQL exception.

---

## 7. API Design

### 7.1 Client Contracts

#### 7.1.1 Transfer Initiation
- **Path:** `POST /api/v1/wallets/{sourceWalletId}/transfers`
- **Request Body:**
  ```json
  {
    "destinationWalletId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "amount": 2500.00,
    "currency": "KES",
    "idempotencyKey": "idem-key-88319-a1b",
    "description": "Payment for supplies"
  }
  ```
- **Response (`200 OK`):** Returns `WalletTransferResponse` with status `PENDING_STEPUP`, source/destination IDs, and a 15-minute `expiresAt` window.

#### 7.1.2 Step-Up Challenge Verification
- **Path:** `POST /api/v1/wallets/{sourceWalletId}/transfers/{transferId}/stepup-token`
- **Request Body:**
  ```json
  {
    "pin": "123456"
  }
  ```
- **Response (`200 OK`):**
  ```json
  {
    "transferId": "4c9e6679-7425-40de-944b-e07fc1f90ae7",
    "token": "d7a8f9b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6",
    "expiresAt": "2026-10-07T12:05:00Z"
  }
  ```

#### 7.1.3 Transfer Completion
- **Path:** `POST /api/v1/wallets/{sourceWalletId}/transfers/{transferId}/complete`
- **Request Body:**
  ```json
  {
    "stepupToken": "d7a8f9b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6"
  }
  ```
- **Response (`200 OK`):** Returns `WalletTransferResponse` with status `COMPLETED` and updated balance figures.

### 7.2 RFC 7807 Standard Error Contract
All 4xx and 5xx responses conform to RFC 7807 Problem Details:
```json
{
  "type": "https://api.mobilemoney.internal/errors/insufficient-funds",
  "title": "Conflict",
  "status": 409,
  "detail": "Insufficient available funds",
  "instance": "/api/v1/wallets/8b7e2831-2f08-4122-bc5d-654876a3e211/transfers",
  "timestamp": "2026-10-07T12:00:00Z"
}
```

---

## 8. Event-Driven Design

### 8.1 Kafka Topic Topology and Configuration

| Topic Name | Purpose | Partition Key | In-Sync Replicas | Retention |
| :--- | :--- | :--- | :--- | :--- |
| `customer.wallet.creation.requested.v1` | Inbound customer provisioning requests | `customerId` | 2 | 7 Days |
| `wallet.created.v1` | Account initialized broadcast | `customerId` | 2 | 30 Days |
| `wallet.transfer.completed.v1` | Transfer settled event | `sourceWalletId` | 2 | 30 Days |
| `wallet.transfer.failed.v1` | Transfer expired / aborted event | `sourceWalletId` | 2 | 30 Days |
| `wallet.transfer.reversed.v1` | Compensatory transfer reversed event | `originalTransferId`| 2 | 30 Days |

### 8.2 Event Schemas and Payloads

#### `wallet.transfer.completed.v1`
```json
{
  "eventId": "f3b07384-d113-4944-9c8e-a9b0e149bc68",
  "eventType": "wallet.transfer.completed.v1",
  "timestamp": "2026-10-07T12:02:15Z",
  "correlationId": "tx-corr-992384-abc",
  "aggregateId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "data": {
    "transferId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "reference": "d04a625e-fa22-44df-911e-450f38b0c8bf",
    "sourceWalletId": "8b7e2831-2f08-4122-bc5d-654876a3e211",
    "destinationWalletId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "amount": 2500.00,
    "currency": "KES",
    "completedAt": "2026-10-07T12:02:15Z"
  }
}
```

### 8.3 Transactional Outbox Relay Implementation
The outbox poller runs every 1 second (`PT1S`):
1. Queries up to 100 pending events ordered by `created_at ASC`.
2. Sends payload to Kafka via `KafkaTemplate.send(topic, aggregateId, payload).get(10, TimeUnit.SECONDS)`.
3. Sets `status = OutboxStatus.PUBLISHED`, updates `published_at`, and clears errors upon broker acknowledgement.

---

## 9. Business Logic Design

### 9.1 State Transition Machines

#### 9.1.1 Wallet Transfer Lifecycle (`WalletTransfer.Status`)
```
                          ┌────────────────────────┐
                          │     (Initiation)       │
                          │   [PENDING_STEPUP]     │
                          └───────────┬────────────┘
                                      │
                 ┌────────────────────┴────────────────────┐
                 │ Valid Token & PIN                       │ Expiry Job (15m) / Error
                 ▼                                         ▼
     ┌───────────────────────┐                 ┌───────────────────────┐
     │      [COMPLETED]      │                 │       [FAILED]        │
     │ (Immutable Settlement)│                 │ (Reservation Released)│
     └───────────────────────┘                 └───────────────────────┘
```

#### 9.1.2 Step-Up Challenge Token Lifecycle (`StepupToken.Status`)
```
     [PENDING] ──(PIN Verified)──▶ [VERIFIED] ──(Used in /complete)──▶ [CONSUMED]
         │                            │
         └──(Expiry / Failed PIN)─────┴──▶ [EXPIRED] / [FAILED]
```

#### 9.1.3 Internal Payment Operation Lifecycle (`WalletPaymentOperation.State`)
```
     [RESERVED] ──(Partner Success)──▶ [COMMITTED] (Debited from Account)
         │
         └──(Partner Failure)────────▶ [RELEASED]  (Funds Unlocked)
```

---

## 10. Transaction & Consistency Design

### 10.1 Concurrency Control & Deadlock Elimination Proof
When transferring money between Wallets $A$ and $B$, concurrent transfers in opposite directions ($A \to B$ and $B \to A$) can cause database deadlocks if locks are acquired arbitrarily.

#### Canonical Ordering Algorithm
The system sorts wallet public IDs lexicographically prior to issuing pessimistic locks:
$$\text{First Lock} = \min(\text{UUID}_A, \text{UUID}_B), \quad \text{Second Lock} = \max(\text{UUID}_A, \text{UUID}_B)$$

```java
UUID lowId = Comparator.<UUID>naturalOrder().compare(firstId, secondId) < 0 ? firstId : secondId;
UUID highId = lowId.equals(firstId) ? secondId : firstId;

Wallet low = walletRepository.findByPublicIdForUpdate(lowId).orElseThrow(...);
Wallet high = walletRepository.findByPublicIdForUpdate(highId).orElseThrow(...);
```
**Deadlock Freedom Proof:** Since all transactions acquire locks in the exact same partial order ($low \to high$), circular wait conditions are mathematically impossible. Deadlock probability is $0\%$.

### 10.2 Idempotency Guarantees
Database uniqueness constraint on `(source_wallet_id, idempotency_key)` guarantees that network retries with identical idempotency keys return the existing record without duplicate balance mutations.

---

## 11. Security Design

### 11.1 Authentication & Authorization
- **OAuth2 Resource Server:** Validates Keycloak JWTs against JWKS public keys.
- **Audience Verification:** Enforces `aud: wallet-service`.
- **Role Mapping:** Maps realm roles `payment-service` $\to$ `PAYMENT_SERVICE` and `ADMIN` $\to$ `WALLET_ADMIN`.

### 11.2 Cryptographic PIN Architecture
- **Algorithm:** PBKDF2 with HMAC-SHA256, 310,000 iterations (OWASP standard).
- **Salt:** 24-byte cryptographically secure random value generated per customer.
- **Server-Side Pepper:** Master HMAC key (`WALLET_STEPUP_HMAC_KEY`) peppers the PIN prior to derivation.
- **Constant-Time Verification:** Hash matching uses `MessageDigest.isEqual()` to prevent timing attacks.
- **Lockout Circuit Breaker:** 3 consecutive failed verification attempts lock the customer credential for 15 minutes (`429 Too Many Requests`).

---

## 12. Error Handling & Resilience

### 12.1 Circuit Breakers on External Dependencies
OpenFeign calls to `customer-service` are wrapped with Resilience4j:
- Sliding window size: 20 calls.
- Failure threshold: 50%.
- Slow call threshold: 500ms.
- Fallback action: Fails fast with `503 Service Unavailable`, rejecting fund movement when identity status cannot be verified.

### 12.2 Automated Sweeper: `WalletTransferExpiryJob`
- Runs every 30 seconds (`PT30S`).
- Queries `findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(PENDING_STEPUP, Instant.now())`.
- Acquires ordered locks, unlocks `reserved_balance`, marks the transfer `FAILED`, and records `wallet.transfer.failed.v1` in the outbox.

---

## 13. Observability & Operational Excellence

### 13.1 Distributed Tracing & Logging
- **`WalletRequestLoggingFilter`:** Enforces `X-Request-Id`, binds `requestId` and Zipkin `traceId` into MDC, and measures HTTP completion durations.
- **`WalletLoggingAspect`:** AOP interceptor capturing execution durations across Controller, Service, and Repository layers.

### 13.2 Health Checks & Metrics
- **Probes:** `/actuator/health/liveness` and `/actuator/health/readiness`.
- **Prometheus Endpoint:** `/actuator/prometheus` tracking Hikari pool usage, JVM memory, and Outbox lag.

---

## 14. Performance & Scalability

### 14.1 Database Optimization & Sizing
- **HikariCP Configuration:** `maximum-pool-size: 10`, `connection-timeout: 30000ms`, `max-lifetime: 1800000ms`.
- **Target Throughput:** 500 TPS at launch, scaling to 5,000 TPS via PostgreSQL read replicas for non-transactional queries.

---

## 15. Deployment & Infrastructure

### 15.1 Container Configuration
Multi-stage Dockerfile utilizing Eclipse Temurin OpenJDK 21:
```dockerfile
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY target/wallet-service.jar app.jar
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app.jar"]
```

### 15.2 Resource Limits
- **CPU:** 1.0 Core (Request: 0.5 Core).
- **RAM:** 1.5 GB (Request: 1.0 GB).

---

## 16. Configuration Management

| Property Key | Environment Variable | Default Value | Description |
| :--- | :--- | :--- | :--- |
| `server.port` | `SERVER_PORT` | `8082` | HTTP listen port |
| `spring.datasource.url` | `WALLET_DB_HOST`, `WALLET_DB_PORT`, `WALLET_DB_NAME` | `jdbc:postgresql://postgresql:5432/wallet_db` | Postgres connection string |
| `app.stepup.hmac-key` | `WALLET_STEPUP_HMAC_KEY` | *(None / Required)* | Master 32-byte HMAC pepper key |
| `app.stepup.transfer-ttl` | `WALLET_TRANSFER_STEPUP_TTL` | `PT15M` | Transfer reservation window |
| `app.stepup.pin-lock-duration` | `WALLET_PIN_LOCK_DURATION` | `PT15M` | PIN lockout period |
| `app.stepup.max-attempts` | `WALLET_STEPUP_MAX_ATTEMPTS` | `3` | Maximum allowed failed PIN attempts |

---

## 17. Testing Design

### 17.1 Testing Pyramid Strategy
1. **Unit Tests (JUnit 5 / Mockito):** PIN crypto algorithms, decimal arithmetic, and validator rules.
2. **Integration Tests (`@DataJpaTest` / Testcontainers):** PostgreSQL concurrency tests verifying deadlock-free locking and WORM database triggers.
3. **Contract Tests (WireMock):** Customer service Feign client contracts and fallback validations.
4. **Kafka Embedded Tests:** Outbox publisher serialization and consumer offset commits.

---

## 18. Sequence & Workflow Diagrams

### 18.1 Failure Flow: Stalled Transfer Expiration Sweeper
```
[WalletTransferExpiryJob]      [Database (PostgreSQL)]            [Kafka Bus]
           │                              │                            │
           │ 1. Find Expired Transfers    │                            │
           ├─────────────────────────────▶│                            │
           │◀── List of Expired IDs ──────┤                            │
           │                              │                            │
           │ 2. Lock Wallets (Ascending)  │                            │
           ├─────────────────────────────▶│                            │
           │ 3. Decrement reserved_balance│                            │
           │ 4. Set status = FAILED       │                            │
           │ 5. Insert Outbox Event       │                            │
           ├─────────────────────────────▶│                            │
           │ 6. Commit Transaction        │                            │
           │◀─────────────────────────────┤                            │
           │                              │                            │
           │ 7. Outbox Relay Publishes    │                            │
           │    wallet.transfer.failed.v1 ├───────────────────────────▶│
```

---

## 19. Non-Functional Design

| Dimension | Target Metric | Architectural Mechanism |
| :--- | :--- | :--- |
| **Availability** | $99.99\%$ Uptime | Stateless container instances with automated Kubernetes pod restarts. |
| **Data Durability** | $\text{RPO} = 0$ | Synchronous PostgreSQL Write-Ahead Log (WAL) replication. |
| **Recovery Time** | $\text{RTO} < 15\text{ min}$ | Automated container redeployment and point-in-time database restoration. |
| **Latency Budget** | $p99 < 200\text{ ms}$ | Ordered row locks, bounded Hikari connection pools, and no external I/O in DB transactions. |

---

## 20. Architectural Decision Records (ADRs)

### ADR-01: Canonical Lexicographical Pessimistic Locking vs. Optimistic Locking
- **Context:** P2P transfers mutate balances across two separate accounts simultaneously.
- **Decision:** Use pessimistic locking (`SELECT ... FOR UPDATE`) ordered by canonical UUID.
- **Rationale:** Optimistic locking fails under high-concurrency wallet activity, generating excessive rollback retries. Lexicographical ordering mathematically guarantees $0\%$ deadlocks.

### ADR-02: Transactional Outbox Pattern vs. Two-Phase Commit (2PC)
- **Context:** Publishing domain events to Kafka upon database mutation.
- **Decision:** Transactional Outbox pattern polling PostgreSQL.
- **Rationale:** 2PC (XA transactions) reduces availability and throughput. The Outbox pattern ensures guaranteed message publication without distributed transaction locks.

### ADR-03: PBKDF2-HMAC-SHA256 with Server HMAC Pepper
- **Context:** Securing 6-digit wallet PINs against brute-force attacks.
- **Decision:** 310,000 iterations of PBKDF2-HMAC-SHA256 combined with a 32-byte server-side HMAC secret pepper.
- **Rationale:** 6-digit PINs have low search space ($10^6$ combinations). The server pepper prevents rainbow table attacks even if the database is compromised.

---

## 21. Risks & Limitations

| Risk ID | Risk Description | Severity | Mitigation Strategy |
| :--- | :--- | :--- | :--- |
| **RSK-01** | Hot wallet lock contention on high-volume merchant accounts. | Medium | Implement asynchronous batch credit queuing for merchant tiers in v2.0. |
| **RSK-02** | Master HMAC secret rotation invalidates existing stored PIN hashes. | High | Support dual-key verification windows during scheduled key rotations. |
| **RSK-03** | PostgreSQL outbox table disk bloat under high throughput. | Low | Implement daily table partitioning and truncate published rows older than 7 days. |

---

## 22. Future Improvements
1. **Merchant Batch Settlement:** Decouple instant reservation from batched nocturnal settlement for corporate merchant accounts.
2. **Cross-Currency Ledgering:** Add multi-currency ledger accounts with foreign exchange rate snapshots.
3. **Kafka CDC with Debezium:** Replace scheduled polling outbox publisher with Debezium change-data-capture streaming from Postgres WAL.

---

## 23. Appendices

### 23.1 Comprehensive Domain Glossary
- **Double-Entry Bookkeeping:** Accounting discipline where total debits equal total credits for every transaction.
- **Idempotency Key:** Client-provided UUID guaranteeing that duplicate HTTP requests execute exactly once.
- **WORM Storage:** Write Once, Read Many storage pattern preventing alteration of financial records.
