// What the OAuth consent page shows and submits, worked out from its URL. Pure functions, so they
// run under `node --test`; oauth-consent.js puts the result into the page. The server checked the
// request before serving the page and checks it again on submit: nothing here is trusted.

/** The authorization request parameters carried through the form, in order. */
export const REQUEST_FIELDS = Object.freeze([
  'response_type', 'client_id', 'redirect_uri', 'code_challenge', 'code_challenge_method',
  'state', 'resource', 'scope',
]);

/**
 * The claims of a client id (a signed JWT the server issued), or null. Only for display: the server
 * verified the signature before serving the page.
 */
export function clientClaims(clientId) {
  if (typeof clientId !== 'string') return null;
  const parts = clientId.split('.');
  if (parts.length !== 3) return null;
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    const claims = JSON.parse(new TextDecoder().decode(bytes));
    return claims && typeof claims === 'object' ? claims : null;
  } catch {
    return null;
  }
}

/** `https://host[:port]` of a URL, or null. */
export function originOf(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.origin : null;
  } catch {
    return null;
  }
}

/**
 * @param {string} search the page URL's query, e.g. `location.search`
 * @returns {{hidden: [string, string][], clientName: string, returnOrigin: string|null, signInFailed: boolean}}
 */
export function consentModel(search) {
  const params = new URLSearchParams(search);
  const hidden = REQUEST_FIELDS
    .filter((name) => params.get(name) !== null)
    .map((name) => [name, params.get(name)]);
  const claims = clientClaims(params.get('client_id'));
  const name = claims && typeof claims.client_name === 'string' ? claims.client_name.trim() : '';
  const registered = claims && Array.isArray(claims.redirect_uris) ? claims.redirect_uris : [];
  const redirect = params.get('redirect_uri') ?? (registered.length === 1 ? registered[0] : null);
  return {
    hidden,
    clientName: name || 'An application',
    returnOrigin: originOf(redirect),
    signInFailed: params.get('retry') === '1',
  };
}

/**
 * The JSON body that approves the connector once the code has been sent: the authorization request as
 * the page received it, the SSN typed in step 1, the challenge and the code.
 * @param {[string, string][]} hidden the request fields from `consentModel`
 */
export function approveBody(hidden, ssn, challengeId, code) {
  const body = Object.fromEntries(hidden);
  body.ssn = String(ssn || '').trim();
  body.challengeId = challengeId;
  body.code = code;
  return body;
}

/**
 * Where the approve answer sends the browser: only a string the server returned that parses as an
 * http(s) URL; anything else stays on the page.
 */
export function approvedRedirect(answer) {
  const target = answer && typeof answer.redirect === 'string' ? answer.redirect : null;
  return target && originOf(target) ? target : null;
}
