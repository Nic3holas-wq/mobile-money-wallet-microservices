# Software Requirements Specification (SRS)
## Mobile Money Platform — Customer Identity & Lifecycle Microservice (`customer-service`)

---

### Document Control & Metadata

| Attribute | Details |
| :--- | :--- |
| **Document Version** | 1.0.0-PROD |
| **Status** | Approved / Baseline Architecture |
| **Author** | Nicholas Murimi(Software Engineer) |
| **Reviewed By** | Principal Systems Architect, Staff Security Engineer, Head of Compliance & AML |
| **Target Service** | `customer-service` (Mobile Money Core Ecosystem) |
| **Classification** | Restricted — Confidential (Customer Personally Identifiable Information - PII) |
| **Last Updated** | October 2026 |

---

## 1. Introduction

### 1.1 Purpose
This Software Requirements Specification (SRS) establishes the authoritative functional, behavioral, and non-functional requirements for the **Customer Identity & Lifecycle Microservice (`customer-service`)** within the mobile money platform. 

This document serves as the formal engineering contract across:
- **Backend Core Engineers:** Specifications for identity registration, contact normalization, OTP challenges, consent version enforcement, KYC review workflows, AES-GCM data encryption, and outbox messaging.
- **Quality Assurance & Automation Engineers:** Complete baseline for automated integration tests, WireMock contract assertions, security boundary tests, and BDD acceptance verification.
- **Site Reliability & Platform Engineers (SRE):** Definitions of operational SLAs, latency budgets, disaster recovery targets (RPO/RTO), and container scaling topologies.
- **Compliance, Privacy & AML Auditors:** Evidence of consent capture, immutable audit trails, PII minimization, sanctions/PEP screening enforcement, and Central Bank regulatory compliance.

### 1.2 Scope

#### 1.2.1 In-Scope Capabilities
The `customer-service` is the sole source of truth for customer identity, contact information, regulatory consent, KYC tier validation, and lifecycle states. It is responsible for:
1. **Keycloak User Account Provisioning:** Providing a public registration endpoint (`/api/v1/auth/register`) creating Keycloak user principals via client-credentials admin integration without exposing elevated privileges.
2. **Customer Profile Management:** Ingesting authenticated profile registrations, enforcing configurable minimum age rules, issuing sequence-backed public customer numbers (`CUS-YYYY-NNNNNN`), and permitting allowlisted profile modifications.
3. **Contact Normalization & Challenge Verification:** Normalizing international phone numbers to E.164 standard using Google `libphonenumber`, generating 6-digit OTP verification challenges with 5-minute TTL, rate-limiting resend attempts, and managing verified primary contact replacement.
4. **Physical Address Management:** Managing residential and work addresses with primary designation tracking.
5. **Regulatory Consent & Policy Governance:** Tracking versioned acceptance and withdrawal of Terms & Conditions, Privacy Policy, and Marketing consents with full evidentiary metadata (channel, timestamp, client IP, user agent).
6. **Know Your Customer (KYC) Lifecycle & Document Protection:** Capturing tiered KYC metadata (Source of Funds, PEP status, Sanctions, Risk Rating), hashing document numbers (HMAC-SHA256), encrypting document numbers with AES-256-GCM, and enforcing dual-control staff review.
7. **Onboarding Completion & Activation Gate:** Dynamically evaluating onboarding prerequisite steps, providing administrative activation, and triggering downstream wallet provisioning via event publication.
8. **Administrative Limit Configuration:** Managing transaction velocity and volume limits per transaction type (DEPOSIT, WITHDRAWAL, TRANSFER, PAYMENT) with mandatory justification logging.
9. **Immutable Business Audit History (WORM):** Recording state changes in an append-only audit table protected by PostgreSQL triggers preventing updates, deletions, or truncations.
10. **Transactional Outbox Event Relay:** Persisting domain events atomically with business transactions for reliable at-least-once delivery to Apache Kafka.

#### 1.2.2 Explicit Scope Exclusions
To maintain strict bounded contexts, the following capabilities are explicitly **excluded** from `customer-service`:
- **Financial Balance & Stored-Value Accounting:** Ledgers, balances, debits, credits, and monetary transfers belong exclusively to `wallet-service`.
- **Payment Rail Integrations:** Direct M-Pesa, card network, or banking rail handshakes belong exclusively to `payment-service`.
- **End-User Authentication & Token Minting:** OIDC token issuance, refresh token rotation, and password credential authentication are owned by Keycloak.
- **Production Notification Transport:** Actual cellular SMS gateway dispatch and transactional SMTP relay are owned by `notification-service`. `customer-service` manages challenge state, hashing, and verification logic.
- **Binary Document Storage:** Image files and PDFs are stored in private object storage (e.g., S3/MinIO). `customer-service` stores only validated opaque URI references.

