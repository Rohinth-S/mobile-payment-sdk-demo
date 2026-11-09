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
}
