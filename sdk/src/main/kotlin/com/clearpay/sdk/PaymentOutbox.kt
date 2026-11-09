package com.clearpay.sdk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable queue boundary for hosts that need crash-safe payment retries. */
interface PaymentOutbox {
    suspend fun enqueue(request: PaymentRequest)
    suspend fun pending(): List<PaymentRequest>
    suspend fun acknowledge(idempotencyKey: String)
}

/** Deterministic implementation for demos and tests; apps can back this with Room/DataStore. */
class InMemoryPaymentOutbox : PaymentOutbox {
    private val lock = Mutex()
    private val entries = linkedMapOf<String, PaymentRequest>()

    override suspend fun enqueue(request: PaymentRequest) = lock.withLock { entries.putIfAbsent(request.idempotencyKey, request) }
    override suspend fun pending(): List<PaymentRequest> = lock.withLock { entries.values.toList() }
    override suspend fun acknowledge(idempotencyKey: String) = lock.withLock { entries.remove(idempotencyKey) }
}
