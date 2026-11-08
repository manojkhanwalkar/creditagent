// Client-side session handling and form checks. The server enforces every rule; these checks only
// give faster feedback. Pure functions are exported for the Node unit tests.

const TOKEN_KEY = 'creditAgent.token';
const USER_KEY = 'creditAgent.user';

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
    save(token, user) {
      try {
        storage.setItem(TOKEN_KEY, token);
        storage.setItem(USER_KEY, user);
      } catch { /* storage blocked: the session lasts until reload */ }
    },
    clear() {
      try {
        storage.removeItem(TOKEN_KEY);
        storage.removeItem(USER_KEY);
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

/** @returns an error message, or null if the form looks valid */
export function checkRegistration({ userId, password, confirm, email, phone }) {
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
  return null;
}
