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
    private val maxAttempts: Int = 3,
    private val outbox: PaymentOutbox = InMemoryPaymentOutbox(),
    private val telemetry: PaymentTelemetry = NoopPaymentTelemetry
) {
    private val mutableState = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val state: StateFlow<PaymentState> = mutableState.asStateFlow()
    private var activeJob: Job? = null
    private var activeKey: String? = null
    private var activeRequest: PaymentRequest? = null

    fun start(request: PaymentRequest) {
        check(activeJob?.isActive != true) { "A payment is already in progress" }
        activeKey = request.idempotencyKey
        activeRequest = request
        activeJob = scope.launch {
            outbox.enqueue(request)
            repeat(maxAttempts) { zeroBased ->
                val attempt = zeroBased + 1
                setState(request, PaymentState.Authorizing(attempt))
                when (val response = runCatching { transport.authorize(request) }.getOrElse { error ->
                    telemetry.transportFailure(request, error)
                    TransportResult.Rejected("transport_error", error.message ?: "transport failed", true)
                }) {
                    is TransportResult.Approved -> { outbox.acknowledge(request.idempotencyKey); setState(request, PaymentState.Succeeded(response.transactionId)); return@launch }
                    is TransportResult.ActionRequired -> { setState(request, PaymentState.RequiresAction(response.url)); return@launch }
                    is TransportResult.Rejected -> {
                        if (!response.retryable || attempt == maxAttempts) { setState(request, PaymentState.Failed(response.code, response.message, response.retryable)); return@launch }
                        delay(backoffMs(attempt))
                    }
                }
            }
        }
    }

    /** Resumes a provider challenge after the host app completes customer verification. */
    fun confirmCustomerAction() {
        val request = activeRequest ?: error("No payment is waiting for customer action")
        check(mutableState.value is PaymentState.RequiresAction) { "Payment is not waiting for customer action" }
        check(activeJob?.isActive != true) { "A payment operation is already in progress" }
        activeJob = scope.launch {
            setState(request, PaymentState.Authorizing(1))
            when (val response = transport.confirm(request)) {
                is TransportResult.Approved -> { outbox.acknowledge(request.idempotencyKey); setState(request, PaymentState.Succeeded(response.transactionId)) }
                is TransportResult.ActionRequired -> setState(request, PaymentState.RequiresAction(response.url))
                is TransportResult.Rejected -> setState(request, PaymentState.Failed(response.code, response.message, response.retryable))
            }
        }
    }

    fun cancel() { activeJob?.cancel(); activeJob = null; activeKey = null; activeRequest = null; mutableState.value = PaymentState.Idle }
    fun currentIdempotencyKey(): String? = activeKey
    private fun backoffMs(attempt: Int) = min(5000L, 250L * (1L shl (attempt - 1)))
    private fun setState(request: PaymentRequest, next: PaymentState) { mutableState.value = next; telemetry.stateChanged(request, next) }
}
