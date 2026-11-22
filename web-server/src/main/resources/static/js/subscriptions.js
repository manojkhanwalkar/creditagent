// What the ID Protection widget, the registration offer and the subscribe button show, from the
// server's answers. Pure functions, so they run under `node --test`; app.js puts the results into the
// page as text.

export const LABELS = {
  ID_PROTECTION: 'ID Protection',
  CREDIT_MONITORING: 'Credit Monitoring',
};

/** "$29.99" for 2999 cents in USD; null when the price is missing. */
export function formatPrice(cents, currency) {
  if (!Number.isInteger(cents) || cents <= 0 || typeof currency !== 'string') return null;
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(cents / 100);
  } catch {
    return null;
  }
}

function readableDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date.toISOString().slice(0, 10);
}

/** The registration tick box's label, or null to leave the offer out (nothing can be bought). */
export function registrationOffer(plans) {
  if (plans?.paymentsAvailable !== true || !Array.isArray(plans.plans)) return null;
  const plan = plans.plans.find((p) => p?.product === 'ID_PROTECTION');
  const price = plan ? formatPrice(plan.priceCents, plan.currency) : null;
  if (!price) return null;
  return `Add ID Protection for ${price} a year (optional): see where and when your information was found on`
    + ' the dark web, and get an email each time it happens.';
}

/**
 * The ID Protection widget.
 * @returns {{ show: boolean, status: string, offer: { product: string, label: string }|null }}
 */
export function subscriptionsView(data) {
  if (data?.status !== 'OK' || !Array.isArray(data.products)) {
    return { show: true, status: 'Your subscriptions could not be loaded. Try again later.', offer: null };
  }
  const product = data.products.find((p) => p?.product === 'ID_PROTECTION');
  if (!product) return { show: false, status: '', offer: null };
  if (product.active === true) {
    const until = readableDate(product.expiresAt);
    return { show: true, status: until ? `ID Protection is active until ${until}.` : 'ID Protection is active.', offer: null };
  }
  const price = formatPrice(product.priceCents, product.currency);
  if (product.offered !== true || !price) {
    return { show: true, status: 'ID Protection is not offered right now.', offer: null };
  }
  if (data.paymentsAvailable !== true) {
    return { show: true, status: `ID Protection costs ${price} a year. Subscriptions open soon.`, offer: null };
  }
  return {
    show: true,
    status: `You are on the free plan. ID Protection costs ${price} a year and shows where and when your`
      + ' information was found on the dark web, with an email each time.',
    offer: { product: 'ID_PROTECTION', label: `Subscribe to ID Protection for ${price} a year` },
  };
}

/** Only an https URL is followed; anything else is ignored. */
export function safeCheckoutUrl(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' ? parsed.href : null;
  } catch {
    return null;
  }
}

/**
 * What a subscribe answer means for the page.
 * @returns {{ message: string, refresh: boolean, checkoutUrl: string|null }}
 */
export function subscribeResult(httpStatus, data) {
  if (httpStatus === 202 && data?.outcome === 'CHECKOUT') {
    const checkoutUrl = safeCheckoutUrl(data.checkoutUrl);
    return checkoutUrl
      ? { message: 'Taking you to the payment page…', refresh: false, checkoutUrl }
      : { message: 'The payment page could not be opened. Try again later.', refresh: false, checkoutUrl: null };
  }
  if ((httpStatus === 201 || httpStatus === 200) && (data?.outcome === 'ACTIVE' || data?.outcome === 'ALREADY_ACTIVE')) {
    return { message: 'ID Protection is active.', refresh: true, checkoutUrl: null };
  }
  if (httpStatus === 402) {
    return { message: 'The payment was declined. No subscription was started.', refresh: false, checkoutUrl: null };
  }
  return { message: 'ID Protection could not be started. Try again later.', refresh: false, checkoutUrl: null };
}

/** What to tell a new user about the ID Protection they asked for at registration; null = nothing. */
export function registrationNotice(outcome) {
  if (!outcome || typeof outcome !== 'object') return null;
  if (outcome.outcome === 'ACTIVE' || outcome.outcome === 'ALREADY_ACTIVE') return 'Your ID Protection is active.';
  if (typeof outcome.message === 'string' && outcome.message.trim()) return outcome.message;
  return null;
}
