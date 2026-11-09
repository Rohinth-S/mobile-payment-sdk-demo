package com.clearpay.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentControllerTest {
    @Test fun retriesTransientFailureThenSucceeds() = runTest {
        var calls = 0
        val transport = object : PaymentTransport { override suspend fun authorize(request: PaymentRequest): TransportResult { calls++; return if (calls == 1) TransportResult.Rejected("timeout", "temporary", true) else TransportResult.Approved("txn_123") } }
        val controller = PaymentController(transport, TestScope(StandardTestDispatcher(testScheduler)))
        controller.start(PaymentRequest(1299, "USD", "order_42", "idem_42")); testScheduler.advanceUntilIdle()
        assertEquals(PaymentState.Succeeded("txn_123"), controller.state.value); assertEquals(2, calls)
    }

    @Test fun doesNotRetryPermanentRejection() = runTest {
        var calls = 0
        val transport = object : PaymentTransport { override suspend fun authorize(request: PaymentRequest): TransportResult { calls++; return TransportResult.Rejected("declined", "insufficient funds", false) } }
        val controller = PaymentController(transport, TestScope(StandardTestDispatcher(testScheduler)))
        controller.start(PaymentRequest(1299, "USD", "order_42", "idem_42")); testScheduler.advanceUntilIdle()
        assertEquals(PaymentState.Failed("declined", "insufficient funds", false), controller.state.value); assertEquals(1, calls)
    }

    @Test fun resumesProviderChallengeWithoutChangingRequestKey() = runTest {
        var confirms = 0
        val transport = object : PaymentTransport {
            override suspend fun authorize(request: PaymentRequest) = TransportResult.ActionRequired("https://issuer.test/challenge")
            override suspend fun confirm(request: PaymentRequest): TransportResult { confirms++; return TransportResult.Approved("txn_after_challenge") }
        }
        val controller = PaymentController(transport, TestScope(StandardTestDispatcher(testScheduler)))
        controller.start(PaymentRequest(1299, "USD", "order_42", "idem_42")); testScheduler.advanceUntilIdle()
        controller.confirmCustomerAction(); testScheduler.advanceUntilIdle()
        assertEquals(PaymentState.Succeeded("txn_after_challenge"), controller.state.value)
        assertEquals("idem_42", controller.currentIdempotencyKey()); assertEquals(1, confirms)
    }

    @Test fun outboxAcknowledgesSuccessfulPaymentAndTelemetrySeesStates() = runTest {
        val outbox = InMemoryPaymentOutbox()
        val states = mutableListOf<PaymentState>()
        val telemetry = object : PaymentTelemetry {
            override fun stateChanged(request: PaymentRequest, state: PaymentState) { states += state }
            override fun transportFailure(request: PaymentRequest, error: Throwable) = Unit
        }
        val controller = PaymentController(object : PaymentTransport {
            override suspend fun authorize(request: PaymentRequest) = TransportResult.Approved("txn_outbox")
        }, TestScope(StandardTestDispatcher(testScheduler)), outbox = outbox, telemetry = telemetry)
        controller.start(PaymentRequest(500, "USD", "order_outbox", "idem_outbox")); testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), outbox.pending())
        assertEquals(PaymentState.Succeeded("txn_outbox"), states.last())
    }
}
