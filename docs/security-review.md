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
| S-2 | High | No service-to-service authentication (MCP, score calculator, generator, A2A) | Open |
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
| S-13 | Low | Request bodies have no global size limit | Partly fixed (MCP, score calculator) |
| S-14 | Low | The generator API can overwrite any report | Open: internal only |
| S-15 | Low | CI supply chain (unpinned actions, broad token permissions) | Fixed |
| S-16 | Low | Container runtime privileges | Fixed |
| S-17 | Info | The HMAC key has no rotation procedure | Open |
| S-18 | Medium | Registration accepts an uploaded image (parser attack surface, oversized files) | Mitigated |

## Findings

### S-1 User authentication (High, partly fixed)
Users now register (BCrypt cost 12; 12–64 character passwords checked against a common-password list and the user id) and log in for an HS256 JWT. Every `/api/` call except register and login needs the token, and the token is accepted only while its DynamoDB session exists and was used in the last 5 minutes. Logout deletes the session. Login answers the same message, in about the same time, for a wrong password and an unknown user, and the per-IP rate limit covers login and registration.
Registration no longer takes a typed SSN. The user uploads a photo of their Social Security card; the web server sends it to the KYC provider, which checks that the photo is sharp and is a card, and returns the SSN it read plus a verification id. A blurred photo is refused (422, `reason: IMAGE_BLURRY`) and the user is asked for a new one. Field checks and the user-id check run first, so a bad form never costs a KYC call. The users table stores only an HMAC-SHA256 of it (key `SSN_HMAC_KEY`) and its last four digits, and a second item, `ssn#<hash>`, written in the same conditional transaction as the user, so one SSN can belong to only one account. Login copies the hash into the session, and `POST /api/v1/credit-score` answers 403 for any SSN whose hash differs, before any report is fetched, so a refusal reveals nothing about the other SSN.
**Still open before real data:** the bundled `kyc-service` is a dummy. Instead of OCR and document-security checks it reads a label that its own sample cards carry in their PNG metadata, so anyone can forge a "card" for any SSN. Replace it with a real provider (document check with liveness/selfie match, or a bureau identity service) behind the same `IdentityVerifier` interface, and authenticate the call to it (S-2). The sample-card endpoint makes a card for any SSN; it is off by default and only compose turns it on. Registration answers 409 for an SSN already in use, which tells a caller that someone registered it; the rate limit slows but does not stop that. There is no account lockout, MFA or password recovery yet (email and phone are stored for recovery). The agent flow (`client-agent` `/api/v1/advice`) is not authenticated and can still ask about any SSN. The token is kept in `sessionStorage`, so an XSS bug could read it; the strict CSP is the mitigation. Consider OIDC instead of local passwords for production.

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

### S-12 MCP tool exposes full reports (Accepted)
`get_credit_report` exists so generic MCP hosts can use the server. If one of those hosts is an LLM app, the report goes to that model. Remove the tool (`McpServerConfiguration`) if this server will be exposed beyond trusted callers.

### S-13 No global body-size limit (Low, partly fixed)
`/mcp` rejects bodies over 64 KiB after reading them. The score calculator caps `/api/*` bodies at 256 KiB (`SCORE_MAX_REQUEST_BYTES`): a larger declared `Content-Length` gets 413, and a chunked body is cut off at the limit (400). Other endpoints rely on JSON binding.
**Fix:** cap request size at the gateway or ingress.

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
- A service is exposed beyond the private network.
- A major upgrade to the MCP or A2A SDKs.
