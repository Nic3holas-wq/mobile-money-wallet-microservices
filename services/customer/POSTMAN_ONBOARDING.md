# Onboarding, verification and consent in Postman

All requests use a customer access token with audience `customer-service`.
The customer UUID comes from the token subject; no user ID is accepted in request bodies.
These endpoints use `/api/v1`. They do not activate a customer or create a wallet.

## Start local verification delivery

Run MailDev from the project root:

```bash
docker compose -f services/docker-compose.yml up -d mail-dev
```

In the customer application's IntelliJ run configuration, set:

```text
SPRING_PROFILES_ACTIVE=local
CUSTOMER_VERIFICATION_HMAC_KEY=<base64-encoded 32-byte key>
```

Generate that key once using `openssl rand -base64 32` and retain it across restarts.
It is separate from the KYC encryption/hash keys. Do not commit it. Changing it
invalidates pending codes. Missing or malformed keys return 503 when verification
is requested. The local delivery adapter is disabled outside the `local` profile;
a production Notification Service adapter is still required for real SMS/email delivery.

Email codes arrive in MailDev at `http://localhost:1081` (SMTP port 1026).
Phone codes go to `.local/customer-sms/{challengeId}.txt`, relative to the service
process working directory. With Maven launched from `services/customer`, the path
is `services/customer/.local/customer-sms/{challengeId}.txt`. The directory is private
and files are owner-readable/writable. `CUSTOMER_SMS_TEST_INBOX` overrides the path.
This is a local SMS simulator, not delivery to a phone. Remove expired test files
when finished. Codes never appear in API responses, ordinary logs or domain events.

## Register and update a profile

```http
POST http://localhost:8081/api/v1/customers
Content-Type: application/json
```

```json
{"firstName":"Jane","lastName":"Doe","dateOfBirth":"1995-05-12","nationality":"KE","preferredLanguage":"en"}
```

Registration enforces `CUSTOMER_MINIMUM_AGE` (development default 18), using UTC.
A customer exactly that age is accepted. Invalid age returns 400 with
`errors.dateOfBirth`. New public numbers use `CUS-2026-000001` format with a
concurrency-safe, non-resetting sequence; gaps are expected. Existing numbers stay unchanged.
Registration remains PENDING and writes an audit record and `customer.registered.v1`
outbox event atomically.

```http
PATCH http://localhost:8081/api/v1/customers/me
Content-Type: application/json
```

```json
{"preferredName":"Jane","preferredLanguage":"sw"}
```

Only these two fields can be patched. Missing/null means unchanged; an empty
preferred name clears it. Empty language and an empty patch return 400.
Legal names, date of birth, nationality, status, ownership and eligibility cannot
be changed through this endpoint. Unknown/protected patch fields return 400.
Changed field names are audited without copying personal values into audit summaries.

## Create and verify a contact

```http
POST http://localhost:8081/api/v1/customers/me/contacts
Content-Type: application/json
```

```json
{"contactType":"PHONE","contactValue":"0712345678","phoneRegion":"KE","primary":true}
```

