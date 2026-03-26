# Stripe Credit Card Payments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Stripe-powered credit card payment support to the property management platform, including card management (save/list/delete) and payment lifecycle (initiate/succeed/fail/refund).

**Architecture:** Tenants save cards via Stripe's SetupIntent flow (card data never touches our server), then pay rent charges using saved cards via synchronous PaymentIntents. Three new DB tables: `stripe_customers`, `saved_cards`, `credit_card_payments`. New packages: `card/` and `creditcardpayment/` for domain logic, `stripe/` for the Stripe SDK wrapper.

**Tech Stack:** Kotlin, Spring Boot 3.3.5, jOOQ 3.19.16, Stripe Java SDK 25.5.0, Flyway, MockK, `@WebMvcTest` for controller tests, Stripe test-mode for integration tests.

---

## File Map

### New Files
| File | Purpose |
|---|---|
| `src/main/resources/db/migration/V4__add_stripe_tables.sql` | Creates `stripe_customers`, `saved_cards`, `credit_card_payments` |
| `src/main/kotlin/com/ender/takehome/model/SavedCard.kt` | `SavedCard` domain model |
| `src/main/kotlin/com/ender/takehome/model/CreditCardPayment.kt` | `CreditCardPayment` model + `CreditCardPaymentStatus` enum |
| `src/main/kotlin/com/ender/takehome/exception/PaymentFailedException.kt` | Exception for Stripe service-level failures (→ 502) |
| `src/main/kotlin/com/ender/takehome/stripe/StripeClient.kt` | Interface + result data classes |
| `src/main/kotlin/com/ender/takehome/stripe/StripeClientImpl.kt` | Wraps Stripe Java SDK static calls |
| `src/main/kotlin/com/ender/takehome/card/CardDataAccess.kt` | jOOQ queries for `stripe_customers` + `saved_cards` |
| `src/main/kotlin/com/ender/takehome/card/CardModule.kt` | Card management business logic |
| `src/main/kotlin/com/ender/takehome/card/CardApi.kt` | REST controller for `/api/cards/**` |
| `src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentDataAccess.kt` | jOOQ queries for `credit_card_payments` |
| `src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentModule.kt` | Payment/refund business logic |
| `src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentApi.kt` | REST controller for `/api/credit-card-payments/**` |
| `src/test/kotlin/com/ender/takehome/card/CardModuleTest.kt` | Unit tests for CardModule |
| `src/test/kotlin/com/ender/takehome/card/CardApiTest.kt` | Controller tests for CardApi |
| `src/test/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentModuleTest.kt` | Unit tests for CreditCardPaymentModule |
| `src/test/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentApiTest.kt` | Controller tests for CreditCardPaymentApi |
| `src/integrationTest/kotlin/com/ender/takehome/CreditCardPaymentIntegrationTest.kt` | End-to-end Stripe test-mode integration test |

### Modified Files
| File | Change |
|---|---|
| `build.gradle.kts` | Add `com.stripe:stripe-java:25.5.0` dependency |
| `src/main/resources/application.yml` | Add `stripe.api-key` + `stripe.currency` config |
| `src/main/kotlin/com/ender/takehome/exception/GlobalExceptionHandler.kt` | Handle `PaymentFailedException` → 502 |
| `src/main/kotlin/com/ender/takehome/dto/request/Requests.kt` | Add `SaveCardRequest`, `CreateCreditCardPaymentRequest` |
| `src/main/kotlin/com/ender/takehome/dto/response/Responses.kt` | Add `SavedCardResponse`, `CreditCardPaymentResponse` |
| `src/main/kotlin/com/ender/takehome/ledger/LedgerDataAccess.kt` | Add `findChargeByIdForUpdate()` (SELECT FOR UPDATE) |
| `src/main/kotlin/com/ender/takehome/ledger/LedgerModule.kt` | Expose `getChargeByIdForUpdate()` |
| `src/test/kotlin/com/ender/takehome/TestFixtures.kt` | Add `savedCard()` and `creditCardPayment()` builders |

---

## Task 1: Dependencies and Configuration

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add Stripe SDK dependency to build.gradle.kts**

In `build.gradle.kts`, in the `dependencies { }` block after the AWS SDK lines, add:
```kotlin
// Stripe
implementation("com.stripe:stripe-java:25.5.0")
```

- [ ] **Step 2: Add Stripe config to application.yml**

Append to `src/main/resources/application.yml`:
```yaml
stripe:
  api-key: ${STRIPE_API_KEY:sk_test_placeholder}
  currency: usd
```

The `sk_test_placeholder` fallback lets the app start without the env var for non-payment tests. Set `STRIPE_API_KEY=sk_test_YOUR_KEY` in your environment before running the server.

- [ ] **Step 3: Verify compilation**

```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/main/resources/application.yml
git commit -m "build: add Stripe Java SDK dependency and configuration"
```

---

## Task 2: Database Migration

**Files:**
- Create: `src/main/resources/db/migration/V4__add_stripe_tables.sql`

- [ ] **Step 1: Create the migration file**

```sql
-- V4__add_stripe_tables.sql

CREATE TABLE stripe_customers (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT       NOT NULL,
    stripe_customer_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_stripe_customers_tenant (tenant_id),
    CONSTRAINT fk_stripe_customers_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE saved_cards (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id                BIGINT       NOT NULL,
    stripe_payment_method_id VARCHAR(255) NOT NULL,
    last4                    VARCHAR(4)   NOT NULL,
    brand                    VARCHAR(50)  NOT NULL,
    exp_month                INT          NOT NULL,
    exp_year                 INT          NOT NULL,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_saved_cards_pm_id (stripe_payment_method_id),
    INDEX idx_saved_cards_tenant_id (tenant_id),
    CONSTRAINT fk_saved_cards_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE credit_card_payments (
    id                       BIGINT         NOT NULL AUTO_INCREMENT,
    rent_charge_id           BIGINT         NOT NULL,
    saved_card_id            BIGINT         NOT NULL,
    stripe_payment_intent_id VARCHAR(255)   NOT NULL,
    amount                   DECIMAL(10, 2) NOT NULL,
    status                   VARCHAR(20)    NOT NULL,
    failure_reason           TEXT,
    created_at               TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ccp_payment_intent (stripe_payment_intent_id),
    INDEX idx_ccp_rent_charge_id (rent_charge_id),
    CONSTRAINT fk_ccp_rent_charge FOREIGN KEY (rent_charge_id) REFERENCES rent_charges (id),
    CONSTRAINT fk_ccp_saved_card  FOREIGN KEY (saved_card_id)  REFERENCES saved_cards (id),
    CONSTRAINT chk_ccp_status CHECK (status IN ('INITIATED', 'SUCCEEDED', 'FAILED', 'REFUNDED'))
);
```

- [ ] **Step 2: Regenerate jOOQ classes from updated migration files**

```bash
./gradlew jooqCodegen
```
Expected: BUILD SUCCESSFUL. New generated classes appear under `build/generated-sources/jooq/com/ender/takehome/generated/tables/`:
- `StripeCustomers.java` with `STRIPE_CUSTOMERS` constant
- `SavedCards.java` with `SAVED_CARDS` constant
- `CreditCardPayments.java` with `CREDIT_CARD_PAYMENTS` constant

- [ ] **Step 3: Apply migration to local database (Docker must be running)**

```bash
./gradlew bootRun &
sleep 10
kill %1
```
Or just run the app — Flyway applies V4 on startup. Check MySQL to confirm tables exist.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V4__add_stripe_tables.sql
git commit -m "feat: add Flyway migration V4 for Stripe tables (stripe_customers, saved_cards, credit_card_payments)"
```

---

## Task 3: Domain Models, DTOs, and Exception

**Files:**
- Create: `src/main/kotlin/com/ender/takehome/model/SavedCard.kt`
- Create: `src/main/kotlin/com/ender/takehome/model/CreditCardPayment.kt`
- Create: `src/main/kotlin/com/ender/takehome/exception/PaymentFailedException.kt`
- Modify: `src/main/kotlin/com/ender/takehome/exception/GlobalExceptionHandler.kt`
- Modify: `src/main/kotlin/com/ender/takehome/dto/request/Requests.kt`
- Modify: `src/main/kotlin/com/ender/takehome/dto/response/Responses.kt`

- [ ] **Step 1: Create SavedCard model**

```kotlin
// src/main/kotlin/com/ender/takehome/model/SavedCard.kt
package com.ender.takehome.model

import java.time.Instant

data class SavedCard(
    val id: Long = 0,
    val tenantId: Long,
    val stripePaymentMethodId: String,
    val last4: String,
    val brand: String,
    val expMonth: Int,
    val expYear: Int,
    val createdAt: Instant = Instant.now(),
)
```

- [ ] **Step 2: Create CreditCardPayment model**

```kotlin
// src/main/kotlin/com/ender/takehome/model/CreditCardPayment.kt
package com.ender.takehome.model

import java.math.BigDecimal
import java.time.Instant

enum class CreditCardPaymentStatus { INITIATED, SUCCEEDED, FAILED, REFUNDED }

