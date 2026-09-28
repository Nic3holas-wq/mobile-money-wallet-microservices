# Customer service functional gap assessment

Reviewed: 25 September 2026.

Baseline: [Customer Microservice Requirements Specification v1.0](Customer_Microservice_Requirements_Specification.docx), functional requirements FR-01–FR-73. Compared with the current controllers, services, DTOs, enums, security configuration and outbox implementation in `services/customer/src/main`, and the service README. This is a source review, not a new runtime acceptance test. “Implemented” describes existing behavior; production acceptance still requires requirement-specific tests.

## Existing foundation

- FR-01–03, FR-05–07, FR-09: authenticated registration, JWT subject ownership, unique registration, pending initial state, basic profile capture and field validation, and own-profile reads.
- FR-15–16, FR-18–19: contact storage, primary selection per type, duplicate protection and verification-state fields. Verification itself is not implemented; primary contacts are optional today.
- FR-24: primary address selection.
- FR-26–27, FR-31–34: KYC profile/document metadata, submission, edit restrictions while submitted, staff approval/rejection and rejection reasons.
- FR-49–50: effective dates and admin-only limit mutations.
- FR-53, FR-55–56: typed/versioned consent acceptance, separate marketing consent and withdrawal preserving acceptance evidence.
- FR-63: current KYC outbox payloads omit document numbers and unnecessary PII.
- FR-65–67, FR-69: JWT validation, role/ownership checks and 401/403 responses. Role separation still needs expansion as described below.

## Missing or partially implemented requirements

| Requirements | Current evidence and gap | Planned work |
|---|---|---|
| FR-04 | `CustomerService` generates `CUS-<UUID>`; unique, but does not provide the short human-readable numbering illustrated in the spec. | Agree public format; use a concurrency-safe database sequence and preserve existing identifiers. |
| FR-08 | Registration only requires a past birth date. | Configurable minimum age, date-boundary tests and field-level errors. |
| FR-10–14 | Only register/get-own profile operations exist. No staff profile lookup, edits, change history or completion response. Sensitive fields cannot currently be edited, but there is no controlled change workflow. | Staff lookup by ID/number; allowlisted profile updates; reviewed changes for protected fields; audit and outstanding-step response. |
| FR-17 | Phones must already match an international-style regex; mapper trims them. | Country-aware E.164 parsing/normalization with explicit country context and canonical duplicate checks. |
| FR-20–21 | Updating a verified contact clears its verification; deletion is allowed. No approved replacement workflow or primary-change event. | Verification challenges, verified replacement before promotion, controlled deletion and transactional change events. |
| FR-22–23, FR-25 | Address CRUD exists but deletion is permanent and updates overwrite data. Types are HOME/WORK/OTHER; no explicit POSTAL type. | Deactivation, address revision history and agreed residential/postal type mapping. |
| FR-28–29 | Document types are fixed enums; no explicit alien-ID type. Opaque file references are stored without checking storage ownership or validity. | Configured allowed document types and document-storage reference validation; keep binaries outside the database. |
| FR-30 | Profile status is APPROVED, whereas the spec calls it VERIFIED; EXPIRED exists but no expiry transition runs. | Resolve the public status contract with a compatible API/data migration; implement expiry behavior. |
| FR-35 | Profile reviewer/time and document provider/time are recorded. Individual document decisions do not persist reviewer identity; rejected documents have no decision timestamp. | Immutable profile/document decision history with actor, method and decision time for both outcomes. |
| FR-36–39 | Expiry is checked during submission/review only. Three KYC events are stored but never published. Activation is unavailable. Resubmission clears previous review fields. | Expiry processing and eligibility rechecks, expiry event, activation gate and preserved submission/decision history. |
| FR-40–45 | Lifecycle enum exists without BLOCKED. Registration remains PENDING; no transition APIs, reasons, restoration, closure checks or lifecycle events. | Explicit transition policy, activation, suspension/blocking, eligible restoration and externally checked closure, with audit/events. |
| FR-46–48 | Admins maintain individual limit rows; no policy-derived tier assignment or applicable-limit resolution for service callers. Transaction types lack wallet balance. | Configured tier policies, deterministic effective-limit lookup, narrowly authorized internal API and wallet-balance cap representation. Payment execution stays outside this service. |
| FR-51–52 | Limit request contains a reason and creation stores actor. Updates overwrite records; deletion has no reason/history; no change events. | Append-only assignment history, actor/reason for every change including removal, and limit-change events. |
| FR-54, FR-57–58 | Consent stores caller-supplied version, acceptance time, IP and user agent. No explicit channel, published-version validation, current-state projection or mandatory-consent gate. | Policy/version catalog, channel evidence, current consent endpoint and activation/restricted-action checks. Define effects of mandatory-consent withdrawal. |
| FR-59–62, FR-64 | KYC changes write PENDING outbox rows atomically. No Kafka publisher/retries; other domains produce no events. Event names have v1 suffixes, but no published schema contracts. Correlation ID is randomly generated per event, disconnected from HTTP request ID. | Shared event envelope/contracts, request correlation, missing event producers and multi-instance-safe outbox relay with retry and stable event IDs. Delivery is at least once; consumers deduplicate. |
| FR-68 | One customer-admin realm role authorizes all current staff operations. Status workflows and dedicated support/compliance/service permissions are absent. | Define action-to-role matrix and narrowly scoped service access; introduce distinct roles where the agreed policy requires them. Suggested role names in the spec are guidance, not an existing contract. |
| FR-70–73 | No administrative customer search. Existing pagination applies to child resources. | Indexed approved-identifier search, bounded pagination, allowlisted sorting, role-based masking and controls against ordinary search becoming unrestricted export. |

