package com.ender.takehome

import com.ender.takehome.config.JwtService
import com.ender.takehome.model.UserRole
import com.stripe.Stripe
import com.stripe.model.PaymentMethod
import com.stripe.model.SetupIntent
import com.stripe.param.PaymentMethodCreateParams
import com.stripe.param.SetupIntentConfirmParams
import org.junit.jupiter.api.Assertions.assertEquals
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

    /**
     * Creates a real Stripe PaymentMethod with a test card, confirms it via SetupIntent,
     * and returns the PaymentMethod ID — simulating what the frontend would do with Stripe.js.
     */
    private fun createConfirmedPaymentMethod(cardNumber: String = "4242424242424242"): String {
        Stripe.apiKey = stripeApiKey

        // 1. Create a PM with test card credentials
        val pm = PaymentMethod.create(
            PaymentMethodCreateParams.builder()
                .setType(PaymentMethodCreateParams.Type.CARD)
                .setCard(
                    PaymentMethodCreateParams.CardDetails.builder()
                        .setNumber(cardNumber)
                        .setExpMonth(12)
                        .setExpYear(2028)
                        .setCvc("123")
                        .build()
                )
                .build()
        )

        // 2. Get setup intent client secret from our API
        val siResponse = mockMvc.post("/api/cards/setup-intent") {
            header("Authorization", "Bearer ${tenantToken()}")
        }.andReturn().response.contentAsString

        val clientSecret = objectMapper.readTree(siResponse)["clientSecret"].asText()
        val setupIntentId = clientSecret.substringBefore("_secret_")

        // 3. Confirm the setup intent with the test PM (simulates Stripe.js frontend step)
        SetupIntent.retrieve(setupIntentId).confirm(
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
        resetChargeToStatus(RENT_CHARGE_ID, "PENDING")

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
        resetChargeToStatus(RENT_CHARGE_ID, "PENDING")

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
