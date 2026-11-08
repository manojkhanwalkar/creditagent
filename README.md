# CreditAgent

A web app that shows a credit score as a coloured ring, plus a pair of A2A agents that turn a credit report into recommendations for raising the score.

* **Login:** users register with their SSN and log in through the web UI; accounts and sessions are in DynamoDB, and the score API needs the JWT issued at login. A user can only see the credit report for their own SSN; asking for any other SSN gets 403. Sessions end on logout or after 5 idle minutes.
* **Web flow:** browser → Spring Boot web server → Credit Report **MCP** server (reads the Equifax, Experian and TransUnion reports; the **Normalized Report Generator** merges them) → Score Calculator → score ring.
* **Agent flow:** Client Agent → (**A2A**) → Credit Intelligence Agent → MCP server + Score Calculator → recommendations.

See [docs/architecture.md](docs/architecture.md) for the diagram and design notes, and [docs/security-review.md](docs/security-review.md) before putting real data anywhere near this.

> ⚠️ **Demo only.** Users log in to the web UI, but the services behind it, and the agent flow, have no authentication, and everything talks over plain HTTP. Use synthetic SSNs only.

## Stack

* Java 26, Spring Boot 4.1
* LangChain4j 1.23: MCP client, `McpServer` for MCP tools, agentic A2A client, and an optional Anthropic chat model
* a2a-java SDK 1.4 (A2A server)
* Maven multi-module build; Node 22 for the UI unit tests (no npm dependencies)

## Modules and ports

| Module | Port | What it does |
|---|---|---|
| `web-server` | 8080 | UI, `POST /api/v1/auth/{register,login,logout}`, `GET /api/v1/auth/session`, and `POST /api/v1/credit-score` (needs `Authorization: Bearer <jwt>`; only the user's own SSN) |
| `score-calculator` | 8081 | `POST /api/v1/scores` (FICO-style five-factor scorecard, 300–850) |
| `credit-report-mcp-server` | 8082 | MCP at `/mcp`; resource `credit-report://ssn/{ssn}`, tool `get_credit_report`. Reads all three bureau reports and returns NRG's merged report |
| `credit-report-generator` | 8083 | `POST /api/v1/reports`; writes one report per bureau to `~/Reports/<Bureau>/`, each in that bureau's format |
| `normalized-report-generator` | 8084 | NRG: `POST /api/v1/normalized-reports`; merges the three bureau reports into one |
| `credit-intelligence-agent` | 8085 | A2A agent; card at `/.well-known/agent-card.json` |
| `client-agent` | 8086 | `POST /api/v1/advice`; calls the agent over A2A |
| `common`, `service-clients`, `web-support` | — | Shared libraries |

## Build and test

```bash
mvn -B verify                 # compile, unit tests, MCP and A2A contract tests, coverage
(cd web-server && npm test)   # score-ring unit tests
```

## Run locally with Docker

```bash
mvn -B package -DskipTests
cp .env.example .env          # set CREDIT_FILE_HMAC_KEY, JWT_SECRET and SSN_HMAC_KEY (openssl rand -hex 32 each)
docker compose up --build
```

Then:

* open http://localhost:8080, register with one of the demo SSNs from `.env` (for example `123-45-6789`), log in, and look up that SSN;
* get recommendations through the agents:
  ```bash
  curl -s localhost:8086/api/v1/advice -H 'Content-Type: application/json' -d '{"ssn":"123-45-6789"}'
  ```

## Run without Docker

Start each service with `mvn -pl <module> spring-boot:run`. The web server needs DynamoDB, for example DynamoDB Local:

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
| `SCORE_CALCULATOR_URL` | web server, agent | `http://localhost:8081` |
| `RATE_LIMIT_PER_MINUTE` | web server | `20` |
| `SCORE_MAX_REQUEST_BYTES` | score calculator | `262144` (256 KiB; larger bodies get 413) |
| `AGENT_PUBLIC_URL` | agent | `http://localhost:<port>` |
| `INTELLIGENCE_AGENT_URL` | client agent | `http://localhost:8085` |
| `LLM_ENABLED`, `ANTHROPIC_API_KEY`, `LLM_MODEL` | agent | `false`, none, `claude-sonnet-5-5` |
| `JWT_SECRET` | web server | **required**, at least 32 characters |
| `SSN_HMAC_KEY` | web server | **required**, at least 32 characters; keyed hash of each user's SSN |
| `JWT_TTL`, `SESSION_IDLE_TIMEOUT` | web server | `1h`, `5m` |
| `DYNAMODB_ENDPOINT` | web server | empty = AWS; `http://localhost:8000` for DynamoDB Local |
| `AWS_REGION`, `USERS_TABLE`, `SESSIONS_TABLE` | web server | `us-east-1`, `credit-agent-users`, `credit-agent-sessions` |
| `DYNAMODB_CREATE_TABLES` | web server | `false` (compose sets `true` for DynamoDB Local) |

## CI/CD

| Workflow | Runs on | What it does |
|---|---|---|
| `ci.yml` | PRs, pushes to `main` | Maven verify on Java 26, UI tests, image build with Trivy scan; pushes `ghcr.io/<owner>/creditagent-<service>` from `main` |
| `codeql.yml` | PRs, `main`, weekly | CodeQL for Java, JavaScript and the workflows |
| `security.yml` | PRs, `main` | Dependency review and Gitleaks secret scan |
| `release.yml` | tags `v*.*.*` | Versioned images with SBOM and provenance, plus a GitHub release |

Dependabot keeps Maven, npm, Docker and action pins up to date.
