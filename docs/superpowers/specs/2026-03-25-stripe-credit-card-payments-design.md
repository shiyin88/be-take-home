# Stripe Credit Card Payments — Design Spec

**Date:** 2026-03-25
**Status:** Approved

---

## Overview

Add the ability for tenants to pay rent charges using a saved credit card via Stripe. The platform currently supports manual/offline payments (cash, check). This feature adds Stripe as an online payment method using the SetupIntent + PaymentIntent pattern.

---

## Approach

Use Stripe's recommended SetupIntent flow to securely collect and store cards without raw card numbers touching our server. Payments are made against saved PaymentMethods via PaymentIntents confirmed synchronously. No webhooks — payment status is determined from the synchronous Stripe API response.

---

## Dependencies

Add to `build.gradle.kts`:
```kotlin
implementation("com.stripe:stripe-java:25.+")
```

---

## Data Model

### New Tables (Flyway migrations V4, V5, V6)

#### `stripe_customers` (V4)
Links a tenant to their Stripe Customer object. One Stripe Customer per tenant.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | |
| tenant_id | BIGINT FK NOT NULL UNIQUE | references `tenants(id)` |
| stripe_customer_id | VARCHAR(255) NOT NULL | e.g. `cus_xxx` |
| created_at | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP | |

#### `saved_cards` (V4)
One row per saved card per tenant.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | |
| tenant_id | BIGINT FK NOT NULL | references `tenants(id)` |
| stripe_payment_method_id | VARCHAR(255) NOT NULL UNIQUE | e.g. `pm_xxx` |
| last4 | VARCHAR(4) NOT NULL | for display only |
| brand | VARCHAR(50) NOT NULL | visa, mastercard, amex, etc. |
| exp_month | INT NOT NULL | |
| exp_year | INT NOT NULL | |
| created_at | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:** `INDEX idx_saved_cards_tenant_id (tenant_id)`

#### `credit_card_payments` (V4)
One row per Stripe payment attempt.

| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | |
| rent_charge_id | BIGINT FK NOT NULL | references `rent_charges(id)` |
| saved_card_id | BIGINT FK NOT NULL | references `saved_cards(id)` |
| stripe_payment_intent_id | VARCHAR(255) NOT NULL UNIQUE | e.g. `pi_xxx` |
| amount | DECIMAL(10,2) NOT NULL | copied from `rent_charges.amount` at payment time |
| status | VARCHAR(20) NOT NULL | INITIATED / SUCCEEDED / FAILED / REFUNDED |
| failure_reason | TEXT NULL | Stripe decline message on failure |
| created_at | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | |

**Indexes:** `INDEX idx_ccp_rent_charge_id (rent_charge_id)`

### Payment Status Lifecycle

```
INITIATED → SUCCEEDED
          → FAILED
SUCCEEDED → REFUNDED
```

- `INITIATED` — PaymentIntent created, Stripe call in progress
- `SUCCEEDED` — Stripe confirmed the charge succeeded
- `FAILED` — Stripe returned a failure (declined, insufficient funds, etc.)
- `REFUNDED` — Payment was refunded via the refund endpoint

### No Changes to Existing Tables

The existing `payments` table (offline payments) and `rent_charges` table schema are unchanged. The `rent_charges.status` field (PENDING/PAID/OVERDUE) is updated as a side effect of payment success/refund.

---

## API Design

### Card Management — Tenant only

| Method | Endpoint | Query Params | Description |
|---|---|---|---|
| `POST` | `/api/cards/setup-intent` | — | Create a Stripe SetupIntent; returns `clientSecret` for frontend |
| `POST` | `/api/cards` | — | Save a confirmed card by PaymentMethod ID |
| `GET` | `/api/cards` | `startAfterId`, `limit` | List tenant's saved cards (cursor-paginated) |
| `DELETE` | `/api/cards/{id}` | — | Remove a saved card |

### Credit Card Payments

| Method | Endpoint | Auth | Query Params | Description |
|---|---|---|---|---|
| `POST` | `/api/credit-card-payments` | Tenant only | — | Pay a rent charge with a saved card |
| `GET` | `/api/credit-card-payments/{id}` | Tenant (own) or PM | — | Get payment by ID |
| `GET` | `/api/credit-card-payments?rentChargeId={id}` | Tenant (own) or PM | `startAfterId`, `limit` | List payments for a charge (paginated, ordered by id ASC) |
| `POST` | `/api/credit-card-payments/{id}/refund` | PM only | — | Refund a succeeded payment |

**Tenant ownership** for read endpoints: payment → rent_charge → lease → `lease.tenant_id == principal.tenantId`. A tenant may not access payments on another tenant's charges.

