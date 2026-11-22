import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  formatPrice, registrationNotice, registrationOffer, safeCheckoutUrl, subscribeResult, subscriptionsView,
} from '../../main/resources/static/js/subscriptions.js';

const plans = { paymentsAvailable: true, plans: [{ product: 'ID_PROTECTION', priceCents: 2999, currency: 'USD' }] };

test('prices are formatted in their currency, and nonsense is refused', () => {
  assert.equal(formatPrice(2999, 'USD'), '$29.99');
  assert.equal(formatPrice(0, 'USD'), null);
  assert.equal(formatPrice(1.5, 'USD'), null);
  assert.equal(formatPrice(100, 'NOT A CODE'), null);
  assert.equal(formatPrice(100, undefined), null);
});

test('the registration offer states the price, and is left out when nothing can be bought', () => {
  assert.match(registrationOffer(plans), /^Add ID Protection for \$29\.99 a year \(optional\)/);
  assert.equal(registrationOffer({ ...plans, paymentsAvailable: false }), null);
  assert.equal(registrationOffer({ paymentsAvailable: true, plans: [] }), null);
  assert.equal(registrationOffer(null), null);
});

test('a free user is offered ID Protection with its price; a subscriber sees until when', () => {
  const free = subscriptionsView({ status: 'OK', paymentsAvailable: true, products: [
    { product: 'ID_PROTECTION', active: false, offered: true, priceCents: 2999, currency: 'USD' },
  ] });
  assert.equal(free.show, true);
  assert.match(free.status, /free plan.*\$29\.99 a year/);
  assert.deepEqual(free.offer, { product: 'ID_PROTECTION', label: 'Subscribe to ID Protection for $29.99 a year' });

  const paid = subscriptionsView({ status: 'OK', paymentsAvailable: true, products: [
    { product: 'ID_PROTECTION', active: true, expiresAt: '2027-11-21T10:00:00Z' },
  ] });
  assert.equal(paid.status, 'ID Protection is active until 2027-11-21.');
  assert.equal(paid.offer, null);
  assert.equal(subscriptionsView({ status: 'OK', products: [{ product: 'ID_PROTECTION', active: true }] }).status,
    'ID Protection is active.');
});

test('without payments, an unoffered product or an unavailable answer, nothing can be bought', () => {
  const closed = subscriptionsView({ status: 'OK', paymentsAvailable: false, products: [
    { product: 'ID_PROTECTION', active: false, offered: true, priceCents: 2999, currency: 'USD' },
  ] });
  assert.match(closed.status, /open soon/);
  assert.equal(closed.offer, null);
  assert.equal(subscriptionsView({ status: 'OK', products: [{ product: 'ID_PROTECTION', offered: false }] }).offer, null);
  assert.equal(subscriptionsView({ status: 'UNAVAILABLE' }).offer, null);
  assert.match(subscriptionsView(null).status, /could not be loaded/);
  assert.equal(subscriptionsView({ status: 'OK', products: [] }).show, false);
});

test('only https checkout pages are followed', () => {
  assert.equal(safeCheckoutUrl('https://checkout.stripe.com/c/pay/1'), 'https://checkout.stripe.com/c/pay/1');
  assert.equal(safeCheckoutUrl('javascript:alert(1)'), null);
  assert.equal(safeCheckoutUrl('http://pay.example/'), null);
  assert.equal(safeCheckoutUrl('not a url'), null);
});

test('each subscribe answer has its message', () => {
  assert.deepEqual(subscribeResult(201, { outcome: 'ACTIVE' }), { message: 'ID Protection is active.', refresh: true, checkoutUrl: null });
  assert.equal(subscribeResult(200, { outcome: 'ALREADY_ACTIVE' }).refresh, true);
  assert.equal(subscribeResult(202, { outcome: 'CHECKOUT', checkoutUrl: 'https://pay.example/1' }).checkoutUrl, 'https://pay.example/1');
  assert.equal(subscribeResult(202, { outcome: 'CHECKOUT', checkoutUrl: 'javascript:x' }).checkoutUrl, null);
  assert.match(subscribeResult(402, {}).message, /declined/);
  assert.match(subscribeResult(503, {}).message, /could not be started/);
});

test('a new user is told what happened to the ID Protection they asked for', () => {
  assert.equal(registrationNotice({ outcome: 'ACTIVE' }), 'Your ID Protection is active.');
  assert.equal(registrationNotice({ outcome: null, message: 'Could not start.' }), 'Could not start.');
  assert.match(registrationNotice({ outcome: 'CHECKOUT', checkoutUrl: 'https://pay.example/1' }), /starts once you pay/);
  assert.equal(registrationNotice({ outcome: null, message: ' ' }), null);
  assert.equal(registrationNotice(null), null);
});
