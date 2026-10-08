# Software Design Specification (SDS)
## Mobile Money Core Platform — Customer Identity & Lifecycle Microservice (`customer-service`)

---

## 1. Document Information

| Attribute | Specification Details |
| :--- | :--- |
| **Project / Service Name** | Mobile Money Core Platform / `customer-service` |
| **Document Version** | 1.0.0-PROD |
| **Author** | Nicholas Murimi(Software Engineer)|
| **Date** | October 2026 |
| **Status** | Approved / Baseline Architecture Design |
| **Classification** | Restricted — Confidential (Customer Personally Identifiable Information - PII) |
| **Related Specifications** | [Customer Microservice Requirements Specification (SRS)](./Customer_Microservice_Requirements_Specification.md) |

---

## 2. Introduction

### 2.1 Purpose
This Software Design Specification (SDS) documents the definitive engineering architecture, internal component decomposition, data modeling, cryptographic mechanisms, and operational topology for the **Customer Identity & Lifecycle Microservice (`customer-service`)**. It acts as the technical manual for developers, security architects, and platform operators.

### 2.2 Scope
This document specifies:
- Layered Ports-and-Adapters (Hexagonal) service structure.
- Entity-relationship schema, blind indexing, authenticated AES-256-GCM PII encryption, and WORM audit triggers in PostgreSQL 16.
- RESTful HTTP contracts, Spring MVC controller behaviors, and RFC 7807 error serialization.
- Apache Kafka event topologies, Outbox publisher design, and backfill synchronization.
- Business state machines governing Customer lifecycle, Contact verification, and KYC approval workflows.
- Security architecture: Keycloak Admin REST Client, JWT verification, OTP hashing, and rate limiting.
- Resilience patterns: Circuit breakers, transaction boundaries, and scheduled outbox relay engines.

### 2.3 Intended Audience
- **Backend Engineers:** Reference for implementing domain logic, service boundaries, and event handlers.
- **Security & Privacy Engineers:** Documentation for PII encryption, blind index hashing, and audit immutability.
- **QA Engineers:** Blueprint for integration, contract, and BDD test suites.
- **DevOps & Platform Engineers:** Container configuration, PostgreSQL resource sizing, and Kafka partition topology.

### 2.4 References
1. **RFC 6749 / RFC 7519:** OAuth 2.0 Authorization Framework & JSON Web Tokens.
2. **RFC 7807:** Problem Details for HTTP APIs.
3. **NIST Special Publication 800-38D:** Recommendation for Block Cipher Modes of Operation: Galois/Counter Mode (GCM).
4. **Google libphonenumber:** International phone number parsing and formatting library.
5. **PostgreSQL 16 Documentation:** Concurrency Control, Explicit Locking, and Trigger Procedures.

### 2.5 Definitions and Acronyms
- **AES-GCM:** Authenticated Encryption with Galois/Counter Mode.
- **Blind Index:** Irreversible HMAC hash of sensitive data enabling deterministic equality lookups without plaintext exposure.
- **E.164:** ITU-T standard for formatting international telephone numbers.
- **MDC:** Mapped Diagnostic Context for distributed request tracing.
- **OTP:** One-Time Password / Challenge code.
- **PII:** Personally Identifiable Information.
- **WORM:** Write Once, Read Many (immutable append-only persistence).

---

## 3. Service Overview

### 3.1 Service Purpose
The `customer-service` is the single source of truth for customer identity, contact information, regulatory consent, KYC tier validation, and lifecycle states within the mobile money platform. It ensures compliance with central banking regulations, AML/CFT rules, and consumer data protection statutes.

### 3.2 Responsibilities
1. **Identity Provisioning:** Mediates user creation with Keycloak and initializes customer profile aggregates.
2. **Contact Normalization & OTP Verification:** Validates and normalizes phone numbers to E.164, generates secure single-use OTP challenges, and enforces verified primary contact replacement rules.
3. **Regulatory Consent Governance:** Records evidentiary metadata for Terms of Service, Privacy Policy, and Marketing consents.
4. **KYC Document & Profile Management:** Ingests document metadata, securely encrypts document numbers using AES-256-GCM, maintains blind index hashes, and manages compliance review states.
5. **Onboarding Evaluation & Activation:** Evaluates customer readiness and executes account activation, publishing events to trigger downstream account creation in `wallet-service`.
6. **Velocity Limit Governance:** Maintains daily, monthly, and per-transaction limits per financial transaction type.
7. **Immutable Audit Logging (WORM):** Persists business state changes into append-only tables guarded by database-level triggers.

