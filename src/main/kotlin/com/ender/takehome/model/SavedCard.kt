package com.ender.takehome.model

import java.time.Instant

data class SavedCard(
    val id: Long = 0,
    val tenantId: Long,
    val stripePaymentMethodId: String,
    val last4: String,
    val brand: String,
    val expMonth: Int,
    val expYear: Int,
    val createdAt: Instant = Instant.now(),
)
