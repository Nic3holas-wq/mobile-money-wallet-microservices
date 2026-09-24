# Customer service

Requires Java 25, PostgreSQL (`customer_db` on localhost:5436 by default), and
Keycloak's `mobile-money-wallet` realm for real access tokens. Start with
`./mvnw spring-boot:run`; the default HTTP port is 8081. Flyway applies migrations
and Hibernate validates the schema at startup.

## Register the authenticated customer

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
Customer numbers are generated as `CUS-<UUID>`. New customers have status
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

Contact/address management is described below. KYC workflows, contact verification
and outbox publishing are not yet implemented.

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

`LoggingAspect` uses Lombok `@Slf4j` (SLF4J) and Spring AOP:

- Public controller methods: INFO completion with operation, status and duration.
- Public service methods: DEBUG start/completion/failure and duration, including
  transaction completion. Enable with `CUSTOMER_LOG_LEVEL=DEBUG`.
- Controller exceptions: WARN for explicit 4xx responses, ERROR otherwise;
  original exceptions are rethrown for the error handler to translate.
- Security handler calls: WARN for authentication rejection (401) and access
  denial (403), including requests that never reach a controller.

Example:

```text
operation=CustomerController.getCurrent(..) event=completed status=200 durationMs=12
```

The aspect does not log method arguments, response bodies, headers, customer IDs,
JWTs, exception messages or stack traces. Framework logging is configured
separately. AOP logs proxied method calls, not a complete HTTP access log: request
binding/validation failures before controller invocation have no controller
execution log. Calls within the same bean bypass Spring's proxy.


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

Contact types are PHONE and EMAIL. Phone values require a `+` followed by 2–15
digits with no leading zero. Email values are validated, trimmed and stored in
lowercase. Phone values are trimmed. Type/value combinations are globally unique
as specified by the ERD; duplicates return 409 without identifying the owner.
New contacts are unverified. Changing the normalized value or type clears
verification, timestamp and source; changing only primary preserves verification.
No OTP/email verification endpoint is implemented yet.

Primary policy: at most one primary address per customer, and one primary contact
per contact type. Setting primary=true demotes the previous primary in the same
transaction. Writes lock the parent customer row to serialize concurrent primary
changes. Setting primary=false or deleting the primary may leave no primary;
other entries are not automatically promoted. No wallet eligibility updates are
made by these APIs yet.

Lists accept zero-based `page` (default 0) and `size` (default 20, maximum 100),
ordered by createdAt then ID, and return:

```json
{"content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0}
```

The existing Flyway schema supports these endpoints; no new migration is required.
