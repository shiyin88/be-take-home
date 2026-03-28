# Property Management Platform — Backend Take-Home

A simplified property management API built with Kotlin + Spring Boot. This is a starter repository for the backend engineering take-home exercise.

## What's Included

This repo contains a working property management platform with:

- **Entities**: Property managers, properties, units, tenants, leases, rent charges, and manual (offline) payments
- **REST API**: Full CRUD for all entities with pagination and role-based access control
- **Authentication**: JWT-based auth with login endpoint, two roles (TENANT, PROPERTY_MANAGER)
- **Database**: MySQL 8 with Flyway migrations and seed data
- **S3 Integration**: File storage service wired to LocalStack S3
- **SQS Background Worker**: Polling-based job processor with an example job (rent charge generation)
- **Tests**: Unit tests with MockK, controller tests with MockMvc + JWT auth

## Prerequisites

- JDK 17+
- Docker and Docker Compose

## Quick Start

```bash
# Start infrastructure (MySQL + LocalStack)
docker-compose up -d

# Run the API server
./gradlew bootRun

# Run the background worker (in a separate terminal)
./gradlew bootRun --args='--worker.enabled=true'

# Run unit tests
./gradlew test

# Run integration tests (Docker required — spins up ElasticMQ via Testcontainers)
./gradlew integrationTest

# Run both
./gradlew test integrationTest
```

The API starts on `http://localhost:8080`.

## Authentication

All API endpoints (except `/api/auth/**`) require a valid JWT token in the `Authorization` header.

### Login

```bash
# Login as property manager
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "admin@greenfieldproperties.com", "password": "password"}'

# Login as tenant
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "alice.johnson@email.com", "password": "password"}'
```

Response:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "userId": 1,
  "email": "admin@greenfieldproperties.com",
  "role": "PROPERTY_MANAGER"
}
```

### Using the Token

```bash
curl http://localhost:8080/api/leases \
  -H 'Authorization: Bearer <token>'
```

### Seeded Users

| Email | Password | Role |
|-------|----------|------|
| admin@greenfieldproperties.com | password | PROPERTY_MANAGER |
| alice.johnson@email.com | password | TENANT |
| bob.smith@email.com | password | TENANT |
| carol.williams@email.com | password | TENANT |

### Role-Based Access

- **Property managers** can manage properties, units, tenants, leases, and record manual payments
- **Tenants** can view their own leases and rent charges
- All authenticated users can access lease and rent charge read endpoints

## Pagination

All list endpoints use cursor-based pagination with `startAfterId` and `limit` query parameters. This avoids the performance and consistency problems of offset-based pagination.

```bash
# First page (default limit: 20)
curl http://localhost:8080/api/tenants -H 'Authorization: Bearer <token>'

# Next page — pass the last item's ID as startAfterId
curl 'http://localhost:8080/api/tenants?startAfterId=20&limit=10' \
  -H 'Authorization: Bearer <token>'
```

Response format:
```json
{
  "content": [...],
  "hasMore": true
}
```

## API Endpoints

### Authentication
- `POST   /api/auth/login` — Authenticate and receive JWT token

### Tenants (PM only for list/create/update)
- `GET    /api/tenants` — List all tenants (paginated)
- `GET    /api/tenants/{id}` — Get tenant by ID
- `POST   /api/tenants` — Create tenant
- `PUT    /api/tenants/{id}` — Update tenant

### Properties & Units (PM only)
- `GET    /api/properties` — List all properties (paginated)
- `GET    /api/properties/{id}` — Get property by ID
- `POST   /api/properties` — Create property
- `GET    /api/properties/{id}/units` — List units for a property (paginated)
- `POST   /api/properties/{id}/units` — Create unit

### Leases
- `GET    /api/leases` — List leases (tenants see only their own, PMs see all; paginated)
- `GET    /api/leases/{id}` — Get lease by ID
- `GET    /api/leases?tenantId={id}` — Get leases by tenant (PM only; paginated)
- `POST   /api/leases` — Create lease (PM only)

### Rent Charges
- `GET    /api/rent-charges/{id}` — Get rent charge by ID
- `GET    /api/rent-charges?leaseId={id}` — Get charges by lease (paginated)
- `GET    /api/rent-charges?leaseId={id}&status=PENDING` — Filter by status (paginated)

### Manual Payments (PM only)
- `GET    /api/manual-payments?rentChargeId={id}` — Get payments for a charge (paginated)
- `POST   /api/manual-payments` — Record a manual payment

## Architecture

```
src/main/kotlin/com/ender/takehome/
├── config/          # Security, JWT, AWS, Jackson configuration
├── model/           # JPA entities
├── repository/      # Spring Data JPA repositories (with @EntityGraph for N+1 prevention)
├── service/         # Business logic
├── controller/      # REST controllers with pagination and auth
├── worker/          # SQS background job processor
├── dto/             # Request/response DTOs
└── exception/       # Error handling
```

### Infrastructure

| Service    | Local               | Purpose                        |
|------------|---------------------|--------------------------------|
| MySQL 8    | Docker (port 3306)  | Primary database               |
| LocalStack | Docker (port 4566)  | S3 file storage + SQS queues   |

### Seed Data

The migration creates sample data: 1 property manager, 2 properties, 4 units, 3 tenants, 2 active leases, rent charges with manual payments, and 4 user accounts.

## Stripe Credit Card Payments

This feature adds the ability for tenants to pay rent charges using a saved credit card via Stripe.

### Setup

**Prerequisites:** A Stripe account with a test secret key (`sk_test_...`). Sign up free at [stripe.com](https://stripe.com) — no real payment info required. Find your key at **Developers → API keys** in the dashboard.

**Run with Stripe enabled:**

```bash
STRIPE_API_KEY=sk_test_your_key_here ./gradlew bootRun
```

**Run integration tests (hits real Stripe test API):**

```bash
STRIPE_API_KEY=sk_test_your_key_here ./gradlew integrationTest
```

Integration tests are skipped automatically when `STRIPE_API_KEY` is not set, so `./gradlew test` always passes in CI without credentials.

---

### New API Endpoints

#### Card Management (Tenant only)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/cards/setup-intent` | Create a Stripe SetupIntent; returns `clientSecret` for frontend to collect card details |
| `POST` | `/api/cards` | Save a confirmed PaymentMethod to the tenant's account |
| `GET` | `/api/cards` | List saved cards (cursor-paginated) |
| `DELETE` | `/api/cards/{id}` | Remove a saved card |