### 3.3 Boundaries and Scope Exclusions
- **Financial Ledgers & Balances:** Owned exclusively by `wallet-service`.
- **Payment Processing:** Owned exclusively by `payment-service`.
- **Primary Authentication:** Owned by Keycloak.
- **Physical SMS / Email Dispatch:** Handled by `notification-service`; this service manages challenge state and verification logic.
- **Binary File Storage:** Binary document files (scans/photos) reside in object storage; this service only maintains verified URI strings.

### 3.4 Relationship to Other Services
- **`Keycloak`:** Upstream OAuth2 provider.
- **`wallet-service`:** Downstream consumer of `customer.wallet.creation.requested.v1` and caller of synchronous KYC verification (`GET /api/v1/customers/me`).
- **`notification-service`:** Downstream consumer of contact verification and lifecycle events.

---

## 4. Architectural Design

### 4.1 Architectural Style
The service is constructed using the **Hexagonal (Ports and Adapters)** architectural pattern:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          INBOUND TRANSPORT ADAPTERS                         │
│                                                                             │
│  [REST Controllers]            [Keycloak Admin Client]  [Outbox Scheduler]  │
│  - CustomerController          - UserRegistration       - WalletOutboxPub   │
│  - ContactVerificationController                                            │
│  - KycProfileController / KycDocumentController                             │
│  - CustomerAdminController / KycAdminController                             │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ DTOs / Invocations
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          APPLICATION SERVICES LAYER                         │
│                                                                             │
│  - CustomerService            - ContactVerificationService                  │
│  - KycProfileService          - KycDocumentService                          │
│  - CustomerConsentService     - CustomerLimitService                        │
│  - CustomerAuditService       - CustomerCompletionService                   │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Domain Mutations & Locking
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            DOMAIN & CRYPTO CORE                             │
│                                                                             │
│  [Domain Entities]            [Cryptographic Engines]  [Normalizers]        │
│  - Customer, CustomerContact  - DocumentProtection     - ContactNormalizer  │
│  - KycProfile, KycDocument    - (AES-256-GCM / HMAC)   - (libphonenumber)   │
│  - CustomerConsent, Audit     - VerificationProtection                      │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Repositories & Messaging Ports
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         OUTBOUND INFRASTRUCTURE ADAPTERS                    │
│                                                                             │
│  [Spring Data JPA / Postgres] [Keycloak Admin REST]    [Apache Kafka]       │
│  - PostgreSQL 16 Hikari Pool  - Keycloak Admin API     - KafkaTemplate      │
│  - WORM Database Triggers                              - Outbox Relay       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Internal Component Architecture
- **Web Layer:** Spring MVC controllers handling authentication extraction, input validation, and HTTP status mappings.
- **Service Layer:** Business workflows, policy checks (age, consent version), and transactional consistency boundaries.
- **Security & Crypto Layer:** AES-GCM document encryption, HMAC-SHA256 blind indexing, and OTP verification hashing.
- **Persistence Layer:** Spring Data JPA repositories with explicit pessimistic locking on customer aggregates.

### 4.3 Service Dependencies and Communication Patterns

| Target System | Protocol | Communication Pattern | Purpose |
| :--- | :--- | :--- | :--- |
| **`Keycloak`** | HTTPS / REST | Synchronous (Client Credentials) | Provisioning user accounts via `/admin/realms/{realm}/users`. |
| **`PostgreSQL 16`** | JDBC / TCP | Synchronous / ACID Transactional | Relational persistence, audit logging, and outbox buffering. |
| **`Apache Kafka`** | TCP (Kafka Binary) | Asynchronous (Outbox Poller) | Emitting lifecycle and onboarding events. |

---

## 5. Component Design

### 5.1 Component Inventory

