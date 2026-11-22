// What the dark-web alert banner shows, from the server's answer. Pure functions, so they run under
// `node --test`; app.js puts the result into the page as text.

export const MAX_FINDINGS = 20;

const DEFAULT_MESSAGE = 'Your information was found on the dark web. Change the passwords you use with this'
  + ' email, and watch your credit report for accounts you did not open.';

const LOCKED_MESSAGE = 'Something connected to your email was found on the dark web. Change the passwords you use'
  + ' with this email. Where and when is listed in your data download; subscribe to ID Protection to see it here.';

function readableLocation(location) {
  return String(location ?? 'an unnamed location').replace(/[-_]+/g, ' ');
}

function readableDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? 'an unknown date' : date.toISOString().slice(0, 10);
}

/** Delays (ms) before asking `GET /api/v1/alerts` again while the alerts service is unavailable. */
export const RETRY_DELAYS_MS = [5000, 30000];

/** True when the answer settles the question (checked: found or not); false means ask again later. */
export function alertsChecked(data) {
  return data?.status === 'OK';
}

/**
 * `locked`: something was found, but the user has no ID Protection, so no details are shown and the
 * banner offers the subscription.
 * @returns {{ show: boolean, message: string|null, findings: string[], locked: boolean }}
 */
export function alertsView(data) {
  if (data?.status !== 'OK' || data.exposed !== true) return { show: false, message: null, findings: [], locked: false };
  if (data.locked === true) {
    const message = typeof data.message === 'string' && data.message.trim() ? data.message : LOCKED_MESSAGE;
    return { show: true, message, findings: [], locked: true };
  }
  const findings = (Array.isArray(data.findings) ? data.findings : [])
    .slice(0, MAX_FINDINGS)
    .map((f) => `Found at ${readableLocation(f.location)} on ${readableDate(f.foundAt)}`);
  return {
    show: true,
    message: typeof data.message === 'string' && data.message.trim() ? data.message : DEFAULT_MESSAGE,
    findings,
    locked: false,
  };
}
