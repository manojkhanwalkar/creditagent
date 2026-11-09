# CreditAgent

A web app that shows a credit score as a coloured ring, plus a pair of A2A agents that turn a credit report into recommendations for raising the score.

* **Login:** users register by uploading a photo of their Social Security card, which a (dummy) **KYC service** checks and reads the SSN from; a blurred photo is refused with a request to retry. They then log in through the web UI; accounts and sessions are in DynamoDB, and the score API needs the JWT issued at login. A user can only see the credit report for their own SSN; asking for any other SSN gets 403. Sessions end on logout or after 5 idle minutes.
* **Web flow:** browser → Spring Boot web server → Credit Report **MCP** server (reads the Equifax, Experian and TransUnion reports; the **Normalized Report Generator** merges them) → Score Calculator → score ring.
* **Agent flow:** Client Agent → (**A2A**) → Credit Intelligence Agent → MCP server + Score Calculator → recommendations.

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
| `web-server` | 8080 | UI, `POST /api/v1/auth/register` (multipart, with the `ssnCard` photo), `POST /api/v1/auth/{login,logout}`, `GET /api/v1/auth/session`, `GET`/`PUT /api/v1/credit-report/status` (lock or unlock the user's own report; a locked report is refused to third parties), internal `POST /internal/v1/report-locks/lookup` (needs `INTERNAL_API_TOKEN`), and `POST /api/v1/credit-score` (needs `Authorization: Bearer <jwt>`; only the user's own SSN) |
| `score-calculator` | 8081 | `POST /api/v1/scores` (FICO-style five-factor scorecard, 300–850) |
| `credit-report-mcp-server` | 8082 | MCP at `/mcp`; resource `credit-report://ssn/{ssn}`, tool `get_credit_report`. Reads all three bureau reports and returns NRG's merged report |
| `credit-report-generator` | 8083 | `POST /api/v1/reports`; writes one report per bureau to `~/Reports/<Bureau>/`, each in that bureau's format |
| `normalized-report-generator` | 8084 | NRG: `POST /api/v1/normalized-reports`; merges the three bureau reports into one |
| `credit-intelligence-agent` | 8085 | A2A agent; card at `/.well-known/agent-card.json` |
| `client-agent` | 8086 | `POST /api/v1/advice`; calls the agent over A2A |
| `kyc-service` | 8087 | Dummy KYC provider: `POST /api/v1/ssn-verifications` (multipart `document`) refuses blurred or non-card photos and returns the SSN on the card; `GET /api/v1/sample-cards/{ssn}?blur=N` makes demo cards when enabled |
| `common`, `service-clients`, `web-support` | — | Shared libraries |

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
| `INTERNAL_API_TOKEN` | web server, agent, MCP server | **required**, at least 32 characters. The agent and the MCP server present it to the web server to check report locks, and the web server and the agent present it to the MCP server so their own reads are not lock-checked again. Without it, third-party pulls are refused and the web server's own lookups are lock-checked like a third party's |
| `REPORT_LOCK_URL` | agent, MCP server | `http://localhost:8080` (the web server, which owns the users table) |
| `JWT_SECRET` | web server | **required**, at least 32 characters |
| `SSN_HMAC_KEY` | web server | **required**, at least 32 characters; keyed hash of each user's SSN |
| `JWT_TTL`, `SESSION_IDLE_TIMEOUT` | web server | `1h`, `5m` |
| `DYNAMODB_ENDPOINT` | web server | empty = AWS; `http://localhost:8000` for DynamoDB Local |
| `AWS_REGION`, `USERS_TABLE`, `SESSIONS_TABLE` | web server | `us-east-1`, `credit-agent-users`, `credit-agent-sessions` |
| `DYNAMODB_CREATE_TABLES` | web server | `false` (compose sets `true` for DynamoDB Local) |
| `DYNAMODB_TIMEOUT` | web server | `5s` (longest DynamoDB call, retries included; the SDK's standard retry mode backs off with jitter) |
| `CREDIT_RETRY_MAX_ATTEMPTS`, `CREDIT_RETRY_INITIAL_DELAY`, `CREDIT_RETRY_MULTIPLIER`, `CREDIT_RETRY_MAX_DELAY`, `CREDIT_RETRY_JITTER` | every service that calls another | `3`, `100ms`, `2`, `1s`, `50ms`: retries of recoverable failures with exponential backoff and jitter (see [architecture](docs/architecture.md#resilience)) |
| `CREDIT_INBOUND_RATE_PER_SECOND`, `CREDIT_INBOUND_BURST`, `CREDIT_INBOUND_MAX_CONCURRENT`, `CREDIT_INBOUND_ENABLED`, `CREDIT_INBOUND_URL_PATTERNS` | every service | `50`, `100`, `32`, `true`, `/*` (web server: `/api/*`): token-bucket rate limit and in-flight cap; excess requests get 429 or 503 with `Retry-After`. Health checks are exempt |

## CI/CD

| Workflow | Runs on | What it does |
|---|---|---|
| `ci.yml` | PRs, pushes to `main` | Maven verify on Java 26, UI tests, image build with Trivy scan; pushes `ghcr.io/<owner>/creditagent-<service>` from `main` |
| `codeql.yml` | PRs, `main`, weekly | CodeQL for Java, JavaScript and the workflows |
| `security.yml` | PRs, `main` | Dependency review and Gitleaks secret scan |
| `release.yml` | tags `v*.*.*` | Versioned images with SBOM and provenance, plus a GitHub release |

Dependabot keeps Maven, npm, Docker and action pins up to date.
