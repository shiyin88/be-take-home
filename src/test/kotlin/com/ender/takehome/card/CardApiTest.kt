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