### 1.3 Definitions, Acronyms, and Abbreviations

| Term / Acronym | Definition |
| :--- | :--- |
| **AES-256-GCM** | Advanced Encryption Standard with Galois/Counter Mode providing authenticated symmetric encryption. |
| **AML / CFT** | Anti-Money Laundering / Countering the Financing of Terrorism. |
| **E.164** | International public telecommunication numbering plan standard formatting telephone numbers (e.g., `+254712345678`). |
| **HMAC** | Hash-based Message Authentication Code used for blind indexing of sensitive document numbers and OTP codes. |
| **KYC** | Know Your Customer — identity verification processes mandated by financial regulators. |
| **PEP** | Politically Exposed Person — individuals entrusted with prominent public functions requiring enhanced due diligence. |
| **PII** | Personally Identifiable Information. |
| **Sanctions Screening** | Verification against international terrorist and sanction watchlists (UN, OFAC, local regulators). |
| **WORM** | Write Once, Read Many — immutable data persistence model enforced by database-level triggers. |

### 1.4 References & Regulatory Frameworks
1. **Data Protection Act (2019) & GDPR Principles:** Lawful consent capture, purpose limitation, and PII protection.
2. **Central Bank of Kenya (CBK) National Payment System (NPS) Guidelines:** Customer identification, KYC tier thresholds, and anti-fraud controls.
3. **RFC 6749 / RFC 7519:** OAuth 2.0 Authorization Framework and JSON Web Token (JWT).
4. **RFC 7807:** Problem Details for HTTP APIs.
5. **NIST SP 800-63B:** Digital Identity Guidelines — Authentication and Lifecycle Management.

---

## 2. Overall Description

### 2.1 Product Perspective & System Ecosystem Architecture
The `customer-service` serves as the authoritative identity and compliance gateway of the platform.

```
                              ┌─────────────────────────────────────────┐
                              │            Client Application           │
                              │        (Mobile App / Web Portal)        │
                              └────────────────────┬────────────────────┘
                                                   │ HTTPS / Bearer JWT
                                                   ▼
                                      ┌─────────────────────────┐
                                      │       API Gateway       │
                                      └────────────┬────────────┘
                                                   │
                  ┌────────────────────────────────┼────────────────────────────────┐
                  │                                │                                │
                  ▼                                ▼                                ▼
       ┌─────────────────────┐          ┌─────────────────────┐          ┌─────────────────────┐
       │  Customer Service   │          │   Wallet Service    │          │   Payment Service   │
       │   (Port: 8081)      │          │    (Port: 8082)     │          │    (Port: 8083)     │
       └──────────┬──────────┘          └──────────┬──────────┘          └──────────┬──────────┘
                  │                                ▲                                │
                  │ customer.wallet.creation.      │ Synchronous KYC Eligibility    │
                  │ requested.v1                   │ (GET /customers/me)            │
                  ▼                                └────────────────────────────────┘
       ┌─────────────────────┐
       │    Apache Kafka     │
       │  Distributed Mesh   │
       └─────────────────────┘
```

- **Identity Foundation:** Integrates with Keycloak to register users and validate JWT tokens with audience `customer-service`.
- **Downstream Enabling:** Upon administrative activation, emits `customer.wallet.creation.requested.v1` to trigger initial account provisioning in `wallet-service`.
- **Synchronous Verification:** Answers real-time eligibility calls from `wallet-service` (`GET /api/v1/customers/me`) before funds can be transferred.

### 2.2 User Classes and System Actors

1. **Unregistered Public Visitor:** Can call `POST /api/v1/auth/register` to establish a Keycloak identity.
2. **End Customer (Authenticated User):** Authenticated via JWT (`sub` claim matching Keycloak user ID). Manages profile, contacts, addresses, consents, and submits KYC documents.
3. **Customer Support / Operations Staff (`CUSTOMER_ADMIN`):** Back-office compliance officer. Audits customer profiles, reviews KYC evidence, approves/rejects tiers, activates accounts, and configures transaction limits.
4. **Downstream Microservice Principal (`wallet-service`):** Authenticated service caller invoking `GET /api/v1/customers/me` to assert active status and wallet eligibility.