## Delivery order

All new HTTP routes remain under `/api/v1`. Routes below are proposals, not implemented endpoints. Each phase includes new Flyway migrations where needed, authorization/ownership tests, concurrent/retry scenarios and updated Postman examples. Do not edit already-applied V1 to introduce new behavior.

### 1. Audit and shared business-change infrastructure

Add immutable business audit records and KYC submission/decision history. Capture actor, target, action, timestamp, request correlation and a minimal approved change summary. Generalize outbox recording beyond KYC and define versioned envelopes with stable event IDs. Apply both to existing KYC and limit changes.

Acceptance: rejection followed by resubmission retains the original decision; failed transactions leave neither state changes nor audit/outbox records; concurrent changes preserve invariants. SLF4J/AOP operational logs do not replace persistent business audit records.

### 2. Complete onboarding, contacts and consent

Implement age policy, customer numbering, allowlisted profile edits, profile completion, phone normalization, verification and protected contact replacement. Add consent-policy validation and current-state evaluation. Preserve/deactivate address records and agree address types.

Proposed routes: `PATCH /api/v1/customers/me`, `GET /api/v1/customers/me/completion`, and contact verification challenge/confirmation resources beneath `/api/v1/customers/me/contacts/{id}`. Notification Service delivers messages; Customer Service owns challenge state and verification decisions.

Acceptance: expired/reused codes fail; attempts and resend rates are bounded; another customer cannot confirm a challenge; the old verified contact remains in use until replacement succeeds; primary changes write events; configured age and consent versions are enforced.

### 3. Reliable Kafka delivery

Publish pending outbox events asynchronously with safe work claiming, bounded backoff, acknowledgement handling, recovery of abandoned claims and stable event IDs. Use customer ID as the partition key and define per-customer ordering. Add missing registration/contact events and schema contract tests.

Acceptance: Kafka outage preserves committed changes; delivery resumes on recovery; crash after publish/before marking completion can duplicate delivery but not the logical event ID; multiple service instances do not silently lose events. Test with Kafka and PostgreSQL.

