import { test } from 'node:test';
import assert from 'node:assert/strict';
import { REQUEST_FIELDS, clientClaims, consentModel, originOf } from '../../main/resources/static/js/oauth-consent-model.js';

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
