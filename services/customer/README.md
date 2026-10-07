# Customer service

Requires Java 25, PostgreSQL (`customer_db` on localhost:5436 by default), and
Keycloak's `mobile-money-wallet` realm for real access tokens. Start with
`./mvnw spring-boot:run`; the default HTTP port is 8081. Flyway applies migrations
and Hibernate validates the schema at startup.

## Create a login account

`POST /api/v1/auth/register` creates a Keycloak account and does not require a
bearer token. Example request:

```json
{
  "username": "jane.doe",
  "email": "jane@example.com",
  "password": "A-long-password-123",
  "firstName": "Jane",
  "lastName": "Doe"
}
```

Passwords must be 12–128 characters. A successful request returns `201` with
the Keycloak user ID. Duplicate username/email returns `409`. The endpoint does
not accept roles or privileges from the caller. After account creation, obtain
an access token through the normal Keycloak login flow, then register the
customer profile below with that token.

The service uses the `wallet-registration-service` Keycloak client through
client credentials. In the `mobile-money-wallet` realm, create a confidential
client with **Service accounts roles** enabled. Assign its service account the
`manage-users` client role from `realm-management`. Configure its client ID and
secret on the customer service with `KEYCLOAK_REGISTRATION_CLIENT_ID` and
`KEYCLOAK_REGISTRATION_CLIENT_SECRET`; the base URL and realm can be set with
`KEYCLOAK_BASE_URL` and `KEYCLOAK_REALM`. Keep the secret in an untracked `.env`
or secret manager. Registration returns `503` until the secret is configured.

## Register the authenticated customer profile

API documentation is available at `http://localhost:8081/swagger-ui/index.html`
after starting the service. The OpenAPI JSON is at `/v3/api-docs`.
Click **Authorize** and paste your Keycloak access token (without the `Bearer `
prefix) to use **Try it out**. The token must include the `customer-service`
audience; admin operations also require the customer admin authority.
Documentation is publicly readable; API operations retain their authorization rules.

`POST /api/v1/customers` with `Authorization: Bearer <access-token>` and
`Content-Type: application/json`:

```json
{
  "firstName": "Jane",
  "lastName": "Doe",
  "dateOfBirth": "1995-05-12",
  "nationality": "KE",
  "preferredLanguage": "en"
}
```

`middleName` and `gender` are optional. Nationality must be two uppercase letters.
The Keycloak user ID comes exclusively from the token's UUID `sub` claim.
New customer numbers use `CUS-<year>-<sequence>`; existing numbers are retained. New customers have status
`PENDING`, KYC status `NOT_STARTED`, tier `TIER_0`, and wallet eligibility `false`.
Identity, status, and eligibility fields supplied by callers are not used.

Returns `201 Created` with the customer response and `Location: /api/v1/customers/me`.
Duplicate registration returns `409`; invalid input returns `400`.

## Retrieve the authenticated customer

`GET /api/v1/customers/me` with the same bearer token returns `200`, or `404` if that
user has not registered. Both endpoints require a valid token (`401` otherwise).
There is no endpoint for fetching another user's customer by ID. Other routes
are denied by the security configuration.

## Verification

Run `./mvnw test` with PostgreSQL available. Tests use the configured database and
roll back customer records after each test. Flyway migrations can still be
applied during test startup; set `CUSTOMER_DB_NAME` to a separate test database
when desired. API tests simulate authenticated JWTs and mock the decoder; a
real Keycloak login/token exchange should also be tested through Postman.

Contact/address and KYC workflows are described below. Contact verification is available through the local delivery adapter described below.
Kafka outbox publishing is not yet implemented.

## Token audience

Access tokens must include `customer-service` in `aud`, in addition to passing
signature, issuer, and expiry checks. Override the expected audience using
`CUSTOMER_JWT_AUDIENCE` if needed. The audience identifies this API, not the
Postman client requesting the token.

For Keycloak 24, select the `mobile-money-wallet` realm, then:

1. Open **Clients → your Postman client → Client scopes → its dedicated scope**.
2. Under **Mappers**, choose **Configure a new mapper** (or **Add mapper → By configuration**).
3. Select **Audience**. Name it `customer-service-audience`.
4. Leave **Included Client Audience** blank and set **Included Custom Audience**
   to `customer-service`.
