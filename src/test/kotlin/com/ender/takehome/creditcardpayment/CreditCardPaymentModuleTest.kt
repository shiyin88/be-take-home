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
        every { dataAccess.savePayment(any()) } answers { firstArg<CreditCardPayment>().copy(id = 10L) }
        every { dataAccess.updatePaymentIntentId(10L, "pi_success") } just Runs
        every { dataAccess.updatePaymentStatus(10L, CreditCardPaymentStatus.SUCCEEDED, null) } returns
            TestFixtures.creditCardPayment(id = 10L, status = CreditCardPaymentStatus.SUCCEEDED)
        every { ledgerModule.updateChargeStatus(charge.id, RentChargeStatus.PAID) } just Runs

        val result = module.pay(charge.id, savedCard.id, tenant.id)

        assertEquals(CreditCardPaymentStatus.SUCCEEDED, result.status)
        verify(exactly = 1) { ledgerModule.getChargeByIdForUpdate(charge.id) }
        verify(exactly = 1) { dataAccess.savePayment(match { it.status == CreditCardPaymentStatus.INITIATED }) }
        verify(exactly = 1) { dataAccess.updatePaymentStatus(10L, CreditCardPaymentStatus.SUCCEEDED, null) }
        verify(exactly = 1) { ledgerModule.updateChargeStatus(charge.id, RentChargeStatus.PAID) }
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
        // Rent charge status must NOT be updated
        verify(exactly = 0) { ledgerModule.updateChargeStatus(any(), any()) }
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
        every { dataAccess.updatePaymentIntentId(12L, "pi_over") } just Runs
        every { dataAccess.updatePaymentStatus(12L, CreditCardPaymentStatus.SUCCEEDED, null) } returns
            TestFixtures.creditCardPayment(id = 12L, status = CreditCardPaymentStatus.SUCCEEDED)
        every { ledgerModule.updateChargeStatus(charge.id, RentChargeStatus.PAID) } just Runs

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
        every { cardModule.getStripeCustomerId(tenant.id) } returns "cus_existing"
    }
}
