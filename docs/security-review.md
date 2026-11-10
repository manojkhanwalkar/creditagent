# Security review — CreditAgent v0.1.0

**Date:** 2026-11-07
**Scope:** All seven modules, the UI, the Dockerfile, docker-compose, and the GitHub Actions workflows.
**Method:** Manual review against OWASP ASVS 4 (L2) and the OWASP API Security Top 10, the MCP Streamable HTTP transport security guidance, A2A protocol guidance, PII-handling practice for SSNs, and supply-chain hardening for CI.

> The project owner's own security-review skills were not available in the session that generated this project. Re-run them and append their findings here.

## Summary

The spec sends an SSN from an **unauthenticated** web page to a chain of services over plain HTTP. That is acceptable for a demo with synthetic data. It is **not** acceptable for real consumer data. Findings S-1 to S-3 must be closed before any real SSN reaches the system.

| ID | Severity | Finding | Status |
|----|----------|---------|--------|
| S-1 | High | No user authentication on the UI or API | Partly fixed: login with JWT and DynamoDB sessions; look-ups limited to the user's own SSN; the SSN is read by a KYC check of the user's card photo, but the bundled KYC provider is a dummy |
| S-2 | High | No service-to-service authentication (MCP, score calculator, generator, A2A) | Open; first step: the report-lock check uses a shared internal token (`INTERNAL_API_TOKEN`) |
| S-3 | High | SSNs travel between services over plaintext HTTP | Open |
| S-4 | Medium | Credit files are not encrypted at rest | Open |
| S-5 | Medium | SSN used as the credit file name would leak through directory listings | Fixed |
| S-6 | Medium | Path traversal through the MCP resource URI | Fixed |
| S-7 | Medium | SSNs could leak into logs, error messages and exception chains | Fixed |
| S-8 | Medium | DNS-rebinding attacks on the MCP HTTP endpoint | Fixed |
| S-9 | Medium | SSN enumeration through the public score API | Mitigated |
| S-10 | Medium | Personal data sent to a third-party LLM, plus prompt injection | Mitigated |
| S-11 | Low | Browser hardening (XSS, clickjacking, caching) | Fixed |
| S-12 | Low | The MCP tool `get_credit_report` hands full reports to any MCP host, including LLM apps | Accepted, documented |
| S-13 | Low | Request bodies have no global size limit | Mostly fixed: every untrusted-body endpoint is capped before reading; services also refuse excess load (token bucket, in-flight cap) |
| S-14 | Low | The generator API can overwrite any report | Open: internal only |
| S-15 | Low | CI supply chain (unpinned actions, broad token permissions) | Fixed |
| S-16 | Low | Container runtime privileges | Fixed |
| S-17 | Info | The HMAC key has no rotation procedure | Open |
| S-18 | Medium | Registration accepts an uploaded image (parser attack surface, oversized files) | Mitigated |
| S-23 | Low | Alert emails: the service emails users about dark-web findings | Mitigated: one email per alert (conditional claims), fixed plain-text wording with no locations or links from outside, no addresses in logs, SMTP over STARTTLS by default, sending paced |
| S-22 | Low | Dark-web alerts: a new table links users' emails to the places they were exposed | Mitigated: only registered users are checked; one account per email; internal look-up behind the service token, email in the body, never logged; placeholder source contacts nothing. Open: emails are not verified |
| S-21 | Low | Card offers: the account now keeps the user's ZIP code and latest credit score | Mitigated: only score and ZIP go to the Offers service; offers are not firm offers of credit and say so |
| S-20 | Medium | The chat sends a user's credit report and free-text questions to an (eventually LLM-backed) agent | Mitigated: own-SSN check, input and output guardrails outside the model, PII redaction, RAM-only bounded memory, internal-only agent |
| S-19 | High | The Claude connector exposes a user's credit data to an internet-facing LLM client | Mitigated: OAuth 2.1 with PKCE, own-data-only tools, encrypted tokens; data still reaches the user's Claude conversations by design |

## Findings

