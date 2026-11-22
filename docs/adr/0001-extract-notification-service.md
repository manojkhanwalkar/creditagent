# ADR 0001: Extract a notification-service (accepted 2026-11-21)

## Context

web-server sends sign-in codes (SES email, SNS SMS) and alert-notifier sends dark-web alert emails. Each
holds its own provider clients, credentials, templates and retry rules, so every provider or channel
change is made twice, and both need SES/SNS permissions.

## Decision

Move delivery into one `notification-service` that owns templates, provider clients and idempotency.
Callers send a template, channel, destination, params and idempotency key; they never render text.
Branch by abstraction, one PR per step: **N1** a `NotificationGateway` seam in web-server
(`LocalNotificationGateway` sends in process, no behaviour change); **N2** the service module; **N3**
remote clients in web-server and alert-notifier behind `NOTIFICATIONS_MODE` (`local` default); **N4**
cut-over and removal of the old senders.

**Status:** N1 merged (#40); N2 merged (#41); **N3 done**: `RemoteNotificationGateway` (web-server) and
`RemoteEmailSender` (alert-notifier) call the service when `NOTIFICATIONS_MODE=remote`; the default and
compose stay `local`. To flip a deployment, set `NOTIFICATIONS_MODE=remote`, `NOTIFICATIONS_URL` (the
service, e.g. `http://notification-service:8094`) and the shared `INTERNAL_API_TOKEN` on web-server and
alert-notifier, with the service running; set it back to `local` to roll back. Answers map as: 202 (`SENT`
or `UNCERTAIN`) sent; 422 refused (the sign-in send is given back; the alert claims are released); 429,
5xx, a timeout or a failed connect unknown (the code stays valid and the send counts; the claims are
released and a later duplicate is accepted); 400, 401, 404, 409 a caller bug (500). web-server retries
only a failed connect, with the same key and `credit.retry` backoff and jitter, never a timeout or a 5xx, so
the service stays the one retry layer. Connects time out after `NOTIFICATIONS_CONNECT_TIMEOUT` (2 s) and answers
after `NOTIFICATIONS_TIMEOUT` (10 s), so a sign-in waits at most about 14.5 s (two failed connects, the
backoff, one full try). `NOTIFICATIONS_URL` must be https unless it names this machine or a compose service. The alert key is `alert:<HMAC of the address>:<run time>`. Sign-in
codes now carry `validSeconds`, so a TTL that is not whole minutes is exact.

The sign-in key is `otp:<challengeId>:<send number>`. A send the provider clearly refused is given back,
so the next send reuses its number: the service must remember only keys it accepted or may have delivered.

### Service contract and data (N2)

- **Contract:** `POST /internal/v1/notifications` with `Idempotency-Key` (at most 128 characters) and
  `{channel, to, template, params}`. The service owns the templates; callers never send text.
  `OTP_SIGN_IN` takes `validSeconds`, not minutes, so the text is exact; N3 switches N1's
  `validMinutes` to seconds.
- **Outcomes:** a clear provider refusal (a 4xx that is not throttling, or an SMTP recipient rejected
  outright) answers 422 and frees the key, so N1's send-number rollback can reuse it. An unknown outcome
  (timeout, 5xx, throttling) answers 503 and keeps the key as `UNCERTAIN`: a repeat answers 202
  `UNCERTAIN` and sends nothing, and a new attempt needs a new key. A repeat of a sent key answers its
  original 202; the same key with a different body is 409.
- **Lease:** the key is reserved as `PENDING` with a 60 s lease before the provider call. If the service
  stops before recording the outcome, the same request may take the key over once the lease ends and send
  again: at-least-once in that window only, at-most-once otherwise. The lease and the shutdown phase must
  exceed the longest send (3 DynamoDB timeouts plus the longer of the provider and SMTP limits, which is
  checked at start-up); SMTP gets an overall limit per send.
- **Data:** table `credit-agent-notifications`, a 30-day TTL on `expiresAt`. The stored key and the
  request hash are HMAC-SHA256 values keyed by `NOTIFICATIONS_HASH_KEY` (falling back to a key derived
  from `INTERNAL_API_TOKEN`), so neither a challenge id nor a code can be recovered by guessing. Changing
  that key re-keys new hashes: repeats of earlier keys are not recognised for up to 30 days. The masked
  destination is pseudonymised and kept for 30 days; account deletion does not reach it. Codes, full
  addresses and message text are never stored or logged.

## Alternatives

- **Stay in the monolith:** least work; provider logic stays duplicated.
- **Modular boundary only** (shared library): no duplication, but each service still holds provider
  credentials and deploys delivery with itself.

## Consequences

- After cut-over, login depends on a second service; an outage blocks sign-in codes. Timeouts, safe
  retries and the existing 503 + Retry-After answer contain it.
- Calls reuse the shared `INTERNAL_API_TOKEN`: a known gap (any holder can call any internal API).
- No broker: synchronous HTTP, so a sign-in learns at once whether its code went.
- Rollback: `NOTIFICATIONS_MODE=local` until N4's cleanup; after it, revert the N4 PR.
