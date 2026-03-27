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
            .and(paymentCursorCondition(startAfterId))
            .orderBy(CREDIT_CARD_PAYMENTS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    fun savePayment(payment: CreditCardPayment): CreditCardPayment {
        check(payment.id == 0L) { "savePayment called with non-zero id: ${payment.id}" }
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

    private fun paymentCursorCondition(startAfterId: Long?) =
        if (startAfterId != null) CREDIT_CARD_PAYMENTS.ID.gt(startAfterId) else DSL.noCondition()

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
