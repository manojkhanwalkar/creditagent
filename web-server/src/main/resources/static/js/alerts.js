// What the dark-web alert banner shows, from the server's answer. Pure functions, so they run under
// `node --test`; app.js puts the result into the page as text.

export const MAX_FINDINGS = 20;

const DEFAULT_MESSAGE = 'Your information was found on the dark web. We suggest you buy identity protection'
  + ' and change the passwords you use with this email.';

function readableLocation(location) {
  return String(location ?? 'an unnamed location').replace(/[-_]+/g, ' ');
}

function readableDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? 'an unknown date' : date.toISOString().slice(0, 10);
}

/** @returns {{ show: boolean, message: string|null, findings: string[] }} */
export function alertsView(data) {
  if (data?.status !== 'OK' || data.exposed !== true) return { show: false, message: null, findings: [] };
  const findings = (Array.isArray(data.findings) ? data.findings : [])
    .slice(0, MAX_FINDINGS)
    .map((f) => `Found at ${readableLocation(f.location)} on ${readableDate(f.foundAt)}`);
  return {
    show: true,
    message: typeof data.message === 'string' && data.message.trim() ? data.message : DEFAULT_MESSAGE,
    findings,
  };
}
