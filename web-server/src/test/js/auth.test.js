import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  authHeaders, checkCardPhoto, checkRegistration, createSession, MAX_CARD_BYTES, registrationFailure, ssnHint,
} from '../../main/resources/static/js/auth.js';

const valid = {
  userId: 'avery',
  password: 'correct horse battery staple',
  confirm: 'correct horse battery staple',
  email: 'avery@example.com',
  phone: '+1 415 555 0100',
  ssnCard: { name: 'card.png', type: 'image/png', size: 120_000 },
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
  assert.match(checkRegistration({ ...valid, ssnCard: null }), /Social Security card/);
});

test('SSN hint shows only the last four', () => {
  assert.equal(ssnHint('***-**-6789'), ', ending in 6789');
  assert.equal(ssnHint(null), '');
});

test('bearer header only when signed in', () => {
  assert.deepEqual(authHeaders(null), {});
  assert.deepEqual(authHeaders('abc'), { Authorization: 'Bearer abc' });
});

test('session survives in storage and clears', () => {
  const map = new Map();
  const storage = { getItem: (k) => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: (k) => map.delete(k) };
  const session = createSession(storage);
  session.save('t', 'avery', '***-**-6789');
  assert.equal(session.token(), 't');
  assert.equal(session.ssnMasked(), '***-**-6789');
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

test('the card photo must be a PNG or JPEG of at most 5 MB', () => {
  assert.equal(checkCardPhoto({ type: 'image/jpeg', size: 1 }), null);
  assert.match(checkCardPhoto(null), /Choose a photo/);
  assert.match(checkCardPhoto({ type: 'image/png', size: 0 }), /Choose a photo/);
  assert.match(checkCardPhoto({ type: 'application/pdf', size: 10 }), /PNG or JPEG/);
  assert.match(checkCardPhoto({ type: 'image/png', size: MAX_CARD_BYTES + 1 }), /5 MB/);
});

test('a blurry photo asks for a new one and keeps the server message', () => {
  const blurry = registrationFailure(422, { reason: 'IMAGE_BLURRY', detail: 'The image is too blurry to read.' });
  assert.equal(blurry.retakePhoto, true);
  assert.equal(blurry.message, 'The image is too blurry to read.');
  assert.equal(registrationFailure(422, { reason: 'NOT_AN_SSN_CARD' }).retakePhoto, true);
  assert.match(registrationFailure(422, { reason: 'X' }).message, /could not be verified/);
});

test('other registration failures keep the photo', () => {
  assert.deepEqual(registrationFailure(409, { detail: 'That user id is already registered.' }),
    { message: 'That user id is already registered.', retakePhoto: false });
  assert.equal(registrationFailure(413, {}).retakePhoto, true);
  assert.match(registrationFailure(429, {}).message, /Too many/);
  assert.match(registrationFailure(503, null).message, /went wrong/);
});
