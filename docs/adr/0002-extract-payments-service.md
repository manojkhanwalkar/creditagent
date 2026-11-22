# ADR 0002: Extract a payments-service (proposed 2026-11-22)

## Context

web-server takes payments in process through `PaymentGateway` (`none` or the local `test` gateway).
Stripe Checkout comes next. Putting provider SDKs, webhook secrets and a payments ledger into the
user-facing server widens its attack surface and its PCI scope, and ties payment releases to UI releases.

## Decision

Move payment taking into a `payments-service` (port 8095) that owns `PaymentGateway` (none/test now,
Stripe later), a Payments table and an outbox. web-server keeps the Subscriptions table and its prices,
and stays its only writer. Branch by abstraction, one PR per step:

- **P1** (this PR): harden the seam. `CheckoutRequest(userId, product, amountCents, currency,
  idempotencyKey, returnUrl)` matches the planned service; `returnUrl` is optional until a redirecting
  provider exists. Answers stay `Paid` | `Redirect`, plus declined and unavailable. `available()` is
  cached for 30 s. Add `POST /internal/v1/subscriptions/activations`
  ([contract](../api/subscriptions-internal-v1.yaml)) for the service to call.
- **P2**: the `payments-service` module with the none/test gateways, the Payments table and the outbox.
  Stripe arrives there, never in web-server.
- **P3**: a remote `PaymentGateway` in web-server behind a config flag (in-process default), the
  cut-over, then removal of the in-process gateways.

## Alternatives

- **Stay in the monolith:** least work now, but Stripe secrets, webhooks and the ledger land in the
  public-facing server, and its PCI scope grows with them.
- **Wait for Stripe, then extract:** the provider code would be written twice, and the contract would be
  shaped by one provider. Building the seam first keeps the contract provider-neutral.

## Saga and compensation

An orchestrated saga run by payments-service: take the payment (idempotency key passed through
unchanged), then call the activation endpoint with the `paymentReference`.

- 200 `ACTIVE` or `ALREADY_SAME_PAYMENT`: done. The same payment twice is safe, so retries are too.
- 409 `ALREADY_ACTIVE`: another term is active; **compensate by refunding** the payment. web-server's
  audit keeps `SUBSCRIPTION_STARTED/ALREADY_ACTIVE`, as today.
- 503 (Retry-After), a timeout or a failed connect: retry with backoff and jitter, the same reference.
- 404 or 400: a bug or a deleted account; refund.

**No broker.** The activation step is recorded in payments-service's DynamoDB outbox in the same
write as the payment, and a relay retries it until it gets a final answer. One table and a poller cost
less to run than a broker for one event type, and the endpoint's idempotency makes at-least-once safe.

## Consequences

- One more internal hop per purchase after P3; purchases are rare, so latency is not a concern.
- **Shared token, a known gap:** the call uses the shared `INTERNAL_API_TOKEN`, so any holder can call
  any internal API, including activations. Per-service credentials (or mTLS) are needed before real
  money moves.
- Until P3 the endpoint has no caller; it is off wherever no internal token is configured (404).
- **Rollback:** until P3's cleanup, flip the gateway flag back to in-process; P1 changes no behaviour
  for users. After the cleanup, revert the P3 PR.
