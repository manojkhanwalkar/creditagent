# CreditAgent

A web app that shows a credit score as a coloured ring, plus a pair of A2A agents that turn a credit report into recommendations for raising the score.

* **Login:** users register by uploading a photo of their Social Security card, which a (dummy) **KYC service** checks and reads the SSN from; a blurred photo is refused with a request to retry. Registration also asks for an email address and a mobile number, both required. Login takes two factors: the password, then a 6-digit one-time code sent by email (Amazon SES) or text message (Amazon SNS), whichever the user picks (see [Sign-in codes](#sign-in-codes)). Accounts, login challenges and sessions are in DynamoDB, and the score API needs the JWT issued at login. A user can only see the credit report for their own SSN; asking for any other SSN gets 403. Sessions end on logout or after 5 idle minutes.
* **Web flow:** browser → Spring Boot web server → Credit Report **MCP** server (reads the Equifax, Experian and TransUnion reports; the **Normalized Report Generator** merges them) → Score Calculator → score ring.
* **Agent flow:** Client Agent → (**A2A**) → Credit Intelligence Agent → MCP server + Score Calculator → recommendations.
* **Offers:** at login, the web server asks the **Offers** service for up to 10 credit card offers ranked for the user's last credit score and the ZIP code they registered with, and the UI shows them. See [Card offers](#card-offers).
* **Chat:** after login, a chat widget answers questions about the user's own credit report. The web server sends each question, with the user's report, to the **User Advisor Agent**, which keeps a short in-memory conversation per login and puts input and output guardrails around a placeholder for an LLM. See [Chat](#chat).
* **Claude connector:** Claude → (**MCP**, OAuth 2.1) → `claude-connector` → the same services. A user connects CreditAgent in Claude, signs in on the web server, and Claude can then read *their own* score, report and advice, and lock or unlock their report. See [Use it from Claude](#use-it-from-claude).

See [docs/architecture.md](docs/architecture.md) for the diagram and design notes, and [docs/security-review.md](docs/security-review.md) before putting real data anywhere near this.

> ⚠️ **Demo only.** The KYC service is a stand-in: it reads the SSN from a label in its own sample cards, not by OCR, so it proves nothing about identity. Users log in to the web UI, but the services behind it, and the agent flow, have no authentication, and everything talks over plain HTTP. Use synthetic SSNs only.

## Stack

* Java 26, Spring Boot 4.1
* LangChain4j 1.23: MCP client, `McpServer` for MCP tools, agentic A2A client, and an optional Anthropic chat model
* a2a-java SDK 1.4 (A2A server)
* Maven multi-module build; Node 22 for the UI unit tests (no npm dependencies)

## Modules and ports

| Module | Port | What it does |
|---|---|---|
| `web-server` | 8080 | UI, `GET /api/v1/offers` (the user's card offers; also returned by login), `GET /api/v1/alerts` (dark-web alerts for the user's email; details for ID Protection subscribers only; also returned by login), `GET`/`POST /api/v1/subscriptions` (the user's subscriptions; buy a year of ID Protection; also returned by login), `GET /api/v1/subscriptions/plans` (prices, no sign-in), `POST /api/v1/chat` (`{ssn, message}`, needs the JWT; own SSN only), `POST /api/v1/auth/register` (multipart, with the `ssnCard` photo and `mobilePhone`), `POST /api/v1/auth/login` (password → challenge), `POST /api/v1/auth/login/otp` (send a code), `POST /api/v1/auth/login/verify` (code → JWT), `POST /api/v1/auth/logout`, `GET /api/v1/auth/session`, `GET`/`PUT /api/v1/credit-report/status` (lock or unlock the user's own report; a locked report is refused to third parties), internal `POST /internal/v1/report-locks/lookup` and `POST /internal/v1/subscriptions/activations` (need `INTERNAL_API_TOKEN`), and `POST /api/v1/credit-score` (needs `Authorization: Bearer <jwt>`; only the user's own SSN) |
| `score-calculator` | 8081 | `POST /api/v1/scores` (FICO-style five-factor scorecard, 300–850) |
| `credit-report-mcp-server` | 8082 | MCP at `/mcp`; resource `credit-report://ssn/{ssn}`, tool `get_credit_report`. Reads all three bureau reports and returns NRG's merged report |
| `credit-report-generator` | 8083 | `POST /api/v1/reports` (needs the internal token); stores one report per bureau, each in that bureau's format, in the `CreditReports` DynamoDB table (encrypted) |
| `normalized-report-generator` | 8084 | NRG: `POST /api/v1/normalized-reports`; merges the three bureau reports into one |
| `credit-intelligence-agent` | 8085 | A2A agent; card at `/.well-known/agent-card.json` |
| `client-agent` | 8086 | `POST /api/v1/advice` (needs `Authorization: Bearer <INTERNAL_API_TOKEN>`); calls the agent over A2A |
| `offers-service` | 8090 | Card offers for a score and ZIP code: MCP at `/mcp` (tool `get_credit_card_offers`), and `POST /api/v1/offers` (`{score, zip}`), which calls that tool through the MCP server. Ranks up to 10 offers from a placeholder marketplace |
| `dark-web-monitor` | 8091 | Runs continuously: every `DARKWEB_SCAN_INTERVAL` it checks registered users' emails against known dark-web locations and writes each exposure to the `Alerts` DynamoDB table. The source is a placeholder that contacts nothing |
| `alert-notifier` | 8093 | Once a day (08:00 UTC) emails each ID Protection subscriber whose address was found in the last 24 hours: one email per user, no locations. Sent through `notification-service` (compose: lands in Mailpit at http://localhost:8025); without `INTERNAL_API_TOKEN` it sends nothing |
| `notification-service` | 8094 | Internal only (service token): `POST /internal/v1/notifications` sends one templated email (SES or SMTP) or SMS (SNS), once per `Idempotency-Key`. Sends every sign-in code (web-server) and dark-web alert (alert-notifier); see [Notifications](#notifications) |
| `alerts-service` | 8092 | Internal only (service token): `POST /internal/v1/alerts/lookup` (`{email}`) answers whether the `Alerts` table holds a record for that email, and where it was found |
| `user-advisor-agent` | 8089 | Chat back end, internal only (service token): `POST /internal/v1/conversations/{id}/messages`, `DELETE /internal/v1/conversations/{id}`. Input and output guardrails, a placeholder LLM, per-conversation memory in RAM |
| `claude-connector` | 8088 | Remote MCP server for Claude at `/mcp` (OAuth 2.1 protected resource); tools `get_credit_score`, `get_credit_report`, `get_credit_advice`, `get_report_lock_status`, `set_report_lock`. The web server is its authorization server (`/oauth/*`, `/.well-known/oauth-authorization-server`) |
| `kyc-service` | 8087 | Dummy KYC provider: `POST /api/v1/ssn-verifications` (multipart `document`) refuses blurred or non-card photos and returns the SSN on the card; `GET /api/v1/sample-cards/{ssn}?blur=N` makes demo cards when enabled |
| `common`, `service-clients`, `web-support`, `alerts-store`, `subscription-store`, `credit-report-store` | — | Shared libraries (`alerts-store`: the `Alerts` table, used by the monitor and the alerts service; `subscription-store`: the Subscriptions table, written by the web server and read by the notifier; `credit-report-store`: the encrypted `CreditReports` table, written by the generator and read by the MCP server) |

## Build and test

```bash
mvn -B verify                 # compile, unit tests, MCP and A2A contract tests, coverage
(cd web-server && npm test)   # score-ring unit tests
.github/scripts/coverage.sh   # line coverage per module and in total
```

**Coverage.** The target is 95% line coverage for the project. `mvn verify` fails if any module drops below 90% (`coverage.module.minimum`), and CI fails if the project as a whole drops below 95%, with a per-module table in the job summary. Spring Boot `*Application` launchers are excluded. The DynamoDB Local tests need Docker, so local figures for `web-server` are a little lower than CI's.

## Run locally with Docker

```bash
mvn -B package -DskipTests
cp .env.example .env          # set CREDIT_FILE_HMAC_KEY, CREDIT_REPORT_ENCRYPTION_KEY, JWT_SECRET, SSN_HMAC_KEY and INTERNAL_API_TOKEN (openssl rand -hex 32 each)
docker compose up --build
```

Then:

* download a sample SSN card for one of the demo SSNs from `.env`, and a blurred one to see the retry:
  ```bash
  curl -o card.png localhost:8087/api/v1/sample-cards/123-45-6789
  curl -o blurry.png 'localhost:8087/api/v1/sample-cards/123-45-6789?blur=4'
  ```
* open http://localhost:8080, register with `card.png` as the card photo (`blurry.png` is refused with "too blurry … try again"), log in, and look up that SSN. The local stack has no AWS account: pick **email** for the code and read it in Mailpit (http://localhost:8025), where `notification-service` delivers it. Texts are only logged, without the code; to sign in by text instead, set `NOTIFICATIONS_SMS_LOG_CODES=true` in `.env` and read it with `docker compose logs notification-service | grep 'local sign-in only'`;
* get recommendations through the agents:
  ```bash
  curl -s localhost:8086/api/v1/advice -H "Authorization: Bearer $INTERNAL_API_TOKEN" -H 'Content-Type: application/json' -d '{"ssn":"123-45-6789"}'
  ```

## Run with AWS Emulator

[AWS Emulator](https://docs.aws.amazon.com/emulator/latest/userguide/what-is-service.html) is AWS's own local emulator of the AWS APIs: no AWS account and no Docker needed. CreditAgent puts every DynamoDB table on it (users, sessions, login challenges, credit reports, alerts).

1. Install it once. On Fedora or Amazon Linux: `sudo dnf install -y https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest/aws-emulator-x86_64.rpm` (to check AWS's signature first, see [Getting started on Linux](https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-linux.html)). Without it, `start.sh` uses Podman instead: it downloads the package, checks AWS's signature and builds a local image.
2. Start it: `scripts/aws-emulator/start.sh`. It runs in the background on `https://127.0.0.1:9297` with telemetry off and 2 GiB of memory at most. It writes fresh credentials and a certificate to `~/.aws/emulator/9297/`, and builds a Java trust store that holds both the JDK's certificates and the emulator's.
3. Once, before opening the service shells, make the shared service token: `openssl rand -hex 32 > ~/.creditagent-internal-token` (every service must use the same value).
4. In each shell that runs a service, run `source scripts/aws-emulator/env.sh` (it adds the trust store to `JAVA_TOOL_OPTIONS`, so every JVM prints "Picked up JAVA_TOOL_OPTIONS" on stderr), then start the service as in [Run without Docker](#run-without-docker), without the `docker run` and `DYNAMODB_ENDPOINT` parts. For example:
   ```bash
   source scripts/aws-emulator/env.sh
   export INTERNAL_API_TOKEN=$(cat ~/.creditagent-internal-token)   # the same value in every shell
   mvn -pl notification-service spring-boot:run        # sends the sign-in codes (logged here)
   JWT_SECRET=$(openssl rand -hex 32) SSN_HMAC_KEY=$(openssl rand -hex 32) mvn -pl web-server spring-boot:run
   ```
5. Stop it with `scripts/aws-emulator/stop.sh`. Everything it held is in memory, so restarting gives empty tables. Each start makes new credentials and a new certificate, so restart the services after it.

`notification-service` logs messages instead of sending them (`NOTIFICATIONS_EMAIL=log`, `NOTIFICATIONS_SMS=log`), and `env.sh` sets `NOTIFICATIONS_SMS_LOG_CODES=true`, so **sign in by text** and read the code with `grep 'local sign-in only'` in its log (emails are logged without the code). Start `notification-service` with the other services. Emulator 1.0.531 keeps SES in its sandbox: a sender can never be verified locally, so every email is refused. It also doesn't implement SNS `Publish` to a phone number yet (it answers `501 NotImplemented`). The SES and SNS calls are covered by unit tests with mocked clients.

## Run without Docker

Start each service with `mvn -pl <module> spring-boot:run`. Registration needs the KYC service (`KYC_SAMPLE_CARDS_ENABLED=true mvn -pl kyc-service spring-boot:run`). The web server needs DynamoDB: [AWS Emulator](#run-with-aws-emulator) (no Docker), or DynamoDB Local:

```bash
docker run -d -p 8000:8000 amazon/dynamodb-local:3.3.2 -jar DynamoDBLocal.jar -inMemory -sharedDb
export INTERNAL_API_TOKEN=$(openssl rand -hex 32) DYNAMODB_ENDPOINT=http://localhost:8000 DYNAMODB_CREATE_TABLES=true \
  AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local
# Sign-in codes come from notification-service: here texts are logged with their code (local only).
NOTIFICATIONS_EMAIL=log NOTIFICATIONS_SMS=log NOTIFICATIONS_SMS_LOG_CODES=true mvn -pl notification-service spring-boot:run &
JWT_SECRET=$(openssl rand -hex 32) SSN_HMAC_KEY=$(openssl rand -hex 32) mvn -pl web-server spring-boot:run
```

Sign in by text and read the code from the notification-service log (`local sign-in only`).

The generator and the MCP server share the `CreditReports` table (the same DynamoDB) and both keys, and the MCP server needs NRG running:

```bash
export CREDIT_FILE_HMAC_KEY=$(openssl rand -hex 32) CREDIT_REPORT_ENCRYPTION_KEY=$(openssl rand -hex 32) \
  DYNAMODB_ENDPOINT=http://localhost:8000 DYNAMODB_CREATE_TABLES=true AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local
CREDIT_SEED_SSNS=123-45-6789 mvn -pl credit-report-generator spring-boot:run
mvn -pl normalized-report-generator spring-boot:run
mvn -pl credit-report-mcp-server spring-boot:run
```

## Sign-in codes

Every login needs the password and a one-time code:

1. `POST /api/v1/auth/login` `{userId, password}` checks the password and answers `{challengeId, channels, expiresInSeconds}`: where a code can go, masked (`a***@example.com`, `***-***-0100`). No token yet.
2. `POST /api/v1/auth/login/otp` `{challengeId, channel: EMAIL|SMS}` sends a fresh 6-digit code (202). Asking again replaces the code.
3. `POST /api/v1/auth/login/verify` `{challengeId, code}` checks it and answers with the JWT, offers and alerts (what login answered before).

| Limit | Default |
|---|---|
| A code works for | 5 minutes (`OTP_CODE_TTL`) |
| A sign-in must finish within | 10 minutes of the password (`OTP_CHALLENGE_TTL`) |
| Wrong codes per sign-in | 5, then start again from the password (`OTP_MAX_ATTEMPTS`) |
| Codes per sign-in | 3, at least 30 s apart (`OTP_MAX_SENDS`, `OTP_RESEND_COOLDOWN`); 429 with `Retry-After` |
| Codes per account per hour | 10, across all sign-ins (`OTP_MAX_SENDS_PER_USER_PER_HOUR`) |

Codes are stored only as an HMAC bound to their challenge (key derived from `JWT_SECRET`), compared in constant time and used once; challenge ids are stored as SHA-256 hashes; challenges expire through the table's TTL. Each limit is a conditional DynamoDB write, so parallel requests cannot get past it. Sends, failures and successful logins are audit events (`LOGIN_CODE_SENT`, `LOGIN_CODE_FAILED`, `LOGIN_SUCCEEDED`). Accounts made before the mobile number was asked for get codes by email only.

**Delivery.** The web server sends each code through `notification-service` (`NOTIFICATIONS_URL`, `INTERNAL_API_TOKEN`, template `OTP_SIGN_IN`, key `otp:<challengeId>:<send number>`); it holds no SES or SNS client or AWS messaging permission, and won't start without the token. Calls are bounded by `NOTIFICATIONS_CONNECT_TIMEOUT` (2 s) and `NOTIFICATIONS_TIMEOUT` (10 s), and only a failed connect is retried (same key, backoff and jitter); the service retries its providers itself. Sign-in depends on the service: while it is down, sending a code answers 503 with `Retry-After`. A send that fails answers 503. If the provider refused the message (the service answers 422) the send is given back and the user can retry or pick the other channel at once; after a timeout, 5xx, 429 or an unreachable service the message may still arrive, so the send keeps counting, its code stays valid, and the 503 says when a new code may be asked for (`Retry-After`). Provider set-up (SES identity, SNS origination number, IAM) is on `notification-service`; see [Notifications](#notifications).

**Claude connector.** Allowing the connector takes the same code step: the consent page posts user id, password and SSN to `POST /oauth/authorize/sign-in` (opens a challenge), sends the code through `POST /api/v1/auth/login/otp`, then posts the authorization request, SSN, challenge and code to `POST /oauth/authorize/approve`, which answers `{redirect}` with the authorization code. A form post of `decision=allow` without the code is refused; Deny is still a plain form post.

## Card offers

At registration the user also gives their **ZIP code**. The web server keeps each user's **latest credit score** on their account: it is recorded in the background at registration, when the KYC check reads the SSN from the card, and again at every score look-up. The SSN itself is never stored, so this is how a score is available at login. On login, and on `GET /api/v1/offers`, the web server sends that score and the ZIP code (nothing else about the user) to `offers-service`, and shows the answer:

| Step | How |
|---|---|
| Eligible | The score reaches the card's typical minimum (secured: none; builder: about 500–550; student and fair credit: about 580; cash back and low interest: about 670; balance transfer and travel: 690–740; premium: 740+). Regional cards (credit unions, state banks) only for ZIP codes in their states; the state comes from the ZIP's 3-digit prefix |
| Priced | The APR quoted for this score sits in the card's range: the top at its minimum score, the bottom at 850 |
| Suitable | Cards for building credit (secured, credit builder, student, fair credit) only below 670, where mainstream cards are not yet in reach |
| Valid | Marketplace data is checked before use (ids, lengths, printable text, APR bounds), so issuer text cannot carry markup or instructions to agents |
| Ranked | Match score: 35% approval likelihood (how far the score clears the minimum), 20% fit (cards meant for scores more than 80 points lower are marked down, to zero at 280), 25% value (rewards on $15,000 a year, less the annual fee), 15% cost (likely APR, 0% intro months), 5% local. Up to 10 offers, best first |
| Format | Each offer carries the terms of a Schumer box (Regulation Z, 12 CFR 1026.60) exactly as the product lists them, never computed: purchase, balance transfer, cash advance and penalty APRs, annual fee, transaction fees, grace period (null = see issuer terms). Plus rewards in the issuer's words, estimated approval odds, availability, expiry and a "not a firm offer of credit" disclosure on the list and on every offer |

The REST API "plugs into" the MCP interface: `POST /api/v1/offers` builds an MCP `tools/call` and runs it through the same MCP server, so both always answer the same. The catalogue is a placeholder (`OfferMarketplace.placeholder()`, fictional issuers) until an offers marketplace exists; replace the `OfferMarketplace` bean to plug one in. Both interfaces need the internal service token. Login asks once and waits at most `OFFERS_LOGIN_TIMEOUT` (1.5 s, no retry), so a slow or down Offers service never holds up a login: the widget says offers are unavailable, and `GET /api/v1/offers` (retried) fetches them later. Right after registration, while the first score is still being computed, the page asks again after 2, 5 and 10 seconds. A user with no recorded score is asked to look up their score first.

## Dark-web alerts

| Part | What it does |
|---|---|
| `Alerts` table | DynamoDB, key `email` (lower-cased) + `location`, attribute `timestamp` (when it was first found). One row per email and location: finding the same exposure again changes nothing |
| `dark-web-monitor` | Runs continuously. Each cycle (`DARKWEB_SCAN_INTERVAL` after the previous one ends: 1 hour by default, 1 minute in compose) it reads registered users' emails from the users table a page at a time (at most `DARKWEB_MAX_EMAILS_PER_CYCLE` rows a cycle; the next cycle carries on where this one stopped), checks each against every location in `DARKWEB_LOCATIONS`, and writes what it finds to `Alerts`. Only registered users' emails are watched, and an email belongs to at most one account (registration refuses a second one, in any letter case), so no account can see another account's alerts |
| Placeholder source | Nothing on the dark web is contacted. Emails in `DARKWEB_PLACEHOLDER_EXPOSED` are always "found"; nothing else is, unless `DARKWEB_PLACEHOLDER_RATE` is set (demos only): then a fixed hash of email and location marks that share of pairs as exposed, the same every time (0.02 at 3 locations is about 6% of users). Replace the `DarkWebSource` bean to plug in a real breach-data provider |
| `alerts-service` | Internal look-up for the web server: `POST /internal/v1/alerts/lookup` with the email in the body (never in a URL or a log), behind the service token |
| Login | The web server asks `alerts-service` for the user's email, in parallel with the offers look-up, once, waiting at most `ALERTS_LOGIN_TIMEOUT` (1.5 s) for the answer (after a quick read of the account). When something was found it also reads the user's subscription from DynamoDB (bounded by `DYNAMODB_TIMEOUT`, 5 s), as does the subscriptions extra; all three run side by side, and none of them can fail the login. If a record exists, the page shows a banner. An ID Protection subscriber sees the locations and dates; a free user is told only that something was found, with a link to subscribe (the data export always includes every finding). If the service is down or slow, login still works and the page asks `GET /api/v1/alerts` (retried) at once, then again after 5 and 30 seconds |

| Email | `alert-notifier` runs once a day (`NOTIFIER_CRON`, 08:00 UTC). It reads, through the table's `by-day` index, the alerts no email has covered yet: normally those first found in the 24 hours before the run, since the previous run took the rest; it looks back up to `NOTIFIER_LOOKBACK` (7 days) so a failed send or a missed run catches up the next day. It claims each one with a conditional write (so two runs or replicas never email the same alert) and sends each ID Protection subscriber one email through `notification-service` (template `DARK_WEB_ALERT`: the count, the first-seen date and the sign-in address; the service owns the wording, and the key `alert:<HMAC of the address>:<run time>` stops a repeat); a free user's alerts are claimed without an email, so they never crowd subscribers out of a run. A failed send (refused, unknown or the service down) releases its claims for the next run. Without `INTERNAL_API_TOKEN` it claims nothing. At most `NOTIFIER_SENDS_PER_SECOND` (10) emails a second |

To see the banner locally, set `DARKWEB_PLACEHOLDER_EXPOSED` to a registered user's email; it shows at that user's next login after the monitor's next cycle. To see the email without waiting for 08:00, start the stack with `NOTIFIER_CRON='0 * * * * *'` (every minute) and open Mailpit at http://localhost:8025.

## Subscriptions

ID Protection is a paid, one-year subscription: subscribers see where and when their email was found on the dark web, and get the daily email. Free users are told that something was found, and are offered it at every login. It can be ticked at registration (optional, never pre-ticked, price shown) or bought later from the ID Protection panel. Credit Monitoring is tracked by the same table but not offered yet.

| Part | What it does |
|---|---|
| Subscriptions table | DynamoDB `credit-agent-subscriptions`, key `pk` + `product`. `pk = <userId>`: the latest term (`startedAt`, `expiresAt`, `paymentReference`). `pk = email#<sha-256>`: the same term against the email, so the notifier can tell a subscriber without reading the users table. A term starts only if none is active (one conditional transaction), so two payments can't both start one |
| Payments | `PAYMENTS_PROVIDER`: `none` (default: listed, not buyable), `test` (every payment succeeds, nothing charged; refused unless DynamoDB is local; compose and the emulator script set it). A real provider (Stripe Checkout) comes next: it answers with a redirect to its hosted page, so card data never reaches CreditAgent. Payment taking moves to a `payments-service` in steps P1–P3 ([ADR 0002](docs/adr/0002-extract-payments-service.md)); whether payments are available is cached for 30 s |
| Activation API | Internal `POST /internal/v1/subscriptions/activations` (needs `INTERNAL_API_TOKEN`), body `{userId, product, paymentReference}`: the future payments-service starts the term for a payment it took. 200 `{outcome: ACTIVE, expiresAt}`; 200 `ALREADY_SAME_PAYMENT` for a repeat of the payment that bought the active term (safe to retry); 409 `ALREADY_ACTIVE` when another term is active (the caller refunds); 404 with problem type `urn:creditagent:problem:unknown-user` for an unknown user (the caller refunds; a 404 without that type, or a 401, means the internal API is misconfigured: retry and alert, never refund); 400 invalid; 503 with Retry-After when DynamoDB fails. `ACTIVE`, `ALREADY_ACTIVE` and a failed write are audited as `SUBSCRIPTION_STARTED` (`OK`, `ALREADY_ACTIVE`, `FAILED`) with actor `service:payments`; `ALREADY_SAME_PAYMENT` changes nothing and writes no audit entry. Contract: [`subscriptions-internal-v1.yaml`](docs/api/subscriptions-internal-v1.yaml) |
| Data rights | The export lists the latest term per product (a renewal replaces the earlier one; the audit log keeps every payment); account deletion erases them, and the audit record names the products and payments erased; the retention job never purges an account with an active term |

## Notifications

`notification-service` (port 8094) sends every sign-in code (from web-server) and dark-web alert (from alert-notifier), so provider clients, templates and credentials live in one place; neither caller sends anything itself ([ADR 0001](docs/adr/0001-extract-notification-service.md)). The contract is [`notification-service-v1.yaml`](notification-service/src/main/resources/static/openapi/notification-service-v1.yaml).

`POST /internal/v1/notifications`, with `Authorization: Bearer <INTERNAL_API_TOKEN>` and `Idempotency-Key` (1 to 128 printable characters), body `{channel: EMAIL|SMS, to, template, params}`:

| Template | Channels | Params |
|---|---|---|
| `OTP_SIGN_IN` | EMAIL, SMS | `code` (6 digits), `validSeconds` (1 to 86400; the text says "5 minutes" or "90 seconds", exactly) |
| `DARK_WEB_ALERT` | EMAIL | `count` (places found), `firstSeen` (ISO date), optional `appUrl` (http or https). The same subject and text as the notifier's email today |

| Answer | When |
|---|---|
| `202 {id, status: "SENT"}` | Sent; a repeat of the same key and body answers the same `id` without sending again (`status: "UNCERTAIN"` if the first attempt's outcome was never known) |
| `400` ProblemDetail | Unknown channel, template or field; missing or extra params; a code that is not 6 digits; a malformed email or E.164 number; SMS for `DARK_WEB_ALERT`; a field over its limit; no `Idempotency-Key` |
| `409` | The key was used for a different body (compared by a keyed SHA-256 hash of the canonical request) |
| `422 {refused: true}` | The provider clearly refused the message (a 4xx that is not throttling, or an SMTP relay that rejected the recipient). Not delivered; the key is free again |
| `503` + `Retry-After` | Outcome unknown (timeout, 5xx, throttling: it may still arrive): the key is kept as UNCERTAIN and never sends again, so a new attempt needs a new key. Also a send with this key in progress, or DynamoDB unavailable before sending |
| `401`, `404`, `413`, `429` | No or wrong token; no token configured; body over 8 KiB; over the inbound rate limit |

**Data.** DynamoDB `credit-agent-notifications`, key `idempotencyKey` (an HMAC of the caller's key: sign-in keys carry the challenge id), with the request hash (HMAC-SHA256), channel, template, masked destination (`a***@example.com`, `+1******0100`), status (`PENDING`, `SENT`, `UNCERTAIN`), provider message id, `createdAt`, and a 30-day TTL on `expiresAt`. The code, the full address or number, and the message text are never stored or logged. Every key that was sent or may have been delivered is remembered; only a clear refusal frees one. The masked destination is pseudonymised personal data: it is kept for those 30 days under the TTL and is not erased by account deletion (it cannot be looked up by user or address).

**Hash key.** Both hashes are keyed with `NOTIFICATIONS_HASH_KEY` (at least 32 characters), or, when it is unset, with a key derived from `INTERNAL_API_TOKEN`. Changing the key in use (including rotating the token when no hash key is set) re-keys every new hash: for up to 30 days, a repeat of a key sent before the change is not recognised and would send again. Set `NOTIFICATIONS_HASH_KEY` so token rotation does not do this, and change it only when no repeats are expected.

**Delivery guarantee.** The key is reserved with a conditional put (PENDING, with a 60 s lease), then the provider is called, then the record becomes SENT. A clear refusal deletes the reservation (not delivered, so the same key may send); an unknown outcome marks it UNCERTAIN (it may have arrived, so the key never sends again). So a key sends at most once, except in one window: if the service stops after calling the provider but before recording the outcome, the record stays PENDING and, once the lease runs out, the same request sends again (at-least-once there). A different request never takes over a key. The lease must exceed the longest send (3 × the DynamoDB timeout + the longer of the provider and SMTP timeouts, 16 s by default), and so must the shutdown phase; both are checked at start-up.

**Providers.** `NOTIFICATIONS_EMAIL=ses|smtp|log` and `NOTIFICATIONS_SMS=sns|log`. SES v2 and SNS Publish (transactional, straight to the number) are bounded by `NOTIFICATIONS_PROVIDER_TIMEOUT` with the SDK's standard retries (one layer); SMTP uses the mail timeouts plus an overall limit per send (`NOTIFICATIONS_SMTP_TIMEOUT`, 10 s; past it the outcome is unknown) and refuses STARTTLS off for any relay but localhost or `mailpit`. `log` sends nothing and writes the template and masked destination (never the code); it is refused unless `DYNAMODB_ENDPOINT` is local. For local sign-in by text, `NOTIFICATIONS_SMS_LOG_CODES=true` (with `NOTIFICATIONS_SMS=log`, under the same local-only rule) also logs the text and its code. Inbound limits come from `web-support` (429/503 with `Retry-After`), and shutdown is graceful (`NOTIFICATIONS_SHUTDOWN_TIMEOUT`, 20 s). In AWS the service's role needs `ses:SendEmail`, `sns:Publish` and `dynamodb:PutItem`, `UpdateItem`, `DeleteItem`, `GetItem` on its table.

Run it alone: `INTERNAL_API_TOKEN=$(openssl rand -hex 32) DYNAMODB_ENDPOINT=http://localhost:8000 DYNAMODB_CREATE_TABLES=true NOTIFICATIONS_EMAIL=log NOTIFICATIONS_SMS=log AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local mvn -pl notification-service spring-boot:run`, then

```bash
curl -i localhost:8094/internal/v1/notifications -H "Authorization: Bearer $INTERNAL_API_TOKEN" -H 'Idempotency-Key: demo-1' \
  -H 'Content-Type: application/json' -d '{"channel":"SMS","to":"+15555550100","template":"OTP_SIGN_IN","params":{"code":"123456","validSeconds":"300"}}'
```

In compose it sends email to Mailpit (http://localhost:8025) and logs texts (with the code only when `NOTIFICATIONS_SMS_LOG_CODES=true`); web-server and alert-notifier start after it.

## Chat

Signed-in users see **Ask about your report**. They enter their SSN once per sign-in; the page keeps it in memory, never in storage. Each question goes to `POST /api/v1/chat`. The web server checks that the SSN is the user's own, fetches the report, and sends both to `user-advisor-agent`.

The agent runs every question through **input guardrails**, then the model, then **output guardrails**:

| Stage | What it checks | What the user gets |
|---|---|---|
| Normalise | Unicode NFKC; invisible characters removed; Cyrillic, Greek and Armenian look-alikes, digit-for-letter spellings and spaced or dotted letters (`i g n o r e`) folded, so tricks like `Іgnоrе` or `sh1t` don't slip past the checks | — |
| Input | Empty, or longer than 1,000 characters (checked again after NFKC, which can expand a character 18 times) | Ask for a (shorter) question |
| Input | Profanity and abuse, including masked forms like `f*ck` | Rephrase politely |
| Input | Prompt injection: "ignore previous instructions", role play, requests for the system prompt, chat-template markers (`<\|system\|>`, `[INST]`), base64, hex or Morse payloads | Fixed refusal |
| Input | Protected characteristics as a factor (race, colour, religion, national origin, sex, sexual orientation, age, marital status, public assistance, …). Asking about a divorce or a joint mortgage is fine | Why credit reports don't use them |
| Input | Someone else's credit; fraud (CPNs, synthetic identities, disputing accurate items) | Refusal |
| Input | Off topic. A short follow-up with no credit word passes only if it points back at the last answer ("why?", "tell me more about that"); greetings are not remembered as context | Examples of what it can answer |
| Input | SSNs (any dash, slash or digit script), card and account numbers, emails, phone numbers | Redacted before the model or memory sees them |
| Output | Offensive text or insults; mention of a protected characteristic or group; leak of the instructions or prompt delimiters; promises ("guaranteed", "will boost you to 800"; a fact like "100% utilisation" is fine) | The answer is replaced, not edited |
| Output | Identifiers redacted, markup removed, length capped | — |
| Output | Every answer | "Educational purposes only … consult a qualified professional" disclaimer |

**Memory.** Conversations live in the agent's RAM, keyed by a hash of the login session, so the agent never learns who the user is. The web server sends the report without the name, masked SSN or report id. A conversation ends at logout, after `ADVISOR_MEMORY_TTL` (5 minutes) without being read or written, or when it is evicted: least recently used past `ADVISOR_MAX_CONVERSATIONS`, and the least recently used half when live heap (the old generation after the last GC) passes `ADVISOR_MEMORY_PRESSURE` (85%). A conversation keeps its last `ADVISOR_MAX_TURNS` turns. Only text that passed the guardrails is stored.

**Eval log.** Every turn, refused or not, is written as one JSON line to `ADVISOR_EVAL_LOG_DIR/advisor-eval.jsonl` (compose: the `advisor-evals` volume), for offline evaluation of answers and guardrails. A line holds the time, the conversation hash, the turn number, the question, the input verdict, the model's raw answer, what was shown, the output verdict, the model and its latency. Identifiers are redacted, and neither the report nor who the user is is written. Files roll daily and at 10 MB, are gzipped, kept 30 days and capped at 500 MB. The appender is asynchronous and drops lines rather than delay a chat. `ADVISOR_EVAL_LOG_ENABLED=false` turns it off.

**Limits of the guardrails.** They are deterministic word lists and patterns, a first layer and not a classifier. Known gaps: other languages, ROT13, base64 split into short chunks, and paraphrases. Add a moderation or injection classifier when a real LLM is plugged in, and re-run `owasp-llm-top10`.

**The model.** `PlaceholderAdvisorModel` answers from the report by rules, and uses the memory for follow-ups ("tell me more"). To plug in an LLM, replace the `AdvisorModel` bean; `PromptBuilder` already builds a prompt that keeps the instructions apart from the report, history and question. The guardrails stay around whichever model is plugged in.

## Use it from Claude

`claude-connector` is a [custom connector](https://support.claude.com/en/articles/11175166-getting-started-with-custom-connectors-using-remote-mcp): a remote MCP server that Claude calls on a signed-in user's behalf.

```
Claude ──(MCP over HTTPS, Bearer token)──> claude-connector :8088 ──> MCP server, Score Calculator, client agent
   │                                              │ introspects the token
   └──(OAuth 2.1: register, sign in, token)──> web-server :8080 (authorization server)
```

**How sign-in works.** Claude calls `/mcp`, gets `401` with a `WWW-Authenticate` header pointing at `/.well-known/oauth-protected-resource`, finds the web server there, registers itself (dynamic client registration) and opens the web server's sign-in page. The user enters their user id, password **and SSN**: the server stores only a keyed hash of the SSN, and the connector needs the SSN itself to fetch the report. The SSN goes inside the access token, which is encrypted, so Claude never sees it. No tool takes an SSN, so Claude can only ever reach the signed-in user's own data. Tokens last `OAUTH_ACCESS_TOKEN_TTL` (1 hour); there are no refresh tokens, so the user reconnects after that.

| Tool | Arguments | Returns | Fails when |
|---|---|---|---|
| `get_credit_score` | none | masked SSN, score (300–850), band | no report on file; a service is down |
| `get_credit_report` | none | the merged report (accounts, inquiries, collections, public records; SSN masked) | as above |
| `get_credit_advice` | none | prioritised recommendations from the Credit Intelligence Agent | the report is **locked** (the agent is a third party); as above |
| `get_report_lock_status` | none | `LOCKED` or `UNLOCKED` | the connection was revoked |
| `set_report_lock` | `locked` (boolean) | the new status | as above |

**Run it locally.** Set `OAUTH_TOKEN_KEY` in `.env` (`openssl rand -hex 32`) and start the stack (`docker compose up --build`). The connector is at `http://localhost:8088/mcp`.

* **Claude Code** (talks to localhost directly; sign-in opens your browser):
  ```bash
  claude mcp add --transport http creditagent http://localhost:8088/mcp
  ```
  then run `/mcp` in Claude Code and choose *Authenticate*.
* **claude.ai and Claude Desktop** call connectors from Anthropic's cloud, so both the connector and the web server need **public HTTPS URLs**. For a demo, run two tunnels (for example `cloudflared tunnel --url http://localhost:8088` and `... :8080`), put their URLs in `.env` as `CONNECTOR_PUBLIC_URL` and `OAUTH_ISSUER`, restart, then add `<CONNECTOR_PUBLIC_URL>/mcp` under **Settings → Connectors → Add custom connector**. Leave the OAuth client fields empty: Claude registers itself. One host also works: route `/mcp` and `/.well-known/oauth-protected-resource*` to 8088 and everything else to 8080, and set both URLs to that host.

> ⚠️ Connecting puts the user's credit data into their Claude conversations. Use synthetic SSNs only. Read [docs/security-review.md](docs/security-review.md) before exposing anything publicly.

## Configuration

| Variable | Used by | Default |
|---|---|---|
| `CREDIT_FILE_HMAC_KEY` | generator, MCP server | **required**, at least 32 characters, the same in both: keys the SSN hash the `CreditReports` table is keyed by |
| `CREDIT_REPORT_ENCRYPTION_KEY` | generator, MCP server | **required**, at least 32 characters, the same in both: encrypts each stored report (AES-256-GCM) |
| `CREDIT_REPORT_PREVIOUS_ENCRYPTION_KEYS` | generator, MCP server | empty; after a rotation, the old keys (comma-separated), which still decrypt reports written with them |
| `CREDIT_REPORTS_TABLE` | generator, MCP server | `CreditReports` (with `DYNAMODB_ENDPOINT`, `DYNAMODB_CREATE_TABLES`, `DYNAMODB_TIMEOUT`, `AWS_REGION` as for the web server) |
| `NRG_URL` | MCP server | `http://localhost:8084` |
| `NRG_MAX_REQUEST_BYTES` | NRG | `3200000` (larger bodies get 413) |
| `CREDIT_SEED_SSNS` | generator | none |
| `CREDIT_MCP_URL` | web server, agent | `http://localhost:8082/mcp` |
| `MCP_TIMEOUT` | web server, agent | `20s` (one MCP look-up, reconnects and retries included; longer than `DOWNSTREAM_TIMEOUT` because it includes the MCP server's own retries of NRG. Raise it if you raise `DOWNSTREAM_TIMEOUT`) |
| `SCORE_CALCULATOR_URL` | web server, agent | `http://localhost:8081` |
| `RATE_LIMIT_PER_MINUTE` | web server | `20` |
| `SCORE_MAX_REQUEST_BYTES` | score calculator | `262144` (256 KiB; larger bodies get 413) |
| `AGENT_PUBLIC_URL` | agent | `http://localhost:<port>` |
| `INTELLIGENCE_AGENT_URL` | client agent | `http://localhost:8085` |
| `INTELLIGENCE_AGENT_TIMEOUT` | client agent | `90s` (longest wait for the agent's answer; longer than the agent's own worst case of about 82 s, report-lock check included) |
| `LLM_ENABLED`, `ANTHROPIC_API_KEY`, `LLM_MODEL` | agent | `false`, none, `claude-sonnet-5-5` |
| `LLM_TIMEOUT` | agent | `30s` (one try; on any failure the rule-based advice answers instead) |
| `KYC_URL`, `KYC_TIMEOUT` | web server | `http://localhost:8087`, `10s` |
| `SSN_CARD_MAX_BYTES`, `SSN_CARD_MAX_REQUEST_BYTES` | web server | `5MB`, `6MB` (larger card photos or form bodies get 413). The UI checks the same 5 MB before uploading (`MAX_CARD_BYTES` in `auth.js`); change both together |
| `KYC_MIN_SHARPNESS` | KYC service | `100` (variance of the Laplacian; lower scores are refused as blurred) |
| `KYC_MAX_IMAGE_BYTES`, `KYC_MAX_PIXELS` | KYC service | `10485760`, `40000000` |
| `KYC_MAX_CONCURRENT` | KYC service | `4` (photo checks in progress at once; each decodes an image in memory) |
| `KYC_SAMPLE_CARDS_ENABLED` | KYC service | `false` (compose sets `true`); demo only, makes a card for any SSN |
| `INTERNAL_API_TOKEN` | web server, agent, MCP server, connector, advisor agent, offers service, alerts service, notification service, generator (erase API), notifier (alert emails; without it none are sent) | **required**, at least 32 characters. The agent and the MCP server present it to the web server to check report locks, and the web server and the agent present it to the MCP server so their own reads are not lock-checked again. Without it, third-party pulls are refused and the web server's own lookups are lock-checked like a third party's |
| `REPORTS_URL`, `ACCOUNT_ERASE_TIMEOUT` | web server | `http://localhost:8083` (the report generator, which erases a deleted account's stored reports), `5s` a try |
| `ACCOUNT_RETENTION`, `ACCOUNT_RETENTION_MAX_PER_RUN`, `ACCOUNT_RETENTION_CRON` | web server | `730d`, `1000`, `0 15 4 * * *` (UTC): accounts with no login for that long are deleted with their data |
| `CREDIT_REPORT_RETENTION`, `CREDIT_REPORT_PURGE_MAX`, `CREDIT_REPORT_RETENTION_CRON` | generator | `730d`, `10000`, `0 30 3 * * *` (UTC): stored reports not refreshed for that long are deleted |
| `ALERT_RETENTION` | dark-web monitor | `730d`: each alert's DynamoDB TTL after it was found |
| `REPORT_LOCK_URL` | agent, MCP server | `http://localhost:8080` (the web server, which owns the users table) |
| `JWT_SECRET` | web server | **required**, at least 32 characters |
| `SSN_HMAC_KEY` | web server | **required**, at least 32 characters; keyed hash of each user's SSN |
| `JWT_TTL`, `SESSION_IDLE_TIMEOUT` | web server | `1h`, `5m` |
| `DYNAMODB_ENDPOINT` | web server | empty = AWS; `http://localhost:8000` for DynamoDB Local |
| `AWS_REGION`, `USERS_TABLE`, `SESSIONS_TABLE` | web server | `us-east-1`, `credit-agent-users`, `credit-agent-sessions` |
| `DYNAMODB_CREATE_TABLES` | web server | `false` (compose sets `true` for DynamoDB Local) |
| `DYNAMODB_TIMEOUT` | web server | `5s` (longest DynamoDB call, retries included; the SDK's standard retry mode backs off with jitter) |
| `OTP_CHALLENGES_TABLE` | web server | `credit-agent-login-challenges` (TTL on `expiresAt`); codes are sent by the notification service |
| `OTP_CODE_TTL`, `OTP_CHALLENGE_TTL`, `OTP_MAX_ATTEMPTS`, `OTP_MAX_SENDS`, `OTP_RESEND_COOLDOWN`, `OTP_MAX_SENDS_PER_USER_PER_HOUR` | web server | `5m`, `10m`, `5`, `3`, `30s`, `10` |
| `CREDIT_RETRY_MAX_ATTEMPTS`, `CREDIT_RETRY_INITIAL_DELAY`, `CREDIT_RETRY_MULTIPLIER`, `CREDIT_RETRY_MAX_DELAY`, `CREDIT_RETRY_JITTER` | every service that calls another | `3`, `100ms`, `2`, `1s`, `50ms`: retries of recoverable failures with exponential backoff and jitter (see [architecture](docs/architecture.md#resilience)) |
| `OAUTH_TOKEN_KEY` | web server | empty = OAuth (and so the Claude connector's sign-in) is off. At least 32 characters; signs client ids and encrypts codes and access tokens |
| `OAUTH_ISSUER` | web server, connector | `http://localhost:8080`: the web server's public URL, exactly as Claude and browsers reach it |
| `CONNECTOR_RESOURCE_URL` | web server | `http://localhost:8088/mcp`: the only audience tokens are issued for; must equal the connector's `<CONNECTOR_PUBLIC_URL>/mcp` |
| `OAUTH_ACCESS_TOKEN_TTL`, `OAUTH_ALLOW_LOOPBACK_REDIRECTS` | web server | `1h`, `true` (loopback redirects serve Claude Code and Desktop; Claude's two web callbacks are always allowed) |
| `OFFERS_URL`, `OFFERS_TIMEOUT`, `OFFERS_LOGIN_TIMEOUT` | web server | `http://localhost:8090`, `3s` a try for `GET /api/v1/offers` (retried), `1500ms` for the single try at login |
| `ALERTS_URL`, `ALERTS_TIMEOUT`, `ALERTS_LOGIN_TIMEOUT` | web server | `http://localhost:8092`, `3s` a try for `GET /api/v1/alerts` (retried), `1500ms` for the single try at login |
| `ALERTS_TABLE` | monitor, alerts service, notifier | `Alerts` |
| `SUBSCRIPTIONS_TABLE` | web server, notifier | `credit-agent-subscriptions` |
| `PAYMENTS_PROVIDER`, `ID_PROTECTION_PRICE_CENTS`, `SUBSCRIPTIONS_CURRENCY` | web server | `none` (`test` for local development only), `2999` (a placeholder until pricing is decided), `USD` |
| `NOTIFICATIONS_URL` | web server, notifier | `http://localhost:8094` (compose: `http://notification-service:8094`). Checked at start-up: an absolute http(s) URL, and https unless the host is `localhost`, `127.0.0.1`, `::1` or a compose service name (no dot), so codes and the token never cross a network in clear text |
| `NOTIFICATIONS_TIMEOUT` | web server, notifier | `10s` (web server), `15s` (notifier): longest one call, above the service's own worst case (about 8 s). The web server retries only a failed connect (same `Idempotency-Key`), never a timeout or a 5xx; the notifier does not retry, and the next run picks up a failed send |
| `NOTIFICATIONS_CONNECT_TIMEOUT` | web server | `2s`: longest a connect may take. With the defaults (3 tries, `credit.retry` backoff) a sign-in waits at most about 14.5 s for the service: two connects that time out (4 s), the backoff (under 0.5 s), then a try that runs to `NOTIFICATIONS_TIMEOUT` (10 s) |
| `NOTIFIER_CRON`, `NOTIFIER_LOOKBACK`, `NOTIFIER_MAX_ALERTS_PER_RUN`, `NOTIFIER_SENDS_PER_SECOND` | notifier | `0 0 8 * * *` (UTC), `7d`, `10000`, `10` |
| `NOTIFIER_APP_URL` | notifier | empty (compose: `http://localhost:8080`); the sender address is the service's `NOTIFICATIONS_EMAIL_FROM` |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_STARTTLS` | notification service (`smtp`) | required for `smtp`, `587`, empty, empty, `true`, `true`. Compose defaults to Mailpit with auth and TLS off; the service refuses to start with STARTTLS off for any relay but localhost or `mailpit` |
| `DARKWEB_SCAN_INTERVAL`, `DARKWEB_SCAN_INITIAL_DELAY`, `DARKWEB_LOCATIONS`, `DARKWEB_MAX_EMAILS_PER_CYCLE` | monitor | `1h` (compose: `60s`), `10s`, `paste-site-alpha,breach-forum-beta,marketplace-gamma`, `10000` |
| `DARKWEB_PLACEHOLDER_EXPOSED`, `DARKWEB_PLACEHOLDER_RATE` | monitor | empty, `0` (no invented exposures) |
| `ALERTS_DYNAMODB_TIMEOUT` | alerts service | `2s`: DynamoDB budget, retries included; kept below the web server's `ALERTS_TIMEOUT` so retries never stack |
| `NOTIFICATIONS_EMAIL`, `NOTIFICATIONS_SMS` | notification service | `ses`, `sns`; `smtp` (with the `MAIL_*` settings) for email; `log` for local development only (compose: `smtp` and `log`) |
| `NOTIFICATIONS_SMS_LOG_CODES` | notification service | `false`. `true` (local sign-in by text only) logs texts with their code; needs `NOTIFICATIONS_SMS=log` and a local `DYNAMODB_ENDPOINT` (the emulator's `env.sh` sets it) |
| `NOTIFICATIONS_EMAIL_FROM`, `NOTIFICATIONS_SES_CONFIGURATION_SET`, `NOTIFICATIONS_SES_ENDPOINT` | notification service | `CreditAgent <no-reply@creditagent.example>`, none, AWS |
| `NOTIFICATIONS_SMS_ORIGINATION_NUMBER`, `NOTIFICATIONS_SMS_SENDER_ID`, `NOTIFICATIONS_SNS_ENDPOINT` | notification service | account default, none, AWS |
| `NOTIFICATIONS_PROVIDER_TIMEOUT`, `NOTIFICATIONS_DYNAMODB_TIMEOUT`, `NOTIFICATIONS_LEASE` | notification service | `4s`, `2s`, `60s` (a send in progress holds its key this long; must exceed the two timeouts together) |
| `NOTIFICATIONS_TABLE`, `NOTIFICATIONS_RETENTION`, `NOTIFICATIONS_SHUTDOWN_TIMEOUT` | notification service | `credit-agent-notifications`, `30d` (TTL of an accepted key), `20s` (at least the longest send) |
| `NOTIFICATIONS_SMTP_TIMEOUT`, `NOTIFICATIONS_HASH_KEY` | notification service | `10s` (one SMTP send in all), empty (derived from `INTERNAL_API_TOKEN`; at least 32 characters when set; changing it re-keys the stored hashes) |
| `ADVISOR_URL`, `ADVISOR_TIMEOUT` | web server | `http://localhost:8089`, `15s` |
| `ADVISOR_MEMORY_TTL`, `ADVISOR_MAX_CONVERSATIONS`, `ADVISOR_MAX_TURNS`, `ADVISOR_MEMORY_PRESSURE` | advisor agent | `5m`, `10000`, `20`, `0.85` |
| `ADVISOR_MAX_PROMPT_CHARS`, `ADVISOR_MAX_REPLY_CHARS` | advisor agent | `1000`, `2000` |
| `ADVISOR_EVAL_LOG_ENABLED`, `ADVISOR_EVAL_LOG_DIR` | advisor agent | `true`, `logs` (compose: `/data/evals` on the `advisor-evals` volume) |
| `CONNECTOR_PUBLIC_URL` | connector | `http://localhost:8088` |
| `WEB_SERVER_URL`, `CLIENT_AGENT_URL` | connector | `http://localhost:8080`, `http://localhost:8086` (internal URLs) |
| `ADVICE_TIMEOUT` | connector | `95s` (longer than the client agent's own 90 s) |
| `CONNECTOR_REQUESTS_PER_USER_PER_MINUTE` | connector | `60` (per-user limit; over it, 429 with `Retry-After`) |
| `CREDIT_INBOUND_RATE_PER_SECOND`, `CREDIT_INBOUND_BURST`, `CREDIT_INBOUND_MAX_CONCURRENT`, `CREDIT_INBOUND_ENABLED`, `CREDIT_INBOUND_URL_PATTERNS` | every service | `50`, `100`, `32`, `true`, `/*` (web server: `/api/*`, `/internal/*`, `/oauth/*`, `/.well-known/*`): token-bucket rate limit and in-flight cap; excess requests get 429 or 503 with `Retry-After`. Health checks are exempt |

## CI/CD

| Workflow | Runs on | What it does |
|---|---|---|
| `ci.yml` | PRs, pushes to `main` | Maven verify on Java 26, UI tests, image build with Trivy scan; pushes `ghcr.io/<owner>/creditagent-<service>` from `main`, then deletes superseded CI builds (tagged only `sha-*`), keeping `latest` and every release image |
| `codeql.yml` | PRs, `main`, weekly | CodeQL (`security-extended`) for Java, JavaScript and the workflows; results go to code scanning (Security tab) and any finding fails the job |
| `security.yml` | PRs, `main` | GitHub dependency review of a PR's dependency changes (high or critical advisories, or AGPL/GPL-3.0 licences, fail it), Trivy scan of all declared dependencies (high and critical advisories with a fix fail it) and Gitleaks secret scan |
| `release.yml` | tags `v*.*.*` | Versioned images with SBOM and provenance, plus a GitHub release |

The repository is public. `main` is protected by GitHub: every change goes through a PR with green required checks (build and tests, UI tests, CodeQL, dependency review and scan, secret scan) before it is merged.

Dependabot keeps Maven, npm, Docker and action pins up to date.
