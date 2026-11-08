import { test } from 'node:test';
import assert from 'node:assert/strict';
import { authHeaders, checkRegistration, createSession } from '../../main/resources/static/js/auth.js';

const valid = {
  userId: 'avery',
  password: 'correct horse battery staple',
  confirm: 'correct horse battery staple',
  email: 'avery@example.com',
  phone: '+1 415 555 0100',
};

test('a good registration passes', () => {
  assert.equal(checkRegistration(valid), null);
});

test('registration problems are reported', () => {
  assert.match(checkRegistration({ ...valid, userId: 'a b' }), /User id/);
  assert.match(checkRegistration({ ...valid, password: 'short', confirm: 'short' }), /at least 12/);
  assert.match(checkRegistration({ ...valid, password: 'avery-is-my-name', confirm: 'avery-is-my-name' }), /user id/);
  assert.match(checkRegistration({ ...valid, confirm: 'something else entirely' }), /do not match/);
  assert.match(checkRegistration({ ...valid, email: 'nope' }), /email/);
  assert.match(checkRegistration({ ...valid, phone: '415 555 0100' }), /international/);
});

test('bearer header only when signed in', () => {
  assert.deepEqual(authHeaders(null), {});
  assert.deepEqual(authHeaders('abc'), { Authorization: 'Bearer abc' });
});

test('session survives in storage and clears', () => {
  const map = new Map();
  const storage = { getItem: (k) => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: (k) => map.delete(k) };
  const session = createSession(storage);
  session.save('t', 'avery');
  assert.equal(session.token(), 't');
  assert.equal(session.user(), 'avery');
  session.clear();
  assert.equal(session.token(), null);
});

test('blocked storage does not throw', () => {
  const blocked = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); } };
  const session = createSession(blocked);
  session.save('t', 'u');
  assert.equal(session.token(), null);
  session.clear();
});
