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
const PHONE_SEPARATORS = /[\s().-]/g;
const PHONE = /^\+[1-9]\d{7,14}$/;
const EMAIL = /^[^@\s]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/;
const SSN = /^\d{3}-?\d{2}-?\d{4}$/;

/** "ending in 6789" for a masked SSN such as ***-**-6789, or "" if unknown. */
export function ssnHint(ssnMasked) {
  const match = /(\d{4})$/.exec(ssnMasked || '');
  return match ? `, ending in ${match[1]}` : '';
}

/** @returns an error message, or null if the form looks valid */
export function checkRegistration({ userId, password, confirm, email, phone, ssn }) {
  const id = (userId || '').trim();
  if (!USER_ID.test(id)) return 'User id must be 3–32 characters: letters, digits, ".", "_" or "-".';
  if ((password || '').length < 12) return 'Password must be at least 12 characters.';
  if (password.length > 64) return 'Password must be at most 64 characters.';
  if (password.toLowerCase().includes(id.toLowerCase())) return 'Password must not contain the user id.';
  if (password !== confirm) return 'Passwords do not match.';
  if (!EMAIL.test((email || '').trim())) return 'Enter a valid email address.';
  if (!PHONE.test((phone || '').trim().replace(PHONE_SEPARATORS, ''))) {
    return 'Enter the phone number in international form, e.g. +1 415 555 0100.';
  }
  if (!SSN.test((ssn || '').trim())) return 'Enter your 9-digit SSN, for example 123-45-6789.';
  return null;
}