5. Enable **Add to access token**; leave **Add to ID token** off. Save.
6. Obtain a new access token in Postman and select **Use Token**.

Previously issued tokens without this audience will receive `401` after the
service restarts. No user accounts or customer records need to be recreated.

Reference: https://www.keycloak.org/docs/latest/server_admin/#_audience

## Error responses

MVC and security failures return `application/problem+json` with `type`, `title`,
`status`, `detail`, and `instance`. Validation errors additionally include an
`errors` object keyed by field. Malformed JSON receives `400`; missing or invalid
tokens receive `401` with a `WWW-Authenticate: Bearer` challenge; forbidden
resources receive `403`. Internal exception details are not returned to clients.
Signed-token tests use a temporary local JWKS server and verify audience, issuer,
expiry, and signature rejection without contacting Keycloak.

## Logging

`LoggingAspect` uses SLF4J via Lombok `@Slf4j` and Spring AOP. New Spring beans
in the controller, service, mapper and repository packages receive the same
logging automatically.

| Coverage | Level and content |
|---|---|
| Controllers | INFO completion, status and duration; WARN client rejections; ERROR failures |
| Services and workflow helpers | DEBUG start, completion/failure and duration |
| Repositories | DEBUG for custom queries and inherited methods such as save/count/flush |
| Mappers | DEBUG start, completion/failure and duration |
| Document protection and JWT role conversion | DEBUG operation metadata only |
| Authentication/access denial | WARN 401/403 events |
| Every synchronous HTTP request | Final status and elapsed time; INFO, WARN or ERROR according to status |

`RequestLoggingFilter` supplies a generated `X-Request-ID` response header and
SLF4J MDC `requestId`. The console pattern includes it on logs produced during
the request. Existing MDC state is restored when the request completes, even on
exceptions. Client-supplied request IDs are not trusted. The HTTP summary also
covers validation, parsing and authentication errors before controller execution.

To see detailed service/repository/mapper/security logs, set this environment
variable in the customer service run configuration and restart:

```text
CUSTOMER_LOG_LEVEL=DEBUG
```

INFO remains the default to avoid verbose logs during normal operation. Example:

```text
INFO [requestId:...] operation=CustomerController.getCurrent(..) event=completed status=200 durationMs=12
DEBUG [requestId:...] layer=repository operation=CrudRepository.count() event=completed durationMs=2
```

Application logging does not record method arguments, return bodies, tokens,
headers, customer identifiers, encryption keys or exception messages/stack traces.
HTTP summaries use route templates rather than raw URLs or query strings;
requests rejected before route resolution show `route=UNMATCHED`. Framework and
SQL logging are configured independently and may have different behavior.

Spring AOP covers calls through Spring proxies, not entities/DTO getters,
constructors, static/private/final methods or calls within the same bean. Framework
startup logging remains provided by Spring Boot. Request-ID propagation into
future async jobs or Kafka consumers requires separate instrumentation.

## Address and contact APIs (v1)

Both resources require a bearer token and an existing customer profile:

| Method | Address path | Contact path | Success |
|---|---|---|---|
| POST | `/api/v1/customers/me/addresses` | `/api/v1/customers/me/contacts` | 201 + Location |
| GET | `/api/v1/customers/me/addresses` | `/api/v1/customers/me/contacts` | 200, paginated |
| GET | `/api/v1/customers/me/addresses/{id}` | `/api/v1/customers/me/contacts/{id}` | 200 |
| PUT | `/api/v1/customers/me/addresses/{id}` | `/api/v1/customers/me/contacts/{id}` | 200 |
| DELETE | `/api/v1/customers/me/addresses/{id}` | `/api/v1/customers/me/contacts/{id}` | 204 |

POST and PUT use the same complete request shape. PUT replaces editable fields;
missing optional fields become null. Ownership and verification fields cannot
be assigned by the caller. Unknown fields are ignored, as in registration.
Requests for another customer's record return 404, including updates/deletes.
Missing authentication returns 401; malformed or invalid input returns 400.