#### Credit Card Payments

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `POST` | `/api/credit-card-payments` | Tenant only | Pay a rent charge with a saved card |
| `GET` | `/api/credit-card-payments/{id}` | Tenant (own) or PM | Get payment by ID |
| `GET` | `/api/credit-card-payments?rentChargeId={id}` | Tenant (own) or PM | List payments for a charge (paginated) |
| `POST` | `/api/credit-card-payments/{id}/refund` | PM only | Refund a succeeded payment |

---

### Example Requests

**Step 1 — Create a setup intent (tenant)**
```bash
curl -X POST http://localhost:8080/api/cards/setup-intent \
  -H "Authorization: Bearer $TENANT_TOKEN"
```
```json
{ "clientSecret": "seti_xxx_secret_yyy" }
```

Use the `clientSecret` with [Stripe.js](https://stripe.com/docs/js) in the frontend to collect the card. The raw card number never touches your server.

**Step 2 — Save the card**
```bash
curl -X POST http://localhost:8080/api/cards \
  -H "Authorization: Bearer $TENANT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"stripePaymentMethodId": "pm_xxx"}'
```
```json
{
  "id": 1,
  "last4": "4242",
  "brand": "visa",
  "expMonth": 12,
  "expYear": 2027,
  "createdAt": "2026-03-25T10:00:00Z"
}
```

**Step 3 — Pay a rent charge**
```bash
curl -X POST http://localhost:8080/api/credit-card-payments \
  -H "Authorization: Bearer $TENANT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"rentChargeId": 1, "savedCardId": 1}'
```
```json
{
  "id": 1,
  "rentChargeId": 1,
  "savedCardId": 1,
  "stripePaymentIntentId": "pi_xxx",
  "amount": "2000.00",
  "status": "SUCCEEDED",
  "failureReason": null,
  "createdAt": "2026-03-25T10:00:00Z",
  "updatedAt": "2026-03-25T10:00:00Z"
}
```

If the card is declined, the response is still `201` with `"status": "FAILED"` and a `failureReason` — the payment record exists; the decline is a business outcome, not an API error.

**Refund (PM only)**
```bash
curl -X POST http://localhost:8080/api/credit-card-payments/1/refund \
  -H "Authorization: Bearer $PM_TOKEN"
```
```json
{ "id": 1, "status": "REFUNDED", "rentChargeId": 1, "amount": "2000.00", ... }
```

**List saved cards**
```bash
curl http://localhost:8080/api/cards \
  -H "Authorization: Bearer $TENANT_TOKEN"
```
```json
{
  "content": [{ "id": 1, "last4": "4242", "brand": "visa", "expMonth": 12, "expYear": 2027 }],
  "hasMore": false
}
```

---

### Data Model

Three new tables added in `V4__add_stripe_tables.sql`:

**`stripe_customers`** — links each tenant to a Stripe Customer object. One per tenant, lazy-created on first setup-intent call.

**`saved_cards`** — one row per saved card. Stores only display metadata (last4, brand, expiry) and the Stripe `payment_method_id`; no raw card numbers ever stored.

**`credit_card_payments`** — one row per payment attempt.

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT PK | |
| `rent_charge_id` | BIGINT FK | |
| `saved_card_id` | BIGINT FK | |
| `stripe_payment_intent_id` | VARCHAR UNIQUE | e.g. `pi_xxx` |
| `amount` | DECIMAL(10,2) | copied from `rent_charges.amount` at payment time; never from client input |
| `status` | VARCHAR | `INITIATED` / `SUCCEEDED` / `FAILED` / `REFUNDED` |
| `failure_reason` | TEXT NULL | Stripe decline message |
| `created_at` / `updated_at` | TIMESTAMP | |

#### Payment Status Lifecycle

```
INITIATED → SUCCEEDED → REFUNDED
          ↘ FAILED
```

- `INITIATED` — row inserted before the Stripe API call; acts as a lock to prevent concurrent double-charges
- `SUCCEEDED` — Stripe confirmed the charge; `rent_charges.status` set to `PAID`
- `FAILED` — Stripe returned a decline or service error; rent charge status unchanged
- `REFUNDED` — PM issued a refund; `rent_charges.status` reverted to `PENDING`

---

### Assumptions and Tradeoffs

**No webhooks.** Payment status is determined from the synchronous Stripe API response. In production, webhooks would be needed for async card flows (3DS redirects, delayed settlement, dispute handling).

**No 3DS.** `PaymentIntents.create(confirm=true)` fails for cards requiring 3DS authentication. A production system needs `return_url` and a redirect-back flow.

**Card decline = 201, not 4xx.** A declined card is a valid business outcome — a `FAILED` payment record is created and returned with `201`. Only Stripe service-level failures (5xx, network) return `502 Bad Gateway`.

**Amount from the server.** The charge amount is always taken from `rent_charges.amount`, never from client input, to prevent billing manipulation.

**OVERDUE charges are payable.** Both `PENDING` and `OVERDUE` charges can be paid; `PAID` charges reject with `409 Conflict`.

**SELECT FOR UPDATE for double-charge prevention.** The payment flow locks the rent charge row before checking for an existing active payment. This prevents two concurrent requests from both reading "no active payment" and both proceeding to charge Stripe.

**Separate payment tables.** Offline and credit card payments stay in separate tables. This keeps the existing offline flow untouched and avoids retrofitting nullable Stripe columns onto the existing `payments` table.

---

### What I'd Do Differently for Production / Scale

- **Add Stripe webhooks** to handle async payment events (3DS, delayed capture, disputes). The synchronous-only approach will miss status updates that arrive after the HTTP response.
- **Idempotency keys** on Stripe API calls so retries on network failure don't double-charge.
- **Background queue for payment processing** — at high volume, synchronous HTTP-to-Stripe adds latency to every pay request. A job queue lets the API return immediately and process the charge asynchronously.
- **Webhook signature verification** — Stripe signs webhook payloads; verify signatures before acting on them.
- **Soft-delete cards** rather than hard-delete, to preserve audit history on past payments that reference the card.
- **Prometheus metrics / structured logging** on Stripe call latency and failure rates for oncall visibility.

---

### How I Used AI

I used Claude (via Claude Code CLI) throughout the full development cycle:

1. **Design** — Used the `brainstorming` skill to work through architecture decisions interactively: SetupIntent vs direct card collection, synchronous vs async payment, webhook vs no-webhook for take-home scope, data model shape.

2. **Spec** — Claude produced `docs/superpowers/specs/2026-03-25-stripe-credit-card-payments-design.md`. A spec-reviewer subagent then checked it for gaps (missing indexes, race condition handling, error-code consistency) before sign-off.

3. **Implementation plan** — The `writing-plans` skill turned the spec into `docs/superpowers/plans/2026-03-26-stripe-credit-card-payments.md` — a task-by-task TDD plan with exact file paths and code snippets. A plan-reviewer subagent checked it before execution.

4. **Execution** — The `subagent-driven-development` skill dispatched a fresh subagent per task (StripeClient, CardDataAccess, CardModule, CardApi, LedgerModule updates, CreditCardPaymentDataAccess, CreditCardPaymentModule, CreditCardPaymentApi, integration tests). After each task, a spec-compliance reviewer and a code-quality reviewer ran before moving to the next.

5. **Bug catching** — Reviewers caught real issues mid-flight: missing `StripeException` wrapping in `StripeClientImpl`, `!!` non-null operators replaced with safe helpers, a missing insert-only guard on `saveCard`. The final overall reviewer caught a tenant ownership gap on the read endpoints (tenants could read any payment by ID) — this was fixed before merge.

All intermediate artifacts (design spec, implementation plan, full commit history) are in the repo under `docs/superpowers/`.

---

## Your Task

See **[TASK.md](./TASK.md)** for the assignment.