#### 5.1.1 Controllers (`com.nicko.customer.controller`)
1. **`UserRegistrationController`:** `POST /api/v1/auth/register` (Public Keycloak registration).
2. **`CustomerController`:** `POST /api/v1/customers`, `GET /api/v1/customers/me`, `PATCH /api/v1/customers/me`.
3. **`CustomerContactController`:** `POST/GET/PUT/DELETE /api/v1/customers/me/contacts`.
4. **`ContactVerificationController`:** `POST /contacts/{id}/verification-challenges`, `POST .../confirmation`.
5. **`CustomerAddressController`:** `POST/GET/PUT /api/v1/customers/me/addresses`.
6. **`CustomerConsentController`:** `GET /consents/policies`, `POST /consents`, `POST /consents/{id}/withdrawal`.
7. **`KycProfileController`:** `POST/GET/PUT /api/v1/customers/me/kyc/profile`, `POST .../submission`.
8. **`KycDocumentController`:** `POST/GET /api/v1/customers/me/kyc/documents`.
9. **`CustomerAdminController`:** `POST /api/v1/admin/customers/{id}/activation`.
10. **`KycAdminController`:** `POST /api/v1/admin/customers/{id}/kyc/profile/review`.
11. **`CustomerLimitAdminController`:** `POST/PUT/DELETE /api/v1/admin/customers/{id}/limits`.
12. **`CustomerAuditController`:** `GET /api/v1/admin/customers/{id}/audit-records`.

#### 5.1.2 Application Services (`com.nicko.customer.service`)
1. **`CustomerService`:** Core profile lifecycle, registration, patch updates, and activation workflows.
2. **`KeycloakUserRegistrationService`:** Client credentials HTTP integration with Keycloak Admin REST API.
3. **`ContactVerificationService`:** OTP challenge generation, rate limiting, and constant-time confirmation.
4. **`ContactNormalizer`:** Google `libphonenumber` integration for E.164 conversion.
5. **`CustomerConsentService` & `ConsentPolicyService`:** Enforces active version catalogs and withdrawal tracking.
6. **`KycProfileService` & `KycDocumentService`:** Coordinates tiered KYC profiles, document metadata, and review decisions.
7. **`CustomerCompletionService`:** Shared evaluator assessing readiness for activation.
8. **`CustomerAuditService`:** Records append-only audit entries under `Propagation.MANDATORY`.
9. **`WalletCreationOutboxPublisher`:** Scheduled poller dispatching outbox events to Kafka.

#### 5.1.3 Cryptographic Components (`com.nicko.customer.config`)
- **`DocumentProtection`:**
  - `hash(type, country, number)`: HMAC-SHA256 blind index generator.
  - `encrypt(number)`: AES-256-GCM symmetric cipher with 12-byte random nonce.
- **`VerificationCodeProtection`:** HMAC-SHA256 hasher for OTP verification challenge codes.

---

## 6. Data Design

### 6.1 Database Technology
- **Engine:** PostgreSQL 16+
- **Connection Pool:** HikariCP (`customer-service-pool`)
- **Schema Migrations:** Flyway (`V1__create_customer_schema.sql`, `V2__add_customer_audit_history.sql`, `V3__onboarding_verification_consent.sql`)

### 6.2 Entity-Relationship Schema (ERD)

