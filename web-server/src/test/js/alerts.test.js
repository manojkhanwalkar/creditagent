import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MAX_FINDINGS, RETRY_DELAYS_MS, alertsChecked, alertsView } from '../../main/resources/static/js/alerts.js';

test('nothing is shown unless the email was found', () => {
  for (const data of [null, {}, { status: 'UNAVAILABLE' }, { status: 'OK', exposed: false }]) {
    assert.deepEqual(alertsView(data), { show: false, message: null, findings: [] });
  }
});

test('a finding shows the message, where and when, and suggests identity protection', () => {
  const view = alertsView({
    status: 'OK',
    exposed: true,
    findings: [{ location: 'breach-forum-beta', foundAt: '2026-11-09T12:00:00Z' }, { location: null, foundAt: 'bad' }],
    message: 'Found. We suggest you buy identity protection.',
  });
  assert.equal(view.show, true);
  assert.equal(view.message, 'Found. We suggest you buy identity protection.');
  assert.deepEqual(view.findings, ['Found at breach forum beta on 2026-11-09', 'Found at an unnamed location on an unknown date']);
  assert.match(alertsView({ status: 'OK', exposed: true, findings: [] }).message, /identity protection/);
});

test('an unavailable or missing answer is asked again; a checked one is final', () => {
  for (const data of [null, undefined, {}, { status: 'UNAVAILABLE' }]) assert.equal(alertsChecked(data), false);
  assert.equal(alertsChecked({ status: 'OK', exposed: false }), true);
  assert.equal(alertsChecked({ status: 'OK', exposed: true, findings: [] }), true);
  assert.ok(RETRY_DELAYS_MS.length > 0 && RETRY_DELAYS_MS.every((ms) => ms > 0));
});

test('the list is capped', () => {
  const findings = Array.from({ length: 30 }, (_, i) => ({ location: `l${i}`, foundAt: '2026-01-01T00:00:00Z' }));
  assert.equal(alertsView({ status: 'OK', exposed: true, findings }).findings.length, MAX_FINDINGS);
});