### 4. Activation, lifecycle and KYC expiry

Create one eligibility evaluator for profile completeness, verified primary contact, mandatory consent, valid KYC and lifecycle restrictions. Expose explicit failure reasons. Implement permitted activation/suspension/blocking/restoration transitions with actor/reason/audit/events. Add repeat-safe KYC expiry processing and check freshness when answering eligibility requests. Validate stored document references through the storage boundary.

Proposed routes: `GET /api/v1/customers/me/eligibility` and authorized lifecycle action resources beneath `/api/v1/admin/customers/{customerId}`. Customer eligibility enables a downstream wallet workflow; Customer Service does not create balances or ledger entries.

Acceptance: KYC approval alone does not activate an incomplete customer; suspended/blocked/expired customers are ineligible; state changes and their events are atomic; repeated expiry processing produces one logical transition. Closure is completed only after an authenticated external check confirms wallet/other required obligations, with a contract that prevents stale approvals.

### 5. Effective limits and staff operations

Implement policy-based tier assignments, nonambiguous effective windows, wallet-balance limits and authorized service reads of applicable limits. Add staff customer lookup/search, role separation and masking. Changes record immutable reasons/history and events.

Acceptance: exact effective-date boundaries and overlaps have deterministic behavior; staff roles cannot exceed their assigned actions; search is indexed, bounded and masked; internal clients cannot read arbitrary customer data outside their permissions.

## Policy decisions to settle before dependent implementation

- Minimum age and applicable date/timezone; do not assume a legal threshold from the examples.
- Public customer-number format and treatment of existing CUS-UUID values.
- Whether to migrate APPROVED to VERIFIED or amend the specification, retaining compatibility for clients and stored events.
- Mandatory activation fields, required consent versions, KYC tiers and expiry consequences; distinguish activation from ongoing wallet eligibility.
- Verification delivery integration, challenge expiry/retry policy and approved contact-replacement evidence.
- Allowed document types and storage ownership/validation contract.
- Staff permission matrix, limit policy values and trusted wallet/closure-check contracts.

These decisions do not block documenting the plan or building generic audit/outbox infrastructure. No new business behavior is implemented by this assessment.

## Phase 1 implementation update — 25 September 2026

Implemented append-only audit records for KYC submission, profile/document decisions
and limit mutations; submission snapshots preserve prior document decisions.
Added bounded staff-only `GET /api/v1/admin/customers/{customerId}/audit-records`.
Limit deletion now requires a JSON reason. New KYC and limit outbox records use a
shared versioned envelope and request/transaction correlation. A new Flyway V2
migration protects audit rows from ordinary UPDATE, DELETE and TRUNCATE.

This completes the initial review/limit-history slice of phase 1. Draft-edit auditing,
full retained evidence versions, other business-domain auditing and automatic audit
of sensitive reads remain follow-up work. Earlier overwritten history is not recoverable.
The assessment table above records the pre-implementation baseline; phases 2–5,
including Kafka delivery and activation, remain planned.

## Onboarding/contact/consent implementation update — 25 September 2026

Added configurable minimum age, sequence-backed public numbers, preferred-name/language
PATCH and completion reporting. Added E.164 parsing with explicit national-number
region, expiring single-use contact challenges, bounded attempts/resends, protected
verified-contact replacement and contact events. Added active consent-version catalog,
current-state projection, API channel evidence, audited acceptance/withdrawal and
mandatory-consent evaluation. Flyway V3 supplies schema changes.

Local verification uses MailDev for email and a private file inbox for SMS simulation;
production Notification Service delivery is not implemented. Policy defaults are
development assumptions pending product confirmation. Legal policy text is not provided
by this service. Profile completion does not activate customers. Address deactivation/
history was outside the requested onboarding/contact/consent slice and remains planned.
See `services/customer/POSTMAN_ONBOARDING.md` for exact routes and setup.
