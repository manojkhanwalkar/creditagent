# Security policy

## Reporting a vulnerability

Please do **not** open a public issue. Use GitHub's private vulnerability reporting instead (*Security → Report a vulnerability* on this repository). You should get an acknowledgement within 3 working days.

## Handling personal data

This project processes Social Security Numbers.

* Never commit real SSNs, credit reports or `.env` files. Test data must be synthetic.
* Never log an SSN. Use `Ssn.masked()` / `Ssn.toString()`, and log exceptions raised while handling personal data with `SafeLogging.describe(e)`.
* SSNs travel only in request bodies, never in URLs.

The current threat model and open findings are in [docs/security-review.md](docs/security-review.md).