### Example Requests and Responses

**`POST /api/cards/setup-intent`**
```json
// Response 200
{
  "clientSecret": "seti_xxx_secret_yyy"
}
```

**`POST /api/cards`**

Request DTO has `@field:NotBlank` on `stripePaymentMethodId`; controller uses `@Valid`.
```json
// Request
{ "stripePaymentMethodId": "pm_xxx" }

// Response 201
{
  "id": 1,
  "last4": "4242",
  "brand": "visa",
  "expMonth": 12,
  "expYear": 2027,
  "createdAt": "2026-03-25T10:00:00Z"
}
```

**`GET /api/cards?startAfterId=0&limit=20`**
```json
// Response 200
{
  "content": [
    { "id": 1, "last4": "4242", "brand": "visa", "expMonth": 12, "expYear": 2027, "createdAt": "2026-03-25T10:00:00Z" }
  ],
  "hasMore": false
}
```

**`POST /api/credit-card-payments`**

Request DTO has `@field:NotNull` on both fields; controller uses `@Valid`.
```json
// Request
{ "rentChargeId": 5, "savedCardId": 2 }

// Response 201 (success)
{
  "id": 1,
  "rentChargeId": 5,
  "savedCardId": 2,
  "stripePaymentIntentId": "pi_xxx",
  "amount": "1500.00",
  "status": "SUCCEEDED",
  "failureReason": null,
  "createdAt": "2026-03-25T10:00:00Z",
  "updatedAt": "2026-03-25T10:00:00Z"
}

// Response 201 (card declined)
{
  "id": 2,
  "status": "FAILED",
  "failureReason": "Your card was declined.",
  ...
}
```

**`GET /api/credit-card-payments?rentChargeId=5&startAfterId=0&limit=20`**
```json
// Response 200
{
  "content": [ { "id": 1, "status": "SUCCEEDED", ... } ],
  "hasMore": false
}
```

**`DELETE /api/cards/{id}`**
```
Response: 204 No Content on success
          409 Conflict if an INITIATED payment references the card
          404 if card not found or belongs to a different tenant
```

**`GET /api/credit-card-payments/{id}`**
```json
// Response 200 — same shape as POST /api/credit-card-payments response body
{
  "id": 1,
  "rentChargeId": 5,
  "savedCardId": 2,
  "stripePaymentIntentId": "pi_xxx",
  "amount": "1500.00",
  "status": "SUCCEEDED",
  "failureReason": null,
  "createdAt": "2026-03-25T10:00:00Z",
  "updatedAt": "2026-03-25T10:00:00Z"
}
```

**`POST /api/credit-card-payments/{id}/refund`**
```json
// Response 200
{
  "id": 1,
  "status": "REFUNDED",
  "rentChargeId": 5,
  "amount": "1500.00",
  "createdAt": "2026-03-25T10:00:00Z",
  "updatedAt": "2026-03-25T10:01:00Z"
}
```

---

## Business Logic

### Card Setup Flow

1. Tenant calls `POST /api/cards/setup-intent`
2. Server creates a Stripe Customer for the tenant if one doesn't exist (stored in `stripe_customers`)
3. Server creates a Stripe SetupIntent attached to the Customer, returns `clientSecret`
4. Frontend uses Stripe.js to collect card details and confirm the SetupIntent (card data never touches our server)
5. Tenant calls `POST /api/cards` with the resulting `stripePaymentMethodId`
6. Server calls Stripe to retrieve PaymentMethod details (last4, brand, expiry), attaches it to the Customer
7. Server saves row to `saved_cards`

### Card Deletion

1. Verify the card belongs to the authenticated tenant
2. Check no `INITIATED` payment exists referencing this card — if one does, return 409 Conflict (payment in flight)
3. Detach PaymentMethod from the Stripe Customer via Stripe API
4. Delete row from `saved_cards`

### Payment Flow

1. Tenant calls `POST /api/credit-card-payments` with `rentChargeId` + `savedCardId`
2. Validate:
   - Rent charge exists and ownership chain resolves to the authenticated tenant (`rent_charge → lease → tenant_id == principal.tenantId`)
   - Rent charge status is `PENDING` or `OVERDUE` (both are payable; PAID charges are rejected with 409)
   - Saved card belongs to the authenticated tenant
   - No existing `INITIATED` or `SUCCEEDED` credit card payment for this rent charge — checked via `SELECT ... FOR UPDATE` on the `rent_charges` row to prevent race conditions under concurrent requests
