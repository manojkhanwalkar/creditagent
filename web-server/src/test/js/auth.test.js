import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  AUTHORIZATION_REQUIRED, checkDeletion, idleWarningDelayMs, nextTab,
  authHeaders, checkCardPhoto, checkRegistration, createSession, lockView, MAX_CARD_BYTES, registrationFailure, ssnHint,
} from '../../main/resources/static/js/auth.js';

const valid = {
  userId: 'avery',
  password: 'correct horse battery staple',
  confirm: 'correct horse battery staple',
  email: 'avery@example.com',
  authorize: true,
  zip: '98101',
  ssnCard: { name: 'card.png', type: 'image/png', size: 120_000 },
};

test('a good registration passes', () => {
  assert.equal(checkRegistration(valid), null);
});

test('registration problems are reported', () => {
  assert.match(checkRegistration({ ...valid, userId: 'a b' }), /User id/);
  assert.match(checkRegistration({ ...valid, zip: '981' }), /ZIP/);
  assert.match(checkRegistration({ ...valid, zip: undefined }), /ZIP/);
  assert.equal(checkRegistration({ ...valid, zip: '98101-1234' }), null);
  assert.match(checkRegistration({ ...valid, password: 'short', confirm: 'short' }), /at least 12/);
  assert.match(checkRegistration({ ...valid, password: 'avery-is-my-name', confirm: 'avery-is-my-name' }), /user id/);
  assert.match(checkRegistration({ ...valid, confirm: 'something else entirely' }), /do not match/);
  assert.match(checkRegistration({ ...valid, email: 'nope' }), /email/);
  assert.equal(checkRegistration({ ...valid, authorize: false }), AUTHORIZATION_REQUIRED);
  assert.equal(checkRegistration({ ...valid, authorize: undefined }), AUTHORIZATION_REQUIRED);
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

test('the lock widget shows the status and offers the opposite action', () => {
  assert.deepEqual(lockView('LOCKED'), {
    locked: true, statusText: 'Your credit report is locked.', buttonText: 'Unlock my report', next: 'UNLOCKED',
  });
  assert.deepEqual(lockView('UNLOCKED'), {
    locked: false, statusText: 'Your credit report is unlocked.', buttonText: 'Lock my report', next: 'LOCKED',
  });
  // Anything unexpected is shown as unlocked, the server's default.
  assert.equal(lockView(undefined).locked, false);
});

test('deletion needs the password, a well-formed SSN and the confirmation', () => {
  const ok = { password: 'x', ssn: '123-45-6789', confirmed: true };
  assert.equal(checkDeletion(ok), null);
  assert.match(checkDeletion({ ...ok, password: '' }), /password/);
  assert.match(checkDeletion({ ...ok, ssn: '12-34' }), /SSN/);
  assert.match(checkDeletion({ ...ok, confirmed: false }), /confirm/);
});

test('the idle warning comes a minute early, or half-way for short timeouts', () => {
  assert.equal(idleWarningDelayMs(300), 240_000);
  assert.equal(idleWarningDelayMs(60), 30_000);
  assert.equal(idleWarningDelayMs(undefined), 0);
});

test('the tabs follow the APG keyboard model', () => {
  assert.equal(nextTab(0, 'ArrowRight', 2), 1);
  assert.equal(nextTab(1, 'ArrowRight', 2), 0);
  assert.equal(nextTab(0, 'ArrowLeft', 2), 1);
  assert.equal(nextTab(1, 'ArrowUp', 2), 0);
  assert.equal(nextTab(1, 'ArrowDown', 2), 0);
  assert.equal(nextTab(1, 'Home', 2), 0);
  assert.equal(nextTab(0, 'End', 2), 1);
  assert.equal(nextTab(0, 'Enter', 2), null);
});