### S-1 User authentication (High, partly fixed)
Users now register (BCrypt cost 12; 12–64 character passwords checked against a common-password list and the user id) and log in for an HS256 JWT. Every `/api/` call except register and login needs the token, and the token is accepted only while its DynamoDB session exists and was used in the last 5 minutes. Logout deletes the session. Login answers the same message, in about the same time, for a wrong password and an unknown user, and the per-IP rate limit covers login and registration.
Registration no longer takes a typed SSN. The user uploads a photo of their Social Security card; the web server sends it to the KYC provider, which checks that the photo is sharp and is a card, and returns the SSN it read plus a verification id. A blurred photo is refused (422, `reason: IMAGE_BLURRY`) and the user is asked for a new one. Field checks and the user-id check run first, so a bad form never costs a KYC call. The users table stores only an HMAC-SHA256 of it (key `SSN_HMAC_KEY`) and its last four digits, and a second item, `ssn#<hash>`, written in the same conditional transaction as the user, so one SSN can belong to only one account. Login copies the hash into the session, and `POST /api/v1/credit-score` answers 403 for any SSN whose hash differs, before any report is fetched, so a refusal reveals nothing about the other SSN.
**Still open before real data:** the bundled `kyc-service` is a dummy. Instead of OCR and document-security checks it reads a label that its own sample cards carry in their PNG metadata, so anyone can forge a "card" for any SSN. Replace it with a real provider (document check with liveness/selfie match, or a bureau identity service) behind the same `IdentityVerifier` interface, and authenticate the call to it (S-2). The sample-card endpoint makes a card for any SSN; it is off by default and only compose turns it on. Registration answers 409 for an SSN already in use, which tells a caller that someone registered it; the rate limit slows but does not stop that. There is no account lockout, MFA or password recovery yet (email and phone are stored for recovery). The agent flow (`client-agent` `/api/v1/advice`) is not authenticated and can still ask about any SSN whose owner has not locked their report. The token is kept in `sessionStorage`, so an XSS bug could read it; the strict CSP is the mitigation. Consider OIDC instead of local passwords for production.

### S-2 No service-to-service authentication (High, open)
The MCP server, score calculator, generator and A2A agent accept any caller on the network. Compose publishes only 8080 and 8086, and only on localhost, which limits exposure locally.
**Fix:** use mTLS through a service mesh, or OAuth2 client-credentials tokens. Advertise the scheme in the A2A agent card (`securitySchemes`), pass a bearer token through `StreamableHttpMcpTransport.customHeaders`, and validate it on the MCP server.

### S-3 Plaintext transport (High, open)
Every hop is `http://`.
**Fix:** terminate TLS at the edge, and use mTLS between services (S-2).

### S-4 Credit files unencrypted at rest (Medium, open)
Files are owner-only (`rw-------` in an `rwx------` directory) and hold only a masked SSN, but the rest of the report is plaintext.
**Fix:** use an encrypted volume, or envelope encryption with a KMS key inside `CreditFileWriter` and `CreditFileRepository`.

### S-5 SSN as file name (Fixed)
A file is named `HMAC-SHA256(key, ssn).json`. The generator and the MCP server share the key, it must be at least 32 bytes, and it has no default value. `resources/list` returns an empty list, so the server never enumerates people.

### S-6 Path traversal (Fixed)
Resource URIs must match `^credit-report://ssn/[0-9-]{9,11}$`, then pass SSN validation. File names are hex by construction. The resolved path must sit directly inside the data directory, and symlinks are not followed (`NOFOLLOW_LINKS`). Covered by `CreditReportMcpHandlerTest.pathTraversalStyleUrisAreRejected`.

### S-7 PII in logs and errors (Fixed)
- `Ssn.toString()` is masked, and request records override `toString()` to redact the SSN.
- Validation messages never echo the input.
- `server.error.include-message: never`; 5xx responses carry a generic `ProblemDetail`.
- Request and response logging is forced off on the MCP client, the A2A client and the LLM client.
- Exceptions raised while handling personal data are logged through `SafeLogging.describe(e)`, which writes class names only. Third-party exception messages (MCP, A2A, HTTP) can contain request content.
- The SSN only ever travels in a POST body, never in a URL.

### S-8 DNS rebinding on MCP (Fixed)
The MCP spec requires Streamable HTTP servers to validate the `Origin` header. `/mcp` refuses any browser origin that is not in `credit.files.allowed-origins`; the list is empty by default. GET and DELETE return 405, because the server offers no stream.

