# Retry policy

Only retryable transport failures are retried, with a bounded exponential delay and a clear maximum attempt count.

## Verification

This decision is covered by source structure, tests, or the deployment configuration and should remain reviewable.