```
  ┌─────────────────────────────────────────────────────────┐
  │                        CUSTOMER                         │
  ├─────────────────────────────────────────────────────────┤
  │ id : UUID (PK)                                          │
  │ keycloak_user_id : UUID (UQ)                            │
  │ customer_number : VARCHAR(255) (UQ)                     │
  │ first_name : VARCHAR(255)                               │
  │ last_name : VARCHAR(255)                                │
  │ date_of_birth : DATE                                    │
  │ nationality : CHAR(2)                                   │
  │ customer_status : VARCHAR [PENDING, ACTIVE, SUSPENDED]  │
  │ kyc_status : VARCHAR [NOT_STARTED, PENDING, APPROVED..] │
  │ kyc_tier : VARCHAR [TIER_0, TIER_1, TIER_2, TIER_3]     │
  │ wallet_eligible : BOOLEAN                               │
  │ version : BIGINT                                        │
  └───────────┬───────────────────┬─────────────────────┬───┘
              │ 1                 │ 1                   │ 1
              │                   │                     │
              │ *                 │ *                   │ 1
  ┌───────────▼───────────┐ ┌─────▼─────────────┐ ┌─────▼───────────┐
  │   CUSTOMER_CONTACT    │ │  CUSTOMER_ADDRESS │ │   KYC_PROFILE   │
  ├───────────────────────┤ ├───────────────────┤ ├─────────────────┤
  │ id : UUID (PK)        │ │ id : UUID (PK)    │ │ id : UUID (PK)  │
  │ customer_id : UUID(FK)│ │ customer_id: UUID │ │ status : VARCHAR│
  │ contact_type : VARCHAR│ │ address_type : VAR│ │ req_tier : VAR  │
  │ contact_value: VARCHAR│ │ is_primary : BOOL │ │ app_tier : VAR  │
  │ is_verified : BOOLEAN │ └───────────────────┘ └────────┬────────┘
  │ is_primary : BOOLEAN  │                                │ 1
  └───────────┬───────────┘                                │
              │ 1                                          │ *
              │ *                                 ┌────────▼────────┐
  ┌───────────▼───────────┐                       │  KYC_DOCUMENT   │
  │CONTACT_VERIFICATION_CH│                       ├─────────────────┤
  ├───────────────────────┤                       │ id : UUID (PK)  │
  │ id : UUID (PK)        │                       │ doc_hash : UQ   │
  │ customer_id: UUID(FK) │                       │ doc_encrypted   │
  │ code_hash : VARCHAR   │                       │ status : VARCHAR│
  │ attempts : INTEGER    │                       └─────────────────┘
  │ expires_at: TIMESTAMPT│
  └───────────────────────┘
```

### 6.3 Table DDL & Append-Only WORM Triggers

#### 6.3.1 Immutable Audit History: `customer_audit_record`
```sql
CREATE TABLE customer_audit_record (
    id             UUID PRIMARY KEY,
    customer_id    UUID NOT NULL REFERENCES customer(id),
    actor_id       UUID NOT NULL,
    action         VARCHAR(100) NOT NULL,
    target_type    VARCHAR(100) NOT NULL,
    target_id      UUID NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    before_state   JSONB NOT NULL,
    after_state    JSONB NOT NULL
);

CREATE INDEX idx_customer_audit_history ON customer_audit_record(customer_id, occurred_at, id);

-- Database-level WORM Protection
CREATE FUNCTION reject_customer_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Customer audit history is append-only';
END;
$$;

CREATE TRIGGER customer_audit_no_mutation 
    BEFORE UPDATE OR DELETE ON customer_audit_record
    FOR EACH ROW EXECUTE FUNCTION reject_customer_audit_mutation();

CREATE TRIGGER customer_audit_no_truncate 
    BEFORE TRUNCATE ON customer_audit_record
    FOR EACH STATEMENT EXECUTE FUNCTION reject_customer_audit_mutation();
```

#### 6.3.2 Encrypted KYC Documents: `kyc_document`
```sql
CREATE TABLE kyc_document (
    id                        UUID PRIMARY KEY,
    kyc_profile_id            UUID NOT NULL REFERENCES kyc_profile (id),
    document_number_hash      VARCHAR(255) NOT NULL, -- Blind index HMAC
    document_type             VARCHAR(255) NOT NULL,
    document_number_encrypted TEXT NOT NULL,         -- AES-256-GCM (v1:nonce:ciphertext)
    issuing_country           CHAR(2) NOT NULL,
    issued_at                 DATE,
    expires_at                DATE,
    front_file_reference      VARCHAR(255) NOT NULL,
    back_file_reference       VARCHAR(255),
    verification_status       VARCHAR(255) NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_kyc_document_document_number_hash UNIQUE (document_number_hash),
    CONSTRAINT ck_kyc_document_document_type CHECK (document_type IN ('NATIONAL_ID', 'PASSPORT', 'DRIVING_LICENSE', 'RESIDENCE_PERMIT')),
    CONSTRAINT ck_kyc_document_verification_status CHECK (verification_status IN ('PENDING', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED', 'EXPIRED'))
);
```

---

## 7. API Design

### 7.1 Key HTTP Endpoints and Contracts

