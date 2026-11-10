// The chat's pure parts: input checks and what each transcript entry shows. No DOM access, so they
// run under `node --test`; app.js puts entries into the page as text (textContent), never as HTML.
import { looksLikeSsn } from './score-ring.js';

export const MAX_MESSAGE = 1000;
export const MAX_ENTRIES = 100;

/** A problem with the message or the SSN to show the user, or null. The server checks again. */
export function checkChat(message, ssn, needSsn) {
  const text = String(message ?? '').trim();
  if (!text) return 'Type a question first.';
  if (text.length > MAX_MESSAGE) return `Keep your question under ${MAX_MESSAGE} characters.`;
  if (needSsn && !looksLikeSsn(ssn)) return 'Enter your 9-digit SSN, for example 123-45-6789.';
  return null;
}

/** The entry for the user's own question. */
export function userEntry(message) {
  return { role: 'user', text: String(message).trim(), disclaimer: null, refused: false };
}

/** The entry for the assistant's answer, as the server sent it. */
export function assistantEntry(data) {
  return {
    role: 'assistant',
    text: typeof data?.reply === 'string' ? data.reply : 'No answer was returned.',
    disclaimer: typeof data?.disclaimer === 'string' ? data.disclaimer : null,
    refused: data?.refused === true,
  };
}

/** The transcript with `entry` added, keeping only the latest MAX_ENTRIES. */
export function appendEntry(entries, entry) {
  const next = [...entries, entry];
  return next.length > MAX_ENTRIES ? next.slice(next.length - MAX_ENTRIES) : next;
}

/**
 * One question at a time: begin() refuses while a question is in flight, so pressing Enter twice
 * cannot send two at once (and the second lose the first as context). reset() is for sign-in and
 * sign-out, where an old request's answer must not unlock the new session's form.
 */
export function createSendGate() {
  let busy = false;
  return {
    begin() {
      if (busy) return false;
      busy = true;
      return true;
    },
    end() { busy = false; },
    reset() { busy = false; },
    get busy() { return busy; },
  };
}
