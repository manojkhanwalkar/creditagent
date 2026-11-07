import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BANDS, MAX_SCORE, bandFor, looksLikeSsn, ringDash } from '../../main/resources/static/js/score-ring.js';

test('perfect score is 850', () => {
  assert.equal(MAX_SCORE, 850);
});

test('band boundaries match the spec (and ScoreBand.java)', () => {
  assert.equal(bandFor(850), BANDS.GREEN);
  assert.equal(bandFor(701), BANDS.GREEN);
  assert.equal(bandFor(700), BANDS.ORANGE);
  assert.equal(bandFor(600), BANDS.ORANGE);
  assert.equal(bandFor(599), BANDS.RED);
  assert.equal(bandFor(400), BANDS.RED);
});

test('850 draws a complete circle', () => {
  const { circumference, dash, gap } = ringDash(850, 80);
  assert.ok(Math.abs(dash - circumference) < 1e-9);
  assert.ok(Math.abs(gap) < 1e-9);
});

test('lower scores draw a proportional partial circle', () => {
  const { circumference, dash, gap } = ringDash(425, 80);
  assert.ok(Math.abs(dash - circumference / 2) < 1e-9);
  assert.ok(Math.abs(dash + gap - circumference) < 1e-9);
});

test('out-of-range and junk input is clamped', () => {
  assert.equal(ringDash(-10, 80).dash, 0);
  assert.equal(ringDash('abc', 80).dash, 0);
  const full = ringDash(9999, 80);
  assert.ok(Math.abs(full.dash - full.circumference) < 1e-9);
});

test('SSN pre-check accepts dashed and undashed forms only', () => {
  assert.ok(looksLikeSsn('123-45-6789'));
  assert.ok(looksLikeSsn('123456789'));
  assert.ok(looksLikeSsn(' 123-45-6789 '));
  assert.ok(!looksLikeSsn('12-345-6789'));
  assert.ok(!looksLikeSsn('1234567890'));
  assert.ok(!looksLikeSsn('<script>'));
});