#### 7.1.1 Public Keycloak User Registration
- `POST /api/v1/auth/register`
- Request: `{"username": "jane.doe", "email": "jane@example.com", "password": "...", "firstName": "Jane", "lastName": "Doe"}`
- Response: `201 Created` with Keycloak User ID.

#### 7.1.2 Profile Ingestion & Verification
- `POST /api/v1/customers`: Register customer aggregate.
- `GET /api/v1/customers/me`: Return authenticated profile, KYC tier, and status.
- `PATCH /api/v1/customers/me`: Allowlisted update for `preferredName` and `preferredLanguage`.

#### 7.1.3 Contact Verification Flow
- `POST /api/v1/customers/me/contacts`: Add phone/email contact.
- `POST /api/v1/customers/me/contacts/{id}/verification-challenges`: Request 6-digit OTP challenge.
- `POST /api/v1/customers/me/contacts/{id}/verification-challenges/{challengeId}/confirmation`: Submit OTP (`{"code": "123456"}`).

#### 7.1.4 KYC Ingestion & Evaluation
- `POST /api/v1/customers/me/kyc/profile`: Initialize KYC details.
- `POST /api/v1/customers/me/kyc/documents`: Upload encrypted document metadata.
- `POST /api/v1/customers/me/kyc/profile/submission`: Submit package for review.
- `POST /api/v1/admin/customers/{id}/kyc/profile/review`: Compliance approval/rejection.

#### 7.1.5 Activation Gate
- `GET /api/v1/customers/me/completion`: Evaluates prerequisite onboarding steps.
- `POST /api/v1/admin/customers/{id}/activation`: Promotes customer to `ACTIVE` and triggers wallet creation event.

---

## 8. Event-Driven Design

### 8.1 Kafka Message Architecture

```
                            EVENT EMISSION WORKFLOW
                            
  [Customer Action / Staff Review]
                 │
                 ▼
  [Database Transaction Commit]
  ├── Update Customer State
  └── Insert into outbox_event (status='PENDING')
                 │
                 ▼
  [WalletCreationOutboxPublisher] (Every 1s)
  ├── Polling outbox_event WHERE status = 'PENDING'
  └── KafkaTemplate.send(topic, customerId, payload)
                 │
                 ▼
      [Apache Kafka Cluster]
      ├── customer.activated.v1
      └── customer.wallet.creation.requested.v1
                 │
                 ▼
         [wallet-service]
```

### 8.2 Event Schemas and Payloads

#### `customer.wallet.creation.requested.v1`
```json
{
  "eventId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "eventType": "customer.wallet.creation.requested.v1",
  "schemaVersion": 1,
  "occurredAt": "2026-10-08T12:00:00Z",
  "customerId": "d3b07384-d113-4944-9c8e-a9b0e149bc68",
  "correlationId": "tx-corr-99482-a1b",
  "data": {
    "currency": "KES"
  }
}
```

---

## 9. Business Logic Design

### 9.1 Customer Lifecycle State Machine (`CustomerStatus`)
```
                          ┌────────────────────────┐
                          │     (Registration)     │
                          │       [PENDING]        │
                          └───────────┬────────────┘
                                      │
                                      │ Staff Activation (Completion Met)
                                      ▼
                          ┌────────────────────────┐
                          │        [ACTIVE]        │
                          └───────────┬────────────┘
                                      │
                   ┌──────────────────┴──────────────────┐
                   │ Compliance Sanction                 │ Regulatory Closure
                   ▼                                     ▼
       ┌───────────────────────┐             ┌───────────────────────┐
       │      [SUSPENDED]      │             │       [CLOSED]        │
       └───────────────────────┘             └───────────────────────┘
```

### 9.2 KYC Review State Machine (`KycStatus`)
```
     [NOT_STARTED] ──(Profile Created)──▶ [UNDER_REVIEW] / [PENDING]
                                                  │
                                                  ├──(Approved)──▶ [APPROVED]
                                                  │
                                                  └──(Rejected)──▶ [REJECTED]
```

---

## 10. Transaction & Consistency Design

### 10.1 Concurrency Control
- **Pessimistic Aggregate Locking:** Methods in `CustomerOwnership` execute `@Lock(LockModeType.PESSIMISTIC_WRITE)` when updating customer records, ensuring profile changes, contact promotions, and KYC updates are serialized.
- **Database Sequence Concurrency:** Customer public numbers use PostgreSQL sequence `customer_public_number_seq` (`SELECT nextval(...)`), guaranteeing contention-free unique identifier generation under heavy concurrent registration.

