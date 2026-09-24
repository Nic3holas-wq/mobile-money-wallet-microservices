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

Contact/address management, KYC workflows, and outbox publishing are not yet
implemented by these endpoints.

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
