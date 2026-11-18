// Fills the OAuth consent page from its URL and runs its two steps. Text only: values go into
// `value` and `textContent`, never into HTML, so nothing in the URL can add markup or script.
//
// Allow: (1) user id, password and SSN to /oauth/authorize/sign-in, which opens a one-time-code
// challenge; (2) a code by email or text message through /api/v1/auth/login/otp; (3) the code to
// /oauth/authorize/approve, which answers with the redirect back to the application. Deny is a plain
// form post, as before.
import { approveBody, approvedRedirect, consentModel } from './oauth-consent-model.js';
import { checkOtpCode, codeSentMessage, otpChoices, otpFailure, resendWaitMessage } from './auth.js';

const $ = (id) => document.getElementById(id);
const model = consentModel(window.location.search);
const form = $('consent-form');

for (const [name, value] of model.hidden) {
  const input = document.createElement('input');
  input.type = 'hidden';
  input.name = name;
  input.value = value;
  form.prepend(input);
}

$('client-name').textContent = model.clientName;
if (model.returnOrigin) {
  $('return-origin').textContent = model.returnOrigin;
  $('return-line').hidden = false;
}
if (model.signInFailed) {
  $('sign-in-error').hidden = false;
}

let challengeId = null;
let resendTimer = null;
/** The last "we sent a code" line, kept when the resend wait ends. */
let sentMessage = '';
const error = $('step-error');
const send = $('otp-send');
const allow = $('allow');

function showError(message) {
  error.textContent = message;
  error.hidden = !message;
}

async function postJson(path, body) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
    credentials: 'same-origin',
    cache: 'no-store',
  });
  const data = await response.json().catch(() => ({}));
  return { response, data };
}

function showCredentials(message) {
  challengeId = null;
  sentMessage = '';
  clearTimeout(resendTimer);
  $('code-step').hidden = true;
  $('credentials').hidden = false;
  $('otp-code').value = '';
  $('otp-status').textContent = '';
  send.disabled = false;
  send.textContent = 'Send code';
  allow.textContent = 'Allow';
  showError(message || '');
  $('user-id').focus();
}

function showCodeStep(challenge) {
  challengeId = challenge.challengeId;
  const choices = otpChoices(challenge.channels);
  $('otp-email-destination').textContent = choices.email || 'your email address';
  $('otp-sms-destination').textContent = choices.sms || '';
  $('otp-sms-choice').hidden = choices.sms === null;
  $('otp-email').checked = true;
  $('credentials').hidden = true;
  $('code-step').hidden = false;
  $('sign-in-error').hidden = true;
  allow.textContent = 'Verify and allow';
  showError('');
  $('code-title').focus();
}

function holdSend(seconds) {
  clearTimeout(resendTimer);
  send.disabled = seconds > 0;
  if (seconds > 0) {
    resendTimer = setTimeout(() => {
      send.disabled = false;
      $('otp-status').textContent = [sentMessage, resendWaitMessage(0)].filter(Boolean).join(' ');
    }, seconds * 1000);
  }
}

async function signIn() {
  const { response, data } = await postJson('/oauth/authorize/sign-in', {
    userId: $('user-id').value.trim(), password: $('password').value, ssn: $('ssn').value.trim(),
  });
  if (!response.ok) {
    showError(response.status === 429 ? 'Too many requests. Please wait a minute and try again.'
      : (typeof data.detail === 'string' ? data.detail : 'Something went wrong. Please try again.'));
    return;
  }
  $('password').value = '';
  showCodeStep(data);
}

async function approve() {
  const { code, error: problem } = checkOtpCode($('otp-code').value);
  if (problem) {
    showError(problem);
    $('otp-code').focus();
    return;
  }
  const { response, data } = await postJson('/oauth/authorize/approve',
    approveBody(model.hidden, $('ssn').value, challengeId, code));
  const target = response.ok ? approvedRedirect(data) : null;
  if (target) {
    window.location.assign(target);
    return;
  }
  const failure = otpFailure(response.status, data);
  if (failure.restart) {
    showCredentials(failure.message);
    return;
  }
  showError(failure.message);
  $('otp-code').select();
}

form.addEventListener('submit', async (event) => {
  // Deny stays a plain form post; Allow runs the steps here.
  if (!event.submitter || event.submitter.value !== 'allow') return;
  event.preventDefault();
  if (!challengeId && !form.reportValidity()) return;
  allow.disabled = true;
  try {
    if (challengeId) await approve(); else await signIn();
  } catch {
    showError('Could not reach the server. Check your connection and try again.');
  } finally {
    allow.disabled = false;
  }
});

send.addEventListener('click', async () => {
  showError('');
  send.disabled = true;
  try {
    const channel = $('otp-sms').checked ? 'SMS' : 'EMAIL';
    const { response, data } = await postJson('/api/v1/auth/login/otp', { challengeId, channel });
    if (!response.ok) {
      const failure = otpFailure(response.status, data);
      if (failure.restart) {
        showCredentials(failure.message);
        return;
      }
      showError(failure.message);
      holdSend(failure.retryAfterSeconds);
      if (failure.retryAfterSeconds > 0) $('otp-status').textContent = resendWaitMessage(failure.retryAfterSeconds);
      $('otp-code').focus(); // the button just pressed is disabled: keep focus on a usable control
      return;
    }
    sentMessage = codeSentMessage(data);
    $('otp-status').textContent = sentMessage;
    send.textContent = 'Send a new code';
    holdSend(Number(data.resendAfterSeconds) || 0);
    $('otp-code').focus();
  } catch {
    showError('Could not reach the server. Check your connection and try again.');
    send.disabled = false;
  }
});