data class CreditCardPayment(
    val id: Long = 0,
    val rentChargeId: Long,
    val savedCardId: Long,
    val stripePaymentIntentId: String,
    val amount: BigDecimal,
    val status: CreditCardPaymentStatus,
    val failureReason: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
```

- [ ] **Step 3: Create PaymentFailedException**

```kotlin
// src/main/kotlin/com/ender/takehome/exception/PaymentFailedException.kt
package com.ender.takehome.exception

class PaymentFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

- [ ] **Step 4: Register PaymentFailedException in GlobalExceptionHandler**

Add to the `GlobalExceptionHandler` class in `src/main/kotlin/com/ender/takehome/exception/GlobalExceptionHandler.kt`:

```kotlin
@ExceptionHandler(PaymentFailedException::class)
fun handlePaymentFailed(ex: PaymentFailedException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
        ErrorResponse(502, "Bad Gateway", ex.message ?: "Payment service error")
    )
```

Also add `@ExceptionHandler` for `IllegalStateException` mapping to 409 Conflict (used for double-charge and card-with-active-payment):
```kotlin
@ExceptionHandler(IllegalStateException::class)
fun handleIllegalState(ex: IllegalStateException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.CONFLICT).body(
        ErrorResponse(409, "Conflict", ex.message ?: "Request conflicts with current state")
    )
```

- [ ] **Step 5: Add request DTOs**

Append to `src/main/kotlin/com/ender/takehome/dto/request/Requests.kt`:

```kotlin
data class SaveCardRequest(
    @field:NotBlank val stripePaymentMethodId: String,
)

data class CreateCreditCardPaymentRequest(
    @field:NotNull val rentChargeId: Long,
    @field:NotNull val savedCardId: Long,
)
```

Add missing import at top of file: `import jakarta.validation.constraints.NotNull` (already present, but double-check).

- [ ] **Step 6: Add response DTOs**

Append to `src/main/kotlin/com/ender/takehome/dto/response/Responses.kt`:

```kotlin
data class SavedCardResponse(
    val id: Long,
    val last4: String,
    val brand: String,
    val expMonth: Int,
    val expYear: Int,
    val createdAt: Instant,
) {
    companion object {
        fun from(card: SavedCard) = SavedCardResponse(
            id = card.id,
            last4 = card.last4,
            brand = card.brand,
            expMonth = card.expMonth,
            expYear = card.expYear,
            createdAt = card.createdAt,
        )
    }
}

data class SetupIntentResponse(val clientSecret: String)

data class CreditCardPaymentResponse(
    val id: Long,
    val rentChargeId: Long,
    val savedCardId: Long,
    val stripePaymentIntentId: String,
    val amount: BigDecimal,
    val status: CreditCardPaymentStatus,
    val failureReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(p: CreditCardPayment) = CreditCardPaymentResponse(
            id = p.id,
            rentChargeId = p.rentChargeId,
            savedCardId = p.savedCardId,
            stripePaymentIntentId = p.stripePaymentIntentId,
            amount = p.amount,
            status = p.status,
            failureReason = p.failureReason,
            createdAt = p.createdAt,
            updatedAt = p.updatedAt,
        )
    }
}
```

Add import for the new models at the top of `Responses.kt`:
```kotlin
import com.ender.takehome.model.CreditCardPayment
import com.ender.takehome.model.CreditCardPaymentStatus
import com.ender.takehome.model.SavedCard
import java.math.BigDecimal
```

- [ ] **Step 7: Compile to verify no errors**

```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/ender/takehome/model/ \
        src/main/kotlin/com/ender/takehome/exception/ \
        src/main/kotlin/com/ender/takehome/dto/
git commit -m "feat: add SavedCard, CreditCardPayment models, DTOs, and PaymentFailedException"
```

---

## Task 4: StripeClient Interface and Implementation

**Files:**
- Create: `src/main/kotlin/com/ender/takehome/stripe/StripeClient.kt`
- Create: `src/main/kotlin/com/ender/takehome/stripe/StripeClientImpl.kt`

- [ ] **Step 1: Create StripeClient interface**

```kotlin
// src/main/kotlin/com/ender/takehome/stripe/StripeClient.kt
package com.ender.takehome.stripe

interface StripeClient {
    /** Creates a Stripe Customer and returns their stripeCustomerId (e.g. "cus_xxx"). */
    fun createCustomer(email: String): String

    /** Creates a SetupIntent for the given customer and returns the clientSecret. */
    fun createSetupIntent(stripeCustomerId: String): String

    /** Fetches card details for a PaymentMethod ID. Throws if not found. */
    fun retrievePaymentMethodDetails(paymentMethodId: String): PaymentMethodDetails

    /** Attaches a PaymentMethod to a Customer in Stripe. */
    fun attachPaymentMethod(paymentMethodId: String, stripeCustomerId: String)

    /** Detaches a PaymentMethod from its Customer in Stripe. */
    fun detachPaymentMethod(paymentMethodId: String)

    /**
     * Creates and immediately confirms a PaymentIntent.
     * Returns [StripePaymentResult.Succeeded] on success.
     * Returns [StripePaymentResult.Failed] if the card is declined.
     * Throws [com.ender.takehome.exception.PaymentFailedException] on Stripe service errors.
     */
    fun createPaymentIntent(
        amountCents: Long,
        currency: String,
        paymentMethodId: String,
        stripeCustomerId: String,
    ): StripePaymentResult

    /** Issues a full refund for the given PaymentIntent. Throws [PaymentFailedException] on error. */
    fun refundPaymentIntent(stripePaymentIntentId: String)
}

data class PaymentMethodDetails(
    val last4: String,
    val brand: String,
    val expMonth: Int,
    val expYear: Int,
)

sealed class StripePaymentResult {
    data class Succeeded(val paymentIntentId: String) : StripePaymentResult()
    data class Failed(val paymentIntentId: String?, val reason: String) : StripePaymentResult()
}
```

- [ ] **Step 2: Create StripeClientImpl**

```kotlin
// src/main/kotlin/com/ender/takehome/stripe/StripeClientImpl.kt
package com.ender.takehome.stripe

import com.ender.takehome.exception.PaymentFailedException
import com.stripe.Stripe
import com.stripe.exception.CardException
import com.stripe.exception.StripeException
import com.stripe.model.Customer
import com.stripe.model.PaymentIntent
import com.stripe.model.PaymentMethod
import com.stripe.model.Refund
import com.stripe.model.SetupIntent
import com.stripe.param.CustomerCreateParams
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.PaymentMethodAttachParams
import com.stripe.param.RefundCreateParams
import com.stripe.param.SetupIntentCreateParams
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class StripeClientImpl(@Value("\${stripe.api-key}") apiKey: String) : StripeClient {

    init {
        Stripe.apiKey = apiKey
    }

    override fun createCustomer(email: String): String {
        val params = CustomerCreateParams.builder().setEmail(email).build()
        return Customer.create(params).id
    }

    override fun createSetupIntent(stripeCustomerId: String): String {
        val params = SetupIntentCreateParams.builder()
            .setCustomer(stripeCustomerId)
            .addPaymentMethodType("card")
            .build()
        return SetupIntent.create(params).clientSecret
    }

    override fun retrievePaymentMethodDetails(paymentMethodId: String): PaymentMethodDetails {
        val pm = PaymentMethod.retrieve(paymentMethodId)
        val card = pm.card ?: throw IllegalArgumentException("Payment method $paymentMethodId is not a card")
        return PaymentMethodDetails(
            last4 = card.last4,
            brand = card.brand,
            expMonth = card.expMonth.toInt(),
            expYear = card.expYear.toInt(),
        )
    }

    override fun attachPaymentMethod(paymentMethodId: String, stripeCustomerId: String) {
        val pm = PaymentMethod.retrieve(paymentMethodId)
        pm.attach(PaymentMethodAttachParams.builder().setCustomer(stripeCustomerId).build())
    }

    override fun detachPaymentMethod(paymentMethodId: String) {
        PaymentMethod.retrieve(paymentMethodId).detach()
    }

    override fun createPaymentIntent(
        amountCents: Long,
        currency: String,
        paymentMethodId: String,
        stripeCustomerId: String,
    ): StripePaymentResult {
        val params = PaymentIntentCreateParams.builder()
            .setAmount(amountCents)
            .setCurrency(currency)
            .setCustomer(stripeCustomerId)
            .setPaymentMethod(paymentMethodId)
            .setConfirm(true)
            .setErrorOnRequiresAction(true)
            .build()

        return try {
            val intent = PaymentIntent.create(params)
            StripePaymentResult.Succeeded(intent.id)
        } catch (e: CardException) {
            val piId = e.stripeError?.paymentIntent?.id
            StripePaymentResult.Failed(piId, e.userMessage ?: e.message ?: "Card declined")
        } catch (e: StripeException) {
            throw PaymentFailedException("Stripe service error: ${e.message}", e)
        }
    }

    override fun refundPaymentIntent(stripePaymentIntentId: String) {
        val params = RefundCreateParams.builder()
            .setPaymentIntent(stripePaymentIntentId)
            .build()
        try {
            Refund.create(params)
        } catch (e: StripeException) {
            throw PaymentFailedException("Refund failed: ${e.message}", e)
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/ender/takehome/stripe/
git commit -m "feat: add StripeClient interface and StripeClientImpl"
```

---

## Task 5: CardDataAccess

**Files:**
- Create: `src/main/kotlin/com/ender/takehome/card/CardDataAccess.kt`

- [ ] **Step 1: Create CardDataAccess**

```kotlin
// src/main/kotlin/com/ender/takehome/card/CardDataAccess.kt
package com.ender.takehome.card

import com.ender.takehome.generated.tables.SavedCards.SAVED_CARDS
import com.ender.takehome.generated.tables.StripeCustomers.STRIPE_CUSTOMERS
import com.ender.takehome.generated.tables.records.SavedCardsRecord
import com.ender.takehome.generated.tables.records.StripeCustomersRecord
import com.ender.takehome.model.SavedCard
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class CardDataAccess(private val dsl: DSLContext) {

    // --- Stripe Customer ---

    fun findStripeCustomerByTenantId(tenantId: Long): String? =
        dsl.selectFrom(STRIPE_CUSTOMERS)
            .where(STRIPE_CUSTOMERS.TENANT_ID.eq(tenantId))
            .fetchOne()
            ?.stripeCustomerId

    fun saveStripeCustomer(tenantId: Long, stripeCustomerId: String) {
        dsl.newRecord(STRIPE_CUSTOMERS).apply {
            this.tenantId = tenantId
            this.stripeCustomerId = stripeCustomerId
        }.store()
    }

    // --- Saved Cards ---

    fun findCardById(id: Long): SavedCard? =
        dsl.selectFrom(SAVED_CARDS)
            .where(SAVED_CARDS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findCardsByTenantIdCursor(tenantId: Long, startAfterId: Long?, limit: Int): List<SavedCard> =
        dsl.selectFrom(SAVED_CARDS)
            .where(SAVED_CARDS.TENANT_ID.eq(tenantId))
            .and(if (startAfterId != null) SAVED_CARDS.ID.gt(startAfterId) else DSL.noCondition())
            .orderBy(SAVED_CARDS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun saveCard(card: SavedCard): SavedCard {
        val record = dsl.newRecord(SAVED_CARDS).apply {
            tenantId = card.tenantId
            stripePaymentMethodId = card.stripePaymentMethodId
            last4 = card.last4
            brand = card.brand
            expMonth = card.expMonth
            expYear = card.expYear
        }
        record.store()
        return card.copy(id = record.id!!)
    }

    fun deleteCard(id: Long) {
        dsl.deleteFrom(SAVED_CARDS).where(SAVED_CARDS.ID.eq(id)).execute()
    }

    fun hasInitiatedPaymentForCard(savedCardId: Long): Boolean {
        // Import CREDIT_CARD_PAYMENTS at the top of this file
        val ccpTable = com.ender.takehome.generated.tables.CreditCardPayments.CREDIT_CARD_PAYMENTS
        return dsl.fetchCount(
            dsl.selectFrom(ccpTable)
                .where(ccpTable.SAVED_CARD_ID.eq(savedCardId))
                .and(ccpTable.STATUS.eq("INITIATED"))
        ) > 0
    }

    private fun SavedCardsRecord.toModel() = SavedCard(
        id = id!!,
        tenantId = tenantId!!,
        stripePaymentMethodId = stripePaymentMethodId!!,
        last4 = last4!!,
        brand = brand!!,
        expMonth = expMonth!!,
        expYear = expYear!!,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
    )
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL. Fix any import issues — jOOQ generated class names follow the table name pattern with first letter uppercase.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/ender/takehome/card/CardDataAccess.kt
git commit -m "feat: add CardDataAccess for stripe_customers and saved_cards"
```

---

## Task 6: CardModule (TDD)

**Files:**
- Create: `src/test/kotlin/com/ender/takehome/card/CardModuleTest.kt`
- Create: `src/main/kotlin/com/ender/takehome/card/CardModule.kt`

- [ ] **Step 1: Write failing tests for CardModule**

```kotlin
// src/test/kotlin/com/ender/takehome/card/CardModuleTest.kt
package com.ender.takehome.card

import com.ender.takehome.TestFixtures
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.stripe.PaymentMethodDetails
import com.ender.takehome.stripe.StripeClient
import com.ender.takehome.tenant.TenantModule
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CardModuleTest {

    private val dataAccess = mockk<CardDataAccess>()
    private val stripeClient = mockk<StripeClient>()
    private val tenantModule = mockk<TenantModule>()
    private val module = CardModule(dataAccess, stripeClient, tenantModule)

    private val tenant = TestFixtures.tenant()
    private val savedCard = TestFixtures.savedCard()

    @BeforeEach
    fun setUp() { clearMocks(dataAccess, stripeClient, tenantModule) }

    @Test
    fun `createSetupIntent creates Stripe customer on first call and returns clientSecret`() {
        every { tenantModule.getById(tenant.id) } returns tenant
        every { dataAccess.findStripeCustomerByTenantId(tenant.id) } returns null
        every { stripeClient.createCustomer(tenant.email) } returns "cus_new"
        every { dataAccess.saveStripeCustomer(tenant.id, "cus_new") } just Runs
        every { stripeClient.createSetupIntent("cus_new") } returns "seti_secret"

        val result = module.createSetupIntent(tenant.id)

        assertEquals("seti_secret", result)
        verify(exactly = 1) { stripeClient.createCustomer(tenant.email) }
        verify(exactly = 1) { dataAccess.saveStripeCustomer(tenant.id, "cus_new") }
    }

    @Test
    fun `createSetupIntent reuses existing Stripe customer`() {
        every { tenantModule.getById(tenant.id) } returns tenant
        every { dataAccess.findStripeCustomerByTenantId(tenant.id) } returns "cus_existing"
        every { stripeClient.createSetupIntent("cus_existing") } returns "seti_secret2"

        val result = module.createSetupIntent(tenant.id)

        assertEquals("seti_secret2", result)
        verify(exactly = 0) { stripeClient.createCustomer(any()) }
    }

    @Test
    fun `saveCard retrieves PM details, attaches to customer, and persists`() {
        val details = PaymentMethodDetails("4242", "visa", 12, 2028)
        every { tenantModule.getById(tenant.id) } returns tenant
        every { dataAccess.findStripeCustomerByTenantId(tenant.id) } returns "cus_existing"
        every { stripeClient.retrievePaymentMethodDetails("pm_xxx") } returns details
        every { stripeClient.attachPaymentMethod("pm_xxx", "cus_existing") } just Runs
        every { dataAccess.saveCard(any()) } answers { firstArg<com.ender.takehome.model.SavedCard>().copy(id = 1L) }

        val result = module.saveCard(tenant.id, "pm_xxx")

        assertEquals("4242", result.last4)
        assertEquals("visa", result.brand)
        verify(exactly = 1) { stripeClient.attachPaymentMethod("pm_xxx", "cus_existing") }
        verify(exactly = 1) { dataAccess.saveCard(any()) }
    }

    @Test
    fun `deleteCard throws 409 when INITIATED payment exists for card`() {
        every { dataAccess.findCardById(savedCard.id) } returns savedCard.copy(tenantId = tenant.id)
        every { dataAccess.hasInitiatedPaymentForCard(savedCard.id) } returns true

        assertThrows<IllegalStateException> {
            module.deleteCard(savedCard.id, tenant.id)
        }
        verify(exactly = 0) { stripeClient.detachPaymentMethod(any()) }
    }

    @Test
    fun `deleteCard throws 404 when card belongs to different tenant`() {
        every { dataAccess.findCardById(savedCard.id) } returns savedCard.copy(tenantId = 999L)

        assertThrows<ResourceNotFoundException> {
            module.deleteCard(savedCard.id, tenant.id)
        }
    }

    @Test
    fun `deleteCard detaches from Stripe and deletes from DB`() {
        every { dataAccess.findCardById(savedCard.id) } returns savedCard.copy(tenantId = tenant.id)
        every { dataAccess.hasInitiatedPaymentForCard(savedCard.id) } returns false
        every { stripeClient.detachPaymentMethod(savedCard.stripePaymentMethodId) } just Runs
        every { dataAccess.deleteCard(savedCard.id) } just Runs

        module.deleteCard(savedCard.id, tenant.id)

        verify(exactly = 1) { stripeClient.detachPaymentMethod(savedCard.stripePaymentMethodId) }
        verify(exactly = 1) { dataAccess.deleteCard(savedCard.id) }
    }

    @Test
    fun `listCards returns cursor page scoped to tenant`() {
        every { dataAccess.findCardsByTenantIdCursor(tenant.id, null, 21) } returns listOf(savedCard)

        val page = module.listCards(tenant.id, null, 20)

        assertEquals(1, page.content.size)
        assertFalse(page.hasMore)
    }
}
```

- [ ] **Step 2: Run failing tests**

```bash
./gradlew test --tests "com.ender.takehome.card.CardModuleTest" 2>&1 | tail -20
```
Expected: compilation error — `CardModule` does not exist yet.

- [ ] **Step 3: Implement CardModule**

```kotlin
// src/main/kotlin/com/ender/takehome/card/CardModule.kt
package com.ender.takehome.card

import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.SavedCard
import com.ender.takehome.stripe.StripeClient
import com.ender.takehome.tenant.TenantModule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CardModule(
    private val dataAccess: CardDataAccess,
    private val stripeClient: StripeClient,
    private val tenantModule: TenantModule,
) {

    /** Returns the Stripe SetupIntent clientSecret for the tenant. Creates Stripe Customer if needed. */
    @Transactional
    fun createSetupIntent(tenantId: Long): String {
        val customerId = getOrCreateStripeCustomer(tenantId)
        return stripeClient.createSetupIntent(customerId)
    }

    /**
     * Saves a confirmed PaymentMethod for the tenant. Retrieves card details from Stripe,
     * attaches the PM to the Stripe Customer, then persists to saved_cards.
     */
    @Transactional
    fun saveCard(tenantId: Long, stripePaymentMethodId: String): SavedCard {
        val customerId = getOrCreateStripeCustomer(tenantId)
        val details = stripeClient.retrievePaymentMethodDetails(stripePaymentMethodId)
        stripeClient.attachPaymentMethod(stripePaymentMethodId, customerId)
        return dataAccess.saveCard(
            SavedCard(
                tenantId = tenantId,
                stripePaymentMethodId = stripePaymentMethodId,
                last4 = details.last4,
                brand = details.brand,
                expMonth = details.expMonth,
                expYear = details.expYear,
            )
        )
    }

    fun listCards(tenantId: Long, startAfterId: Long?, limit: Int): CursorPage<SavedCard> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findCardsByTenantIdCursor(tenantId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    /**
     * Deletes a saved card. Verifies ownership, blocks deletion if a payment is in flight,
     * then detaches from Stripe and removes from DB.
     */
    @Transactional
    fun deleteCard(cardId: Long, tenantId: Long) {
        val card = dataAccess.findCardById(cardId)
            ?: throw ResourceNotFoundException("Card not found: $cardId")
        if (card.tenantId != tenantId)
            throw ResourceNotFoundException("Card not found: $cardId")
        if (dataAccess.hasInitiatedPaymentForCard(cardId))
            throw IllegalStateException("Cannot delete card: a payment is currently in progress")
        stripeClient.detachPaymentMethod(card.stripePaymentMethodId)
        dataAccess.deleteCard(cardId)
    }

    /** Returns a saved card by ID, or throws ResourceNotFoundException. */
    fun getCardById(cardId: Long): SavedCard =
        dataAccess.findCardById(cardId)
            ?: throw ResourceNotFoundException("Card not found: $cardId")

    /**
     * Returns the Stripe Customer ID for a tenant.
     * Throws if the tenant has never created a setup intent (no Stripe Customer exists yet).
     */
    fun getStripeCustomerId(tenantId: Long): String =
        dataAccess.findStripeCustomerByTenantId(tenantId)
            ?: throw IllegalStateException("No Stripe customer found for tenant $tenantId — create a setup intent first")

    private fun getOrCreateStripeCustomer(tenantId: Long): String {
        val existing = dataAccess.findStripeCustomerByTenantId(tenantId)
        if (existing != null) return existing
        val tenant = tenantModule.getById(tenantId)
        val customerId = stripeClient.createCustomer(tenant.email)
        dataAccess.saveStripeCustomer(tenantId, customerId)
        return customerId
    }
}
```

- [ ] **Step 4: Add `savedCard()` fixture to TestFixtures**

In `src/test/kotlin/com/ender/takehome/TestFixtures.kt`, add inside the `TestFixtures` object:

```kotlin
fun savedCard(
    id: Long = 1L,
    tenantId: Long = 1L,
    stripePaymentMethodId: String = "pm_test_visa",
    last4: String = "4242",
    brand: String = "visa",
    expMonth: Int = 12,
    expYear: Int = 2028,
) = SavedCard(
    id = id,
    tenantId = tenantId,
    stripePaymentMethodId = stripePaymentMethodId,
    last4 = last4,
    brand = brand,
    expMonth = expMonth,
    expYear = expYear,
)
```

Add `import com.ender.takehome.model.SavedCard` to TestFixtures.

- [ ] **Step 5: Run tests and verify they pass**

```bash
./gradlew test --tests "com.ender.takehome.card.CardModuleTest"
```
Expected: All 6 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ender/takehome/card/CardModule.kt \
        src/test/kotlin/com/ender/takehome/card/CardModuleTest.kt \
        src/test/kotlin/com/ender/takehome/TestFixtures.kt
git commit -m "feat: add CardModule with TDD — setup intent, save, list, delete card"
```

---

## Task 7: CardApi (TDD)

**Files:**
- Create: `src/test/kotlin/com/ender/takehome/card/CardApiTest.kt`
- Create: `src/main/kotlin/com/ender/takehome/card/CardApi.kt`

- [ ] **Step 1: Write failing tests for CardApi**

```kotlin
// src/test/kotlin/com/ender/takehome/card/CardApiTest.kt
package com.ender.takehome.card

import com.ender.takehome.TestFixtures
import com.ender.takehome.config.JwtAuthenticationFilter
import com.ender.takehome.config.JwtService
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.model.UserRole
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(CardApi::class)
@Import(CardApiTest.TestSecurityConfig::class)
class CardApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var cardModule: CardModule
    @Autowired private lateinit var jwtService: JwtService

    @TestConfiguration
    @EnableMethodSecurity
    class TestSecurityConfig {
        @Bean fun jwtService() = JwtService(
            secret = "test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256",
            expirationMs = 86400000L,
        )
        @Bean fun jwtAuthenticationFilter(jwtService: JwtService) = JwtAuthenticationFilter(jwtService)
        @Bean fun securityFilterChain(http: HttpSecurity, jwtFilter: JwtAuthenticationFilter): SecurityFilterChain =
            http.csrf { it.disable() }
                .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
                .authorizeHttpRequests { it.anyRequest().authenticated() }
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
                .build()
        @Bean fun cardModule(): CardModule = mockk()
    }

    private val savedCard = TestFixtures.savedCard()

    private fun tenantToken(tenantId: Long = 1L) = jwtService.generateToken(
        userId = tenantId, email = "tenant@test.com", role = UserRole.TENANT, tenantId = tenantId, pmId = null,
    )

    private fun pmToken() = jwtService.generateToken(
        userId = 99L, email = "pm@test.com", role = UserRole.PROPERTY_MANAGER, tenantId = null, pmId = 1L,
    )

    @Test
    fun `POST setup-intent returns clientSecret for tenant`() {
        every { cardModule.createSetupIntent(1L) } returns "seti_secret"

        mockMvc.post("/api/cards/setup-intent") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.clientSecret") { value("seti_secret") }
        }
    }

    @Test
    fun `POST setup-intent returns 403 for PM`() {
        mockMvc.post("/api/cards/setup-intent") {
            header("Authorization", "Bearer ${pmToken()}")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `POST cards saves card and returns 201`() {
        every { cardModule.saveCard(1L, "pm_xxx") } returns savedCard

        mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"stripePaymentMethodId":"pm_xxx"}"""
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.last4") { value("4242") }
            jsonPath("$.brand") { value("visa") }
        }
    }

    @Test
    fun `POST cards returns 400 when stripePaymentMethodId is blank`() {
        mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"stripePaymentMethodId":""}"""
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `GET cards returns paginated list for tenant`() {
        every { cardModule.listCards(1L, null, any()) } returns CursorPage(listOf(savedCard), hasMore = false)

        mockMvc.get("/api/cards") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].last4") { value("4242") }
            jsonPath("$.hasMore") { value(false) }
        }
    }

    @Test
    fun `DELETE cards returns 204 on success`() {
        every { cardModule.deleteCard(1L, 1L) } returns Unit

        mockMvc.delete("/api/cards/1") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `GET cards returns 403 for unauthenticated request`() {
        mockMvc.get("/api/cards").andExpect { status { isForbidden() } }
    }
}
```

- [ ] **Step 2: Run failing tests**

```bash
./gradlew test --tests "com.ender.takehome.card.CardApiTest" 2>&1 | tail -20
```
Expected: compilation error — `CardApi` does not exist yet.

- [ ] **Step 3: Implement CardApi**

```kotlin
// src/main/kotlin/com/ender/takehome/card/CardApi.kt
package com.ender.takehome.card

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.SaveCardRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.SavedCardResponse
import com.ender.takehome.dto.response.SetupIntentResponse
import com.ender.takehome.model.UserRole
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/cards")
class CardApi(private val cardModule: CardModule) {

