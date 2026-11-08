# CreditAgent

A web app that shows a credit score as a coloured ring, plus a pair of A2A agents that turn a credit report into recommendations for raising the score.

* **Web flow:** browser → Spring Boot web server → Credit Report **MCP** server → Score Calculator → score ring.
* **Agent flow:** Client Agent → (**A2A**) → Credit Intelligence Agent → MCP server + Score Calculator → recommendations.

See [docs/architecture.md](docs/architecture.md) for the diagram and design notes, and [docs/security-review.md](docs/security-review.md) before putting real data anywhere near this.

> ⚠️ **Demo only.** As specified, there is no authentication and services talk over plain HTTP. Use synthetic SSNs only.

## Stack

* Java 26, Spring Boot 4.1
* LangChain4j 1.23: MCP client, `McpServer` for MCP tools, agentic A2A client, and an optional Anthropic chat model
* a2a-java SDK 1.4 (A2A server)
* Maven multi-module build; Node 22 for the UI unit tests (no npm dependencies)

## Modules and ports

| Module | Port | What it does |
|---|---|---|
| `web-server` | 8080 | UI and `POST /api/v1/credit-score` |
| `score-calculator` | 8081 | `POST /api/v1/scores` (FICO-style five-factor scorecard, 300–850) |
| `credit-report-mcp-server` | 8082 | MCP at `/mcp`; resource `credit-report://ssn/{ssn}`, tool `get_credit_report` |
| `credit-report-generator` | 8083 | `POST /api/v1/reports`; writes credit files |
| `credit-intelligence-agent` | 8085 | A2A agent; card at `/.well-known/agent-card.json` |
| `client-agent` | 8086 | `POST /api/v1/advice`; calls the agent over A2A |
| `common`, `service-clients` | — | Shared libraries |

## Build and test

```bash
mvn -B verify                 # compile, unit tests, MCP and A2A contract tests, coverage
(cd web-server && npm test)   # score-ring unit tests
```

## Run locally with Docker

```bash
mvn -B package -DskipTests
cp .env.example .env          # set CREDIT_FILE_HMAC_KEY (openssl rand -hex 32)
docker compose up --build
```

Then:

* open http://localhost:8080 and enter one of the demo SSNs from `.env` (for example `123-45-6789`);
* get recommendations through the agents:
  ```bash
  curl -s localhost:8086/api/v1/advice -H 'Content-Type: application/json' -d '{"ssn":"123-45-6789"}'
  ```

## Run without Docker

Start each service with `mvn -pl <module> spring-boot:run`. The generator and the MCP server need the same key and directory:

```bash
export CREDIT_FILE_HMAC_KEY=$(openssl rand -hex 32) CREDIT_FILES_DIR=$PWD/data/credit-files
CREDIT_SEED_SSNS=123-45-6789 mvn -pl credit-report-generator spring-boot:run
```

## Configuration

| Variable | Used by | Default |
|---|---|---|
| `CREDIT_FILE_HMAC_KEY` | generator, MCP server | **required**, at least 32 characters |
| `CREDIT_FILES_DIR` | generator, MCP server | `./data/credit-files` |
| `CREDIT_SEED_SSNS` | generator | none |
| `CREDIT_MCP_URL` | web server, agent | `http://localhost:8082/mcp` |
| `SCORE_CALCULATOR_URL` | web server, agent | `http://localhost:8081` |
| `RATE_LIMIT_PER_MINUTE` | web server | `20` |
| `SCORE_MAX_REQUEST_BYTES` | score calculator | `262144` (256 KiB; larger bodies get 413) |
| `AGENT_PUBLIC_URL` | agent | `http://localhost:<port>` |
| `INTELLIGENCE_AGENT_URL` | client agent | `http://localhost:8085` |
| `LLM_ENABLED`, `ANTHROPIC_API_KEY`, `LLM_MODEL` | agent | `false`, none, `claude-sonnet-5-5` |

## CI/CD

| Workflow | Runs on | What it does |
|---|---|---|
| `ci.yml` | PRs, pushes to `main` | Maven verify on Java 26, UI tests, image build with Trivy scan; pushes `ghcr.io/<owner>/creditagent-<service>` from `main` |
| `codeql.yml` | PRs, `main`, weekly | CodeQL for Java, JavaScript and the workflows |
| `security.yml` | PRs, `main` | Dependency review and Gitleaks secret scan |
| `release.yml` | tags `v*.*.*` | Versioned images with SBOM and provenance, plus a GitHub release |

Dependabot keeps Maven, npm, Docker and action pins up to date.
