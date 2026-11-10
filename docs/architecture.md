# Architecture

This follows the *Credit Agent* spec and system diagram. Numbers and letters on the arrows match the diagram's flows.

```mermaid
flowchart LR
    subgraph Browser
        UI[Web UI<br/>register with SSN card photo<br/>SSN form + score ring]
    end
    subgraph Web["web-server :8080 (Spring Boot)"]
        WS[CreditScoreController<br/>CreditScoreService]
    end
    subgraph MCP["credit-report-mcp-server :8082"]
        M[/mcp Streamable HTTP<br/>resource credit-report://ssn/&#123;ssn&#125;<br/>tool get_credit_report/]
    end
    FS[(~/Reports/&lt;Bureau&gt;/<br/>Equifax · Experian · TransUnion<br/>HMAC-named JSON)]
    NRG[normalized-report-generator :8084<br/>merges 3 bureau reports]
    GEN[credit-report-generator :8083]
    SC[score-calculator :8081<br/>five-factor scorecard 300–850]
    KYC[kyc-service :8087<br/>dummy KYC provider<br/>blur check + reads SSN from card]
    subgraph Agents["Agents · LangChain4j · A2A"]
        CA[client-agent :8086]
        CIA[credit-intelligence-agent :8085]
    end
    OFS[offers-service :8090<br/>MCP tool + REST<br/>ranks card offers]
    UAA[user-advisor-agent :8089<br/>guardrails · placeholder LLM<br/>memory in RAM]
    CL([Claude])
    CC[claude-connector :8088<br/>/mcp · OAuth protected resource]

    UI -- "1 · SSN (POST)" --> WS
    WS -- "2 · resources/read" --> M
    M -- "3 · read 3 reports" --> FS
    FS -. "4 · 3 bureau files" .-> M
    M -- "5 · 3 reports" --> NRG
    NRG -. "6 · merged" .-> M
    M -. "7 · credit report" .-> WS
    WS -- "8 · report" --> SC
    SC -. "9 · score" .-> WS
    WS -. "10 · score" .-> UI
    GEN -- "writes 3 bureau reports" --> FS
    UI -- "R1 · register + card photo (multipart)" --> WS
    WS -- "R2 · card photo" --> KYC
    KYC -. "R3 · SSN + verification id, or REJECTED (IMAGE_BLURRY…)" .-> WS
    CA -- "A · SSN (A2A SendMessage)" --> CIA
    CIA -- "B · resources/read (runs 3–6)" --> M
    M -. "C · credit report" .-> CIA
    CIA -- "D · report" --> SC
    SC -. "E · score" .-> CIA
    CIA -. "F · recommendations" .-> CA
    UI -- "L1 · login" --> WS
    WS -- "L2 · last score + ZIP (POST /api/v1/offers)" --> OFS
    OFS -. "L3 · up to 10 ranked offers" .-> WS
    UI -- "Q1 · question + SSN (POST /api/v1/chat)" --> WS
    WS -- "Q2 · report (resources/read)" --> M
    WS -- "Q3 · question + report" --> UAA
    UAA -. "Q4 · answer + disclaimer" .-> WS
    CL -- "O1 · OAuth: register, sign in (password + SSN), token" --> WS
    CL -- "C1 · tools/call + Bearer token" --> CC
    CC -- "C2 · introspect token → user + SSN" --> WS
    CC -- "C3 · report (resources/read)" --> M
    CC -- "C4 · score" --> SC
    CC -- "C5 · advice (POST /api/v1/advice)" --> CA
```

## Modules

