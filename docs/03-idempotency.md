# Idempotency

The request key is preserved across retry and customer-action recovery so provider retries cannot create duplicate authorizations.

## Verification

This decision is covered by source structure, tests, or the deployment configuration and should remain reviewable.

