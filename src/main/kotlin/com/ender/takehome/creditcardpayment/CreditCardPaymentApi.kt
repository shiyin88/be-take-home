package com.ender.takehome.creditcardpayment

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.CreateCreditCardPaymentRequest
import com.ender.takehome.dto.response.CreditCardPaymentResponse
import com.ender.takehome.dto.response.CursorPage
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/credit-card-payments")
class CreditCardPaymentApi(private val paymentModule: CreditCardPaymentModule) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TENANT')")
    fun pay(@Valid @RequestBody request: CreateCreditCardPaymentRequest): CreditCardPaymentResponse {
        val tenantId = UserPrincipal.current().requireTenantId()
        return CreditCardPaymentResponse.from(
            paymentModule.pay(request.rentChargeId!!, request.savedCardId!!, tenantId)
        )
    }

    @GetMapping("/{id}")
    fun getPayment(@PathVariable id: Long): CreditCardPaymentResponse =
        CreditCardPaymentResponse.from(paymentModule.getPaymentById(id))

    @GetMapping(params = ["rentChargeId"])
    fun getPaymentsByCharge(
        @RequestParam rentChargeId: Long,
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<CreditCardPaymentResponse> {
        val page = paymentModule.getPaymentsByRentChargeId(rentChargeId, startAfterId, limit)
        return CursorPage(page.content.map { CreditCardPaymentResponse.from(it) }, page.hasMore)
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasRole('PROPERTY_MANAGER')")
    fun refund(@PathVariable id: Long): CreditCardPaymentResponse =
        CreditCardPaymentResponse.from(paymentModule.refund(id))

    private fun UserPrincipal.requireTenantId(): Long =
        tenantId ?: error("TENANT principal missing tenantId")
}
