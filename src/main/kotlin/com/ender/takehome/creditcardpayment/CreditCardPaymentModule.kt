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
import java.math.BigDecimal
import java.util.UUID

@Service
class CreditCardPaymentModule(
    private val dataAccess: CreditCardPaymentDataAccess,
    private val stripeClient: StripeClient,
    private val ledgerModule: LedgerModule,
    private val leaseModule: LeaseModule,
    private val cardModule: CardModule,
    @Value("\${stripe.currency:usd}") private val currency: String,
) {

    /**
     * Returns a payment by ID. If [tenantId] is provided, verifies the payment belongs to that tenant
     * (via rent_charge → lease → tenant_id). Pass null for PM access (no ownership restriction).
     */
    fun getPaymentById(id: Long, tenantId: Long?): CreditCardPayment {
        val payment = dataAccess.findPaymentById(id)
            ?: throw ResourceNotFoundException("Credit card payment not found: $id")
        if (tenantId != null) verifyChargeOwnership(payment.rentChargeId, tenantId)
        return payment
    }

    /**
     * Returns payments for a rent charge. If [tenantId] is provided, verifies the charge belongs to
     * that tenant before returning results. Pass null for PM access.
     */
    fun getPaymentsByRentChargeId(rentChargeId: Long, startAfterId: Long?, limit: Int, tenantId: Long?): CursorPage<CreditCardPayment> {
        if (tenantId != null) verifyChargeOwnership(rentChargeId, tenantId)
        val sanitized = CursorPage.sanitizeLimit(limit)
        val items = dataAccess.findPaymentsByRentChargeIdCursor(rentChargeId, startAfterId, sanitized + 1)
        return CursorPage.of(items, sanitized)
    }

    private fun verifyChargeOwnership(rentChargeId: Long, tenantId: Long) {
        val charge = ledgerModule.getChargeById(rentChargeId)
        val lease = leaseModule.getById(charge.leaseId)
        if (lease.tenantId != tenantId)
            throw ResourceNotFoundException("Credit card payment not found")
    }

    /**
     * Initiates a credit card payment for a rent charge.
     *
     * The SELECT FOR UPDATE on the rent charge row prevents concurrent double-charges.
     * The amount is always taken from the rent charge, never from client input.
     * Card declines return a FAILED payment record — they are not API errors.
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
        val payment = dataAccess.savePayment(
            CreditCardPayment(
                rentChargeId = rentChargeId,
                savedCardId = savedCardId,
                // Placeholder PI ID replaced after Stripe responds; UUID prevents uniqueness collision
                stripePaymentIntentId = "pending_${UUID.randomUUID()}",
                amount = charge.amount,
                status = CreditCardPaymentStatus.INITIATED,
            )
        )

        // Get Stripe customer ID (must exist — created during card setup)
        val stripeCustomerId = cardModule.getStripeCustomerId(tenantId)

        return when (val result = stripeClient.createPaymentIntent(
            amountCents = charge.amount.multiply(BigDecimal(100)).toLong(),
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
