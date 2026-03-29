package com.ender.takehome.stripe

import com.ender.takehome.exception.PaymentFailedException
import com.stripe.Stripe
import com.stripe.exception.CardException
import com.stripe.exception.StripeException
import com.stripe.model.Customer
import com.stripe.model.PaymentIntent
import com.stripe.model.PaymentMethod
import com.stripe.model.Refund
import com.stripe.model.SetupIntent
import com.stripe.param.CustomerCreateParams
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.PaymentMethodAttachParams
import com.stripe.param.RefundCreateParams
import com.stripe.param.SetupIntentCreateParams
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class StripeClientImpl(@Value("\${stripe.api-key}") apiKey: String) : StripeClient {

    init {
        Stripe.apiKey = apiKey
    }

    override fun createCustomer(email: String): String {
        return try {
            val params = CustomerCreateParams.builder().setEmail(email).build()
            Customer.create(params).id
        } catch (e: StripeException) {
            throw PaymentFailedException("Failed to create Stripe customer: ${e.message}", e)
        }
    }

    override fun createSetupIntent(stripeCustomerId: String): String {
        return try {
            val params = SetupIntentCreateParams.builder()
                .setCustomer(stripeCustomerId)
                .addPaymentMethodType("card")
                .build()
            SetupIntent.create(params).clientSecret
                ?: throw PaymentFailedException("SetupIntent created but clientSecret was null")
        } catch (e: PaymentFailedException) {
            throw e
        } catch (e: StripeException) {
            throw PaymentFailedException("Failed to create SetupIntent: ${e.message}", e)
        }
    }

    override fun retrievePaymentMethodDetails(paymentMethodId: String): PaymentMethodDetails {
        return try {
            val pm = PaymentMethod.retrieve(paymentMethodId)
            val card = pm.card ?: throw IllegalArgumentException("Payment method $paymentMethodId is not a card")
            PaymentMethodDetails(
                last4 = card.last4,
                brand = card.brand,
                expMonth = card.expMonth.toInt(),
                expYear = card.expYear.toInt(),
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: StripeException) {
            throw PaymentFailedException("Failed to retrieve payment method: ${e.message}", e)
        }
    }

    override fun attachPaymentMethod(paymentMethodId: String, stripeCustomerId: String) {
        try {
            val pm = PaymentMethod.retrieve(paymentMethodId)
            pm.attach(PaymentMethodAttachParams.builder().setCustomer(stripeCustomerId).build())
        } catch (e: StripeException) {
            throw PaymentFailedException("Failed to attach payment method: ${e.message}", e)
        }
    }

    override fun detachPaymentMethod(paymentMethodId: String) {
        try {
            PaymentMethod.retrieve(paymentMethodId).detach()
        } catch (e: StripeException) {
            throw PaymentFailedException("Failed to detach payment method: ${e.message}", e)
        }
    }

    override fun createPaymentIntent(
        amountCents: Long,
        currency: String,
        paymentMethodId: String,
        stripeCustomerId: String,
    ): StripePaymentResult {
        val params = PaymentIntentCreateParams.builder()
            .setAmount(amountCents)
            .setCurrency(currency)
            .setCustomer(stripeCustomerId)
            .setPaymentMethod(paymentMethodId)
            .addPaymentMethodType("card")
            .setConfirm(true)
            .setErrorOnRequiresAction(true)
            .build()

        return try {
            val intent = PaymentIntent.create(params)
            StripePaymentResult.Succeeded(intent.id)
        } catch (e: CardException) {
            val piId = e.stripeError?.paymentIntent?.id
            StripePaymentResult.Failed(piId, e.userMessage ?: e.message ?: "Card declined")
        } catch (e: StripeException) {
            throw PaymentFailedException("Stripe service error: ${e.message}", e)
        }
    }

    override fun refundPaymentIntent(stripePaymentIntentId: String) {
        val params = RefundCreateParams.builder()
            .setPaymentIntent(stripePaymentIntentId)
            .build()
        try {
            Refund.create(params)
        } catch (e: StripeException) {
            throw PaymentFailedException("Refund failed: ${e.message}", e)
        }
    }
}