Address example:

```json
{
  "addressType": "HOME",
  "countryCode": "KE",
  "county": "Nairobi",
  "cityOrTown": "Nairobi",
  "postalCode": "00100",
  "addressLine1": "12 Example Road",
  "addressLine2": null,
  "primary": true
}
```

Address types are HOME, WORK and OTHER. Country code requires two uppercase
letters. Postal code and addressLine2 are optional; other fields are required.

Contact example:

```json
{
  "contactType": "PHONE",
  "contactValue": "+254712345678",
  "primary": true
}
```

Contact types are PHONE and EMAIL. Phone values are parsed and normalized to E.164; national-format input requires
an explicit two-letter `phoneRegion` such as `KE`. Email values are validated, trimmed and stored in
lowercase. Phone metadata is validated with libphonenumber. Type/value combinations are globally unique
as specified by the ERD; duplicates return 409 without identifying the owner.
New contacts are unverified. Verified values/types cannot be edited directly; create
and verify a replacement. Changing an unverified value invalidates pending codes.
See the verification guide below for phone/email challenges.

Primary policy: at most one primary address per customer, and one primary contact
per contact type. Setting primary=true demotes the previous primary in the same
transaction. Writes lock the parent customer row to serialize concurrent primary
changes. For unverified contacts, setting primary=false or deletion may leave no primary.
A verified primary contact requires a verified replacement before removal.
Other entries are not automatically promoted. No wallet eligibility updates are
made by these APIs yet.

Lists accept zero-based `page` (default 0) and `size` (default 20, maximum 100),
ordered by createdAt then ID, and return:

```json
{"content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0}
```

The existing Flyway schema supports these endpoints; no new migration is required.

## Remaining entity APIs (v1)

All customer routes below start with `/api/v1/customers/me`. The identity is
always taken from the authenticated token. All list endpoints use the same
bounded `page`/`size` response as addresses and contacts.

| Resource | Operations |
|---|---|
| `/consents` | POST acceptance; GET paginated history |
| `/consents/{id}` | GET own record |
| `/consents/{id}/withdrawal` | POST withdrawal; repeated withdrawal is idempotent |
| `/limits` and `/limits/{id}` | GET own limits (including historical/future records) |
| `/kyc-profile` | POST draft, GET, PUT editable draft |
| `/kyc-profile/submission` | POST submit for review |
| `/kyc-profile/documents` | POST document metadata, GET paginated list |
| `/kyc-profile/documents/{id}` | GET, PUT, DELETE while profile is editable |

Consent POST body:

```json
{"consentType":"TERMS_AND_CONDITIONS","documentVersion":"v1"}
```

Acceptance time, IP address and user agent are recorded by the service. The
original acceptance remains immutable; withdrawal records `withdrawnAt`.
Consent history has no PUT/DELETE. Duplicate active acceptance of the same
consent type/version returns 409. The supplied version must match the configured active policy catalog. Current state
is exposed separately; legal document text is hosted outside this service.

KYC profile POST/PUT body:

```json
{
  "requestedTier":"TIER_1",
  "occupation":"Engineer",
  "employerName":"Example employer",
  "sourceOfFunds":"SALARY",
  "expectedMonthlyVolume":10000
}
```

KYC document POST/PUT body:

```json
{
  "documentType":"NATIONAL_ID",
  "documentNumber":"EXAMPLE-12345",
  "issuingCountry":"KE",
  "issuedAt":"2020-01-01",
  "expiresAt":"2030-01-01",
  "frontFileReference":"opaque-file-reference",
  "backFileReference":null
}
```

File references are stored as metadata; this API does not upload, download, or
verify ownership of files in a storage service. Document numbers are normalized
by trimming and uppercasing, encrypted with AES-256-GCM, and matched for duplicates
using HMAC-SHA256 over document type, issuing country and normalized number.
Responses never expose document numbers, hashes or ciphertext.

### Document protection setup

Set two separate, stable, base64-encoded 32-byte keys in the customer service's
runtime environment:

```bash
export CUSTOMER_DOCUMENT_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export CUSTOMER_DOCUMENT_HASH_KEY="$(openssl rand -base64 32)"
```