| Module | Role | Key technology |
|---|---|---|
| `common` | SSN value type, credit report model, bureau report formats and code tables, score bands, HMAC file names, safe logging | Plain Java |
| `web-support` | Request-size limit filter for services that accept untrusted JSON (score calculator, NRG) | Spring Web |
| `service-clients` | MCP client for reports, HTTP client for scores (shared by the web server and the agent) | LangChain4j MCP client, Spring `RestClient` |
| `score-calculator` | `POST /api/v1/scores`: report in, score out | Spring Boot |
| `kyc-service` | Dummy external KYC provider: `POST /api/v1/ssn-verifications` takes a photo of an SSN card, refuses blurred or non-card images, and returns the SSN it read | Spring Boot, Java ImageIO |
| `credit-report-generator` | Creates a synthetic credit history and writes one report per bureau, each in that bureau's format | Spring Boot |
| `normalized-report-generator` | NRG: `POST /api/v1/normalized-reports`, three bureau reports in, one `CreditReport` out | Spring Boot |
| `credit-report-mcp-server` | MCP server over the file store; reads all three bureau files and has NRG merge them | LangChain4j `McpServer` (tools) plus resources, Streamable HTTP |
| `web-server` | Serves the UI, registers users after a KYC check of their SSN card, logs them in (DynamoDB, JWT), and orchestrates flow 1–10 | Spring Boot, AWS SDK DynamoDB, Nimbus JOSE |
| `credit-intelligence-agent` | A2A server: report, then score, then recommendations | a2a-java server, LangChain4j AI service (optional) |
| `client-agent` | A2A client that sends the SSN | LangChain4j `AgenticServices.a2aBuilder` |
| `offers-service` | Credit card offers for a score and ZIP code, ranked; an MCP tool, and a REST API that calls it | Spring Boot, LangChain4j `McpServer` |
| `user-advisor-agent` | Chat back end: input and output guardrails around a placeholder LLM, with per-conversation memory in RAM | Spring Boot |
| `claude-connector` | Remote MCP server for Claude: the signed-in user's own score, report, advice and report lock | Spring Boot, hand-written MCP JSON-RPC over Streamable HTTP, OAuth 2.1 resource server |

## Design decisions

**MCP resource as well as a tool.** LangChain4j's community MCP server only implements tools, and only over stdio. The spec calls for a file-system *resource*. `CreditReportMcpHandler` therefore answers `initialize` and `resources/*` itself, delegates `tools/*` to LangChain4j's `McpServer`, and is served over Streamable HTTP in single-response mode. The project's own clients use the resource. `McpClientContractTest` checks compatibility with the real LangChain4j client.

**A2A server inside Spring Boot.** The a2a-java reference servers are Quarkus-based. `A2aServerConfiguration` wires the SDK's components through its builder API, and `A2aJsonRpcController` mirrors the reference JSON-RPC routes. The agent card advertises `streaming=false`, so the protocol surface is blocking request/response only. `A2aContractTest` drives it with LangChain4j's A2A client.

