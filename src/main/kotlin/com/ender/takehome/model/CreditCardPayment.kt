package com.ender.takehome.model

import java.math.BigDecimal
import java.time.Instant

enum class CreditCardPaymentStatus { INITIATED, SUCCEEDED, FAILED, REFUNDED }

data class CreditCardPayment(
    val id: Long = 0,
    val rentChargeId: Long,
    val savedCardId: Long,
    val stripePaymentIntentId: String,
    val amount: BigDecimal,
    val status: CreditCardPaymentStatus,
    val failureReason: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