Generate these once, retain them securely, and supply the same values on later
runs. For IntelliJ, configure them in the application's run configuration.
Do not commit them. Document creation/update returns 503 if the keys are missing
or malformed. Do not replace keys on each restart: existing data and duplicate
checks depend on them. Key rotation requires a separate migration strategy.

### Staff endpoints and Keycloak role

Staff routes start with `/api/v1/admin/customers/{customerId}`. `customerId` is
the UUID from the customer API, not the Keycloak user UUID.

| Resource | Operations |
|---|---|
| `/limits` | POST, GET paginated list |
| `/limits/{id}` | GET, PUT, DELETE |
| `/kyc-profile` | GET |
| `/kyc-profile/review` | POST approve or reject submitted KYC |
| `/kyc-profile/documents` | GET paginated list |
| `/kyc-profile/documents/{id}/review` | POST verify or reject a document |
| `/outbox-events` and `/outbox-events/{id}` | GET metadata only |
| `/activation` | POST activate a customer who completed onboarding |

Default staff role: Keycloak **realm role** `customer-admin`. Create that role
in `mobile-money-wallet`, assign it to a separate staff user, and obtain a fresh
access token whose `realm_access.roles` includes it. The token must still have
audience `customer-service`. Override the role name with `CUSTOMER_ADMIN_ROLE`.
The service maps this realm role to internal authority `CUSTOMER_ADMIN`; an
OAuth scope of the same name does not grant staff privileges. Method security
also protects staff service operations. Staff cannot review their own KYC.

Limit POST/PUT example:

```json
{
  "transactionType":"TRANSFER",
  "currency":"KES",
  "perTransactionLimit":1000,
  "dailyLimit":5000,
  "monthlyLimit":20000,
  "dailyCountLimit":5,
  "effectiveFrom":"2026-01-01T00:00:00Z",
  "effectiveUntil":null,
  "reason":"Approved limits"
}
```

Amounts must be nonnegative, with per-transaction <= daily <= monthly. The end
must follow the start. The original creating staff ID is derived from the token.
These endpoints maintain limit records; payment enforcement and resolution of
overlapping effective windows are not implemented here.

Document review example:

```json
{"decision":"VERIFIED","verificationProvider":"manual-review","providerReference":"review-123"}
```

Use `REJECTED` with a nonblank `failureReason` to reject a document. Verification
is a staff assertion; no external KYC provider is called.

Profile review example:

```json
{
  "decision":"APPROVED",
  "approvedTier":"TIER_1",
  "pepStatus":"NOT_PEP",
  "sanctionsStatus":"CLEAR",
  "riskRating":"LOW",
  "expiresAt":"2030-01-01T00:00:00Z"
}
```

Initial workflow policy: draft (`NOT_STARTED`) or `REJECTED` profiles are editable.
Submission requires at least one unexpired document, sets KYC to `PENDING`, and
resets document reviews. Approval requires all documents verified/unexpired,
a tier above TIER_0 no higher than requested, future profile expiry, NOT_PEP,
CLEAR sanctions status, and LOW/MEDIUM risk. Rejection requires a nonblank
`rejectionReason`. Screening rules are initial application policy, not automatic
screening or a complete compliance process. No expiry scheduler is implemented.

Reviews update the customer's KYC status/tier and create an outbox event in the
same transaction. Approval does not activate the customer or enable a wallet;
staff activate separately with `POST /activation` (no body).

Activation applies the same checks as `GET /api/v1/customers/me/completion`:
minimum age, verified primary phone, accepted mandatory policies, and approved,
unexpired KYC with all documents verified. If any step is outstanding it returns
409 listing the steps, e.g. `Customer is not ready for activation: VERIFY_PRIMARY_PHONE`.
Only `PENDING` customers can be activated, and staff cannot activate themselves.
Success sets `customerStatus` to `ACTIVE` and `walletEligible` to true, and records a
`CUSTOMER_ACTIVATED` audit entry and a `customer.activated.v1` outbox event.
KYC resubmission, KYC review and mandatory consent withdrawal reset
`walletEligible` to false; they do not change `customerStatus`.
Outbox records remain PENDING: Kafka publishing/retries are a separate feature.
There are no public endpoints for arbitrary outbox creation, mutation or deletion.