### 2.3 Operating Environment & Deployment Topology

| Component | Standard Specification |
| :--- | :--- |
| **Runtime Platform** | Linux container, OpenJDK 21 LTS |
| **Framework** | Spring Boot 3.3.x, Spring Cloud 2023.x |
| **Database Tier** | PostgreSQL 16+ with ACID compliant write-ahead logging |
| **Connection Pool** | HikariCP (`maximum-pool-size: 10`, `connection-timeout: 30000ms`) |
| **Message Broker** | Apache Kafka 3.6+ cluster (`acks: all`, `idempotence: true`) |
| **Identity Provider** | Keycloak 24+ (`mobile-money-wallet` realm) |

### 2.4 Assumptions and System Dependencies
1. **Keycloak Service Account:** Assumes Keycloak client `wallet-registration-service` is provisioned with `manage-users` role in `realm-management` to create customer accounts.
2. **Cryptographic Key Provisioning:** Assumes `CUSTOMER_DOCUMENT_ENCRYPTION_KEY`, `CUSTOMER_DOCUMENT_HASH_KEY`, and `CUSTOMER_VERIFICATION_HMAC_KEY` are provisioned as 32-byte Base64-encoded secrets from a secure vault.
3. **Database Sequence Integrity:** Assumes `customer_public_number_seq` generates non-colliding sequential numbers for public customer IDs (`CUS-YYYY-NNNNNN`).

---

## 3. Functional Requirements

---

### FR-01: Keycloak User Account Provisioning
- **Endpoint:** `POST /api/v1/auth/register`
- **Description:** Public endpoint allowing prospective customers to register a Keycloak account using client-credentials admin API.
- **Inputs:**
  ```json
  {
    "username": "jane.doe",
    "email": "jane@example.com",
    "password": "Valid-Password-123",
    "firstName": "Jane",
    "lastName": "Doe"
  }
  ```
- **Preconditions:**
  - `KEYCLOAK_REGISTRATION_CLIENT_SECRET` must be configured.
  - Password length must be between 12 and 128 characters.
- **Postconditions:**
  - Keycloak user created with `emailVerified = false`, `enabled = true`.
  - HTTP `201 Created` returning Keycloak UUID, username, and email.
- **Error Conditions:**
  - Secret unconfigured $\rightarrow$ `503 Service Unavailable`.
  - Duplicate username or email $\rightarrow$ `409 Conflict`.
  - Password policy violation $\rightarrow$ `400 Bad Request`.

---

### FR-02: Authenticated Customer Profile Registration
- **Endpoint:** `POST /api/v1/customers`
- **Description:** Creates the sovereign customer entity linked to the authenticated Keycloak user ID.
- **Inputs:**
  - Headers: `Authorization: Bearer <JWT>`
  - Request Body:
    ```json
    {
      "firstName": "Jane",
      "middleName": "Achieng",
      "lastName": "Doe",
      "dateOfBirth": "1995-05-12",
      "gender": "FEMALE",
      "nationality": "KE",
      "preferredLanguage": "en"
    }
    ```
- **Preconditions:**
  - Authenticated JWT contains `aud: customer-service`.
  - Customer record does not already exist for JWT `sub`.
  - Customer age $\ge$ `CUSTOMER_MINIMUM_AGE` (Default: 18 years evaluated in UTC).
- **Postconditions:**
  - Customer created with `customer_status = 'PENDING'`, `kyc_status = 'NOT_STARTED'`, `kyc_tier = 'TIER_0'`, `wallet_eligible = false`.
  - `customer_number` generated as `CUS-<YEAR>-<SEQUENCE>`.
  - WORM audit record created (`action = 'CUSTOMER_REGISTERED'`).
  - Outbox event written (`customer.registered.v1`).
- **Error Conditions:**
  - Underage $\rightarrow$ `400 Bad Request` with `errors.dateOfBirth`.
  - Duplicate profile $\rightarrow$ `409 Conflict`.
  - Invalid nationality code $\rightarrow$ `400 Bad Request`.

---

