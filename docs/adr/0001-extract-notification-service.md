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
