// What the offers widget shows, from the server's answer. Pure functions, so they run under
// `node --test`; app.js puts the result into the page as text.

export const MAX_OFFERS = 10;

/**
 * Right after registration the first score is still being computed in the background, so the login
 * usually finds none. The page asks again after these delays (ms) until offers arrive.
 */
export const NO_SCORE_RETRY_DELAYS = Object.freeze([2000, 5000, 10000]);

/** Shown on every offer when the offer carries no disclosure of its own. */
export const DEFAULT_DISCLOSURE = 'Not a pre-approval. Confirm the terms with the issuer before applying.';

const STATUS_TEXT = Object.freeze({
  NO_SCORE: 'Look up your credit score above to see card offers matched to it.',
  UNAVAILABLE: 'Card offers are not available right now. Please try again later.',
});

function pct(value) {
  return `${Number(Number(value).toFixed(2))}%`;
}

/** One offer as display rows: the terms people compare. */
export function offerRows(offer) {
  const terms = offer?.terms ?? {};
  const purchase = terms.purchaseApr ?? {};
  const intro = purchase.intro ? `${pct(purchase.intro.rate)} intro for ${purchase.intro.months} months, then ` : '';
  const transfer = terms.balanceTransferApr?.intro;
  const rows = [
    ['Purchase APR', `${intro}${pct(purchase.yourRate ?? purchase.min)} variable (range ${pct(purchase.min)}–${pct(purchase.max)})`],
    ['Annual fee', terms.annualFee ? `$${terms.annualFee}` : 'None'],
    ['Approval odds', String(offer?.approvalOdds ?? 'Unknown').toLowerCase()],
  ];
  if (transfer) rows.push(['Balance transfers', `${pct(transfer.rate)} for ${transfer.months} months, ${pct(terms.balanceTransferFeePercent)} fee`]);
  if (terms.securityDeposit) rows.push(['Security deposit', `$${terms.securityDeposit}`]);
  if (offer?.availability && offer.availability !== 'Nationwide') rows.push(['Availability', offer.availability]);
  return rows;
}

/**
 * The widget's content: a status message, or the offers (at most MAX_OFFERS, in rank order) with
 * the disclaimer and what they were matched on.
 */
export function offersView(data) {
  const status = data?.status;
  if (status !== 'OK') {
    return { message: STATUS_TEXT[status] ?? STATUS_TEXT.UNAVAILABLE, offers: [], basis: null, disclaimer: null };
  }
  const offers = Array.isArray(data.offers)
    ? [...data.offers].sort((a, b) => (a.rank ?? 0) - (b.rank ?? 0)).slice(0, MAX_OFFERS)
    : [];
  const basis = data.zip ? `Matched on your score of ${data.score} and ZIP code ${data.zip}.`
    : `Matched on your score of ${data.score}.`;
  return {
    message: offers.length ? null : 'No card offers match your score yet.',
    offers: offers.map((o) => ({
      title: `${o.productName ?? 'Card'} · ${o.issuer ?? ''}`.trim(),
      headline: o.headline ?? '',
      rows: offerRows(o),
      disclosure: typeof o.disclosure === 'string' && o.disclosure.trim() ? o.disclosure : DEFAULT_DISCLOSURE,
    })),
    basis,
    disclaimer: typeof data.disclaimer === 'string' ? data.disclaimer : null,
  };
}