### FR-03: Authenticated Customer Profile Query
- **Endpoint:** `GET /api/v1/customers/me`
- **Description:** Retrieves the authenticated customer's own profile, KYC tier, customer status, and wallet eligibility.
- **Inputs:** `Authorization: Bearer <JWT>`
- **Outputs (`200 OK`):**
  ```json
  {
    "id": "d3b07384-d113-4944-9c8e-a9b0e149bc68",
    "customerNumber": "CUS-2026-000001",
    "firstName": "Jane",
    "middleName": "Achieng",
    "lastName": "Doe",
    "preferredName": "Jane",
    "dateOfBirth": "1995-05-12",
    "gender": "FEMALE",
    "nationality": "KE",
    "customerStatus": "ACTIVE",
    "kycStatus": "APPROVED",
    "kycTier": "TIER_1",
    "walletEligible": true,
    "preferredLanguage": "en",
    "createdAt": "2026-10-07T10:00:00Z",
    "updatedAt": "2026-10-07T12:00:00Z"
  }
  ```
- **Error Conditions:** Unregistered caller $\rightarrow$ `404 Not Found`.

---

### FR-04: Allowlisted Customer Profile Modification
- **Endpoint:** `PATCH /api/v1/customers/me`
- **Description:** Updates non-legal customer profile attributes (`preferredName`, `preferredLanguage`). Legal names, birth dates, nationality, and eligibility are immutable via this endpoint.
- **Inputs:**
  ```json
  {
    "preferredName": "Janie",
    "preferredLanguage": "sw"
  }
  ```
- **Preconditions:** Customer must exist.
- **Postconditions:** Attributes updated, audit record created logging changed fields.
- **Error Conditions:** Empty patch body $\rightarrow$ `400 Bad Request`.

---

### FR-05: Customer Contact Creation & E.164 Normalization
- **Endpoint:** `POST /api/v1/customers/me/contacts`
- **Description:** Adds an email or phone contact. Phone numbers are parsed and formatted into standard E.164 (`+254...`) using Google `libphonenumber`.
- **Inputs:**
  ```json
  {
    "contactType": "PHONE",
    "contactValue": "0712345678",
    "phoneRegion": "KE",
    "primary": true
  }
  ```
- **Postconditions:**
  - Phone normalized to `+254712345678`.
  - Contact saved with `is_verified = false`.
  - If marked `primary`, existing primary contact of same type is demoted.
- **Error Conditions:**
  - Invalid phone number format for region $\rightarrow$ `400 Bad Request`.
  - Duplicate contact value across system $\rightarrow$ `409 Conflict`.

---

### FR-06: Contact Verification Challenge Issuance
- **Endpoint:** `POST /api/v1/customers/me/contacts/{contactId}/verification-challenges`
- **Description:** Issues a single-use 6-digit OTP to verify an unverified contact.
- **Preconditions:**
  - Contact exists, belongs to caller, and `is_verified == false`.
  - Rate limits satisfied: $\ge 60\text{ seconds}$ cooldown between resends; $< 5\text{ challenges}$ per customer per hour.
- **Postconditions:**
  - Generates secure 6-digit random code.
  - Computes HMAC-SHA256 hash using `CUSTOMER_VERIFICATION_HMAC_KEY` over `challengeId:code`.
  - Saves challenge with 5-minute TTL (`expires_at = NOW() + 300s`).
  - Invalidates any prior active challenges for the contact.
  - Dispatches code via delivery adapter.
  - Returns challenge ID and `resendAfter` timestamp.
- **Error Conditions:**
  - Already verified $\rightarrow$ `409 Conflict`.
  - Rate limit exceeded $\rightarrow$ `429 Too Many Requests`.
  - Verification key unconfigured $\rightarrow$ `503 Service Unavailable`.

---

### FR-07: Contact Verification Challenge Confirmation
- **Endpoint:** `POST /api/v1/customers/me/contacts/{contactId}/verification-challenges/{challengeId}/confirmation`
- **Inputs:** `{"code": "123456"}`
- **Preconditions:** Challenge is active (`consumed_at == null`, `expires_at > NOW()`, `attempts < 5`).
- **Postconditions:**
  - Compares hash using constant-time `MessageDigest.isEqual()`.
  - Sets `contact.is_verified = true`, `verified_at = NOW()`, `verification_source = 'OTP'`.
  - Marks challenge as consumed.
  - Emits `customer.contact.verified.v1` outbox event.
- **Error Conditions:**
  - Expired, consumed, or mismatched challenge $\rightarrow$ `400 Bad Request`.
  - Wrong code $\rightarrow$ Increments attempts; locks on 5th attempt; returns `400 Bad Request`.

