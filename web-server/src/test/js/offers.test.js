import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MAX_OFFERS, offerRows, offersView } from '../../main/resources/static/js/offers.js';

const offer = (rank, extra = {}) => ({
  rank,
  issuer: 'Woodgrove Bank',
  productName: `Card ${rank}`,
  headline: '2% cash back, no annual fee',
  approvalOdds: 'HIGH',
  availability: 'Nationwide',
  terms: {
    purchaseApr: { intro: null, min: 19.24, max: 29.24, yourRate: 21.5 },
    balanceTransferApr: { intro: { rate: 0, months: 18 } },
    balanceTransferFeePercent: 3,
    annualFee: 0,
    securityDeposit: null,
  },
  ...extra,
});

test('statuses other than OK show a message and no offers', () => {
  assert.match(offersView({ status: 'NO_SCORE' }).message, /Look up your credit score/);
  assert.match(offersView({ status: 'UNAVAILABLE' }).message, /not available/);
  assert.match(offersView(null).message, /not available/);
  assert.deepEqual(offersView({ status: 'NO_SCORE' }).offers, []);
});

test('offers are shown in rank order, at most ten, with what they were matched on', () => {
  const many = Array.from({ length: 12 }, (_, i) => offer(12 - i));
  const view = offersView({ status: 'OK', score: 731, zip: '98101', offers: many, disclaimer: 'Not firm offers.' });
  assert.equal(view.offers.length, MAX_OFFERS);
  assert.equal(view.offers[0].title, 'Card 1 · Woodgrove Bank');
  assert.equal(view.basis, 'Matched on your score of 731 and ZIP code 98101.');
  assert.equal(view.disclaimer, 'Not firm offers.');
  assert.equal(view.message, null);
  assert.equal(offersView({ status: 'OK', score: 600, offers: [] }).message, 'No card offers match your score yet.');
  assert.equal(offersView({ status: 'OK', score: 600, offers: [] }).basis, 'Matched on your score of 600.');
});

test('rows carry the terms people compare', () => {
  const rows = Object.fromEntries(offerRows(offer(1)));
  assert.equal(rows['Purchase APR'], '21.5% variable (range 19.24%–29.24%)');
  assert.equal(rows['Annual fee'], 'None');
  assert.equal(rows['Approval odds'], 'high');
  assert.equal(rows['Balance transfers'], '0% for 18 months, 3% fee');
  const secured = Object.fromEntries(offerRows(offer(2, {
    availability: 'Residents of OR, WA',
    terms: { purchaseApr: { intro: { rate: 0, months: 12 }, min: 28.24, max: 28.24, yourRate: 28.24 }, annualFee: 39, securityDeposit: 200 },
  })));
  assert.equal(secured['Purchase APR'], '0% intro for 12 months, then 28.24% variable (range 28.24%–28.24%)');
  assert.equal(secured['Annual fee'], '$39');
  assert.equal(secured['Security deposit'], '$200');
  assert.equal(secured.Availability, 'Residents of OR, WA');
  assert.ok(offerRows({}).length >= 3);
});