    @PostMapping("/setup-intent")
    fun createSetupIntent(): SetupIntentResponse {
        val principal = requireTenant()
        return SetupIntentResponse(cardModule.createSetupIntent(principal.tenantId!!))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun saveCard(@Valid @RequestBody request: SaveCardRequest): SavedCardResponse {
        val principal = requireTenant()
        return SavedCardResponse.from(cardModule.saveCard(principal.tenantId!!, request.stripePaymentMethodId))
    }

    @GetMapping
    fun listCards(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<SavedCardResponse> {
        val principal = requireTenant()
        val page = cardModule.listCards(principal.tenantId!!, startAfterId, limit)
        return CursorPage(page.content.map { SavedCardResponse.from(it) }, page.hasMore)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCard(@PathVariable id: Long) {
        val principal = requireTenant()
        cardModule.deleteCard(id, principal.tenantId!!)
    }

    private fun requireTenant(): UserPrincipal {
        val principal = UserPrincipal.current()
        if (principal.role != UserRole.TENANT || principal.tenantId == null)
            throw AccessDeniedException("Only tenants can manage cards")
        return principal
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

```bash
./gradlew test --tests "com.ender.takehome.card.CardApiTest"
```
Expected: All 7 tests PASS.

- [ ] **Step 5: Run all unit tests to check nothing is broken**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ender/takehome/card/CardApi.kt \
        src/test/kotlin/com/ender/takehome/card/CardApiTest.kt
git commit -m "feat: add CardApi for /api/cards with TDD — setup-intent, save, list, delete"
```

---

## Task 8: LedgerModule/DataAccess Updates and CreditCardPaymentDataAccess

**Files:**
- Modify: `src/main/kotlin/com/ender/takehome/ledger/LedgerDataAccess.kt`
- Modify: `src/main/kotlin/com/ender/takehome/ledger/LedgerModule.kt`
- Create: `src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentDataAccess.kt`

- [ ] **Step 1: Add `findChargeByIdForUpdate` to LedgerDataAccess**

In `src/main/kotlin/com/ender/takehome/ledger/LedgerDataAccess.kt`, after the `findChargeById` method, add:

```kotlin
/** SELECT ... FOR UPDATE — must be called within a @Transactional context. */
fun findChargeByIdForUpdate(id: Long): RentCharge? =
    dsl.selectFrom(RENT_CHARGES)
        .where(RENT_CHARGES.ID.eq(id))
        .forUpdate()
        .fetchOne()
        ?.toModel()
```

- [ ] **Step 2: Expose `getChargeByIdForUpdate` from LedgerModule**

In `src/main/kotlin/com/ender/takehome/ledger/LedgerModule.kt`, add:

```kotlin
/** Locks the rent charge row FOR UPDATE. Call only within a @Transactional method. */
fun getChargeByIdForUpdate(id: Long): RentCharge =
    dataAccess.findChargeByIdForUpdate(id)
        ?: throw ResourceNotFoundException("Rent charge not found: $id")
```

- [ ] **Step 3: Create CreditCardPaymentDataAccess**

```kotlin
// src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentDataAccess.kt
package com.ender.takehome.creditcardpayment

import com.ender.takehome.generated.tables.CreditCardPayments.CREDIT_CARD_PAYMENTS
import com.ender.takehome.generated.tables.records.CreditCardPaymentsRecord
import com.ender.takehome.model.CreditCardPayment
import com.ender.takehome.model.CreditCardPaymentStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset

@Component
class CreditCardPaymentDataAccess(private val dsl: DSLContext) {

    fun findPaymentById(id: Long): CreditCardPayment? =
        dsl.selectFrom(CREDIT_CARD_PAYMENTS)
            .where(CREDIT_CARD_PAYMENTS.ID.eq(id))
            .fetchOne()
            ?.toModel()

    fun findActivePaymentForCharge(rentChargeId: Long): CreditCardPayment? =
        dsl.selectFrom(CREDIT_CARD_PAYMENTS)
            .where(CREDIT_CARD_PAYMENTS.RENT_CHARGE_ID.eq(rentChargeId))
            .and(
                CREDIT_CARD_PAYMENTS.STATUS.eq(CreditCardPaymentStatus.INITIATED.name)
                    .or(CREDIT_CARD_PAYMENTS.STATUS.eq(CreditCardPaymentStatus.SUCCEEDED.name))
            )
            .fetchOne()
            ?.toModel()

    fun findPaymentsByRentChargeIdCursor(rentChargeId: Long, startAfterId: Long?, limit: Int): List<CreditCardPayment> =
        dsl.selectFrom(CREDIT_CARD_PAYMENTS)
            .where(CREDIT_CARD_PAYMENTS.RENT_CHARGE_ID.eq(rentChargeId))
            .and(if (startAfterId != null) CREDIT_CARD_PAYMENTS.ID.gt(startAfterId) else DSL.noCondition())
            .orderBy(CREDIT_CARD_PAYMENTS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun savePayment(payment: CreditCardPayment): CreditCardPayment {
        val record = dsl.newRecord(CREDIT_CARD_PAYMENTS).apply {
            rentChargeId = payment.rentChargeId
            savedCardId = payment.savedCardId
            stripePaymentIntentId = payment.stripePaymentIntentId
            amount = payment.amount
            status = payment.status.name
            failureReason = payment.failureReason
        }
        record.store()
        return payment.copy(id = record.id!!)
    }

    fun updatePaymentStatus(id: Long, status: CreditCardPaymentStatus, failureReason: String?): CreditCardPayment {
        dsl.update(CREDIT_CARD_PAYMENTS)
            .set(CREDIT_CARD_PAYMENTS.STATUS, status.name)
            .set(CREDIT_CARD_PAYMENTS.FAILURE_REASON, failureReason)
            .where(CREDIT_CARD_PAYMENTS.ID.eq(id))
            .execute()
        // Re-fetch to pick up DB-managed updated_at value
        return findPaymentById(id)!!
    }

    /**
     * Updates the stripe_payment_intent_id of an INITIATED record.
     * Called after Stripe responds with a real PI ID, replacing the placeholder inserted at insert time.
     */
    fun updatePaymentIntentId(id: Long, paymentIntentId: String) {
        dsl.update(CREDIT_CARD_PAYMENTS)
            .set(CREDIT_CARD_PAYMENTS.STRIPE_PAYMENT_INTENT_ID, paymentIntentId)
            .where(CREDIT_CARD_PAYMENTS.ID.eq(id))
            .execute()
    }

    private fun CreditCardPaymentsRecord.toModel() = CreditCardPayment(
        id = id!!,
        rentChargeId = rentChargeId!!,
        savedCardId = savedCardId!!,
        stripePaymentIntentId = stripePaymentIntentId!!,
        amount = amount!!,
        status = CreditCardPaymentStatus.valueOf(status!!),
        failureReason = failureReason,
        createdAt = createdAt!!.toInstant(ZoneOffset.UTC),
        updatedAt = updatedAt!!.toInstant(ZoneOffset.UTC),
    )
}
```

- [ ] **Step 4: Add unit tests for the new LedgerModule methods**

In `src/test/kotlin/com/ender/takehome/ledger/LedgerModuleTest.kt`, add:

```kotlin
@Test
fun `getChargeByIdForUpdate returns charge when found`() {
    every { dataAccess.findChargeByIdForUpdate(rentCharge.id) } returns rentCharge

    val result = module.getChargeByIdForUpdate(rentCharge.id)

    assertEquals(rentCharge.id, result.id)
}

@Test
fun `getChargeByIdForUpdate throws ResourceNotFoundException when not found`() {
    every { dataAccess.findChargeByIdForUpdate(99L) } returns null

    assertThrows<com.ender.takehome.exception.ResourceNotFoundException> {
        module.getChargeByIdForUpdate(99L)
    }
}

@Test
fun `updateChargeStatus saves charge with new status`() {
    every { dataAccess.findChargeById(rentCharge.id) } returns rentCharge
    every { dataAccess.saveCharge(any()) } answers { firstArg() }

    module.updateChargeStatus(rentCharge.id, RentChargeStatus.PAID)

    verify(exactly = 1) { dataAccess.saveCharge(match { it.status == RentChargeStatus.PAID }) }
}
```

- [ ] **Step 5: Run the updated LedgerModuleTest**

```bash
./gradlew test --tests "com.ender.takehome.ledger.LedgerModuleTest"
```
Expected: All tests PASS (including the 3 new ones).

- [ ] **Step 6: Compile full project**

```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/ender/takehome/ledger/ \
        src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentDataAccess.kt \
        src/test/kotlin/com/ender/takehome/ledger/LedgerModuleTest.kt
git commit -m "feat: add CreditCardPaymentDataAccess and LedgerModule SELECT FOR UPDATE support"
```

---

## Task 9: CreditCardPaymentModule (TDD)

**Files:**
- Create: `src/test/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentModuleTest.kt`
- Create: `src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentModule.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentModuleTest.kt
package com.ender.takehome.creditcardpayment

import com.ender.takehome.TestFixtures
import com.ender.takehome.card.CardModule
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.leasing.LeaseModule
import com.ender.takehome.ledger.LedgerModule
import com.ender.takehome.model.CreditCardPayment
import com.ender.takehome.model.CreditCardPaymentStatus
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.stripe.StripeClient
import com.ender.takehome.stripe.StripePaymentResult
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class CreditCardPaymentModuleTest {

    private val dataAccess = mockk<CreditCardPaymentDataAccess>()
    private val stripeClient = mockk<StripeClient>()
    private val ledgerModule = mockk<LedgerModule>()
    private val leaseModule = mockk<LeaseModule>()
    private val cardModule = mockk<CardModule>()

    private val module = CreditCardPaymentModule(
        dataAccess, stripeClient, ledgerModule, leaseModule, cardModule, "usd"
    )

    private val tenant = TestFixtures.tenant()
    private val lease = TestFixtures.lease(tenantId = tenant.id)
    private val charge = TestFixtures.rentCharge(leaseId = lease.id)
    private val savedCard = TestFixtures.savedCard(tenantId = tenant.id)

    @BeforeEach fun setUp() { clearMocks(dataAccess, stripeClient, ledgerModule, leaseModule, cardModule) }

    @Test
    fun `pay succeeds — records INITIATED then SUCCEEDED and marks charge PAID`() {
        setupHappyPath(chargeStatus = RentChargeStatus.PENDING)
        every { stripeClient.createPaymentIntent(any(), "usd", savedCard.stripePaymentMethodId, "cus_existing") } returns
            StripePaymentResult.Succeeded("pi_success")
        val capturedSaved = slot<CreditCardPayment>()
        every { dataAccess.savePayment(capture(capturedSaved)) } answers {
            firstArg<CreditCardPayment>().copy(id = 10L)
        }
        every { dataAccess.updatePaymentStatus(10L, CreditCardPaymentStatus.SUCCEEDED, null) } returns
            TestFixtures.creditCardPayment(status = CreditCardPaymentStatus.SUCCEEDED)
        every { ledgerModule.getChargeById(charge.id) } returns charge  // for status update
        every { dataAccess.findPaymentById(10L) } returns TestFixtures.creditCardPayment(id = 10L, status = CreditCardPaymentStatus.SUCCEEDED)

        val result = module.pay(charge.id, savedCard.id, tenant.id)

        assertEquals(CreditCardPaymentStatus.SUCCEEDED, result.status)
        verify(exactly = 1) { ledgerModule.getChargeByIdForUpdate(charge.id) }
        verify(exactly = 1) { dataAccess.savePayment(match { it.status == CreditCardPaymentStatus.INITIATED }) }
        verify(exactly = 1) { dataAccess.updatePaymentStatus(10L, CreditCardPaymentStatus.SUCCEEDED, null) }
    }

    @Test
    fun `pay records FAILED when card is declined`() {
        setupHappyPath(chargeStatus = RentChargeStatus.PENDING)
        every { stripeClient.createPaymentIntent(any(), any(), any(), any()) } returns
            StripePaymentResult.Failed(null, "Your card was declined.")
        every { dataAccess.savePayment(any()) } answers { firstArg<CreditCardPayment>().copy(id = 11L) }
        every { dataAccess.updatePaymentStatus(11L, CreditCardPaymentStatus.FAILED, "Your card was declined.") } returns
            TestFixtures.creditCardPayment(id = 11L, status = CreditCardPaymentStatus.FAILED, failureReason = "Your card was declined.")

        val result = module.pay(charge.id, savedCard.id, tenant.id)

        assertEquals(CreditCardPaymentStatus.FAILED, result.status)
        assertEquals("Your card was declined.", result.failureReason)
        // Rent charge status must NOT be updated to PAID
        verify(exactly = 0) { ledgerModule.getChargeById(any()) }
    }

    @Test
    fun `pay throws 409 when INITIATED or SUCCEEDED payment already exists`() {
        every { ledgerModule.getChargeByIdForUpdate(charge.id) } returns charge
        every { leaseModule.getById(lease.id) } returns lease
        every { dataAccess.findActivePaymentForCharge(charge.id) } returns
            TestFixtures.creditCardPayment(status = CreditCardPaymentStatus.SUCCEEDED)

        assertThrows<IllegalStateException> { module.pay(charge.id, savedCard.id, tenant.id) }
    }

    @Test
    fun `pay throws 409 when rent charge is already PAID`() {
        every { ledgerModule.getChargeByIdForUpdate(charge.id) } returns charge.copy(status = RentChargeStatus.PAID)
        every { leaseModule.getById(lease.id) } returns lease
        every { dataAccess.findActivePaymentForCharge(charge.id) } returns null

        assertThrows<IllegalStateException> { module.pay(charge.id, savedCard.id, tenant.id) }
    }

    @Test
    fun `pay throws 404 when rent charge belongs to a different tenant`() {
        every { ledgerModule.getChargeByIdForUpdate(charge.id) } returns charge
        every { leaseModule.getById(lease.id) } returns lease.copy(tenantId = 999L)

        assertThrows<ResourceNotFoundException> { module.pay(charge.id, savedCard.id, tenant.id) }
    }

    @Test
    fun `pay throws 404 when saved card belongs to a different tenant`() {
        every { ledgerModule.getChargeByIdForUpdate(charge.id) } returns charge
        every { leaseModule.getById(lease.id) } returns lease
        every { dataAccess.findActivePaymentForCharge(charge.id) } returns null
        every { cardModule.getCardById(savedCard.id) } returns savedCard.copy(tenantId = 999L)

        assertThrows<ResourceNotFoundException> { module.pay(charge.id, savedCard.id, tenant.id) }
    }

    @Test
    fun `pay works for OVERDUE charge`() {
        setupHappyPath(chargeStatus = RentChargeStatus.OVERDUE)
        every { stripeClient.createPaymentIntent(any(), any(), any(), any()) } returns
            StripePaymentResult.Succeeded("pi_over")
        every { dataAccess.savePayment(any()) } answers { firstArg<CreditCardPayment>().copy(id = 12L) }
        every { dataAccess.updatePaymentStatus(12L, CreditCardPaymentStatus.SUCCEEDED, null) } returns
            TestFixtures.creditCardPayment(id = 12L, status = CreditCardPaymentStatus.SUCCEEDED)

        val result = module.pay(charge.id, savedCard.id, tenant.id)

        assertEquals(CreditCardPaymentStatus.SUCCEEDED, result.status)
    }

    @Test
    fun `refund calls Stripe, marks REFUNDED and reverts charge to PENDING`() {
        val payment = TestFixtures.creditCardPayment(status = CreditCardPaymentStatus.SUCCEEDED, rentChargeId = charge.id)
        every { dataAccess.findPaymentById(payment.id) } returns payment
        every { stripeClient.refundPaymentIntent(payment.stripePaymentIntentId) } just Runs
        every { dataAccess.updatePaymentStatus(payment.id, CreditCardPaymentStatus.REFUNDED, null) } returns
            payment.copy(status = CreditCardPaymentStatus.REFUNDED)
        every { ledgerModule.getChargeById(charge.id) } returns charge.copy(status = RentChargeStatus.PAID)
        every { ledgerModule.updateChargeStatus(charge.id, RentChargeStatus.PENDING) } just Runs

        val result = module.refund(payment.id)

        assertEquals(CreditCardPaymentStatus.REFUNDED, result.status)
        verify(exactly = 1) { stripeClient.refundPaymentIntent(payment.stripePaymentIntentId) }
        verify(exactly = 1) { ledgerModule.updateChargeStatus(charge.id, RentChargeStatus.PENDING) }
    }

    @Test
    fun `refund throws 409 when payment is not SUCCEEDED`() {
        val payment = TestFixtures.creditCardPayment(status = CreditCardPaymentStatus.FAILED)
        every { dataAccess.findPaymentById(payment.id) } returns payment

        assertThrows<IllegalStateException> { module.refund(payment.id) }
        verify(exactly = 0) { stripeClient.refundPaymentIntent(any()) }
    }

    // --- Helpers ---

    private fun setupHappyPath(chargeStatus: RentChargeStatus) {
        every { ledgerModule.getChargeByIdForUpdate(charge.id) } returns charge.copy(status = chargeStatus)
        every { leaseModule.getById(lease.id) } returns lease
        every { dataAccess.findActivePaymentForCharge(charge.id) } returns null
        every { cardModule.getCardById(savedCard.id) } returns savedCard
        every { dataAccess.findStripeCustomerByTenantId(tenant.id) } returns "cus_existing"
    }
}
```

- [ ] **Step 2: Add `updateChargeStatus` to LedgerModule and `creditCardPayment()` to TestFixtures**

In `src/main/kotlin/com/ender/takehome/ledger/LedgerModule.kt`, add:
```kotlin
@Transactional
fun updateChargeStatus(chargeId: Long, status: RentChargeStatus) {
    val charge = dataAccess.findChargeById(chargeId)
        ?: throw ResourceNotFoundException("Rent charge not found: $chargeId")
    dataAccess.saveCharge(charge.copy(status = status))
}
```

In `src/test/kotlin/com/ender/takehome/TestFixtures.kt`, add:
```kotlin
fun creditCardPayment(
    id: Long = 1L,
    rentChargeId: Long = 1L,
    savedCardId: Long = 1L,
    stripePaymentIntentId: String = "pi_test_123",
    amount: java.math.BigDecimal = java.math.BigDecimal("2000.00"),
    status: com.ender.takehome.model.CreditCardPaymentStatus = com.ender.takehome.model.CreditCardPaymentStatus.INITIATED,
    failureReason: String? = null,
) = com.ender.takehome.model.CreditCardPayment(
    id = id,
    rentChargeId = rentChargeId,
    savedCardId = savedCardId,
    stripePaymentIntentId = stripePaymentIntentId,
    amount = amount,
    status = status,
    failureReason = failureReason,
)
```

- [ ] **Step 3: Implement CreditCardPaymentModule**

```kotlin
// src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentModule.kt
package com.ender.takehome.creditcardpayment

import com.ender.takehome.card.CardModule
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.leasing.LeaseModule
import com.ender.takehome.ledger.LedgerModule
import com.ender.takehome.model.CreditCardPayment
import com.ender.takehome.model.CreditCardPaymentStatus
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.stripe.StripeClient
import com.ender.takehome.stripe.StripePaymentResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CreditCardPaymentModule(
    private val dataAccess: CreditCardPaymentDataAccess,
    private val stripeClient: StripeClient,
    private val ledgerModule: LedgerModule,
    private val leaseModule: LeaseModule,
    private val cardModule: CardModule,
    @Value("\${stripe.currency:usd}") private val currency: String,
) {

    fun getPaymentById(id: Long): CreditCardPayment =
        dataAccess.findPaymentById(id)
            ?: throw ResourceNotFoundException("Credit card payment not found: $id")

    fun getPaymentsByRentChargeId(rentChargeId: Long, startAfterId: Long?, limit: Int): CursorPage<CreditCardPayment> {
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findPaymentsByRentChargeIdCursor(rentChargeId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    /**
     * Initiates a credit card payment for a rent charge.
     *
     * The SELECT FOR UPDATE on the rent charge row prevents concurrent double-charges.
     * The amount is always taken from the rent charge, never from client input.
     * Card declines return a FAILED payment record with a 201 status — they are not API errors.
     */
    @Transactional
    fun pay(rentChargeId: Long, savedCardId: Long, tenantId: Long): CreditCardPayment {
        // Lock rent charge row to prevent concurrent double-charges
        val charge = ledgerModule.getChargeByIdForUpdate(rentChargeId)

        // Verify tenant owns this charge via the lease
        val lease = leaseModule.getById(charge.leaseId)
        if (lease.tenantId != tenantId)
            throw ResourceNotFoundException("Rent charge not found: $rentChargeId")

        // Validate charge is payable
        if (charge.status == RentChargeStatus.PAID)
            throw IllegalStateException("Rent charge $rentChargeId is already paid")

        // Prevent double-charging
        if (dataAccess.findActivePaymentForCharge(rentChargeId) != null)
            throw IllegalStateException("An active payment already exists for this charge")

        // Verify card ownership
        val card = cardModule.getCardById(savedCardId)
        if (card.tenantId != tenantId)
            throw ResourceNotFoundException("Card not found: $savedCardId")

        // Insert INITIATED record before calling Stripe
        var payment = dataAccess.savePayment(
            CreditCardPayment(
                rentChargeId = rentChargeId,
                savedCardId = savedCardId,
                // Placeholder PI ID replaced after Stripe responds; UUID prevents any uniqueness collision
                stripePaymentIntentId = "pending_${java.util.UUID.randomUUID()}",
                amount = charge.amount,
                status = CreditCardPaymentStatus.INITIATED,
            )
        )

        // Get Stripe customer ID (must exist — created during card setup)
        val stripeCustomerId = cardModule.getStripeCustomerId(tenantId)

        return when (val result = stripeClient.createPaymentIntent(
            amountCents = charge.amount.multiply(java.math.BigDecimal(100)).toLong(),
            currency = currency,
            paymentMethodId = card.stripePaymentMethodId,
            stripeCustomerId = stripeCustomerId,
        )) {
            is StripePaymentResult.Succeeded -> {
                // Two separate updates by design: PI ID arrives from Stripe only after INITIATED insert.
                // updatePaymentIntentId replaces the placeholder; updatePaymentStatus transitions the state.
                dataAccess.updatePaymentIntentId(payment.id, result.paymentIntentId)
                val updated = dataAccess.updatePaymentStatus(payment.id, CreditCardPaymentStatus.SUCCEEDED, null)
                ledgerModule.updateChargeStatus(rentChargeId, RentChargeStatus.PAID)
                updated
            }
            is StripePaymentResult.Failed -> {
                if (result.paymentIntentId != null)
                    dataAccess.updatePaymentIntentId(payment.id, result.paymentIntentId)
                dataAccess.updatePaymentStatus(payment.id, CreditCardPaymentStatus.FAILED, result.reason)
            }
        }
    }

    @Transactional
    fun refund(paymentId: Long): CreditCardPayment {
        val payment = dataAccess.findPaymentById(paymentId)
            ?: throw ResourceNotFoundException("Credit card payment not found: $paymentId")
        if (payment.status != CreditCardPaymentStatus.SUCCEEDED)
            throw IllegalStateException("Only SUCCEEDED payments can be refunded (current status: ${payment.status})")

        stripeClient.refundPaymentIntent(payment.stripePaymentIntentId)
        val updated = dataAccess.updatePaymentStatus(paymentId, CreditCardPaymentStatus.REFUNDED, null)
        ledgerModule.updateChargeStatus(payment.rentChargeId, RentChargeStatus.PENDING)
        return updated
    }
}
```

Note: `getStripeCustomerId()` was added to `CardModule` in Task 6. `updatePaymentIntentId()` was added to `CreditCardPaymentDataAccess` in Task 8. Both are available when you reach this task.

- [ ] **Step 4: Run failing tests**

```bash
./gradlew test --tests "com.ender.takehome.creditcardpayment.CreditCardPaymentModuleTest" 2>&1 | tail -30
```
Expected: all 8 tests PASS. Fix any compilation issues first.

- [ ] **Step 5: Run full test suite**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ender/takehome/ \
        src/test/kotlin/com/ender/takehome/
git commit -m "feat: add CreditCardPaymentModule with TDD — pay, refund, ownership validation"
```

---

## Task 10: CreditCardPaymentApi (TDD)

**Files:**
- Create: `src/test/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentApiTest.kt`
- Create: `src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentApi.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentApiTest.kt
package com.ender.takehome.creditcardpayment

import com.ender.takehome.TestFixtures
import com.ender.takehome.config.JwtAuthenticationFilter
import com.ender.takehome.config.JwtService
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.exception.ResourceNotFoundException
import com.ender.takehome.model.CreditCardPaymentStatus
import com.ender.takehome.model.UserRole
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(CreditCardPaymentApi::class)
@Import(CreditCardPaymentApiTest.TestSecurityConfig::class)
class CreditCardPaymentApiTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var paymentModule: CreditCardPaymentModule
    @Autowired private lateinit var jwtService: JwtService

    @TestConfiguration
    @EnableMethodSecurity
    class TestSecurityConfig {
        @Bean fun jwtService() = JwtService(
            secret = "test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256",
            expirationMs = 86400000L,
        )
        @Bean fun jwtAuthenticationFilter(jwtService: JwtService) = JwtAuthenticationFilter(jwtService)
        @Bean fun securityFilterChain(http: HttpSecurity, jwtFilter: JwtAuthenticationFilter): SecurityFilterChain =
            http.csrf { it.disable() }
                .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
                .authorizeHttpRequests { it.anyRequest().authenticated() }
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
                .build()
        @Bean fun creditCardPaymentModule(): CreditCardPaymentModule = mockk()
    }

    private val payment = TestFixtures.creditCardPayment(status = CreditCardPaymentStatus.SUCCEEDED)

    private fun tenantToken(tenantId: Long = 1L) = jwtService.generateToken(
        userId = tenantId, email = "tenant@test.com", role = UserRole.TENANT, tenantId = tenantId, pmId = null,
    )
    private fun pmToken() = jwtService.generateToken(
        userId = 99L, email = "pm@test.com", role = UserRole.PROPERTY_MANAGER, tenantId = null, pmId = 1L,
    )

    @Test
    fun `POST credit-card-payments creates payment and returns 201`() {
        every { paymentModule.pay(5L, 2L, 1L) } returns payment

        mockMvc.post("/api/credit-card-payments") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"rentChargeId":5,"savedCardId":2}"""
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("SUCCEEDED") }
            jsonPath("$.stripePaymentIntentId") { value("pi_test_123") }
        }
    }

    @Test
    fun `POST credit-card-payments returns 403 for PM`() {
        mockMvc.post("/api/credit-card-payments") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"rentChargeId":5,"savedCardId":2}"""
            header("Authorization", "Bearer ${pmToken()}")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `POST credit-card-payments returns 400 when rentChargeId is missing`() {
        mockMvc.post("/api/credit-card-payments") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"savedCardId":2}"""
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `GET credit-card-payments by id returns payment for tenant who owns it`() {
        every { paymentModule.getPaymentById(1L) } returns payment
        // Ownership check: payment.rentChargeId -> lease -> tenantId == 1L is done in module

        mockMvc.get("/api/credit-card-payments/1") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(1) }
        }
    }

    @Test
    fun `GET credit-card-payments returns 404 when payment not found`() {
        every { paymentModule.getPaymentById(999L) } throws ResourceNotFoundException("Not found")

        mockMvc.get("/api/credit-card-payments/999") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET credit-card-payments by rentChargeId returns paginated list`() {
        every { paymentModule.getPaymentsByRentChargeId(5L, null, any()) } returns
            CursorPage(listOf(payment), hasMore = false)

        mockMvc.get("/api/credit-card-payments?rentChargeId=5") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].status") { value("SUCCEEDED") }
        }
    }

    @Test
    fun `POST refund returns 200 for PM`() {
        every { paymentModule.refund(1L) } returns payment.copy(status = CreditCardPaymentStatus.REFUNDED)

        mockMvc.post("/api/credit-card-payments/1/refund") {
            header("Authorization", "Bearer ${pmToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REFUNDED") }
        }
    }

    @Test
    fun `POST refund returns 403 for tenant`() {
        mockMvc.post("/api/credit-card-payments/1/refund") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `GET credit-card-payments returns 404 when tenant tries to access another tenant's payment`() {
        // Ownership check is enforced in the module layer; controller surfaces the 404
        every { paymentModule.getPaymentById(99L) } throws ResourceNotFoundException("Credit card payment not found: 99")

        mockMvc.get("/api/credit-card-payments/99") {
            header("Authorization", "Bearer ${tenantToken(tenantId = 2L)}")
        }.andExpect { status { isNotFound() } }
    }
}
```

- [ ] **Step 2: Run failing tests**

```bash
./gradlew test --tests "com.ender.takehome.creditcardpayment.CreditCardPaymentApiTest" 2>&1 | tail -20
```
Expected: compilation error — `CreditCardPaymentApi` does not exist yet.

- [ ] **Step 3: Implement CreditCardPaymentApi**

```kotlin
// src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentApi.kt
package com.ender.takehome.creditcardpayment

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.CreateCreditCardPaymentRequest
import com.ender.takehome.dto.response.CreditCardPaymentResponse
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.model.UserRole
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/credit-card-payments")
class CreditCardPaymentApi(private val paymentModule: CreditCardPaymentModule) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun pay(@Valid @RequestBody request: CreateCreditCardPaymentRequest): CreditCardPaymentResponse {
        val principal = requireTenant()
        return CreditCardPaymentResponse.from(
            paymentModule.pay(request.rentChargeId!!, request.savedCardId!!, principal.tenantId!!)
        )
    }

    @GetMapping("/{id}")
    fun getPayment(@PathVariable id: Long): CreditCardPaymentResponse =
        CreditCardPaymentResponse.from(paymentModule.getPaymentById(id))

    @GetMapping(params = ["rentChargeId"])
    fun getPaymentsByCharge(
        @RequestParam rentChargeId: Long,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<CreditCardPaymentResponse> {
        val page = paymentModule.getPaymentsByRentChargeId(rentChargeId, startAfterId, limit)
        return CursorPage(page.content.map { CreditCardPaymentResponse.from(it) }, page.hasMore)
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun refund(@PathVariable id: Long): CreditCardPaymentResponse =
        CreditCardPaymentResponse.from(paymentModule.refund(id))

    private fun requireTenant(): UserPrincipal {
        val principal = UserPrincipal.current()
        if (principal.role != UserRole.TENANT || principal.tenantId == null)
            throw AccessDeniedException("Only tenants can initiate credit card payments")
        return principal
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

```bash
./gradlew test --tests "com.ender.takehome.creditcardpayment.CreditCardPaymentApiTest"
```
Expected: All 8 tests PASS.

- [ ] **Step 5: Run full test suite**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentApi.kt \
        src/test/kotlin/com/ender/takehome/creditcardpayment/CreditCardPaymentApiTest.kt
git commit -m "feat: add CreditCardPaymentApi with TDD — pay, get, list, refund"
```

---

## Task 11: Integration Tests

**Files:**
- Create: `src/integrationTest/kotlin/com/ender/takehome/CreditCardPaymentIntegrationTest.kt`

**Prerequisites:** Docker running (MySQL), `STRIPE_API_KEY=sk_test_YOUR_KEY` set in environment.

- [ ] **Step 1: Create the integration test**

```kotlin
// src/integrationTest/kotlin/com/ender/takehome/CreditCardPaymentIntegrationTest.kt
package com.ender.takehome

import com.ender.takehome.model.CreditCardPaymentStatus
import com.ender.takehome.model.RentChargeStatus
import com.ender.takehome.model.UserRole
import com.fasterxml.jackson.databind.ObjectMapper
import com.stripe.Stripe
import com.stripe.model.PaymentMethod
import com.stripe.model.SetupIntent
import com.stripe.param.PaymentMethodCreateParams
import com.stripe.param.SetupIntentConfirmParams
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.delete
import com.ender.takehome.config.JwtService

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "STRIPE_API_KEY", matches = ".+")
class CreditCardPaymentIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Value("\${stripe.api-key}") private lateinit var stripeApiKey: String

    companion object {
        // Seeded tenant ID 1 = alice.johnson@email.com (from V1 migration)
        private const val TENANT_ID = 1L
        // Seeded rent charge ID 1 (PENDING, lease 1)
        private const val RENT_CHARGE_ID = 1L
    }

    private fun tenantToken() = jwtService.generateToken(
        userId = TENANT_ID, email = "alice.johnson@email.com", role = UserRole.TENANT,
        tenantId = TENANT_ID, pmId = null,
    )

    private fun pmToken() = jwtService.generateToken(
        userId = 1L, email = "admin@greenfieldproperties.com", role = UserRole.PROPERTY_MANAGER,
        tenantId = null, pmId = 1L,
    )

    /**
     * Creates a real Stripe PaymentMethod with a test card, confirms it via SetupIntent,
     * and returns the PaymentMethod ID — simulating what the frontend would do with Stripe.js.
     * The SetupIntent + customer are created by calling our own API, just as the frontend would.
     */
    private fun createConfirmedPaymentMethod(cardNumber: String = "4242424242424242"): String {
        Stripe.apiKey = stripeApiKey

        // 1. Create a PM with test card credentials
        val pm = PaymentMethod.create(
            PaymentMethodCreateParams.builder()
                .setType(PaymentMethodCreateParams.Type.CARD)
                .setCard(
                    PaymentMethodCreateParams.Card.builder()
                        .setNumber(cardNumber)
                        .setExpMonth(12)
                        .setExpYear(2028)
                        .setCvc("123")
                        .build()
                )
                .build()
        )

        // 2. Get setup intent client secret from our API, then retrieve the setup intent ID
        val siResponse = mockMvc.post("/api/cards/setup-intent") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andReturn().response.contentAsString

        val clientSecret = objectMapper.readTree(siResponse)["clientSecret"].asText()
        val setupIntentId = clientSecret.substringBefore("_secret_")

        // 3. Confirm the setup intent with the test PM (simulates Stripe.js frontend step)
        // Use the static confirm(id, params) form — compatible with stripe-java 25.x
        SetupIntent.confirm(
            setupIntentId,
            SetupIntentConfirmParams.builder()
                .setPaymentMethod(pm.id)
                .build()
        )

        return pm.id
    }

    private fun resetChargeToStatus(chargeId: Long, status: String) {
        jdbc.update("UPDATE rent_charges SET status = ? WHERE id = ?", status, chargeId)
    }

    @Test
    fun `full payment flow — save card, pay charge, verify SUCCEEDED and charge PAID`() {
        // Reset charge to PENDING for clean test run
        resetChargeToStatus(RENT_CHARGE_ID, "PENDING")

        // Get Stripe customer ID (will be created when we call setup-intent)
        // We call setup-intent to get the clientSecret, then confirm to get the PM
        val pmId = createConfirmedPaymentMethod()

        // Save the card via our API
        val saveCardResponse = mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("stripePaymentMethodId" to pmId))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.last4") { value("4242") }
        }.andReturn().response.contentAsString

        val savedCardId = objectMapper.readTree(saveCardResponse)["id"].asLong()

        // Pay the rent charge
        val payResponse = mockMvc.post("/api/credit-card-payments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf(
                "rentChargeId" to RENT_CHARGE_ID,
                "savedCardId" to savedCardId,
            ))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("SUCCEEDED") }
        }.andReturn().response.contentAsString

        // Verify rent charge is now PAID
        val paymentId = objectMapper.readTree(payResponse)["id"].asLong()
        mockMvc.get("/api/credit-card-payments/$paymentId") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SUCCEEDED") }
        }

        val chargeStatus = jdbc.queryForObject(
            "SELECT status FROM rent_charges WHERE id = ?", String::class.java, RENT_CHARGE_ID
        )
        assertEquals("PAID", chargeStatus)
    }

