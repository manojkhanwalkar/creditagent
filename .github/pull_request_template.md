## What and why

<!-- One or two sentences. Link the issue if there is one. -->

## Checklist

- [ ] Tests added or updated (`mvn verify`, and `npm test` in `web-server` for UI changes); every behaviour change has a test that fails without it
- [ ] Coverage stays at or above the target: 95% for the project, 90% for each module (`.github/scripts/coverage.sh`)
- [ ] No SSN or report content in logs, error messages, URLs or test fixtures (synthetic data only)
- [ ] New endpoints validate input and have a rate or size limit where they face the outside world
- [ ] `docs/security-review.md` updated if this changes auth, data flows, or external calls
