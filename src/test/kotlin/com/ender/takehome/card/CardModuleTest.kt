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
        verify(exactly = 0) { stripeClient.detachPaymentMethod(any()) }
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