---

## 11. Security Design

### 11.1 Authentication & Role-Based Access Control
- Validates Keycloak Bearer JWTs with audience `customer-service`.
- Custom converter maps realm role `customer-admin` to authority `CUSTOMER_ADMIN`.
- Endpoints enforcing `CUSTOMER_ADMIN` prevent self-review (`reviewer.equals(customer.keycloakUserId()) == false`).

### 11.2 Cryptographic Data Protection (`DocumentProtection`)
1. **AES-256-GCM Encryption:**
   - Document numbers are encrypted using 256-bit AES keys with a 12-byte random IV (`SecureRandom`).
   - Format: `v1:<Base64-Nonce>:<Base64-Ciphertext>`.
2. **HMAC-SHA256 Blind Indexing:**
   - Enables fast exact-match document duplication lookups without decrypting all database rows.
   - Hash formula: $\text{HMAC-SHA256}_{K_{\text{hash}}}(\text{Type} \parallel ":" \parallel \text{Country} \parallel ":" \parallel \text{NormalizedNumber})$.

---

## 12. Error Handling & Resilience

### 12.1 Circuit Breakers & Fallbacks
- Keycloak Admin REST interactions are monitored with timeouts: Connect 2000ms, Read 3000ms.
- Downstream delivery failures for OTP simulation fail fast with `503 Service Unavailable`, preventing the issuance of phantom challenges.

### 12.2 Contact Challenge Rate Limiting
- Bounded attempts: At most 5 incorrect code attempts per challenge before permanent invalidation.
- Resend cooldown: Minimum 60-second delay between challenge generations.
- Volume ceiling: Maximum 5 challenges per customer per rolling hour.

---

## 13. Observability & Operational Excellence

### 13.1 Distributed Tracing & MDC Logging
- **`RequestLoggingFilter`:** Enforces `X-Request-Id`, binds `requestId` and Zipkin `traceId` to SLF4J MDC, and logs structured latency metrics.
- **`LoggingAspect`:** AOP interceptor monitoring Controller, Service, and Repository execution times.

### 13.2 Health Checks & Probes
- `/actuator/health/liveness`: Container vitality check.
- `/actuator/health/readiness`: PostgreSQL and Kafka connectivity verification.

---

## 14. Performance & Scalability

### 14.1 Database Indexing Strategy
- Unique indexes on `keycloak_user_id` and `customer_number`.
- Unique blind index index on `kyc_document.document_number_hash`.
- Composite index on `contact_verification_challenge(customer_id, created_at)`.

---

## 15. Deployment & Infrastructure

### 15.1 Containerization
- Base Image: `eclipse-temurin:21-jre-alpine`.
- Non-root runtime user: `appuser`.
- JVM Flags: `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0`.

---

## 16. Configuration Management

| Property Key | Environment Variable | Default Value | Description |
| :--- | :--- | :--- | :--- |
| `server.port` | `SERVER_PORT` | `8081` | HTTP server listen port |
| `spring.datasource.url` | `CUSTOMER_DB_HOST`, `CUSTOMER_DB_PORT`, `CUSTOMER_DB_NAME` | `jdbc:postgresql://postgresql:5432/customer_db` | Postgres connection string |
| `app.kyc.document-encryption-key` | `CUSTOMER_DOCUMENT_ENCRYPTION_KEY` | *(None / Required)* | 32-byte Base64 AES-256 key |
| `app.kyc.document-hash-key` | `CUSTOMER_DOCUMENT_HASH_KEY` | *(None / Required)* | 32-byte Base64 HMAC hash key |
| `app.verification.hmac-key` | `CUSTOMER_VERIFICATION_HMAC_KEY` | *(None / Required)* | 32-byte Base64 OTP challenge key |
| `app.onboarding.minimum-age` | `CUSTOMER_MINIMUM_AGE` | `18` | Minimum registration age |

---

## 17. Testing Design

