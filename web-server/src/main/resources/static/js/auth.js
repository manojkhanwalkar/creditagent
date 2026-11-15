// Client-side session handling and form checks. The server enforces every rule; these checks only
// give faster feedback. Pure functions are exported for the Node unit tests.

const TOKEN_KEY = 'creditAgent.token';
const USER_KEY = 'creditAgent.user';
const SSN_KEY = 'creditAgent.ssnMasked';

/**
 * The token lives in sessionStorage: it survives a reload but not closing the tab, and is never
 * sent automatically like a cookie. The server also ends the session after 5 idle minutes.
 */
export function createSession(storage) {
  return {
    token() {
      try { return storage.getItem(TOKEN_KEY); } catch { return null; }
    },
    user() {
      try { return storage.getItem(USER_KEY); } catch { return null; }
    },
    ssnMasked() {
      try { return storage.getItem(SSN_KEY); } catch { return null; }
    },
    save(token, user, ssnMasked) {
      try {
        storage.setItem(TOKEN_KEY, token);
        storage.setItem(USER_KEY, user);
        if (ssnMasked) storage.setItem(SSN_KEY, ssnMasked);
      } catch { /* storage blocked: the session lasts until reload */ }
    },
    clear() {
      try {
        storage.removeItem(TOKEN_KEY);
        storage.removeItem(USER_KEY);
        storage.removeItem(SSN_KEY);
      } catch { /* nothing stored */ }
    },
  };
}

export function authHeaders(token) {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

const USER_ID = /^[a-z0-9][a-z0-9._-]{2,31}$/i;
const EMAIL = /^[^@\s]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/;

/**
 * Largest SSN card photo the server accepts. Must match the server's SSN_CARD_MAX_BYTES
 * (spring.servlet.multipart.max-file-size); the server is authoritative and answers 413 above it.
 */
export const MAX_CARD_BYTES = 5 * 1024 * 1024;
const CARD_TYPES = new Set(['image/png', 'image/jpeg']);

/**
 * What the lock widget shows for a report status from the server.
 * @returns {{ locked: boolean, statusText: string, buttonText: string, next: string }}
 */
export function lockView(status) {
  const locked = status === 'LOCKED';
  return {
    locked,
    statusText: locked ? 'Your credit report is locked.' : 'Your credit report is unlocked.',
    buttonText: locked ? 'Unlock my report' : 'Lock my report',
    next: locked ? 'UNLOCKED' : 'LOCKED',
  };
}

/** "ending in 6789" for a masked SSN such as ***-**-6789, or "" if unknown. */
export function ssnHint(ssnMasked) {
  const match = /(\d{4})$/.exec(ssnMasked || '');
  return match ? `, ending in ${match[1]}` : '';
}

/**
 * @param ssnCard the chosen File (or anything with name, type and size), or null
 * @returns an error message, or null if the form looks valid
 */
/** Shown when the terms / FCRA authorisation box is not ticked; the server refuses the same way. */
export const AUTHORIZATION_REQUIRED = 'To open an account, agree to the terms of use and privacy notice and authorise'
  + ' CreditAgent to obtain your credit reports.';

export function checkRegistration({ userId, password, confirm, email, zip, authorize, ssnCard }) {
  const id = (userId || '').trim();
  if (!USER_ID.test(id)) return 'User id must be 3–32 characters: letters, digits, ".", "_" or "-".';
  if ((password || '').length < 12) return 'Password must be at least 12 characters.';
  if (password.length > 64) return 'Password must be at most 64 characters.';
  if (password.toLowerCase().includes(id.toLowerCase())) return 'Password must not contain the user id.';
  if (password !== confirm) return 'Passwords do not match.';
  if (!EMAIL.test((email || '').trim())) return 'Enter a valid email address.';
  if (!/^\d{5}(-\d{4})?$/.test((zip || '').trim())) return 'Enter your 5-digit ZIP code.';
  const photo = checkCardPhoto(ssnCard);
  if (photo) return photo;
  return authorize === true ? null : AUTHORIZATION_REQUIRED;
}

/** @returns an error message for the SSN card photo, or null if it can be sent */
export function checkCardPhoto(file) {
  if (!file || !file.size) return 'Choose a photo of your Social Security card.';
  if (!CARD_TYPES.has(file.type)) return 'The photo must be a PNG or JPEG image.';
  if (file.size > MAX_CARD_BYTES) return 'The photo must be 5 MB or smaller.';
  return null;
}

/**
 * What a failed registration means for the form.
 * @returns {{ message: string, retakePhoto: boolean }} retakePhoto: clear the photo and ask for a new one
 */
export function registrationFailure(status, body) {
  const reason = body && typeof body.reason === 'string' ? body.reason : null;
  if (status === 422 && reason) {
    const fallback = 'Your Social Security card could not be verified. Upload a clear photo of the front of the card.';
    // IMAGE_BLURRY's detail already asks for a sharper photo; every reason needs a new one.
    return { message: typeof body.detail === 'string' ? body.detail : fallback, retakePhoto: true };
  }
  if (status === 413) return { message: 'The photo must be 5 MB or smaller.', retakePhoto: true };
  if (status === 429) return { message: 'Too many requests. Please wait a minute and try again.', retakePhoto: false };
  if (body && typeof body.detail === 'string') return { message: body.detail, retakePhoto: false };
  return { message: 'Something went wrong. Please try again.', retakePhoto: false };
}

/**
 * Checks the account-deletion form before it is sent. The server asks for the password and SSN again
 * as well (WCAG 3.3.4: a deletion is checked and confirmed, not done by one click).
 * @returns an error message, or null if the request can be sent
 */
export function checkDeletion({ password, ssn, confirmed }) {
  if (!password) return 'Enter your password.';
  if (!/^\d{3}-?\d{2}-?\d{4}$/.test(String(ssn || '').trim())) return 'Enter your SSN as AAA-GG-SSSS or 9 digits.';
  if (confirmed !== true) return 'Tick the box to confirm that you want to delete your account and data.';
  return null;
}

/**
 * When to warn about the idle timeout (WCAG 2.2.1): `warnSeconds` before it ends, but never sooner
 * than half-way through, so a short timeout still leaves time to act.
 * @returns milliseconds from the last activity
 */
export function idleWarningDelayMs(idleTimeoutSeconds, warnSeconds = 60) {
  const idle = Math.max(0, Number(idleTimeoutSeconds) || 0);
  return Math.max(idle - warnSeconds, idle / 2) * 1000;
}

/**
 * Keyboard model for the sign-in tabs (WAI-ARIA APG tabs): arrows move and wrap, Home/End jump.
 * @returns the tab index to select, or null if the key is not a tab key
 */
export function nextTab(current, key, count) {
  switch (key) {
    case 'ArrowRight': case 'ArrowDown': return (current + 1) % count;
    case 'ArrowLeft': case 'ArrowUp': return (current - 1 + count) % count;
    case 'Home': return 0;
    case 'End': return count - 1;
    default: return null;
  }
}
