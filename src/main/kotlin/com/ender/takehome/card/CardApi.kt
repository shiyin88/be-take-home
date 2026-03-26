package com.ender.takehome.card

import com.ender.takehome.config.UserPrincipal
import com.ender.takehome.dto.request.SaveCardRequest
import com.ender.takehome.dto.response.CursorPage
import com.ender.takehome.dto.response.SavedCardResponse
import com.ender.takehome.dto.response.SetupIntentResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/cards")
class CardApi(private val cardModule: CardModule) {

    @PostMapping("/setup-intent")
    @PreAuthorize("hasRole('TENANT')")
    fun createSetupIntent(): SetupIntentResponse {
        val tenantId = UserPrincipal.current().requireTenantId()
        return SetupIntentResponse(cardModule.createSetupIntent(tenantId))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TENANT')")
    fun saveCard(@Valid @RequestBody request: SaveCardRequest): SavedCardResponse {
        val tenantId = UserPrincipal.current().requireTenantId()
        return SavedCardResponse.from(cardModule.saveCard(tenantId, request.stripePaymentMethodId))
    }

    @GetMapping
    @PreAuthorize("hasRole('TENANT')")
    fun listCards(
        @RequestParam(required = false) startAfterId: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPage<SavedCardResponse> {
        val tenantId = UserPrincipal.current().requireTenantId()
        val page = cardModule.listCards(tenantId, startAfterId, limit)
        return CursorPage(page.content.map { SavedCardResponse.from(it) }, page.hasMore)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('TENANT')")
    fun deleteCard(@PathVariable id: Long) {
        val tenantId = UserPrincipal.current().requireTenantId()
        cardModule.deleteCard(id, tenantId)
    }

    private fun UserPrincipal.requireTenantId(): Long =
        tenantId ?: error("TENANT principal missing tenantId")
}