**Lazy remote connections.** The MCP client and the A2A client both connect on first use and reconnect after a failure. Services can therefore start in any order, and the retries described under [Resilience](#resilience) cover a dependency that is still starting.

**Two Jackson versions.** Spring Boot 4 uses Jackson 3 (`tools.jackson`) for HTTP and domain JSON. LangChain4j and the MCP protocol objects still use Jackson 2. Each module uses whichever its library expects; the two do not mix.

**Advice: rules first, LLM optional.** The rule-based advisor is deterministic and testable. The LLM advisor (Anthropic through LangChain4j) only ever sees de-identified data, and falls back to the rules if it fails.

## Resilience

Every service-to-service call is bounded, retried when the failure is recoverable, and refused early when the callee is overloaded.

**Timeouts that shrink down the chain.** Every outbound call has a timeout, and each caller waits longer than everything the service it calls may do, retries included. Otherwise a caller gives up on work still in progress, and its retry doubles the load at the worst moment.

| Caller → callee | Timeout | Covers |
|---|---|---|
| MCP server → NRG, web server and agent → Score Calculator | `DOWNSTREAM_TIMEOUT` 5 s per try; 3 tries + backoff + up to 1 s `Retry-After` per retry ≈ 17.4 s | — |
| web server and agent → MCP server | `MCP_TIMEOUT` 20 s for the whole look-up: reconnect, every try and the waits, enforced by the client and interrupting the call | the MCP server's NRG retries (≈ 17.4 s) plus the file reads |
| agent → Anthropic (when `LLM_ENABLED`) | `LLM_TIMEOUT` 30 s, one try | on failure the rules answer at once |
| agent and MCP server → web server (report-lock check) | 12 s, one try (retried only if never delivered) | the web server's lookup: two DynamoDB reads of up to 5 s |
| client agent → intelligence agent | `INTELLIGENCE_AGENT_TIMEOUT` 90 s, enforced by the client and interrupting the call | the agent's worst case: lock check ≈ 14.5 s + MCP 20 s + Score Calculator 17.4 s + LLM 30 s ≈ 82 s |
| web server → KYC | `KYC_TIMEOUT` 10 s | — |
| web server → DynamoDB | `DYNAMODB_TIMEOUT` 5 s per call, retries included | — |

`TimeoutBudgetTest`, `AgentPropertiesTest` and `RequestLimitsTest` check these orderings for the defaults (`RequestLimitsTest` repeats the agent's MCP and LLM defaults, which live in another module). The budgets assume the default `DOWNSTREAM_TIMEOUT`: raise `MCP_TIMEOUT` and `INTELLIGENCE_AGENT_TIMEOUT` with it.

**Retries with exponential backoff and jitter.** `service-clients` builds every `RestClient` with `RetryingInterceptor`, which uses Spring Framework's `RetryTemplate`. By default a call is tried 3 times, waiting about 100 ms and then 200 ms (doubling, capped at 1 s), each wait lengthened at random by up to 50 ms (Spring never shortens it below the base delay) so that callers which failed together do not all retry together (`CREDIT_RETRY_*`). What is retried depends on whether repeating the call is safe:

| Call | Safe to repeat? | Retried on |
|---|---|---|
| web server → score calculator, agent → score calculator, MCP server → NRG | yes (pure computations) | any I/O failure or timeout; 429, 502, 503, 504 |
| web server, agent → MCP `resources/read` | yes (a read) | only when the MCP server did no work: the request never arrived (the client reconnects), or it was refused with 429, 502 or 503 (retried after 1 s). Never after a timeout, and never on an MCP error answer: the MCP server already retries NRG, and a second read would double that work |
| web server → KYC | no (a real provider may bill or record each check) | only failures that prove the request never arrived (connection refused, connect timeout, unknown host), and 429 or 503 |
| client agent → intelligence agent (A2A) | no (it may be running a slow LLM call) | only failures that prove the request never arrived; never after a timeout |
| web server → DynamoDB | yes | the AWS SDK's standard retry mode (3 attempts, exponential backoff with jitter) |

On the last try the caller sees the real failure, and maps it to 503 (or 502 in the client agent) as before. Client errors and 500s are never retried: they would only repeat.

**`Retry-After` is honoured.** A 429 or 503 from our services carries `Retry-After` (at least 1 s). A retry sooner would only be refused again, so the HTTP clients wait that long before retrying (then the usual backoff). If a server asks for longer than the 1 s maximum wait, the client stops retrying and fails at once instead of holding a thread. The MCP client cannot see response headers, so it waits 1 s, the smallest `Retry-After` the services send, before retrying a refusal.

**The LLM is tried once.** The agent's model call has its own timeout and no automatic retries (LangChain4j would otherwise make 3 tries of up to 30 s). Any failure falls back to the rule-based advice, so the user still gets recommendations.

**Admission control: token bucket and bulkhead.** `web-support` adds `InboundLimitFilter` to every service through Boot auto-configuration. A token bucket admits `CREDIT_INBOUND_RATE_PER_SECOND` requests a second (50) with bursts up to `CREDIT_INBOUND_BURST` (100); beyond it a request gets 429 with `Retry-After`. At most `CREDIT_INBOUND_MAX_CONCURRENT` requests (32) run at once; beyond that a request gets 503 with `Retry-After: 1` instead of queueing on a thread. The KYC service, which decodes images in memory, admits 4 at once. Both answers are ones the callers retry with backoff, so a short overload slows callers down instead of failing them. Health checks (`/actuator/*`) are never limited. In the web server the shared limits cover only `/api/*`, so page loads do not use up the bucket, and its per-IP limit (`RATE_LIMIT_PER_MINUTE`) runs first: one client over its own limit is stopped before it can drain the shared bucket and get everyone else refused.

**Body limits.** Every endpoint that reads a body caps it before reading (`RequestSizeLimitFilter`): score calculator, NRG, KYC, MCP (`/mcp`, 64 KiB), the A2A endpoint (64 KiB), the client agent (4 KiB), the report generator (16 KiB) and the web server's JSON endpoints (64 KiB; registration is multipart with its own 5/6 MB limits).

## Multi-bureau model

The generator writes each consumer's history three times, to `~/Reports/Equifax/`, `~/Reports/Experian/` and `~/Reports/TransUnion/`, under the same HMAC file name. The bureaus do not publish JSON schemas, so each format is an assumption modelled on public descriptions of their reports and of the Metro 2 furnisher format. The records are in `common/…/bureau/`, and `BureauCodes` holds the code tables both sides use.

| Bureau | Dates | Account type | Status and late payments | Other differences |
|---|---|---|---|---|
| Equifax | `MM/dd/yyyy`, opened `MM/yyyy` | Metro 2 code (`18`, `00`, `26`, `12`, `01`) | Rate `R1`/`I9` (portfolio letter + manner of payment), `closed` flag, 30/60/90 counters | Name split into first/last; SSN last four only |
| Experian | `yyyyMMdd` | Metro 2 code | Status `11` open, `13` closed, `97` charged off; late payments only in the 84-month `paymentProfile` grid (`0`–`3`) | Amounts as strings; names upper case, `LAST, FIRST` |
| TransUnion | ISO `yyyy-MM-dd` | Industry code (`BC`, `FA`, `FM`, `ES`, `FP`) | Rating `01`/`09`, remark `CLOSED`, `historicalCounters` | `creditLimit` null for instalment trades |

The views differ the way real files do: each lender reports to each bureau with 85% probability (always at least one), bureaus last updated an account on different days so older views show older balances, and each hard inquiry appears at exactly one bureau.

**NRG merge rules.** Every report must carry the same SSN last four, or NRG answers 422. Accounts are matched across bureaus by type, creditor (case and punctuation ignored) and account number last four. The balance comes from the newest update. Limit, age and each late counter take the highest value. Status is charged off if any bureau says so, otherwise the newest bureau's status. Inquiries are combined, de-duplicated by subscriber and date, and counted over the 12 months before the newest report. Collections and public records take the highest count. A consumer missing at some bureaus is merged from the ones that have a file.

## User registration and login

```mermaid
sequenceDiagram
    participant UI as Web UI
    participant WS as web-server :8080
    participant K as kyc-service :8087
    participant U as DynamoDB users
    participant S as DynamoDB sessions
    UI->>WS: POST /api/v1/auth/register (multipart: userId, password, email, phone, ssnCard image)
    WS->>WS: check fields, password rules, user id free (400 / 409, no KYC call)
    WS->>K: POST /api/v1/ssn-verifications (multipart: document)
    alt photo blurred, not a card, or unreadable
        K-->>WS: REJECTED + reason (IMAGE_BLURRY, NOT_AN_SSN_CARD, …)
        WS-->>UI: 422 {reason, detail}; UI clears the photo and asks for a new one
    else card verified
        K-->>WS: VERIFIED {ssn, verificationId}
        WS->>U: TransactWriteItems: user (BCrypt hash, SSN HMAC, KYC id) + ssn#hash claim, both if not exists
        WS-->>UI: 201, or 409 if the user id or the SSN is taken
    end
    UI->>WS: POST /api/v1/auth/login {userId, password}
    WS->>U: GetItem userId
    WS->>S: PutItem session (SSN hash, expiresAt = now + 5 min)
    WS-->>UI: JWT (sub = userId, jti = sessionId)
    UI->>WS: POST /api/v1/credit-score {ssn}, Authorization: Bearer JWT
    WS->>S: GetItem jti; refuse if missing or idle 5 min; else refresh expiresAt
    WS->>WS: HMAC(ssn) == session SSN hash? else 403
    WS-->>UI: score (flow 2–10)
    UI->>WS: POST /api/v1/auth/logout
    WS->>S: DeleteItem session
```

**SSN from the card, not the keyboard.** Registration has no SSN field. The user uploads a photo of their Social Security card and the web server sends it to the KYC provider through `IdentityVerifier` (`KycHttpClient`). The SSN bound to the account is the one the provider read, so nobody can claim another person's SSN by typing it. Field, password and user-id checks run first, so a bad form never costs a KYC call. The web server only forwards PNG and JPEG files (by magic bytes) up to 5 MB, and never decodes them itself.

**The dummy KYC provider.** `kyc-service` stands in for an external provider and runs the checks a real one does, in order: the file is a PNG or JPEG of sensible size (pixel count checked from the header before decoding); the photo is sharp enough; it is an SSN card; a valid SSN can be read. Sharpness is the variance of the Laplacian of the grey-scale image scaled to 1000 px wide; below `KYC_MIN_SHARPNESS` (default 100) the answer is `REJECTED` with reason `IMAGE_BLURRY`, and the UI shows "The image is too blurry to read. Take a sharper photo…" and asks for a new photo while keeping the rest of the form. Instead of OCR, it reads a label its own sample cards carry in their PNG metadata, which keeps the demo deterministic. Sample cards come from `GET /api/v1/sample-cards/{ssn}?blur=N` when `KYC_SAMPLE_CARDS_ENABLED=true` (compose only). A sharp sample scores about 1,450, blur 2 about 140 and blur 3 about 48.

| Provider answer | Web server | UI |
|---|---|---|
| `VERIFIED`, SSN, verification id | creates the user (409 if the SSN or id is taken) | logs in |
| `REJECTED` `IMAGE_BLURRY` | 422, `reason: IMAGE_BLURRY` | "too blurry", clears the photo, asks to retry |
| `REJECTED` `UNSUPPORTED_IMAGE`, `IMAGE_SIZE`, `NOT_AN_SSN_CARD`, `SSN_UNREADABLE` | 422 with that reason | the reason's message, asks for a new photo |
| error, timeout, or a malformed answer | 503 | "try again in a few minutes" |

**Tables.** `credit-agent-users` is keyed by `userId` (stored lower case) and holds the BCrypt hash, email, phone, the SSN's HMAC (`ssnHash`) and last four digits, the KYC verification id (`kycVerificationId`), the credit report lock (`reportStatus`: `LOCKED` or `UNLOCKED`), and creation time. The same table holds one `ssn#<hash>` item per user, written in the same transaction, so an SSN cannot be registered twice. The SSN itself is never stored. `credit-agent-sessions` is keyed by `sessionId` and holds `userId`, the SSN hash and last four, `createdAt`, `lastActivityAt` and `expiresAt` (epoch seconds, the table's TTL attribute).

**Credit report lock.** A signed-in user can lock or unlock their own credit report from the UI. `GET /api/v1/credit-report/status` returns `{"reportStatus":"LOCKED"|"UNLOCKED"}` and `PUT` with the same body sets it; both need the bearer token, and the user id comes from the session, so a user can only change their own status. The value is the `reportStatus` attribute on the user's item. New accounts start `UNLOCKED`, and rows written before the attribute existed read as `UNLOCKED`. The update is conditional (`attribute_exists` on the user id and password hash), so it can never create a user or touch an `ssn#` claim item. 

**A locked report is refused to third parties.** The owner always sees their own report: the web server's score lookup is for the signed-in user's own SSN and never checks the lock. Third parties check it before every pull and refuse a locked report:

| Third-party path | Check | Locked answer |
|---|---|---|
| Client agent → Credit Intelligence Agent (A2A) | the agent, before it reads the report | `{"error":"This credit report is locked by its owner."}`, which the client agent returns as 422 |
| Any MCP host → `get_credit_report` tool | the MCP server, before it reads the files | the tool text "This credit report is locked by its owner." |
| Any MCP host → `resources/read` without the service token | the MCP server, before it reads the files | MCP error `-32010` "This credit report is locked by its owner." |

The check is `POST /internal/v1/report-locks/lookup` on the web server, which owns the users table, with `{"ssn":"…"}` in the body. It needs the shared `INTERNAL_API_TOKEN` as a bearer token (`InternalApiFilter`, compared in constant time, on the normalised path); without a configured token the internal API answers 404. The web server hashes the SSN, reads the `ssn#<hash>` claim to find the owner, and answers `{"locked":true|false}`. An SSN with no account answers `false`, the same as an unlocked one, so the endpoint does not reveal who has registered. The callers retry it like any read, and **fail closed**: if the lock cannot be checked, the pull is refused ("temporarily unavailable"), because a lock that silently stops working would be worse than an outage. Internal callers identify themselves to the MCP server with the same token (`Authorization: Bearer`, checked by `ServiceToken` in constant time): the web server, which reads only for the signed-in owner, and the agent, which has already checked the lock. Their `resources/read` calls are not checked again; every other caller's are. On the web server, `InternalApiFilter` runs before the per-IP and shared limits, so a caller without the token is turned away before it can use up the shared bucket. The lock check has its own 12 s timeout (longer than the web server's two DynamoDB reads) and is retried only when the request never arrived, so a slow lookup is never repeated.