---

### FR-08: Protected Primary Contact Replacement
- **Description:** Business policy governing verified primary contacts.
- **Invariants:**
  - A verified primary contact cannot be deleted or set to non-primary unless another verified contact of the same type is promoted to primary.
  - Promoting a new verified contact to primary automatically demotes the prior primary contact and emits `customer.contact.primary.changed.v1`.

---

### FR-09: Customer Address Management
- **Endpoints:**
  - `POST /api/v1/customers/me/addresses`: Create address (HOME, WORK, OTHER).
  - `PUT /api/v1/customers/me/addresses/{id}`: Update address.
  - `GET /api/v1/customers/me/addresses`: List customer addresses with pagination.
- **Postconditions:** Manages addresses with single-primary enforcement per customer.

---

### FR-10 & FR-11: Consent Policy Governance & Acceptance
- **FR-10 Query Policies (`GET /api/v1/customers/me/consents/policies`):** Returns active policy versions (`CUSTOMER_TERMS_VERSION`, `CUSTOMER_PRIVACY_VERSION`, `CUSTOMER_MARKETING_VERSION`).
- **FR-11 Accept Consent (`POST /api/v1/customers/me/consents`):**
  - Inputs: `{"consentType": "TERMS_AND_CONDITIONS", "documentVersion": "v1"}`
  - Preconditions: Version matches active configured version; not already accepted.
  - Postconditions: Persists acceptance timestamp, client IP address (`INET`), user agent, and channel (`API`). Audited in WORM log.

---

### FR-12: Consent Withdrawal
- **Endpoint:** `POST /api/v1/customers/me/consents/{id}/withdrawal`
- **Description:** Records revocation of previously given consent.
- **Postconditions:**
  - Sets `withdrawn_at = NOW()`.
  - Original acceptance evidence is preserved immutably.
  - If mandatory consent (`TERMS_AND_CONDITIONS` or `PRIVACY_POLICY`) is withdrawn, sets `customer.wallet_eligible = false`.

---

### FR-13 & FR-14: KYC Profile & Document Encrypted Ingestion
- **FR-13 KYC Profile (`POST /api/v1/customers/me/kyc/profile`):** Captures Source of Funds, occupation, employer, and expected volume. Initial status is `NOT_STARTED`.
- **FR-14 KYC Document (`POST /api/v1/customers/me/kyc/documents`):**
  - Ingests document metadata (NATIONAL_ID, PASSPORT, DRIVING_LICENSE, RESIDENCE_PERMIT).
  - **Blind Index Hash:** Computes HMAC-SHA256 over `type:country:normalizedNumber` to enforce uniqueness across accounts without storing plaintext.
  - **Symmetric Encryption:** Encrypts document number using AES-256-GCM (`v1:nonce:ciphertext`).
  - Saves file references (`front_file_reference`, `back_file_reference`).

---

### FR-15: KYC Profile Submission
- **Endpoint:** `POST /api/v1/customers/me/kyc/profile/submission`
- **Description:** Customer finalizes KYC package and submits for compliance review.
- **Preconditions:**
  - At least one KYC document uploaded.
  - All uploaded documents must be unexpired (`expires_at > TODAY`).
- **Postconditions:**
  - Profile status transitions to `PENDING`.
  - Documents transition to `PENDING`.
  - Outbox event emitted (`customer.kyc.submitted.v1`).

---

### FR-16: Staff KYC Review & Sanctions Clearance
- **Endpoint:** `POST /api/v1/admin/customers/{customerId}/kyc/profile/review`
- **Authorization:** Requires `CUSTOMER_ADMIN` role. Caller cannot review their own customer profile.
- **Inputs:**
  ```json
  {
    "decision": "APPROVED", // or "REJECTED"
    "approvedTier": "TIER_1",
    "expiresAt": "2027-10-08T00:00:00Z",
    "pepStatus": "NOT_PEP",
    "sanctionsStatus": "CLEAR",
    "riskRating": "LOW"
  }
  ```
- **Postconditions:**
  - For `APPROVED`: Verifies all documents are verified and unexpired; sets `profile.status = 'APPROVED'`, `customer.kyc_tier = approvedTier`. Emits `customer.kyc.verified.v1`.
  - For `REJECTED`: Mandates `rejectionReason`; sets `profile.status = 'REJECTED'`, `customer.kyc_tier = 'TIER_0'`. Emits `customer.kyc.rejected.v1`.
  - Audit snapshot captures reviewer ID, decision, and document states.

