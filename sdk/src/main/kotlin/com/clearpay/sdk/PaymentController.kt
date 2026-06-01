package com.clearpay.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

/** Coordinates one payment attempt at a time and guarantees idempotency at the SDK boundary. */
class PaymentController(
    private val transport: PaymentTransport,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val maxAttempts: Int = 3
) {
    private val mutableState = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val state: StateFlow<PaymentState> = mutableState.asStateFlow()
    private var activeJob: Job? = null
    private var activeKey: String? = null

    fun start(request: PaymentRequest) {
        check(activeJob?.isActive != true) { "A payment is already in progress" }
        activeKey = request.idempotencyKey
        activeJob = scope.launch {
            repeat(maxAttempts) { zeroBased ->
                val attempt = zeroBased + 1
                mutableState.value = PaymentState.Authorizing(attempt)
                when (val response = transport.authorize(request)) {
                    is TransportResult.Approved -> { mutableState.value = PaymentState.Succeeded(response.transactionId); return@launch }
                    is TransportResult.ActionRequired -> { mutableState.value = PaymentState.RequiresAction(response.url); return@launch }
                    is TransportResult.Rejected -> {
                        if (!response.retryable || attempt == maxAttempts) { mutableState.value = PaymentState.Failed(response.code, response.message, response.retryable); return@launch }
                        delay(backoffMs(attempt))
                    }
                }
            }
        }
    }

    fun cancel() { activeJob?.cancel(); activeJob = null; activeKey = null; mutableState.value = PaymentState.Idle }
    fun currentIdempotencyKey(): String? = activeKey
    private fun backoffMs(attempt: Int) = min(5000L, 250L * (1L shl (attempt - 1)))
}