**Own report only.** `CreditScoreController` hashes the requested SSN with the same key and compares it, in constant time, with the session's hash. A mismatch is 403 "You can only look up your own credit report." and happens before the MCP server is called.

**Session end.** A session ends on logout, when a request arrives after 5 idle minutes (the session is deleted and the request refused), or when `SessionSweeper` finds it expired (every minute). DynamoDB's TTL is a last resort, since it can take days. The JWT itself also expires after 1 hour, whatever the activity. To save writes, `lastActivityAt` is refreshed at most every 30 seconds.

**Why not Spring Security.** The app needs one bearer-token filter and a password hasher, so it uses `spring-security-crypto` and Nimbus JOSE directly. Moving to Spring Security's resource server (and OIDC) is the natural next step for production.

## Claude connector

```mermaid
sequenceDiagram
    participant U as User (browser)
    participant C as Claude
    participant CC as claude-connector
    participant W as web-server (OAuth)
    C->>CC: POST /mcp (no token)
    CC-->>C: 401 WWW-Authenticate: resource_metadata=…
    C->>CC: GET /.well-known/oauth-protected-resource
    C->>W: GET /.well-known/oauth-authorization-server
    C->>W: POST /oauth/register (redirect_uris)
    W-->>C: client_id (signed, stateless)
    C->>U: open /oauth/authorize?…&code_challenge (S256)&resource
    U->>W: user id + password + SSN, Allow
    W-->>U: 302 to Claude's callback ?code&state&iss
    C->>W: POST /oauth/token (code, code_verifier)
    W->>W: open grant row (single use per code)
    W-->>C: access token (JWE: user, grant, SSN, aud = connector)
    C->>CC: POST /mcp tools/call + Bearer
    CC->>W: POST /internal/v1/connector/introspect (service token)
    W-->>CC: user id, SSN (grant still open)
    CC-->>C: tool result
```