---

### FR-17: Customer Onboarding Completion Check
- **Endpoint:** `GET /api/v1/customers/me/completion`
- **Description:** Evaluates customer readiness for account activation.
- **Evaluation Criteria:**
  1. Age $\ge 18$.
  2. Primary phone is verified (`is_verified == true`).
  3. Mandatory consents active (`TERMS_AND_CONDITIONS`, `PRIVACY_POLICY`).
  4. KYC Profile is `APPROVED`, unexpired, with verified, unexpired documents.
- **Outputs (`200 OK`):**
  ```json
  {
    "complete": true,
    "outstandingSteps": []
  }
  ```

---

### FR-18: Administrative Customer Activation
- **Endpoint:** `POST /api/v1/admin/customers/{customerId}/activation`
- **Authorization:** Requires `CUSTOMER_ADMIN`.
- **Preconditions:**
  - Customer status is `PENDING`.
  - `outstandingSteps` is completely empty.
- **Postconditions:**
  - `customer.customer_status = 'ACTIVE'`, `customer.wallet_eligible = true`.
  - Emits `customer.activated.v1`.
  - Emits `customer.wallet.creation.requested.v1` (Payload: `{"currency": "KES"}`).

---

### FR-19: Administrative Transaction Limit Management
- **Endpoints:**
  - `POST /api/v1/admin/customers/{customerId}/limits`
  - `PUT /api/v1/admin/customers/{customerId}/limits/{id}`
  - `DELETE /api/v1/admin/customers/{customerId}/limits/{id}` (Requires JSON `{"reason": "..."}`)
- **Description:** Sets custom velocity limits (per-transaction, daily, monthly, daily count) per transaction type (DEPOSIT, WITHDRAWAL, TRANSFER, PAYMENT).
- **Postconditions:** Every limit creation, update, and deletion is audited with actor and reason.

---

### FR-20: Append-Only Audit History
- **Endpoint:** `GET /api/v1/admin/customers/{customerId}/audit-records`
- **Authorization:** Requires `CUSTOMER_ADMIN`.
- **Description:** Returns immutable historical snapshots of customer state transitions, KYC decisions, and limit modifications.

---

### FR-21: Transactional Outbox Event Publishing
- **Component:** `WalletCreationOutboxPublisher`
- **Description:** Reliable poller executing every 1s dispatching `customer.wallet.creation.requested.v1` events to Kafka with backfill verification for existing active eligible customers.

---

## 4. Non-Functional Requirements (NFR)

### 4.1 Performance & Latency Budgets
- **`GET /api/v1/customers/me`:** $p50 < 15\text{ ms}$, $p95 < 40\text{ ms}$, $p99 < 80\text{ ms}$.
- **Profile Registration (`POST /customers`):** $p99 < 150\text{ ms}$.
- **Contact Verification Confirmation (`POST .../confirmation`):** $p99 < 80\text{ ms}$.
- **Database Transaction Latency:** Maximum transaction duration $\le 50\text{ ms}$.

### 4.2 Scalability & Concurrency Model
- Target capacity: **1,000 requests/second** at launch, scaling to **10,000 requests/second** with read replicas for `GET /customers/me`.
- Row-level pessimistic locking (`SELECT ... FOR UPDATE`) on Customer aggregate during state mutations prevents concurrent duplicate updates.

### 4.3 Availability & Fault Tolerance
- **Uptime SLA:** $99.99\%$ monthly availability ($\le 4.38\text{ minutes}$ unplanned downtime).
- **Keycloak Circuit Breaking:** If Keycloak admin client fails, registration fails fast with `503 Service Unavailable`.
- **Kafka Resilience:** If Kafka broker is down, the Transactional Outbox safely buffers events in PostgreSQL without failing customer HTTP requests.

### 4.4 Consistency Guarantees
- **Customer State, KYC & Limits:** Strict immediate consistency within PostgreSQL ACID boundaries.
- **Kafka Domain Events:** Eventual consistency with $< 1\text{ second}$ outbox relay latency.

### 4.5 Security Architecture & Cryptographic Controls
- **Stateless Bearer JWT Validation:** Validated against Keycloak JWKS public certs with audience `customer-service`.
- **Document Number Blind Index:** HMAC-SHA256 with 32-byte secret key prevents plaintext database storage while permitting fast unique lookups.
- **Document Number Encryption:** Authenticated AES-256-GCM encryption with 12-byte random nonce and 128-bit authentication tag.
- **OTP Protection:** 6-digit verification codes hashed with HMAC-SHA256; zero cleartext codes in database, logs, or events.

