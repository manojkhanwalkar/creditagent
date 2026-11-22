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

**Status:** N1 merged (#40); N2 in review (#41); **N3 done**: `RemoteNotificationGateway` (web-server) and
`RemoteEmailSender` (alert-notifier) call the service when `NOTIFICATIONS_MODE=remote`; the default and
compose stay `local`. To flip a deployment, set `NOTIFICATIONS_MODE=remote`, `NOTIFICATIONS_URL` (the
service, e.g. `http://notification-service:8094`) and the shared `INTERNAL_API_TOKEN` on web-server and
alert-notifier, with the service running; set it back to `local` to roll back. Answers map as: 202 (`SENT`
or `UNCERTAIN`) sent; 422 refused (the sign-in send is given back; the alert claims are released); 429,
5xx, a timeout or a failed connect unknown (the code stays valid and the send counts; the claims are
released and a later duplicate is accepted); 400, 401, 404, 409 a caller bug (500). web-server retries
only a failed connect, with the same key and `credit.retry` backoff and jitter, never a timeout or a 5xx, so
the service stays the one retry layer. The alert key is `alert:<HMAC of the address>:<run time>`. Sign-in
codes now carry `validSeconds`, so a TTL that is not whole minutes is exact.

The sign-in key is `otp:<challengeId>:<send number>`. A send the provider clearly refused is given back,
so the next send reuses its number: the service must remember only keys it accepted or may have delivered.

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