    @Test
    fun `declined card — payment recorded as FAILED, charge remains PENDING`() {
        resetChargeToStatus(RENT_CHARGE_ID, "PENDING")

        // Use declined card number
        val declinedPmId = createConfirmedPaymentMethod("4000000000000002")

        val saveResponse = mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("stripePaymentMethodId" to declinedPmId))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andReturn().response.contentAsString

        val savedCardId = objectMapper.readTree(saveResponse)["id"].asLong()

        mockMvc.post("/api/credit-card-payments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf(
                "rentChargeId" to RENT_CHARGE_ID,
                "savedCardId" to savedCardId,
            ))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.failureReason") { isNotEmpty() }
        }

        val chargeStatus = jdbc.queryForObject(
            "SELECT status FROM rent_charges WHERE id = ?", String::class.java, RENT_CHARGE_ID
        )
        assertEquals("PENDING", chargeStatus)
    }

    @Test
    fun `double charge prevention — second payment on same charge returns 409`() {
        resetChargeToStatus(RENT_CHARGE_ID, "PENDING")
        val pmId = createConfirmedPaymentMethod()
        val saveResponse = mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("stripePaymentMethodId" to pmId))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andReturn().response.contentAsString
        val savedCardId = objectMapper.readTree(saveResponse)["id"].asLong()

        // First payment
        mockMvc.post("/api/credit-card-payments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("rentChargeId" to RENT_CHARGE_ID, "savedCardId" to savedCardId))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect { status { isCreated() } }

        // Second payment on same charge — should 409
        mockMvc.post("/api/credit-card-payments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("rentChargeId" to RENT_CHARGE_ID, "savedCardId" to savedCardId))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `refund flow — SUCCEEDED payment can be refunded by PM, charge reverts to PENDING`() {
        resetChargeToStatus(RENT_CHARGE_ID, "PENDING")
        val pmId = createConfirmedPaymentMethod()
        val saveResponse = mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("stripePaymentMethodId" to pmId))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andReturn().response.contentAsString
        val savedCardId = objectMapper.readTree(saveResponse)["id"].asLong()

        val payResponse = mockMvc.post("/api/credit-card-payments") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("rentChargeId" to RENT_CHARGE_ID, "savedCardId" to savedCardId))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andReturn().response.contentAsString
        val paymentId = objectMapper.readTree(payResponse)["id"].asLong()

        // PM issues refund
        mockMvc.post("/api/credit-card-payments/$paymentId/refund") {
            header("Authorization", "Bearer ${pmToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REFUNDED") }
        }

        val chargeStatus = jdbc.queryForObject(
            "SELECT status FROM rent_charges WHERE id = ?", String::class.java, RENT_CHARGE_ID
        )
        assertEquals("PENDING", chargeStatus)
    }
}
```

- [ ] **Step 2: Run integration tests (requires Docker + STRIPE_API_KEY)**

```bash
STRIPE_API_KEY=sk_test_YOUR_KEY ./gradlew integrationTest
```
Expected: All 4 integration tests PASS. Without `STRIPE_API_KEY` set, the tests are skipped.

- [ ] **Step 3: Run all tests**

```bash
./gradlew test
STRIPE_API_KEY=sk_test_YOUR_KEY ./gradlew integrationTest
```
Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 4: Commit**

```bash
git add src/integrationTest/kotlin/com/ender/takehome/CreditCardPaymentIntegrationTest.kt \
        src/test/kotlin/com/ender/takehome/TestFixtures.kt
git commit -m "feat: add Stripe integration tests — full payment, decline, double-charge prevention, refund"
```

---

## Final Verification

- [ ] **Run full test suite one last time**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, no test failures.

- [ ] **Smoke test the running server**

```bash
docker-compose up -d
STRIPE_API_KEY=sk_test_YOUR_KEY ./gradlew bootRun &
sleep 15

# Login as tenant
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice.johnson@email.com","password":"password"}' | jq -r .token)

# Create setup intent
curl -s http://localhost:8080/api/cards/setup-intent \
  -X POST -H "Authorization: Bearer $TOKEN"
# Expected: {"clientSecret":"seti_xxx_secret_yyy"}
```

- [ ] **Final commit**

```bash
git add -A
git status  # Review — should be clean or only new files
git commit -m "feat: complete Stripe credit card payment integration" --allow-empty-message || true
```
