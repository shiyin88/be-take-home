package com.ender.takehome

import com.ender.takehome.config.JwtService
import com.ender.takehome.model.UserRole
import com.stripe.Stripe
import com.stripe.model.PaymentMethod
import com.stripe.model.SetupIntent
import com.stripe.param.PaymentMethodCreateParams
import com.stripe.param.SetupIntentConfirmParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

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

    @BeforeEach
    fun setUp() {
        Stripe.apiKey = stripeApiKey
        // Clean payment records so each test starts from a fresh PENDING charge.
        // saved_cards is NOT cleaned — each test creates unique PMs via tok_* tokens.
        jdbc.update("DELETE FROM credit_card_payments WHERE rent_charge_id = ?", RENT_CHARGE_ID)
        jdbc.update("UPDATE rent_charges SET status = 'PENDING' WHERE id = ?", RENT_CHARGE_ID)
    }

    /**
     * Creates a unique Stripe PaymentMethod from a named test token (no raw card numbers,
     * no "raw card data" account permission required), confirms it via SetupIntent, and returns
     * the PaymentMethod ID — simulating what the frontend does with Stripe.js.
     *
     * Each call to PaymentMethod.create() with a tok_* token produces a fresh unique pm_xxx,
     * so there are no uniqueness collisions even when tests reuse the same token name.
     *
     * tok_visa → Visa 4242, always succeeds at both setup and charge.
     */
    private fun createConfirmedPaymentMethod(token: String = "tok_visa"): String {
        // Create a unique PM from a named test token
        val pm = PaymentMethod.create(
            PaymentMethodCreateParams.builder()
                .setType(PaymentMethodCreateParams.Type.CARD)
                .setCard(
                    PaymentMethodCreateParams.CardDetails.builder()
                        .putExtraParam("token", token)
                        .build()
                )
                .build()
        )

        // Get setup intent client secret from our API
        val siResponse = mockMvc.post("/api/cards/setup-intent") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andReturn().response.contentAsString

        val clientSecret = objectMapper.readTree(siResponse)["clientSecret"].asText()
        val setupIntentId = clientSecret.substringBefore("_secret_")

        // Confirm the setup intent with the test PM (simulates Stripe.js frontend step)
        SetupIntent.retrieve(setupIntentId).confirm(
            SetupIntentConfirmParams.builder()
                .setPaymentMethod(pm.id)
                .build()
        )

        return pm.id
    }

    /**
     * Creates a unique PM from tok_chargeCustomerFail — a Stripe test token that attaches
     * to a customer successfully but returns card_declined when charged. Saves it directly
     * via /api/cards (no SetupIntent needed — the setup step is not what we're testing here).
     */
    private fun saveDeclinedCard(): Long {
        val pm = PaymentMethod.create(
            PaymentMethodCreateParams.builder()
                .setType(PaymentMethodCreateParams.Type.CARD)
                .setCard(
                    PaymentMethodCreateParams.CardDetails.builder()
                        .putExtraParam("token", "tok_chargeCustomerFail")
                        .build()
                )
                .build()
        )

        val saveResponse = mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("stripePaymentMethodId" to pm.id))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString

        return objectMapper.readTree(saveResponse)["id"].asLong()
    }

    @Test
    fun `full payment flow — save card, pay charge, verify SUCCEEDED and charge PAID`() {
        val pmId = createConfirmedPaymentMethod()

        val saveCardResponse = mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("stripePaymentMethodId" to pmId))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.last4") { value("4242") }
        }.andReturn().response.contentAsString

        val savedCardId = objectMapper.readTree(saveCardResponse)["id"].asLong()

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
        val savedCardId = saveDeclinedCard()

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
        val pmId = createConfirmedPaymentMethod()
        val saveResponse = mockMvc.post("/api/cards") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("stripePaymentMethodId" to pmId))
            header("Authorization", "Bearer ${tenantToken()}")
        }.andReturn().response.contentAsString
        val savedCardId = objectMapper.readTree(saveResponse)["id"].asLong()

        // First payment succeeds
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