`phoneRegion` supplies explicit country context for national numbers. International
numbers can omit it. Phones are validated and normalized to E.164 (for this example,
`+254712345678`) using [Google libphonenumber](https://github.com/google/libphonenumber).
Equivalent spellings are checked against the same globally unique contact value.
Email example: `{"contactType":"EMAIL","contactValue":"jane@example.com","primary":true}`.

Use the contact ID returned by creation:

```http
POST http://localhost:8081/api/v1/customers/me/contacts/{contactId}/verification-challenges
```

Returns 201 with a Location header and:

```json
{"id":"<challengeId>","expiresAt":"<UTC timestamp>","resendAfter":"<UTC timestamp>","status":"PENDING"}
```

Read the code from MailDev or the local SMS test inbox, then send:

```http
POST http://localhost:8081/api/v1/customers/me/contacts/{contactId}/verification-challenges/{challengeId}/confirmation
Content-Type: application/json
```

```json
{"code":"123456"}
```

Replace the example with the delivered six-digit code. Success returns 200 with
`verified: true`, `verifiedAt` and `verificationSource: OTP`.
GET the challenge's Location to see PENDING, EXPIRED or CLOSED status.

Verification rules:

- Codes expire after five minutes and can be used only once.
- At most five wrong code attempts; attempts persist even when confirmation returns 400.
- Resend has a 60-second cooldown; at most five issued challenges per customer per hour.
- Resending invalidates older codes. Changing an unverified contact invalidates its codes.
- Challenges are bound to the customer, contact and normalized value. Other customers get 404.
- Deleting a contact does not remove the challenge history used for account rate limiting.
- Missing delivery configuration/failure returns 503 and rolls back challenge creation.
- Local delivery uses bounded SMTP timeouts. Delivery occurs before transaction completion;
  an unusually late database failure can leave a delivered but unusable code. Request another
  challenge after a failed request; durable notification delivery belongs to the integration phase.

## Replace a verified primary contact

1. Create the replacement with `primary: false`.
2. Verify it using the challenge flow above.
3. PUT the replacement's full contact body with `primary: true`.
4. The old contact becomes secondary and remains verified. It can then be deleted.

Verified contact values/types cannot be edited directly. A verified primary cannot
be unset or deleted until a verified replacement has been selected. An unverified
replacement cannot demote a verified primary. Primary changes generate
`customer.contact.primary.changed.v1`; successful verification generates
`customer.contact.verified.v1`. Events contain identifiers/type/status, not contact values or codes.

## Policies, acceptance and withdrawal

```http
GET http://localhost:8081/api/v1/customers/me/consents/policies
```

The active catalog contains terms, privacy and optional marketing. Development
versions default to `v1`; configure `CUSTOMER_TERMS_VERSION`,
`CUSTOMER_PRIVACY_VERSION` and `CUSTOMER_MARKETING_VERSION` to match your published
policy documents. This catalog identifies active versions; it does not supply legal
text or publish legal documents. Existing historical consent is not rewritten when
configuration changes. Only acceptance of the currently configured version satisfies
current policy checks.

Accept terms and privacy separately:

```http
POST http://localhost:8081/api/v1/customers/me/consents
Content-Type: application/json
```

```json
{"consentType":"TERMS_AND_CONDITIONS","documentVersion":"v1"}
```

Repeat with `PRIVACY_POLICY`. `MARKETING` is optional. Unknown/obsolete versions
return field-level 400 errors. Repeating an active acceptance returns 409.
Acceptance records server time, channel `API`, remote IP and available user agent.
Older consent records have channel `UNKNOWN`; no historical channel is fabricated.

```http
GET http://localhost:8081/api/v1/customers/me/consents/current
POST http://localhost:8081/api/v1/customers/me/consents/{consentId}/withdrawal
```

Withdrawal preserves original acceptance evidence and is idempotent. Marketing
withdrawal does not affect onboarding. Withdrawing terms/privacy clears stored
wallet eligibility and makes mandatory-consent checks fail. It does not close or
suspend an account; those lifecycle workflows remain separate. Acceptance and
withdrawal create persistent audit records.

## Check onboarding completion

```http
GET http://localhost:8081/api/v1/customers/me/completion
```

Example:

```json
{"complete":false,"outstandingSteps":["VERIFY_PRIMARY_PHONE","ACCEPT_REQUIRED_POLICIES","COMPLETE_KYC"]}
```

Minimum age is also rechecked, including for customers registered before this
policy existed. A valid KYC profile must be APPROVED, unexpired, and have verified,
unexpired evidence. Marketing is not a mandatory step. `complete: true` describes
onboarding completion only: lifecycle restrictions, activation and wallet eligibility
are handled by the planned lifecycle policy.
