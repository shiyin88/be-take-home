package com.ender.takehome.card

import com.ender.takehome.generated.tables.CreditCardPayments.CREDIT_CARD_PAYMENTS
import com.ender.takehome.generated.tables.SavedCards.SAVED_CARDS
import com.ender.takehome.generated.tables.StripeCustomers.STRIPE_CUSTOMERS
import com.ender.takehome.generated.tables.records.SavedCardsRecord
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
            .and(cardCursorCondition(startAfterId))
            .orderBy(SAVED_CARDS.ID)
            .limit(limit)
            .fetch()
            .map { it.toModel() }

    private fun cardCursorCondition(startAfterId: Long?) =
        if (startAfterId != null) SAVED_CARDS.ID.gt(startAfterId) else DSL.noCondition()

    fun saveCard(card: SavedCard): SavedCard {
        check(card.id == 0L) { "saveCard called with non-zero id: ${card.id}" }
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

    fun hasInitiatedPaymentForCard(savedCardId: Long): Boolean =
        dsl.fetchCount(
            dsl.selectFrom(CREDIT_CARD_PAYMENTS)
                .where(CREDIT_CARD_PAYMENTS.SAVED_CARD_ID.eq(savedCardId))
                .and(CREDIT_CARD_PAYMENTS.STATUS.eq("INITIATED"))
        ) > 0

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
