# CreditAgent

A web app that shows a credit score as a coloured ring, plus a pair of A2A agents that turn a credit report into recommendations for raising the score.

* **Login:** users register by uploading a photo of their Social Security card, which a (dummy) **KYC service** checks and reads the SSN from; a blurred photo is refused with a request to retry. They then log in through the web UI; accounts and sessions are in DynamoDB, and the score API needs the JWT issued at login. A user can only see the credit report for their own SSN; asking for any other SSN gets 403. Sessions end on logout or after 5 idle minutes.
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
| `web-server` | 8080 | UI, `GET /api/v1/offers` (the user's card offers; also returned by login), `GET /api/v1/alerts` (dark-web alerts for the user's email; also returned by login),  `POST /api/v1/chat` (`{ssn, message}`, needs the JWT; own SSN only), `POST /api/v1/auth/register` (multipart, with the `ssnCard` photo), `POST /api/v1/auth/{login,logout}`, `GET /api/v1/auth/session`, `GET`/`PUT /api/v1/credit-report/status` (lock or unlock the user's own report; a locked report is refused to third parties), internal `POST /internal/v1/report-locks/lookup` (needs `INTERNAL_API_TOKEN`), and `POST /api/v1/credit-score` (needs `Authorization: Bearer <jwt>`; only the user's own SSN) |
| `score-calculator` | 8081 | `POST /api/v1/scores` (FICO-style five-factor scorecard, 300–850) |
| `credit-report-mcp-server` | 8082 | MCP at `/mcp`; resource `credit-report://ssn/{ssn}`, tool `get_credit_report`. Reads all three bureau reports and returns NRG's merged report |
| `credit-report-generator` | 8083 | `POST /api/v1/reports`; writes one report per bureau to `~/Reports/<Bureau>/`, each in that bureau's format |
| `normalized-report-generator` | 8084 | NRG: `POST /api/v1/normalized-reports`; merges the three bureau reports into one |
| `credit-intelligence-agent` | 8085 | A2A agent; card at `/.well-known/agent-card.json` |
| `client-agent` | 8086 | `POST /api/v1/advice`; calls the agent over A2A |
| `offers-service` | 8090 | Card offers for a score and ZIP code: MCP at `/mcp` (tool `get_credit_card_offers`), and `POST /api/v1/offers` (`{score, zip}`), which calls that tool through the MCP server. Ranks up to 10 offers from a placeholder marketplace |
| `dark-web-monitor` | 8091 | Runs continuously: every `DARKWEB_SCAN_INTERVAL` it checks registered users' emails against known dark-web locations and writes each exposure to the `Alerts` DynamoDB table. The source is a placeholder that contacts nothing |
| `alerts-service` | 8092 | Internal only (service token): `POST /internal/v1/alerts/lookup` (`{email}`) answers whether the `Alerts` table holds a record for that email, and where it was found |
| `user-advisor-agent` | 8089 | Chat back end, internal only (service token): `POST /internal/v1/conversations/{id}/messages`, `DELETE /internal/v1/conversations/{id}`. Input and output guardrails, a placeholder LLM, per-conversation memory in RAM |
| `claude-connector` | 8088 | Remote MCP server for Claude at `/mcp` (OAuth 2.1 protected resource); tools `get_credit_score`, `get_credit_report`, `get_credit_advice`, `get_report_lock_status`, `set_report_lock`. The web server is its authorization server (`/oauth/*`, `/.well-known/oauth-authorization-server`) |
| `kyc-service` | 8087 | Dummy KYC provider: `POST /api/v1/ssn-verifications` (multipart `document`) refuses blurred or non-card photos and returns the SSN on the card; `GET /api/v1/sample-cards/{ssn}?blur=N` makes demo cards when enabled |
| `common`, `service-clients`, `web-support`, `alerts-store` | — | Shared libraries (`alerts-store`: the `Alerts` table, used by the monitor and the alerts service) |

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
cp .env.example .env          # set CREDIT_FILE_HMAC_KEY, JWT_SECRET, SSN_HMAC_KEY and INTERNAL_API_TOKEN (openssl rand -hex 32 each)
docker compose up --build
```

Then:

* download a sample SSN card for one of the demo SSNs from `.env`, and a blurred one to see the retry:
  ```bash
  curl -o card.png localhost:8087/api/v1/sample-cards/123-45-6789
  curl -o blurry.png 'localhost:8087/api/v1/sample-cards/123-45-6789?blur=4'
  ```
* open http://localhost:8080, register with `card.png` as the card photo (`blurry.png` is refused with "too blurry … try again"), log in, and look up that SSN;
* get recommendations through the agents:
  ```bash
  curl -s localhost:8086/api/v1/advice -H 'Content-Type: application/json' -d '{"ssn":"123-45-6789"}'
  ```

## Run without Docker

Start each service with `mvn -pl <module> spring-boot:run`. Registration needs the KYC service (`KYC_SAMPLE_CARDS_ENABLED=true mvn -pl kyc-service spring-boot:run`). The web server needs DynamoDB, for example DynamoDB Local:

```bash
docker run -d -p 8000:8000 amazon/dynamodb-local:3.3.2 -jar DynamoDBLocal.jar -inMemory -sharedDb
JWT_SECRET=$(openssl rand -hex 32) SSN_HMAC_KEY=$(openssl rand -hex 32) DYNAMODB_ENDPOINT=http://localhost:8000 DYNAMODB_CREATE_TABLES=true \
  AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local mvn -pl web-server spring-boot:run