### 4.6 Auditability & Immutability (WORM)
- Table `customer_audit_record` is protected by database triggers rejecting all `UPDATE`, `DELETE`, and `TRUNCATE` operations.
- Historical evidence survives entity deletions (target ID has no cascade foreign key).

### 4.7 Disaster Recovery & Business Continuity
- **RPO:** $\text{RPO} = 0$ for committed customer records via synchronous PostgreSQL Write-Ahead Log replication.
- **RTO:** $\text{RTO} < 15\text{ minutes}$ for complete service restoration.

---

## 5. External Interface Requirements

### 5.1 RESTful API Contracts
Published in OpenAPI 3.0 format at `/swagger-ui/index.html` and `/v3/api-docs`.

### 5.2 Event Streaming Interface (Apache Kafka)

#### Produced Events

| Topic Name | Event Type | Partition Key | Description |
| :--- | :--- | :--- | :--- |
| `customer.registered.v1` | `customer.registered.v1` | `customerId` | Emitted when customer profile is initialized. |
| `customer.contact.verified.v1` | `customer.contact.verified.v1` | `customerId` | Emitted when phone/email OTP is confirmed. |
| `customer.contact.primary.changed.v1` | `customer.contact.primary.changed.v1` | `customerId` | Emitted when primary contact changes. |
| `customer.kyc.submitted.v1` | `customer.kyc.submitted.v1` | `customerId` | Emitted when customer submits KYC package. |
| `customer.kyc.verified.v1` | `customer.kyc.verified.v1` | `customerId` | Emitted upon staff KYC approval. |
| `customer.kyc.rejected.v1` | `customer.kyc.rejected.v1` | `customerId` | Emitted upon staff KYC rejection. |
| `customer.activated.v1` | `customer.activated.v1` | `customerId` | Emitted when customer transitions to ACTIVE. |
| `customer.wallet.creation.requested.v1`| `customer.wallet.creation.requested.v1` | `customerId` | Triggers wallet creation in `wallet-service`. |

---

## 6. Data Requirements

### 6.1 Logical Data Model & ERD

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
  └───────────┬─────────────────────────────────┬───────────┘
              │ 1                               │ 1
              │                                 │
              │ *                               │ 1
  ┌───────────▼─────────────┐       ┌───────────▼─────────────┐
  │    CUSTOMER_CONTACT     │       │       KYC_PROFILE       │
  ├─────────────────────────┤       ├─────────────────────────┤
  │ id : UUID (PK)          │       │ id : UUID (PK)          │
  │ customer_id : UUID (FK) │       │ customer_id : UUID (FK) │
  │ contact_type : VARCHAR  │       │ status : VARCHAR        │
  │ contact_value : VARCHAR │       │ requested_tier : VARCHAR│
  │ is_verified : BOOLEAN   │       │ approved_tier : VARCHAR │
  │ is_primary : BOOLEAN    │       │ pep_status : VARCHAR    │
  └───────────┬─────────────┘       │ sanctions_status : VAR  │
              │ 1                   └───────────┬─────────────┘
              │                                 │ 1
              │ *                               │ *
  ┌───────────▼─────────────┐       ┌───────────▼─────────────┐
  │CONTACT_VERIFICATION_CHAL│       │      KYC_DOCUMENT       │
  ├─────────────────────────┤       ├─────────────────────────┤
  │ id : UUID (PK)          │       │ id : UUID (PK)          │
  │ customer_id : UUID (FK) │       │ kyc_profile_id : UUID   │
  │ contact_id : UUID       │       │ doc_number_hash : UQ    │
  │ code_hash : VARCHAR(64) │       │ doc_number_encrypted    │
  │ expires_at : TIMESTAMPTZ│       │ verification_status     │
  │ attempts : INTEGER      │       └─────────────────────────┘
  └─────────────────────────┘
