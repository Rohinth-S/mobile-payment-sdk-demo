package com.clearpay.sdk

/** Host-observable instrumentation without coupling the SDK to a logging vendor. */
interface PaymentTelemetry {
    fun stateChanged(request: PaymentRequest, state: PaymentState)
    fun transportFailure(request: PaymentRequest, error: Throwable)
}

object NoopPaymentTelemetry : PaymentTelemetry {
    override fun stateChanged(request: PaymentRequest, state: PaymentState) = Unit
    override fun transportFailure(request: PaymentRequest, error: Throwable) = Unit
}
