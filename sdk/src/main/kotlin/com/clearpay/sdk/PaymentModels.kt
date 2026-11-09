package com.clearpay.sdk

import java.util.UUID

data class PaymentRequest(
    val amountMinor: Long,
    val currency: String,
    val merchantReference: String,
    val idempotencyKey: String = UUID.randomUUID().toString()
) {
    init {
        require(amountMinor > 0) { "amountMinor must be positive" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "currency must be ISO-4217 uppercase" }
        require(merchantReference.isNotBlank()) { "merchantReference is required" }
    }
}

sealed interface PaymentState {
    data object Idle : PaymentState
    data class Authorizing(val attempt: Int) : PaymentState
    data class RequiresAction(val actionUrl: String) : PaymentState
    data class Succeeded(val transactionId: String) : PaymentState
    data class Failed(val code: String, val message: String, val retryable: Boolean) : PaymentState
}

data class PaymentReceipt(val transactionId: String, val amountMinor: Long, val currency: String)

interface PaymentTransport { suspend fun authorize(request: PaymentRequest): TransportResult }
sealed interface TransportResult {
    data class Approved(val transactionId: String) : TransportResult
    data class ActionRequired(val url: String) : TransportResult
    data class Rejected(val code: String, val message: String, val retryable: Boolean) : TransportResult
}
