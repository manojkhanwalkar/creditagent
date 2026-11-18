import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { REQUEST_FIELDS, approveBody, approvedRedirect, clientClaims, consentModel, originOf } from '../../main/resources/static/js/oauth-consent-model.js';

function clientId(claims) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'HS256' })}.${encode(claims)}.signature`;
}

const CALLBACK = 'https://claude.ai/api/mcp/auth_callback';

test('carries every request field through the form, in order, and nothing else', () => {
  const id = clientId({ client_name: 'Claude', redirect_uris: [CALLBACK] });
  const search = new URLSearchParams({
    scope: 'credit', client_id: id, response_type: 'code', redirect_uri: CALLBACK, state: 's<1>',
    code_challenge: 'c', code_challenge_method: 'S256', resource: 'https://c/mcp', userId: 'x', retry: '1',
  }).toString();
  const model = consentModel(`?${search}`);
  assert.deepEqual(model.hidden.map(([name]) => name), REQUEST_FIELDS);
  assert.deepEqual(model.hidden.find(([name]) => name === 'state'), ['state', 's<1>']);
  assert.equal(model.clientName, 'Claude');
  assert.equal(model.returnOrigin, 'https://claude.ai');
  assert.equal(model.signInFailed, true);
});

test('falls back to the only registered redirect URI and a neutral name', () => {
  const id = clientId({ redirect_uris: ['http://localhost:53682/callback'] });
  const model = consentModel(`?client_id=${id}`);
  assert.equal(model.clientName, 'An application');
  assert.equal(model.returnOrigin, 'http://localhost:53682');
  assert.equal(model.signInFailed, false);
  assert.deepEqual(model.hidden, [['client_id', id]]);
});

test('a missing or unreadable client id shows nothing borrowed from it', () => {
  assert.equal(clientClaims(null), null);
  assert.equal(clientClaims('one.part'), null);
  assert.equal(clientClaims('a.!!!.c'), null);
  assert.equal(clientClaims(`a.${Buffer.from('7').toString('base64url')}.c`), null);
  const model = consentModel('?client_id=garbage');
  assert.equal(model.clientName, 'An application');
  assert.equal(model.returnOrigin, null);
});

test('decodes non-ASCII client names', () => {
  assert.equal(consentModel(`?client_id=${clientId({ client_name: 'Clàude ✓' })}`).clientName, 'Clàude ✓');
});

test('only http(s) URLs have an origin to show', () => {
  assert.equal(originOf('https://claude.com/api/mcp/auth_callback?x=1'), 'https://claude.com');
  assert.equal(originOf('javascript:alert(1)'), null);
  assert.equal(originOf('not a url'), null);
  assert.equal(originOf(null), null);
});

test('the approve body carries the request, the SSN, the challenge and the code', () => {
  const body = approveBody([['client_id', 'c'], ['state', 's']], ' 123-45-6789 ', 'ch-1', '012345');
  assert.deepEqual(body, { client_id: 'c', state: 's', ssn: '123-45-6789', challengeId: 'ch-1', code: '012345' });
  assert.equal(approveBody([], null, 'ch', '1').ssn, '');
});

test('the page navigates only to an http(s) redirect the server returned', () => {
  assert.equal(approvedRedirect({ redirect: `${CALLBACK}?code=x` }), `${CALLBACK}?code=x`);
  assert.equal(approvedRedirect({ redirect: 'javascript:alert(1)' }), null);
  assert.equal(approvedRedirect({ redirect: 42 }), null);
  assert.equal(approvedRedirect({}), null);
  assert.equal(approvedRedirect(null), null);
});

test('the consent page has a labelled code step and never posts allow without it', () => {
  const html = readFileSync(new URL('../../main/resources/oauth/consent.html', import.meta.url), 'utf8');
  assert.match(html, /<div id="code-step" hidden>/);
  assert.match(html, /<fieldset>\s*<legend>Send my sign-in code by<\/legend>/);
  assert.match(html, /<input id="otp-code"[^>]*autocomplete="one-time-code"/);
  assert.match(html, /<label for="otp-code">/);
  assert.match(html, /<p class="error" id="step-error" role="alert" hidden>/);
  assert.match(html, /<p id="otp-status"[^>]*role="status"/);
  const script = readFileSync(new URL('../../main/resources/static/js/oauth-consent.js', import.meta.url), 'utf8');
  assert.match(script, /event\.preventDefault\(\)/);
  assert.match(script, /\/oauth\/authorize\/approve/);
  assert.doesNotMatch(script, /innerHTML/);
});