**Where it runs.** The connector is its own service, so the web server stays the UI and the identity owner, and the connector can be scaled or switched off on its own. The web server is the authorization server, because it holds the accounts.

**No SSN from Claude.** Tools have no SSN argument. The SSN the connector uses comes from the access token, which the web server encrypted (JWE `dir`/`A256GCM`) when the user consented. Claude holds the token but cannot read it, and the SSN never appears in a prompt or a transcript. The consent form asks for the SSN because the users table keeps only its keyed hash; it is checked against that hash before a code is issued.

**Mostly stateless OAuth.** Client ids are signed JWTs holding the client's redirect URIs, so dynamic registration writes nothing. Authorization codes are encrypted JWTs with a 60-second life. The state is two rows in the sessions table, written when a code is exchanged. A *code used* marker (`code#<grant id>`, a conditional put that lives as long as the code) makes each code single use, even after the grant is revoked; a reused code is refused and the grant it opened is revoked. The *grant* row makes tokens revocable (`/oauth/revoke`) before they expire. A grant is not extended by use, unlike a login session.

**Trade-offs.**
* *No refresh tokens.* Rotating refresh tokens safely needs reuse detection and more state. Access tokens therefore last one hour (`OAUTH_ACCESS_TOKEN_TTL`) and the user reconnects after that. Add refresh tokens with rotation if reconnecting becomes a nuisance.
* *Introspection on every request.* The connector asks the web server about the token on every MCP request (one DynamoDB read). Revocation therefore takes effect at once, at the cost of a hop. A short, bounded cache would cut the load if it ever matters, but revocation would then take that long to apply.
* *Owner reads are not lock-checked.* Like the web UI, the connector reads the report with the service token: the owner can always see their own report. Advice comes from the Credit Intelligence Agent, a third party, so it is refused while the report is locked, and the tool says how to unlock it.
* *Two public hosts.* Claude must reach both the connector and the web server. A reverse proxy can put both behind one host by path.