Subsequent phases add Flyway V2 and V3 migrations. Integration tests cover these routes;
unit tests cover encryption, duplicate hashes, missing keys and staff-role mapping.

## Business audit and event history (phase 1)

Flyway `V2__add_customer_audit_history.sql` adds append-only audit history.
KYC submissions, profile decisions, document decisions, and limit create/update/delete
operations now persist actor, target, action, timestamp, correlation ID and selected
before/after values in the same transaction as the business change. Database triggers
reject updates, deletes and truncation of audit records; privileged database owners
can still alter those protections. No API exposes audit mutations.

KYC submission snapshots retain document review metadata before it is reset.
Profile/document decisions remain in history after rejection, resubmission or later
editable-document deletion. Document rejection has an actor and decision timestamp
in history even though its current `verifiedAt` remains null. Profile decisions record
`STAFF_REVIEW`; document decisions record the supplied verification provider.
History starts when V2 and this application version are deployed: earlier overwritten
decisions cannot be reconstructed. Draft profile/document edits are not individually
audited in this phase. Snapshots omit document numbers, hashes, ciphertext, file
references and occupation/employer details. Reasons and provider references are
restricted staff evidence: do not enter secrets or document numbers into free text.
Absent snapshot fields represent null/unset values; scalar snapshot values are strings.

Using a `customer-admin` token:

```http
GET /api/v1/admin/customers/{customerId}/audit-records?page=0&size=20
```

Returns the standard paginated response, ordered by occurrence time and ID. Size is
bounded to 100. Each entry includes `actorId`, `action`, `targetType`, `targetId`,
`occurredAt`, `correlationId`, `beforeState` and `afterState`. The actor comes from
the authenticated JWT; HTTP audit records and events share the response `X-Request-ID`.
For non-HTTP operations, a transaction-scoped correlation ID is generated.

**Limit deletion now requires a JSON body** so the removal reason is retained:

```http
DELETE /api/v1/admin/customers/{customerId}/limits/{id}
Content-Type: application/json

{"reason":"Replaced by the revised limit policy"}
```

Missing/blank reasons or reasons over 255 characters return 400. Limit updates retain
the updating staff identity in history independently of the original creating actor.
Deleted limit rows remain represented by their historical snapshots.

New outbox payloads use the [version 1 envelope](../../../documentation/customer-ms/events/customer-event-v1.schema.json):
`eventId`, `eventType`, `schemaVersion`, `occurredAt`, `customerId`, `correlationId`
and `data`. KYC event names are unchanged. Limit mutations now produce
`customer.limit.changed.v1` with limit ID and action; reasons and review evidence
are excluded from event payloads. Event IDs remain stable in the stored row.
Existing outbox rows are not rewritten and may still have the earlier flat payload;
the future relay must account for this legacy format. Kafka delivery/retries remain
pending phase 3; events are still stored as PENDING.

## Onboarding, contact verification and consent (phase 2)

Flyway V3 adds preferred names, public-number sequencing, consent channel evidence
and contact verification challenges. Read the [Postman setup and complete examples](POSTMAN_ONBOARDING.md)
for local delivery configuration, profile PATCH, protected contact replacement,
current consent checks and onboarding completion. Defaults are development policy
values (minimum age 18, UTC, terms/privacy version v1), configurable before deployment.

New routes include `/api/v1/customers/me/completion`,
`/api/v1/customers/me/contacts/{contactId}/verification-challenges`,
`/api/v1/customers/me/consents/policies` and `/api/v1/customers/me/consents/current`.
Profile PATCH allows only preferred name/language. Existing audit history now also
covers registration, profile updates, contact changes/verification and consent changes.
Contact OTPs never appear in audit records or event payloads.

The default delivery adapter works only with the `local` Spring profile: MailDev
for email, private local files for simulated SMS. Real notification delivery,
Kafka publication, lifecycle activation and externally enforced eligibility remain
separate integration work. This phase does not change address history behavior.