```

### 6.2 Data Retention Policies
- **`customer` & `customer_audit_record`:** 10 Years following account closure (AML/CFT regulatory mandate).
- **`kyc_document` metadata:** 10 Years following KYC profile supersession.
- **`contact_verification_challenge`:** 90 Days post-expiry, then purged via automated cleanup job.
- **`outbox_event`:** Published events purged after 30 days.

---

## 7. System Constraints

### 7.1 Architectural & Technology Constraints
- **Language / Framework:** Java 21 LTS with Spring Boot 3.3.
- **PII Encryption Mandate:** Raw document numbers must never be persisted in cleartext. Plaintext document numbers in log files constitute a Tier-1 security violation.
- **Sequence Generator:** `CUS-YYYY-NNNNNN` must be generated using `customer_public_number_seq` to eliminate concurrency contention.

### 7.2 Regulatory Constraints
- **Data Sovereignty:** All personal identifiable data must reside within national database instances.
- **Separation of Duties:** Staff members are prohibited from reviewing their own KYC submissions or activating their own customer profiles.

---

## 8. Acceptance Criteria (BDD Given-When-Then Scenarios)

### AC-01: Customer Profile Registration with Minimum Age Check (FR-02)
```gherkin
Scenario: Customer of legal age successfully registers profile
  Given Keycloak user "U-123" exists and is authenticated
    And Customer has birth date "2000-01-01" (age >= 18)
  When Customer sends POST /api/v1/customers with valid profile details
  Then the response status is 201 Created
    And the customer status is "PENDING"
    And customer number matches pattern "CUS-\d{4}-\d{6}"
    And a WORM audit record is created for "CUSTOMER_REGISTERED"
```

### AC-02: Rejection of Underage Customer Registration (FR-02)
```gherkin
Scenario: Underage customer attempts registration
  Given Keycloak user "U-999" is authenticated
    And Customer provides birth date indicating age 17
  When Customer sends POST /api/v1/customers
  Then the response status is 400 Bad Request
    And the error response contains "errors.dateOfBirth"
    And no customer record is saved in the database
```

### AC-03: Contact OTP Challenge and Verification (FR-06, FR-07)
```gherkin
Scenario: Customer successfully verifies phone contact via OTP
  Given Customer "C-100" has unverified phone "+254712345678"
  When Customer requests verification challenge
  Then a challenge is created with 5-minute expiry
  When Customer confirms challenge with valid 6-digit code
  Then the response status is 200 OK
    And the contact "is_verified" is true
    And verification source is "OTP"
    And an outbox event "customer.contact.verified.v1" is persisted
```

### AC-04: KYC Submission and Staff Approval (FR-15, FR-16)
```gherkin
Scenario: Compliance officer approves submitted KYC profile
  Given Customer "C-100" has submitted KYC profile with verified national ID
    And Reviewer holds authority "CUSTOMER_ADMIN"
  When Reviewer approves KYC profile with tier "TIER_1", PEP "NOT_PEP", and Sanctions "CLEAR"
  Then the profile status becomes "APPROVED"
    And the customer KYC tier becomes "TIER_1"
    And an outbox event "customer.kyc.verified.v1" is persisted
```

### AC-05: Customer Activation Triggers Wallet Creation (FR-18)
```gherkin
Scenario: Admin activates fully onboarded customer
  Given Customer "C-100" has satisfied all onboarding steps
    And Customer status is "PENDING"
    And Caller holds authority "CUSTOMER_ADMIN"
  When Caller posts to /api/v1/admin/customers/C-100/activation
  Then customer status becomes "ACTIVE"
    And customer "wallet_eligible" becomes true
    And an outbox event "customer.wallet.creation.requested.v1" is persisted
```

---

## 9. Appendices

### 9.1 Comprehensive Domain Glossary
- **Blind Index:** A deterministic hash of a sensitive value allowing indexed exact-match lookups without decrypting or exposing the underlying plaintext.
- **E.164:** Telecommunications standard defining international phone number formatting.
- **WORM Trigger:** Database trigger enforcing Write-Once-Read-Many constraints on financial and compliance audit tables.

### 9.2 Open Questions & Architectural Risks

| Risk ID | Description | Severity | Mitigation Plan |
| :--- | :--- | :--- | :--- |
| **CUS-R01** | Production Notification Service integration replacing local delivery adapters. | High | Implement production Kafka/REST Notification Service client adapter in next sprint. |
| **CUS-R02** | Master document encryption key rotation. | Critical | Implement key version prefixing (`v1:`, `v2:`) in `DocumentProtection` supporting multi-key decryption during annual rotations. |
| **CUS-R03** | Automated Sanctions screening provider integration. | Medium | Integrate automated Dow Jones / ComplyAdvantage REST webhooks prior to production launch. |
