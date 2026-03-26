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
