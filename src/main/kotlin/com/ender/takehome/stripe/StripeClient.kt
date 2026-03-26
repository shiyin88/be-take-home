package com.ender.takehome.stripe

interface StripeClient {
    /** Creates a Stripe Customer and returns their stripeCustomerId (e.g. "cus_xxx"). */
    fun createCustomer(email: String): String

    /** Creates a SetupIntent for the given customer and returns the clientSecret. */
    fun createSetupIntent(stripeCustomerId: String): String

    /** Fetches card details for a PaymentMethod ID. Throws if not found or not a card. */
    fun retrievePaymentMethodDetails(paymentMethodId: String): PaymentMethodDetails

    /** Attaches a PaymentMethod to a Customer in Stripe. */
    fun attachPaymentMethod(paymentMethodId: String, stripeCustomerId: String)

    /** Detaches a PaymentMethod from its Customer in Stripe. */
    fun detachPaymentMethod(paymentMethodId: String)

    /**
     * Creates and immediately confirms a PaymentIntent.
     * Returns [StripePaymentResult.Succeeded] on success.
     * Returns [StripePaymentResult.Failed] if the card is declined.
     * Throws [com.ender.takehome.exception.PaymentFailedException] on Stripe service errors.
     */
    fun createPaymentIntent(
        amountCents: Long,
        currency: String,
        paymentMethodId: String,
        stripeCustomerId: String,
    ): StripePaymentResult

    /** Issues a full refund for the given PaymentIntent. Throws [com.ender.takehome.exception.PaymentFailedException] on error. */
    fun refundPaymentIntent(stripePaymentIntentId: String)
}

data class PaymentMethodDetails(
    val last4: String,
    val brand: String,
    val expMonth: Int,
    val expYear: Int,
)

sealed class StripePaymentResult {
    data class Succeeded(val paymentIntentId: String) : StripePaymentResult()
    data class Failed(val paymentIntentId: String?, val reason: String) : StripePaymentResult()
}
