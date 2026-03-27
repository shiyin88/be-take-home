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
        every { paymentModule.getPaymentById(99L) } throws ResourceNotFoundException("Credit card payment not found: 99")

        mockMvc.get("/api/credit-card-payments/99") {
            header("Authorization", "Bearer ${tenantToken(tenantId = 2L)}")
        }.andExpect { status { isNotFound() } }
    }
}