```

 The generator and the MCP server need the same key and reports directory (default `~/Reports`), and the MCP server needs NRG running:

```bash
export CREDIT_FILE_HMAC_KEY=$(openssl rand -hex 32) CREDIT_REPORTS_DIR=$HOME/Reports
CREDIT_SEED_SSNS=123-45-6789 mvn -pl credit-report-generator spring-boot:run
mvn -pl normalized-report-generator spring-boot:run
mvn -pl credit-report-mcp-server spring-boot:run
```

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
| `dark-web-monitor` | Runs continuously. Each cycle (`DARKWEB_SCAN_INTERVAL`, 1 minute after the previous one ends) it reads registered users' emails from the users table a page at a time (at most `DARKWEB_MAX_EMAILS_PER_CYCLE` rows a cycle; the next cycle carries on where this one stopped), checks each against every location in `DARKWEB_LOCATIONS`, and writes what it finds to `Alerts`. Only registered users are watched, so nothing is stored about anyone else |
| Placeholder source | Nothing on the dark web is contacted. Emails in `DARKWEB_PLACEHOLDER_EXPOSED` are always "found"; otherwise a fixed hash of the email and location marks about `DARKWEB_PLACEHOLDER_RATE` (2%) of pairs as exposed, the same every time. Replace the `DarkWebSource` bean to plug in a real breach-data provider |
| `alerts-service` | Internal look-up for the web server: `POST /internal/v1/alerts/lookup` with the email in the body (never in a URL or a log), behind the service token |
| Login | The web server asks `alerts-service` for the user's email, in parallel with the offers look-up, once, waiting at most `ALERTS_LOGIN_TIMEOUT` (1.5 s). If a record exists, the page shows a banner: the user's information was found on the dark web, with the locations and dates, and a suggestion to buy identity protection. If the service is down, login still works and the page asks again later via `GET /api/v1/alerts` (retried) |

To see the banner locally, set `DARKWEB_PLACEHOLDER_EXPOSED` to a registered user's email; it shows at that user's next login after the monitor's next cycle.

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
| `CREDIT_FILE_HMAC_KEY` | generator, MCP server | **required**, at least 32 characters |
| `CREDIT_REPORTS_DIR` | generator, MCP server | `~/Reports` (bureau files in `Equifax/`, `Experian/`, `TransUnion/`). The old name `CREDIT_FILES_DIR` is still read as a fallback; files in the old flat layout are not migrated, so regenerate them |
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
| `INTERNAL_API_TOKEN` | web server, agent, MCP server, connector, advisor agent, offers service | **required**, at least 32 characters. The agent and the MCP server present it to the web server to check report locks, and the web server and the agent present it to the MCP server so their own reads are not lock-checked again. Without it, third-party pulls are refused and the web server's own lookups are lock-checked like a third party's |
| `REPORT_LOCK_URL` | agent, MCP server | `http://localhost:8080` (the web server, which owns the users table) |
| `JWT_SECRET` | web server | **required**, at least 32 characters |
| `SSN_HMAC_KEY` | web server | **required**, at least 32 characters; keyed hash of each user's SSN |
| `JWT_TTL`, `SESSION_IDLE_TIMEOUT` | web server | `1h`, `5m` |
| `DYNAMODB_ENDPOINT` | web server | empty = AWS; `http://localhost:8000` for DynamoDB Local |
| `AWS_REGION`, `USERS_TABLE`, `SESSIONS_TABLE` | web server | `us-east-1`, `credit-agent-users`, `credit-agent-sessions` |
| `DYNAMODB_CREATE_TABLES` | web server | `false` (compose sets `true` for DynamoDB Local) |
| `DYNAMODB_TIMEOUT` | web server | `5s` (longest DynamoDB call, retries included; the SDK's standard retry mode backs off with jitter) |
| `CREDIT_RETRY_MAX_ATTEMPTS`, `CREDIT_RETRY_INITIAL_DELAY`, `CREDIT_RETRY_MULTIPLIER`, `CREDIT_RETRY_MAX_DELAY`, `CREDIT_RETRY_JITTER` | every service that calls another | `3`, `100ms`, `2`, `1s`, `50ms`: retries of recoverable failures with exponential backoff and jitter (see [architecture](docs/architecture.md#resilience)) |
| `OAUTH_TOKEN_KEY` | web server | empty = OAuth (and so the Claude connector's sign-in) is off. At least 32 characters; signs client ids and encrypts codes and access tokens |
| `OAUTH_ISSUER` | web server, connector | `http://localhost:8080`: the web server's public URL, exactly as Claude and browsers reach it |
| `CONNECTOR_RESOURCE_URL` | web server | `http://localhost:8088/mcp`: the only audience tokens are issued for; must equal the connector's `<CONNECTOR_PUBLIC_URL>/mcp` |
| `OAUTH_ACCESS_TOKEN_TTL`, `OAUTH_ALLOW_LOOPBACK_REDIRECTS` | web server | `1h`, `true` (loopback redirects serve Claude Code and Desktop; Claude's two web callbacks are always allowed) |
| `OFFERS_URL`, `OFFERS_TIMEOUT`, `OFFERS_LOGIN_TIMEOUT` | web server | `http://localhost:8090`, `3s` a try for `GET /api/v1/offers` (retried), `1500ms` for the single try at login |
| `ALERTS_URL`, `ALERTS_TIMEOUT`, `ALERTS_LOGIN_TIMEOUT` | web server | `http://localhost:8092`, `3s` a try for `GET /api/v1/alerts` (retried), `1500ms` for the single try at login |
| `ALERTS_TABLE` | monitor, alerts service | `Alerts` |
| `DARKWEB_SCAN_INTERVAL`, `DARKWEB_SCAN_INITIAL_DELAY`, `DARKWEB_LOCATIONS`, `DARKWEB_MAX_EMAILS_PER_CYCLE` | monitor | `1m`, `10s`, `paste-site-alpha,breach-forum-beta,marketplace-gamma`, `10000` |
| `DARKWEB_PLACEHOLDER_EXPOSED`, `DARKWEB_PLACEHOLDER_RATE` | monitor | empty, `0.02` |
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
| `ci.yml` | PRs, pushes to `main` | Maven verify on Java 26, UI tests, image build with Trivy scan; pushes `ghcr.io/<owner>/creditagent-<service>` from `main` |
| `codeql.yml` | PRs, `main`, weekly | CodeQL for Java, JavaScript and the workflows |
| `security.yml` | PRs, `main` | Dependency review and Gitleaks secret scan |
| `release.yml` | tags `v*.*.*` | Versioned images with SBOM and provenance, plus a GitHub release |

Dependabot keeps Maven, npm, Docker and action pins up to date.
