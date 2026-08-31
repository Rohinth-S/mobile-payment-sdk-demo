package com.clearpay.sdk

/** Replays durable outbox entries after process or network recovery using their original idempotency keys. */
class PaymentReconciler(
    private val outbox: PaymentOutbox,
    private val transport: PaymentTransport,
    private val telemetry: PaymentTelemetry = NoopPaymentTelemetry
) {
    suspend fun reconcile(): ReconciliationReport {
        var succeeded = 0
        var deferred = 0
        for (request in outbox.pending()) {
            val result = runCatching { transport.authorize(request) }.getOrElse { error ->
                telemetry.transportFailure(request, error)
                TransportResult.Rejected("transport_error", error.message ?: "transport failed", true)
            }
            when (result) {
                is TransportResult.Approved -> { outbox.acknowledge(request.idempotencyKey); succeeded++ }
                else -> { deferred++ }
            }
        }
        return ReconciliationReport(attempted = succeeded + deferred, succeeded = succeeded, deferred = deferred)
    }
}

data class ReconciliationReport(val attempted: Int, val succeeded: Int, val deferred: Int)