### S-9 SSN enumeration (Mitigated)
`RateLimitFilter` allows 20 look-ups per minute per IP. The limit is per-instance and in-memory. The API still answers 404 for an unknown SSN and 200 for a known one, which tells a caller whether a file exists; authentication (S-1) removes this oracle.
**For production:** enforce the limit at the gateway and configure `server.forward-headers-strategy` so the real client IP is used.

### S-10 LLM data handling (Mitigated)
The LLM path is off by default. When it is enabled:
- Only a de-identified view of the report is sent (no name, SSN or report id); `LlmCreditAdvisorTest` checks this.
- The system prompt treats the report as data.
- Output is capped at 6 recommendations of 600 characters each.
- Any failure falls back to the deterministic rules.

Check the model provider's data-retention terms before enabling it.

### S-11 Browser hardening (Fixed)
- Strict CSP: `default-src 'none'`, `script-src 'self'`, no inline script or style.
- `frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, COOP.
- `Cache-Control: no-store` on `/api/**`.
- The SSN field is a password input with a show toggle, and it is cleared after a successful look-up. Registration has no SSN field at all.
- The UI writes only `textContent`, never `innerHTML`.

### S-18 Uploaded SSN card images (Mitigated)
- The web server caps uploads at 5 MB (`spring.servlet.multipart.max-file-size`; 413 above it), never decodes the image, and forwards only files whose magic bytes are PNG or JPEG. The user's file name is not forwarded.
- `kyc-service` caps bodies at 10 MB plus multipart framing, sniffs the format from magic bytes, reads width and height from the header and refuses anything over 40 megapixels before decoding a pixel, decodes anything over 4 megapixels subsampled (a 300 KB, 40-megapixel 16-bit PNG would otherwise take about 320 MB of heap, enough to stop the JVM under `-XX:+ExitOnOutOfMemoryError`), and treats any decoder error as an unsupported image.
- The SSN read from the card is never logged; `Verification` and `VerifiedSsn` redact it in `toString()`. Only the KYC verification id is stored with the user.
- The image itself is not stored by the web server or the dummy provider.

### S-23 Alert emails (Mitigated)
- Recipients are only registered users' addresses from the Alerts table; an address belongs to one account. Addresses are not verified (S-22), so an email can reach someone who never registered: it then only tells the owner of that address about their own address, and says to sign in, never to reply or click.
- Phishing hygiene: fixed wording, plain text, no location names, no links but the configured sign-in URL, and a line saying we never ask for a password or SSN by email.
- Header injection: the subject is a constant; the recipient passed registration's address rule (no spaces or line breaks) and JavaMail parses it strictly.
- Logs carry counts and exception class names only. SMTP credentials come from the environment; STARTTLS is required: the notifier refuses to start with it off for any relay but a local mail catcher (`localhost`, `mailpit`). Compose defaults to the bundled Mailpit with TLS off; pointing `MAIL_HOST` elsewhere without `MAIL_STARTTLS=true` stops the notifier at start-up rather than sending in clear. Mailpit's inbox (127.0.0.1:8025) shows every email and is for local use only.
- Open: SPF, DKIM and DMARC for the sending domain are deployment work, so mail is not spoofable in our name.

### S-22 Dark-web alerts (Mitigated)
- The `Alerts` table holds an email, a location name and a time: personal data. Only the emails of registered users are checked.
- Alerts are keyed by email, so an email belongs to one account: a conditional `email#<sha-256>` claim item, written in the registration transaction, refuses a second account with the same address (any letter case). No account can see another account's alerts.
- Open (Low): addresses are not verified. Someone can register with an address that is not theirs, if no account has it yet, and learn whether it was exposed. It costs them an SSN card that passes KYC (one account per SSN). Fix: email verification, then watch and show alerts only for verified addresses.
- `alerts-service` is internal: every request needs the service token, bodies are limited to 4 KB, and the email is validated (`@Email`, at most 254 characters). The email travels in the POST body, so it does not reach access logs; `toString` of requests and alerts redacts it, and the monitor logs counts only.
- The web server only ever looks up the signed-in user's own email; there is no endpoint that takes an email from the browser.
- The placeholder source contacts nothing. A real provider would send users' emails to a third party: re-review then (data-processing agreement, k-anonymity or hashed look-ups).
- Open: there is no account deletion yet; when one is added, it must delete the user's alerts too.

### S-21 Card offers (Mitigated)
- **New personal data at rest:** the users table now holds the ZIP code (asked at registration) and the latest credit score with its time. Both are personal data; they live in the same encrypted-at-rest table as the rest of the account and are never logged. The SSN is still never stored: the first score is computed from the SSN the KYC check read, then dropped.
- **Minimal disclosure:** the Offers service receives a score and a ZIP code only, no identity. Both its interfaces still need the internal service token, and the MCP endpoint refuses browser requests (any `Origin`).
- **Agent-facing content (reviewed with `owasp-llm-top10`):** terms are the issuer's own, never computed; the tool description says terms are indicative and offers are not pre-approvals; marketplace text is validated (ids, lengths, printable characters) before it reaches agents; unsuitable cards are not offered.
- **Consumer protection:** every offer and the list carry a "not a firm offer of credit / not a pre-approval" disclosure and the card's Schumer-box terms; approval odds are an estimate. Placeholder issuers are fictional.
- **Failure:** offers never fail a login; the page says when they are unavailable.

### S-20 Chat and the User Advisor Agent (Mitigated)
Reviewed against the OWASP Top 10 for LLM Applications (2025) and for Agentic Applications (2026).
- **LLM01 / ASI01 prompt injection:** deterministic input checks on normalised text (Unicode, invisible characters, homoglyphs, leetspeak, spaced letters, base64, hex, Morse, role markers), a topic allow-list where follow-ups must point back at the last answer, and a prompt that keeps instructions apart from data with every data block escaped. The placeholder model has no tools, so injection can only change text.
- **LLM02 sensitive data:** the chat reaches only the signed-in user's own report (the same own-SSN check as the score API), sent without name, masked SSN or report id. Identifiers (SSNs in any separator or digit script, card, account, phone and email) are redacted in both directions and in the eval log. The agent sees a hash of the session, never the user id.
- **LLM05 output handling:** answers are shown with `textContent`, markup characters are removed, and answers are length-capped.
- **LLM07 leakage:** instructions carry a sentinel; any answer that contains it, a prompt delimiter or a role marker is replaced.
- **LLM09 misinformation:** answers come from report data; promises are blocked; every answer carries the educational-use disclaimer.
- **LLM10 / ASI08 consumption and cascades:** bounded memory (TTL, turns, LRU, heap pressure), input and output size caps, per-IP and shared inbound limits, timeouts, and no retry of a message that may have arrived.
- **ASI06 memory poisoning:** only guardrail-checked text is stored; memory is per login session, cleared at logout.
- **Eval log:** one redacted JSON line per turn (no report, no identity), rolled and size-capped, written asynchronously; it is personal-data-adjacent (users' questions), so keep its volume private and its retention short.
- **Reviewed with `owasp-llm-top10` (PR #17):** 3 Medium findings fixed (prompt-block escape, protected-characteristic gaps in the output filter, length checked before Unicode expansion) along with the Low ones; see the PR's findings table.
- **Residual:** word lists and patterns are a first layer, not a classifier: other languages, ROT13, base64 split into short chunks and paraphrases still get through. Add a moderation and injection classifier when a real LLM is plugged in, add a per-user chat rate limit before a paid model, and re-run `owasp-llm-top10` then.

### S-19 Claude connector (Mitigated)
The connector (`claude-connector`) and the web server's OAuth endpoints are meant to be reachable from the internet, unlike every other service here.
- **Who:** every MCP request needs an access token the web server issued for this connector (`aud` = `CONNECTOR_RESOURCE_URL`, RFC 8707). Tokens come only from the authorization code flow with PKCE `S256`; there is no implicit flow, no password grant and no refresh token. Codes live 60 s and work once: a "code used" marker written on the first exchange outlives any revocation, and a reused code is refused and revokes the grant it opened. Tokens are revocable (`/oauth/revoke`) and checked against their grant on every request.
- **What:** tools take no SSN. The SSN comes from the token, so a prompt-injected model cannot ask for anyone else's report. The only write is the user's own report lock; its description tells the model to act only on an explicit request.
- **Consent:** the user signs in on the web server's own page with password **and** SSN (checked against the stored keyed hash). The page names the app and the host it will return to, cannot be framed (`frame-ancestors 'none'`), and only widens `form-action` to the registered redirect origin. It is a fixed file with no request data in it: `oauth-consent.js` fills it from the URL as text (`value`, `textContent`), and error pages show fixed text, so no request value can become markup. A failed sign-in redirects back to the form without the password or SSN. Sign-in attempts are rate limited per IP.
- **Redirects:** only Claude's two callbacks (exact match) and loopback URLs (RFC 8252) can be registered. An unknown client or redirect URI is shown an error page and never redirected, so the server is not an open redirector.
- **Secrets:** client ids are signed and codes and tokens encrypted with keys derived from `OAUTH_TOKEN_KEY` (separate labels for signing and encryption). Codes and tokens carry the SSN, so they must stay encrypted: never switch them to signed-only JWTs. Introspection, which returns the SSN, is internal only (service token).
- **Residual risk:** once connected, the user's report, score and advice enter their Claude conversations and Anthropic's data handling. That is the feature, but users should be told. Rotating `OAUTH_TOKEN_KEY` ends every connection.

### S-12 MCP tool exposes full reports (Accepted)
`get_credit_report` exists so generic MCP hosts can use the server. If one of those hosts is an LLM app, the report goes to that model. Remove the tool (`McpServerConfiguration`) if this server will be exposed beyond trusted callers.

### S-13 No global body-size limit (Low, mostly fixed)
Every endpoint that reads an untrusted body caps it with `RequestSizeLimitFilter` before the body is read: a larger declared `Content-Length` gets 413, and a chunked body is cut off at the limit.

| Service | Limit |
|---|---|
| score calculator `/api/*` | 256 KiB (`SCORE_MAX_REQUEST_BYTES`) |
| NRG `/api/*` | 3.2 MB (`NRG_MAX_REQUEST_BYTES`) |
| KYC `/api/*` | 10 MB plus multipart framing |
| MCP `/mcp` | 64 KiB. Before the deep review, the whole body was buffered into a String first and only then checked |
| intelligence agent (A2A) | 64 KiB |
| web server | JSON endpoints (`/credit-score`, `/auth/login`) 64 KiB; registration multipart 5 MB / 6 MB |
| client agent `/api/*` | 4 KiB |
| report generator `/api/*` | 16 KiB |

Every service also refuses excess load before doing any work: a token bucket (429) and an in-flight cap (503), both with `Retry-After` (`credit.inbound.*`, see the architecture's Resilience section).
**Remaining:** cap request size at the gateway or ingress as well.

### S-14 Generator overwrites reports (Low, open)
`POST /api/v1/reports` replaces any existing file. It is an internal tool and is not published in compose. Keep it unexposed; once S-2 is in place, give it its own credentials.

### S-15 CI supply chain (Fixed)
- Every action is pinned to a commit SHA, and Dependabot keeps the pins current.
- Workflows default to `permissions: contents: read`; write scopes are granted per job.
- `persist-credentials: false` on every checkout.
- CodeQL (`security-extended`) runs on Java, JavaScript and the workflows themselves.
- Dependency review fails a PR on high-severity advisories. Gitleaks scans for secrets. Trivy scans every image.
- Release images carry SBOM and provenance attestations.

### S-16 Container runtime (Fixed)
Containers run as non-root UID 10001 with a read-only root filesystem, `cap_drop: ALL` and `no-new-privileges`. The MCP server mounts the credit files read-only.

### S-17 HMAC key rotation (Info, open)
Changing the key orphans every existing file.
**Fix:** support a list of keys (current plus previous) in `CreditFileRepository`, and add a re-keying job.

## Re-review triggers
Re-run this review whenever any of these happen:
- Authentication is added.
- A new data source or LLM provider is added.
- A service is exposed beyond the private network (the Claude connector and the OAuth endpoints already are: re-review when they change).
- A major upgrade to the MCP or A2A SDKs.