3. Insert `credit_card_payments` row with status `INITIATED`; amount is taken from `rent_charges.amount` (not client input)
4. Call Stripe `PaymentIntents.create()` with `confirm=true`, amount (in cents), currency, PaymentMethod ID, Customer ID
5. On Stripe success → update payment status to `SUCCEEDED`, update `rent_charges.status` to `PAID` (in one transaction)
6. On Stripe card error (`StripeException`) → update payment status to `FAILED`, store `failure_reason`; rent charge remains unchanged. Return 201 with `status: FAILED` (the payment record was created; the decline is not an API error)
7. On Stripe service error (5xx, network) → update payment status to `FAILED`, store `failure_reason`; return 502 Bad Gateway

### Refund Flow

1. PM calls `POST /api/credit-card-payments/{id}/refund`
2. Validate payment exists and status is `SUCCEEDED`
3. Call Stripe `Refunds.create()` with the `stripe_payment_intent_id`
4. Update payment status to `REFUNDED`, update `rent_charges.status` back to `PENDING` (in one transaction)

### Key Constraints

- A rent charge may only have one `INITIATED` or `SUCCEEDED` credit card payment at a time; enforced at application layer with `SELECT ... FOR UPDATE`
- Only the tenant who owns the lease may pay with a card
- Only PMs may issue refunds
- Card deletion is blocked when an `INITIATED` payment references the card

---

## Architecture

### New Modules

- **`StripeClient`** (interface + implementation) — wraps Stripe SDK; mockable in unit tests
- **`CardModule`** — card management business logic (setup intent, save, list, delete)
- **`CardDataAccess`** — jOOQ queries for `stripe_customers` and `saved_cards`
- **`CreditCardPaymentModule`** — payment and refund business logic
- **`CreditCardPaymentDataAccess`** — jOOQ queries for `credit_card_payments`
- **`CardApi`** — REST controller for `/api/cards/**`
- **`CreditCardPaymentApi`** — REST controller for `/api/credit-card-payments/**`

### Error Handling

Add `PaymentFailedException` (maps to 502 Bad Gateway) for Stripe service-level failures. Card declines are **not** exceptions — they produce a `FAILED` payment record and a 201 response.

### Configuration

New `application.yml` properties:
```yaml
stripe:
  api-key: ${STRIPE_API_KEY}
  currency: usd
```

`STRIPE_API_KEY` must be set in the environment (test mode: `sk_test_xxx`).

---

## Testing

### Unit Tests (MockK)

- **`StripeCardModuleTest`** — card setup, save, delete; `StripeClient` mocked
- **`CreditCardPaymentModuleTest`** — payment success path, payment failure (card decline), Stripe service error, double-charge prevention, refund logic
- **`CardApiTest`** — auth rules (tenant only), `@Valid` request validation
- **`CreditCardPaymentApiTest`** — tenant can't pay another tenant's charge, PM can't pay, PM can refund

### Integration Tests (real Stripe test-mode)

Extends existing `IntegrationTestBase`. Requires `STRIPE_API_KEY` environment variable. Tests are skipped via `@EnabledIfEnvironmentVariable(named = "STRIPE_API_KEY", matches = ".+")` when the key is absent, so CI passes without credentials.

- **`CreditCardPaymentIntegrationTest`**
  - Full flow: setup intent → save card → pay charge → verify `SUCCEEDED` + rent charge `PAID`
  - Decline path: use Stripe test PaymentMethod `pm_card_visa_chargeDeclined` → verify `FAILED`, rent charge unchanged
  - Refund path: `SUCCEEDED` payment → refund → verify `REFUNDED` + rent charge back to `PENDING`
  - Double-charge prevention: second payment on same charge returns 409

---

## Assumptions and Tradeoffs

- **No webhooks** — payment status is determined synchronously from the Stripe API response. This is sufficient for most card types in test mode. In production, webhooks would be needed for async card processing (3DS, delayed settlement).
- **No 3DS handling** — `PaymentIntents.create(confirm=true)` will fail for cards requiring 3DS authentication. Acceptable for take-home scope; production would need `return_url` and redirect handling.
- **Synchronous payment** — no background job for payment processing; the HTTP request waits for Stripe response. Acceptable at this scale; high-volume production systems might queue payment jobs.
- **Separate tables** — offline and credit card payments remain separate. This keeps the existing payment flow untouched and gives credit card payments room to grow without retrofitting.
- **One Stripe Customer per tenant** — lazy-created on first setup intent call.
- **OVERDUE charges are payable** — tenants can pay both PENDING and OVERDUE charges. PAID charges reject with 409.
- **Card decline = 201 not 4xx** — a declined card produces a `FAILED` payment record; it is a valid business outcome, not an API error. Stripe service failures return 502.
- **Amount from server** — the charge amount is always taken from `rent_charges.amount`, never from client input, to prevent billing manipulation.