**Limits.** The connector has the shared inbound limits (token bucket and concurrency cap), plus a per-user window (`CONNECTOR_REQUESTS_PER_USER_PER_MINUTE`, through the same `KeyedRateLimiter` the web server uses per IP). A request with no token, or one that is not even shaped like a JWE (five base64url parts, at most 4 KB), is refused without calling the web server. A JWE-shaped forgery still costs one introspection call, but the web server rejects it after a local decryption failure, with no DynamoDB read, and the shared inbound limits bound how many arrive. On the web server, the sign-in form (`/oauth/authorize`) is limited per IP like the API. The token, registration and revocation endpoints are not, because Claude's servers share a few addresses among every user. A guessed code or token is useless anyway.

**Timeouts.** Every outbound call is bounded: the web server takes 12 s (one try may wait on two DynamoDB calls), the report read 20 s, the score 12 s, and advice 95 s (longer than the client agent's own 90 s). Introspection, status reads and lock changes are retried with backoff (setting the lock to a value is idempotent). Advice is retried only when it never arrived.

## Chat (User Advisor Agent)

```mermaid
sequenceDiagram
    participant B as Browser
    participant W as web-server
    participant M as MCP server
    participant A as user-advisor-agent
    B->>W: POST /api/v1/chat {ssn, message} + JWT
    W->>W: own SSN? (403 if not)
    W->>M: resources/read (the user's report)
    W->>A: POST /internal/v1/conversations/{hash(session)}/messages {message, report}
    A->>A: input guardrails (normalise → abuse → injection → protected → other person → fraud → topic → redact)
    A->>A: model(question, history, report)
    A->>A: output guardrails (offensive/protected → leak → promises → redact → cap) + disclaimer
    A->>A: remember the checked turn
    A-->>W: {reply, disclaimer, refused}
    W-->>B: the same
    B->>W: POST /api/v1/auth/logout
    W->>A: DELETE /internal/v1/conversations/{hash(session)}
```

**Why a separate agent.** The guardrails, the memory and later the LLM are the parts most likely to change and to need their own scaling, so they sit apart from the web server. The agent is internal only: every route but health needs the service token.

**Guardrails outside the model.** Every check is deterministic code before or after the model, so it holds for any model plugged in later (OWASP LLM01, LLM05, LLM07). Text is normalised first: NFKC, invisible characters removed, look-alike letters and digit spellings folded. Without that, `Іgnоrе previous instructions` in Cyrillic, or `sh1t`, gets past a word list. Refusals are fixed texts and never echo the input. An answer that fails the output checks is replaced whole rather than edited. Only checked text is stored, so a successful injection cannot poison later turns (ASI06). Everything placed inside the prompt's data blocks is escaped (`&`, `<`, `>`), so no spelling of a closing tag can end a block early. Greetings are not remembered, so they cannot open the door for an off-topic "follow-up".

**Memory trade-offs.** The spec asks for memory that is not persistent, expires on logout or inactivity, and is evicted LRU under memory pressure. The agent therefore holds state. Run one instance, or route by conversation id (sticky sessions); a restart forgets every conversation, which costs users only their context. All limits are bounded: idle TTL (5 minutes, matching the login session; reads count as use), turns per conversation (20, each capped at 1,000 + 2,000 characters by the guardrails), conversations (10,000, LRU), and a heap-pressure check on every write (85% of the old generation still live after the last GC) that drops the least recently used half. LRU was chosen over LFU: chats are short-lived, so recency predicts reuse better than frequency.

**The report travels with every question**, as the spec says, without the name, masked SSN and report id. That costs one MCP read per message. Caching the report in the agent would save it, but would put more personal data in a stateful service.

**Resilience.** Each try waits at most `ADVISOR_TIMEOUT` (15 s). A chat message is retried only when it provably never arrived (connection refused, connect timeout) or was refused with 429 or 503 before any work, because each message adds to the conversation. With the default 3 tries, the worst case is therefore about 47 s (`RetrySettings.worstCase`); a read timeout is never retried. Forgetting a conversation is retried on any transient failure, and runs on a virtual thread after logout: a slow or unreachable agent never delays the logout, and the conversation expires on its own anyway.

**Eval log.** Every turn is also written as one JSON line to its own rolling file for offline evals: the question, the input verdict, the model's raw answer, what was shown, the output verdict, the model and its latency. It goes through a dedicated logger (`advisor-eval`, outside every package, so no class log lines reach the file) and an asynchronous appender with a bounded queue that drops lines rather than block a chat. Everything written is redacted; the report and the user's identity never are. Retention is bounded (30 days, 500 MB). Trade-off: a raw answer the output guardrails replaced is kept in the log, because that is exactly what an eval of the guardrails needs. The agent has the shared inbound limits (token bucket and concurrency cap) and a 512 KB body limit, and the web server's per-IP limit covers `/api/v1/chat`.

## Card offers

```mermaid
sequenceDiagram
    participant B as Browser
    participant W as web-server
    participant D as users table
    participant O as offers-service
    Note over W,D: registration: KYC reads the SSN → score computed in the background → lastScore stored
    Note over W,D: every score look-up also updates lastScore
    B->>W: POST /api/v1/auth/login
    W->>D: user (zip, lastScore)
    W->>O: POST /api/v1/offers {score, zip}
    O->>O: REST → MCP tools/call get_credit_card_offers → eligible → priced → ranked
    O-->>W: up to 10 offers (Schumer-box terms)
    W-->>B: token + offers
```

**Score at login.** Login has no SSN (only its keyed hash is stored), so it cannot compute a score. The account therefore keeps the latest score and when it was computed: recorded at registration (the one time the server holds the SSN without the user typing it, from the KYC check; computed on a virtual thread so registration does not wait) and at every score look-up. Trade-off: offers can be based on a score that is days old; the widget says which score they were matched on.

**REST plugs into MCP.** The Offers service has one entry to its logic, the MCP tool `get_credit_card_offers`. `POST /api/v1/offers` builds an MCP `tools/call` and runs it through the same LangChain4j `McpServer`, so the two interfaces cannot drift apart. The price is a JSON round trip inside the process, small next to the HTTP hop.

**What the service sees.** A score and a ZIP code only: no name, SSN or user id. Both interfaces still need the internal service token (only the web server and internal agents call them), and the MCP endpoint refuses requests that carry an `Origin`. It accepts string or numeric JSON-RPC ids (LangChain4j's `McpServer` takes numbers only, so the handler maps them).

**Honest terms, sensible ranking.** Every Schumer-box term is the product's own; an unknown one is null ("see issuer terms"), never a default. Cards for building credit (secured, builder, student, fair credit) are offered only below 670, and the match score gives 20% to fit, so a 700 sees cash-back and travel cards, not a student card. Marketplace data is validated before ranking, since issuer text reaches agents' context.

**Resilience.** Login asks once and waits at most `OFFERS_LOGIN_TIMEOUT` (1.5 s, no retry), so a hung Offers service costs a login 1.5 s, not the ~11 s three retried tries would. `GET /api/v1/offers` uses `OFFERS_TIMEOUT` (3 s) a try with retries. Any failure, the account read included, becomes `UNAVAILABLE`, never an error; a failed score write never fails a score look-up. Up to 10 offers are shown even if the service returned more, and the web server adds its own disclaimer if the service sent none. After registration the page re-asks at 2, 5 and 10 s, since the first score is computed in the background.

**Placeholder marketplace.** Fictional issuers modelled on typical market score floors per card type. Real offers will come from a marketplace with many issuers; `OfferMarketplace` is the seam.