### 17.1 Test Suites
1. **Unit Tests:** Phone E.164 parsing, AES-GCM encryption/decryption, age boundary validation.
2. **Integration Tests (`@SpringBootTest` / Testcontainers):** PostgreSQL WORM trigger enforcement and outbox event persistence.
3. **Security Slice Tests:** Keycloak JWT token validation and self-review prohibition tests.

---

## 18. Sequence & Workflow Diagrams

### 18.1 Happy Path: Contact OTP Challenge Flow
```
[Client]             [Gateway]           [Customer Svc]       [Delivery Adapter]     [Postgres DB]
   │                     │                      │                     │                    │
   │ 1. POST /challenges │                      │                     │                    │
   ├────────────────────▶│ 2. Forward JWT       │                     │                    │
   │                     ├─────────────────────▶│                     │                    │
   │                     │                      │ 3. Check Rate Limits│                    │
   │                     │                      │ 4. Hash Code (HMAC) │                    │
   │                     │                      │ 5. Save Challenge   │                    │
   │                     │                      ├─────────────────────────────────────────▶│
   │                     │                      │ 6. Send OTP Code    │                    │
   │                     │                      ├────────────────────▶│                    │
   │                     │ 7. 201 Created       │                     │                    │
   │◀────────────────────┼──────────────────────┤                     │                    │
   │                     │                      │                     │                    │
   │ 8. POST /confirmation (Code)               │                     │                    │
   ├────────────────────▶│                      │                     │                    │
   │                     ├─────────────────────▶│                     │                    │
   │                     │                      │ 9. Verify Hash      │                    │
   │                     │                      │ 10. is_verified=true│                    │
   │                     │                      │ 11. Insert Outbox   │                    │
   │                     │                      ├─────────────────────────────────────────▶│
   │                     │ 12. 200 OK           │                                          │
   │◀────────────────────┼──────────────────────┤                                          │
```

---

## 19. Non-Functional Design

| Dimension | Target Metric | Architectural Mechanism |
| :--- | :--- | :--- |
| **Availability** | $99.99\%$ Uptime | Kubernetes multi-pod deployment with rolling updates. |
| **PII Confidentiality**| Zero Plaintext Leaks | Authenticated AES-256-GCM encryption for document IDs. |
| **Audit Durability** | $100\%$ Immutable | PostgreSQL WORM triggers blocking UPDATE/DELETE/TRUNCATE. |
| **Latency SLA** | $p99 < 80\text{ ms}$ for `/me` | Indexed customer lookups and caching of Keycloak certs. |

---

## 20. Architectural Decision Records (ADRs)

### ADR-01: Blind Indexing with HMAC-SHA256 vs. Plaintext Unique Columns
- **Context:** Ensuring duplicate identity documents are rejected across accounts without storing plaintext national IDs.
- **Decision:** Use an HMAC-SHA256 blind index stored in `document_number_hash` with a unique constraint.
- **Rationale:** Prevents PII leakage in the event of database exfiltration while providing $O(1)$ duplicate lookups.

### ADR-02: Database Trigger WORM Protection vs. Application-Level Audit Logs
- **Context:** Enforcing non-repudiation for regulatory compliance.
- **Decision:** PL/pgSQL database triggers blocking UPDATE, DELETE, and TRUNCATE on `customer_audit_record`.
- **Rationale:** Prevents audit tampering even if an attacker gains application database credentials.

---

## 21. Risks & Limitations

| Risk ID | Description | Severity | Mitigation Strategy |
| :--- | :--- | :--- | :--- |
| **CUS-SDS-01** | Master encryption key compromise. | Critical | Store keys in cloud KMS with automated annual rotation policies. |
| **CUS-SDS-02** | External SMS delivery provider outages. | High | Support multi-vendor SMS routing (e.g., Africa's Talking, Twilio) in Notification Service. |

---

## 22. Future Improvements
1. **Biometric Face Verification:** Facial recognition matching between selfie uploads and government ID photos.
2. **Automated Sanctions Webhooks:** Real-time webhooks connecting PEP/Sanctions checks directly to Dow Jones or ComplyAdvantage.

---

## 23. Appendices

### 23.1 Comprehensive Domain Glossary
- **Blind Index:** Irreversible keyed hash of sensitive data permitting indexed searches.
- **WORM Storage:** Write Once, Read Many storage pattern preventing alteration of compliance records.
