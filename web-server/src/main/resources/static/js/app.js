import { bandFor, looksLikeSsn, ringDash, scoreDescription } from './score-ring.js';
import {
  authHeaders, checkDeletion, checkOtpCode, checkRegistration, codeSentMessage, createSession, resendWaitMessage, idleWarningDelayMs,
  lockView, nextTab, otpChoices, otpFailure, registrationFailure, ssnHint,
} from './auth.js';
import { appendEntry, assistantEntry, checkChat, createSendGate, userEntry } from './chat.js';
import { NO_SCORE_RETRY_DELAYS, offersView } from './offers.js';
import { RETRY_DELAYS_MS, alertsChecked, alertsView } from './alerts.js';
import { registrationNotice, registrationOffer, subscribeResult, subscriptionsView } from './subscriptions.js';

const RADIUS = 80;
const session = createSession(window.sessionStorage);

const $ = (id) => document.getElementById(id);

// The login between its two steps: the challenge id from the password step (in memory only, never
// stored), the timer that re-enables "Send code", and whether the login follows a registration.
let otpChallenge = null;
let otpResendTimer = null;
let loginAfterRegistration = false;
/** The last "we sent a code" line, kept when the resend wait ends. */
let sentMessage = '';

/** Leaves the code step, if it is showing, and forgets its challenge. */
function resetCodeStep() {
  otpChallenge = null;
  sentMessage = '';
  clearTimeout(otpResendTimer);
  const form = $('otp-form');
  form.reset();
  form.hidden = true;
  $('otp-status').textContent = '';
  $('otp-error').hidden = true;
  const send = $('otp-send');
  send.disabled = false;
  send.textContent = 'Send code';
}

const account = $('account');
const accountUser = $('account-user');
const notice = $('notice');
const authWidget = $('auth-widget');
const scoreWidget = $('score-widget');
const lockWidget = $('lock-widget');
const chatWidget = $('chat-widget');
const offersWidget = $('offers-widget');
const dataWidget = $('data-widget');
const subscriptionsWidget = $('subscriptions-widget');
const subscribeButton = $('subscribe-button');
// Bumped on every sign-out and login answer, so a subscriptions answer for an earlier session is ignored.
let subscriptionsGeneration = 0;

// ---------- signed-in / signed-out view ----------

function showSignedIn(userId, ssnMasked, offers, alerts, subscriptions) {
  accountUser.textContent = userId;
  $('ssn-hint').textContent = ssnHint(ssnMasked || session.ssnMasked());
  account.hidden = false;
  authWidget.hidden = true;
  scoreWidget.hidden = false;
  lockWidget.hidden = false;
  chatWidget.hidden = false;
  offersWidget.hidden = false;
  dataWidget.hidden = false;
  subscriptionsWidget.hidden = false;
  hideNotice();
  scheduleIdleWarning();
  resetLockWidget();
  resetChat();
  loadReportStatus();
  if (offers) renderOffers(offers); else loadOffers();
  // Login waits 1.5 s at most: if the alerts service did not answer in time, ask again.
  if (alertsChecked(alerts)) renderAlerts(alerts); else loadAlerts();
  // Free users are offered ID Protection at every login.
  if (subscriptions?.status === 'OK') {
    subscriptionsGeneration++; // a load still on its way is older than this answer
    renderSubscriptions(subscriptions);
  } else {
    loadSubscriptions();
  }
}

function showSignedOut(message) {
  session.clear();
  resetCodeStep();
  account.hidden = true;
  authWidget.hidden = false;
  scoreWidget.hidden = true;
  lockWidget.hidden = true;
  chatWidget.hidden = true;
  offersWidget.hidden = true;
  dataWidget.hidden = true;
  subscriptionsWidget.hidden = true;
  $('alerts-banner').hidden = true;
  // An answer still on its way for this user must not reach the next one.
  subscriptionsGeneration++;
  $('subscriptions-status').textContent = '';
  subscribeButton.hidden = true;
  showFormError($('subscribe-error'), '');
  resetDataWidget();
  cancelIdleWarning();
  renderAlerts(null);
  alertsGeneration += 1;
  offersGeneration += 1;
  signInGeneration += 1;
  $('offers-list').replaceChildren();
  resetLockWidget();
  resetChat();
  $('result').hidden = true;
  $('ssn').value = '';
  if (message) showNotice(message);
}

function showNotice(message) {
  // The live region must be in the accessibility tree before its text changes, or the message may not
  // be announced (WCAG 4.1.3): show it empty, then fill it on the next frame.
  notice.textContent = '';
  notice.hidden = false;
  requestAnimationFrame(() => { notice.textContent = message; });
}

function hideNotice() {
  notice.textContent = '';
  notice.hidden = true;
}

// ---------- credit report lock ----------

const lockStatus = $('lock-status');
const lockToggle = $('lock-toggle');
const lockError = $('lock-error');
// The status the button will set, or null when the status is unknown (the button then retries).
let nextStatus = null;
// Bumped on every sign-in and sign-out, so a response for an earlier session is ignored.
let lockGeneration = 0;

function resetLockWidget() {
  lockGeneration += 1;
  nextStatus = null;
  lockStatus.textContent = 'Checking…';
  lockStatus.classList.remove('locked');
  lockToggle.textContent = 'Lock my report';
  lockToggle.disabled = true;
  showFormError(lockError, '');
}

function showReportStatus(status) {
  const view = lockView(status);
  lockStatus.textContent = view.statusText;
  lockStatus.classList.toggle('locked', view.locked);
  lockToggle.textContent = view.buttonText;
  lockToggle.disabled = false;
  nextStatus = view.next;
}

function showLockUnavailable(message) {
  nextStatus = null;
  lockStatus.textContent = 'The lock status is not available right now.';
  lockStatus.classList.remove('locked');
  lockToggle.textContent = 'Try again';
  lockToggle.disabled = false;
  showFormError(lockError, message);
}

async function loadReportStatus() {
  const generation = lockGeneration;
  showFormError(lockError, '');
  lockToggle.disabled = true;
  try {
    const { response, data } = await api('/api/v1/credit-report/status', { method: 'GET' });
    if (generation !== lockGeneration) return;
    if (response.status === 401) {
      showSignedOut(messageFor(401, data));
      return;
    }
    if (!response.ok) {
      showLockUnavailable(messageFor(response.status, data));
      return;
    }
    showReportStatus(data.reportStatus);
  } catch {
    if (generation !== lockGeneration) return;
    showLockUnavailable('Could not reach the server. Check your connection and try again.');
  }
}

async function api(path, { method = 'POST', body } = {}) {
  const response = await fetch(path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...authHeaders(session.token()),
    },
    body: body ? JSON.stringify(body) : undefined,
    credentials: 'same-origin',
    cache: 'no-store',
  });
  const data = await response.json().catch(() => ({}));
  // Every authenticated request counts as activity on the server; restart the idle warning's clock.
  if (response.ok && session.token()) scheduleIdleWarning();
  return { response, data };
}

// ---------- idle warning (WCAG 2.2.1: warn before the session times out, and let the user extend it) ----------

const idleWarning = $('idle-warning');
const IDLE_KEY = 'creditAgent.idleTimeoutSeconds';
// The server's idle timeout, from the login answer; kept for this tab, so a reload warns on time too.
let idleTimeoutSeconds = (() => {
  try { return Number(window.sessionStorage.getItem(IDLE_KEY)) || 300; } catch { return 300; }
})();
let idleTimer = null;
let focusBeforeWarning = null;

function scheduleIdleWarning() {
  clearTimeout(idleTimer);
  idleTimer = setTimeout(showIdleWarning, idleWarningDelayMs(idleTimeoutSeconds));
}

function cancelIdleWarning() {
  clearTimeout(idleTimer);
  idleTimer = null;
  idleWarning.hidden = true;
}

function showIdleWarning() {
  focusBeforeWarning = document.activeElement;
  idleWarning.hidden = false;
  $('idle-stay').focus();
}

async function staySignedIn() {
  idleWarning.hidden = true;
  if (focusBeforeWarning && typeof focusBeforeWarning.focus === 'function') focusBeforeWarning.focus();
  try {
    const { response, data } = await api('/api/v1/auth/session', { method: 'GET' });
    if (response.status === 401) showSignedOut(messageFor(401, data));
  } catch {
    showNotice('Could not reach the server. Your session may end soon.');
  }
}

$('idle-stay').addEventListener('click', staySignedIn);
idleWarning.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') staySignedIn();
});

function messageFor(status, body) {
  if (status === 429) return 'Too many requests. Please wait a minute and try again.';
  if (body && typeof body.detail === 'string') return body.detail;
  return 'Something went wrong. Please try again.';
}

function showFormError(element, message) {
  element.textContent = message;
  element.hidden = !message;
}

// ---------- tabs ----------

const tabLogin = $('tab-login');
const tabRegister = $('tab-register');
const loginForm = $('login-form');
const registerForm = $('register-form');

function selectTab(register) {
  resetCodeStep();
  tabLogin.setAttribute('aria-selected', String(!register));
  tabRegister.setAttribute('aria-selected', String(register));
  // Roving tabindex: only the selected tab is in the Tab order; arrows move between tabs.
  tabLogin.tabIndex = register ? -1 : 0;
  tabRegister.tabIndex = register ? 0 : -1;
  loginForm.hidden = register;
  registerForm.hidden = !register;
  $('auth-title').textContent = register ? 'Create an account' : 'Sign in';
}

tabLogin.addEventListener('click', () => selectTab(false));
tabRegister.addEventListener('click', () => selectTab(true));
const tabs = [tabLogin, tabRegister];
tabs.forEach((tab, index) => tab.addEventListener('keydown', (event) => {
  const next = nextTab(index, event.key, tabs.length);
  if (next === null) return;
  event.preventDefault();
  selectTab(next === 1);
  tabs[next].focus();
}));
selectTab(false);

// ---------- login: password, then a one-time code ----------

const otpForm = $('otp-form');
const otpSend = $('otp-send');

/** Step 1: the password. On success the code step replaces the form; no session exists yet. */
async function logIn(userId, password, errorElement) {
  const { response, data } = await api('/api/v1/auth/login', { body: { userId, password } });
  if (!response.ok) {
    showFormError(errorElement, messageFor(response.status, data));
    return false;
  }
  showCodeStep(data);
  return true;
}

function showCodeStep(challenge) {
  selectTab(false);
  otpChallenge = challenge.challengeId;
  const choices = otpChoices(challenge.channels);
  $('otp-email-destination').textContent = choices.email || 'your email address';
  $('otp-sms-destination').textContent = choices.sms || '';
  $('otp-sms-choice').hidden = choices.sms === null;
  $('otp-email').checked = true;
  loginForm.hidden = true;
  otpForm.hidden = false;
  $('auth-title').textContent = 'Confirm sign-in';
  $('otp-title').focus();
}

/** Back to the password, e.g. when the sign-in expired or used up its tries. */
function showPasswordStep(message) {
  loginAfterRegistration = false;
  selectTab(false);
  showFormError($('login-error'), message || '');
  // The user id is still filled in: go straight to the password.
  $($('login-user').value ? 'login-password' : 'login-user').focus();
}

/** Keeps "Send code" disabled for `seconds` (the server's resend wait). */
function holdSend(seconds) {
  clearTimeout(otpResendTimer);
  otpSend.disabled = seconds > 0;
  if (seconds > 0) {
    otpResendTimer = setTimeout(() => {
      otpSend.disabled = false;
      $('otp-status').textContent = [sentMessage, resendWaitMessage(0)].filter(Boolean).join(' ');
    }, seconds * 1000);
  }
}

/** Step 3 succeeded: the answer carries the token, offers and alerts, as login did before. */
function finishLogin(data) {
  const afterRegistration = loginAfterRegistration;
  resetCodeStep();
  loginForm.reset();
  loginAfterRegistration = false;
  session.save(data.token, data.userId, data.ssnMasked);
  if (data.idleTimeoutSeconds) {
    idleTimeoutSeconds = data.idleTimeoutSeconds;
    try { window.sessionStorage.setItem(IDLE_KEY, String(idleTimeoutSeconds)); } catch { /* storage blocked */ }
  }
  // The login answer carries the offers matched to the user's last score and ZIP code.
  showSignedIn(data.userId, data.ssnMasked, data.offers, data.alerts, data.subscriptions);
  if (afterRegistration) waitForFirstOffers();
  if (afterRegistration && pendingRegistrationNotice) showNotice(pendingRegistrationNotice);
  pendingRegistrationNotice = null;
}

loginForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const error = $('login-error');
  showFormError(error, '');
  const button = $('login-submit');
  button.disabled = true;
  try {
    if (await logIn($('login-user').value.trim(), $('login-password').value, error)) {
      // Keep the user id: if the code step has to start over, only the password is asked again (WCAG 3.3.7).
      $('login-password').value = '';
    }
  } catch {
    showFormError(error, 'Could not reach the server. Check your connection and try again.');
  } finally {
    button.disabled = false;
  }
});

// Step 2: send (or resend) a code to the chosen channel.
otpSend.addEventListener('click', async () => {
  const error = $('otp-error');
  showFormError(error, '');
  const channel = $('otp-sms').checked ? 'SMS' : 'EMAIL';
  otpSend.disabled = true;
  try {
    const { response, data } = await api('/api/v1/auth/login/otp', { body: { challengeId: otpChallenge, channel } });
    if (!response.ok) {
      const failure = otpFailure(response.status, data);
      if (failure.restart) {
        showPasswordStep(failure.message);
        return;
      }
      showFormError(error, failure.message);
      holdSend(failure.retryAfterSeconds);
      if (failure.retryAfterSeconds > 0) $('otp-status').textContent = resendWaitMessage(failure.retryAfterSeconds);
      $('otp-code').focus(); // the button just pressed is disabled: keep focus on a usable control
      return;
    }
    sentMessage = codeSentMessage(data);
    $('otp-status').textContent = sentMessage;
    otpSend.textContent = 'Send a new code';
    holdSend(Number(data.resendAfterSeconds) || 0);
    $('otp-code').focus();
  } catch {
    showFormError(error, 'Could not reach the server. Check your connection and try again.');
    otpSend.disabled = false;
  }
});

// Step 3: the code.
otpForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const error = $('otp-error');
  const { code, error: problem } = checkOtpCode($('otp-code').value);
  showFormError(error, problem || '');
  if (problem) {
    $('otp-code').focus();
    return;
  }
  const button = $('otp-submit');
  button.disabled = true;
  try {
    const { response, data } = await api('/api/v1/auth/login/verify', { body: { challengeId: otpChallenge, code } });
    if (!response.ok) {
      const failure = otpFailure(response.status, data);
      if (failure.restart) {
        showPasswordStep(failure.message);
        return;
      }
      showFormError(error, failure.message);
      $('otp-code').select();
      return;
    }
    finishLogin(data);
  } catch {
    showFormError(error, 'Could not reach the server. Check your connection and try again.');
  } finally {
    button.disabled = false;
  }
});

$('otp-cancel').addEventListener('click', () => showPasswordStep(''));

// ---------- registration ----------

// What to say after the first sign-in about the ID Protection asked for at registration.
let pendingRegistrationNotice = null;

/** Shows the optional ID Protection tick box, with its price, when it can be bought. */
async function loadRegistrationOffer() {
  const offer = $('reg-id-protection-offer');
  try {
    const response = await fetch('/api/v1/subscriptions/plans', { headers: { Accept: 'application/json' }, cache: 'no-store' });
    const label = response.ok ? registrationOffer(await response.json()) : null;
    if (label) $('reg-id-protection-label').textContent = label;
    offer.hidden = !label;
  } catch {
    offer.hidden = true;
  }
  if (offer.hidden) $('reg-id-protection').checked = false;
}

registerForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const error = $('register-error');
  const form = {
    userId: $('reg-user').value.trim(),
    password: $('reg-password').value,
    confirm: $('reg-confirm').value,
    email: $('reg-email').value.trim(),
    mobilePhone: $('reg-phone').value.trim(),
    authorize: $('reg-authorize').checked,
    zip: $('reg-zip').value.trim(),
    ssnCard: $('reg-ssn-card').files[0] || null,
  };
  const problem = checkRegistration(form);
  showFormError(error, problem || '');
  if (problem) return;

  const button = $('register-submit');
  const label = button.textContent;
  button.disabled = true;
  button.textContent = 'Checking your card…';
  try {
    // Multipart: the browser sets the Content-Type with its boundary. There is no SSN field; the
    // server's KYC provider reads the SSN from the card photo.
    const body = new FormData();
    body.append('userId', form.userId);
    body.append('password', form.password);
    body.append('email', form.email);
    body.append('mobilePhone', form.mobilePhone);
    body.append('authorize', 'true'); // checkRegistration refused the form without it
    body.append('zip', form.zip);
    body.append('ssnCard', form.ssnCard, form.ssnCard.name);
    if (!$('reg-id-protection-offer').hidden && $('reg-id-protection').checked) body.append('idProtection', 'true');
    const response = await fetch('/api/v1/auth/register', {
      method: 'POST',
      headers: { Accept: 'application/json' },
      body,
      credentials: 'same-origin',
      cache: 'no-store',
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      const failure = registrationFailure(response.status, data);
      showFormError(error, failure.message);
      if (failure.retakePhoto) {
        // Keep everything else the user typed; only the photo has to be chosen again.
        const photo = $('reg-ssn-card');
        photo.value = '';
        photo.focus();
      }
      return;
    }
    pendingRegistrationNotice = registrationNotice(data.idProtection);
    // The new account signs in like any other: password (already known), then a code.
    loginAfterRegistration = true;
    if (await logIn(form.userId, form.password, error)) {
      registerForm.reset();
    } else {
      loginAfterRegistration = false;
    }
  } catch {
    showFormError(error, 'Could not reach the server. Check your connection and try again.');
  } finally {
    button.disabled = false;
    button.textContent = label;
  }
});

lockToggle.addEventListener('click', async () => {
  if (!nextStatus) {
    loadReportStatus(); // status unknown: the button reads "Try again"
    return;
  }
  const generation = lockGeneration;
  showFormError(lockError, '');
  lockToggle.disabled = true;
  try {
    const { response, data } = await api('/api/v1/credit-report/status', {
      method: 'PUT',
      body: { reportStatus: nextStatus },
    });
    if (generation !== lockGeneration) return;
    if (response.status === 401) {
      showSignedOut(messageFor(401, data));
      return;
    }
    if (!response.ok) {
      showFormError(lockError, messageFor(response.status, data));
      lockToggle.disabled = false;
      return;
    }
    showReportStatus(data.reportStatus);
  } catch {
    if (generation !== lockGeneration) return;
    showFormError(lockError, 'Could not reach the server. Check your connection and try again.');
    lockToggle.disabled = false;
  }
});

// ---------- logout ----------

$('logout').addEventListener('click', async () => {
  try {
    await api('/api/v1/auth/logout');
  } catch {
    // Signed out locally either way; the server session also times out after 5 idle minutes.
  }
  showSignedOut('You have been logged out.');
});

// ---------- credit score ----------

const scoreForm = $('score-form');
const input = $('ssn');
const toggle = $('toggle-ssn');
const submit = $('submit');
const scoreError = $('form-error');
const result = $('result');

toggle.addEventListener('click', () => {
  const show = input.type === 'password';
  input.type = show ? 'text' : 'password';
  toggle.textContent = show ? 'Hide' : 'Show';
  toggle.setAttribute('aria-pressed', String(show));
});

scoreForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  showFormError(scoreError, '');

  const ssn = input.value.trim();
  if (!looksLikeSsn(ssn)) {
    showFormError(scoreError, 'Enter a 9-digit SSN, for example 123-45-6789.');
    result.hidden = true;
    return;
  }

  submit.disabled = true;
  submit.textContent = 'Checking…';
  try {
    const { response, data } = await api('/api/v1/credit-score', { body: { ssn } });
    if (response.status === 401) {
      showSignedOut(messageFor(401, data));
      return;
    }
    if (!response.ok) {
      showFormError(scoreError, messageFor(response.status, data));
      result.hidden = true;
      return;
    }
    render(data);
    // Do not leave the SSN sitting in the field.
    input.value = '';
    // The server recorded this score: offers may have changed.
    loadOffers();
  } catch {
    showFormError(scoreError, 'Could not reach the server. Check your connection and try again.');
  } finally {
    submit.disabled = false;
    submit.textContent = 'Get score';
  }
});

function render({ score, ssnMasked }) {
  const band = bandFor(score);
  const { dash, gap } = ringDash(score, RADIUS);
  const ringValue = $('ring-value');
  const ringScore = $('ring-score');
  ringValue.style.strokeDasharray = `${dash} ${gap}`;
  ringValue.style.stroke = band.color;
  ringScore.textContent = String(score);
  ringScore.style.fill = band.color;
  $('ring-caption').textContent = `${band.label} · ${ssnMasked}`;
  // The ring is role="img": its <title> is all a screen reader hears, so it carries the number.
  $('ring-label').textContent = scoreDescription(score);
  result.hidden = false;
}

// ---------- dark-web alerts ----------

let alertsGeneration = 0;

function renderAlerts(data) {
  const view = alertsView(data);
  const banner = $('alerts-banner');
  $('alerts-title').textContent = view.locked
    ? 'Something was found on the dark web' : 'Your information was found on the dark web';
  $('alerts-upsell').hidden = !view.locked;
  $('alerts-message').textContent = view.message ?? '';
  $('alerts-findings').replaceChildren(...view.findings.map((text) => {
    const item = document.createElement('li');
    item.textContent = text;
    return item;
  }));
  banner.hidden = !view.show;
}

async function loadAlerts(attempt = 0) {
  const generation = attempt === 0 ? ++alertsGeneration : alertsGeneration;
  let data = null;
  try {
    const answer = await api('/api/v1/alerts', { method: 'GET' });
    if (answer.response.ok) data = answer.data;
  } catch {
    // Treated like an unavailable service below.
  }
  if (generation !== alertsGeneration) return;
  if (alertsChecked(data)) {
    renderAlerts(data);
  } else if (attempt < RETRY_DELAYS_MS.length) {
    // Still unavailable: ask again a little later (a sign-out or new sign-in cancels it).
    setTimeout(() => { if (generation === alertsGeneration) loadAlerts(attempt + 1); }, RETRY_DELAYS_MS[attempt]);
  }
  // After that, no banner is better than a wrong one; the next login checks again.
}

// ---------- ID Protection subscription ----------


function renderSubscriptions(data) {
  const view = subscriptionsView(data);
  $('subscriptions-status').textContent = view.status;
  subscribeButton.hidden = !view.offer;
  if (view.offer) subscribeButton.textContent = view.offer.label;
}

async function loadSubscriptions() {
  const generation = ++subscriptionsGeneration;
  let data = null;
  try {
    const answer = await api('/api/v1/subscriptions', { method: 'GET' });
    if (answer.response.ok) data = answer.data;
  } catch {
    // Shown as "could not be loaded" below.
  }
  if (generation === subscriptionsGeneration) renderSubscriptions(data);
}

subscribeButton.addEventListener('click', async () => {
  const error = $('subscribe-error');
  showFormError(error, '');
  subscribeButton.disabled = true;
  try {
    const { response, data } = await api('/api/v1/subscriptions', { method: 'POST', body: { product: 'ID_PROTECTION' } });
    if (response.status === 401) {
      showSignedOut(messageFor(401, data));
      return;
    }
    const result = subscribeResult(response.status, data);
    if (result.checkoutUrl) {
      $('subscriptions-status').textContent = result.message;
      window.location.assign(result.checkoutUrl);
      return;
    }
    if (result.refresh) {
      showNotice(result.message);
      await loadSubscriptions();
      // The button is gone now: keep keyboard focus in the panel, not at the top of the page (WCAG 2.4.3).
      if (subscribeButton.hidden) subscriptionsWidget.focus();
      loadAlerts(); // the details are unlocked now
    } else {
      showFormError(error, result.message);
    }
  } catch {
    showFormError(error, 'Could not reach the server. Reload the page to see whether ID Protection started before trying again.');
  } finally {
    subscribeButton.disabled = false;
  }
});

// ---------- card offers ----------

// Bumped on every sign-in and sign-out, so offers for an earlier session are ignored.
let offersGeneration = 0;

// The status of the offers last shown: after registration, NO_SCORE means "not computed yet".
let offersStatus = null;
// Bumped at every sign-out only (loadOffers bumps offersGeneration on each call), so pending
// re-checks after a registration stop when that user signs out.
let signInGeneration = 0;

function renderOffers(data) {
  offersStatus = data?.status ?? null;
  const view = offersView(data);
  const message = $('offers-message');
  message.textContent = view.message ?? '';
  message.hidden = !view.message;
  const list = $('offers-list');
  list.replaceChildren(...view.offers.map((offer) => {
    const item = document.createElement('li');
    item.className = 'offer-card';
    const title = document.createElement('h3');
    title.textContent = offer.title;
    const headline = document.createElement('p');
    headline.className = 'offer-headline';
    headline.textContent = offer.headline;
    const terms = document.createElement('dl');
    terms.className = 'offer-terms';
    for (const [name, value] of offer.rows) {
      const dt = document.createElement('dt');
      dt.textContent = name;
      const dd = document.createElement('dd');
      dd.textContent = value;
      terms.append(dt, dd);
    }
    const disclosure = document.createElement('p');
    disclosure.className = 'offer-disclosure';
    disclosure.textContent = offer.disclosure;
    item.append(title, headline, terms, disclosure);
    return item;
  }));
  const basis = $('offers-basis');
  basis.textContent = view.basis ?? '';
  basis.hidden = !view.basis;
  const disclaimer = $('offers-disclaimer');
  disclaimer.textContent = view.disclaimer ?? '';
  disclaimer.hidden = !view.disclaimer;
}

/**
 * Right after registration the first score is still being computed in the background: ask again a
 * few times, stopping once offers arrive or the user signs out.
 */
function waitForFirstOffers() {
  const generation = signInGeneration;
  NO_SCORE_RETRY_DELAYS.forEach((delay) => {
    setTimeout(() => {
      if (offersStatus === 'NO_SCORE' && !offersWidget.hidden && generation === signInGeneration) loadOffers();
    }, delay);
  });
}

async function loadOffers() {
  const generation = ++offersGeneration;
  try {
    const { response, data } = await api('/api/v1/offers', { method: 'GET' });
    if (generation !== offersGeneration) return;
    if (response.status === 401) {
      showSignedOut(messageFor(401, data));
      return;
    }
    renderOffers(response.ok ? data : { status: 'UNAVAILABLE' });
  } catch {
    if (generation === offersGeneration) renderOffers({ status: 'UNAVAILABLE' });
  }
}

// ---------- chat ----------

const chatForm = $('chat-form');
const chatLog = $('chat-log');
const chatMessage = $('chat-message');
const chatSsn = $('chat-ssn');
const chatSsnRow = $('chat-ssn-row');
const chatSend = $('chat-send');
const chatError = $('chat-error');
let chatEntries = [];
// The SSN the chat sends with each question. In this page's memory only: never stored, and dropped
// at sign-out. Asked for once per sign-in.
let chatSsnValue = null;
let chatGeneration = 0;
const chatGate = createSendGate();

function resetChat() {
  chatGeneration += 1;
  chatEntries = [];
  chatSsnValue = null;
  chatLog.replaceChildren();
  chatSsn.value = '';
  chatMessage.value = '';
  chatSsnRow.hidden = false;
  chatGate.reset();
  chatSend.disabled = false;
  chatSend.textContent = 'Send';
  showFormError(chatError, '');
}

function renderChatEntry(entry) {
  const item = document.createElement('li');
  item.className = `chat-entry ${entry.role}${entry.refused ? ' refused' : ''}`;
  item.textContent = entry.text;
  if (entry.disclaimer) {
    const note = document.createElement('small');
    note.className = 'chat-disclaimer';
    note.textContent = entry.disclaimer;
    item.append(note);
  }
  chatLog.append(item);
  while (chatLog.children.length > chatEntries.length) chatLog.firstElementChild.remove();
  chatLog.scrollTop = chatLog.scrollHeight;
}

function addChatEntry(entry) {
  chatEntries = appendEntry(chatEntries, entry);
  renderChatEntry(entry);
}

chatForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const needSsn = chatSsnValue === null;
  const ssn = needSsn ? chatSsn.value.trim() : chatSsnValue;
  const message = chatMessage.value;
  if (chatGate.busy) return; // Enter while "Thinking…": wait for the answer
  const problem = checkChat(message, ssn, needSsn);
  showFormError(chatError, problem || '');
  if (problem) return;
  chatGate.begin();

  const generation = chatGeneration;
  addChatEntry(userEntry(message));
  chatMessage.value = '';
  chatSend.disabled = true;
  chatSend.textContent = 'Thinking…';
  try {
    const { response, data } = await api('/api/v1/chat', { body: { ssn, message: message.trim() } });
    if (generation !== chatGeneration) return;
    if (response.status === 401) {
      showSignedOut(messageFor(401, data));
      return;
    }
    if (!response.ok) {
      showFormError(chatError, messageFor(response.status, data));
      return;
    }
    if (needSsn) {
      chatSsnValue = ssn;
      chatSsn.value = '';
      chatSsnRow.hidden = true;
    }
    addChatEntry(assistantEntry(data));
  } catch {
    if (generation !== chatGeneration) return;
    showFormError(chatError, 'Could not reach the server. Check your connection and try again.');
  } finally {
    // A request from before a sign-out or sign-in must not unlock the new session's form.
    if (generation === chatGeneration) {
      chatGate.end();
      chatSend.disabled = false;
      chatSend.textContent = 'Send';
    }
  }
});

chatMessage.addEventListener('keydown', (event) => {
  // Enter sends; Shift+Enter makes a new line.
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    chatForm.requestSubmit();
  }
});

// ---------- your data: download and delete ----------

function resetDataWidget() {
  $('export-form').reset();
  $('delete-form').reset();
  $('delete-details').open = false;
  showFormError($('export-error'), '');
  showFormError($('delete-error'), '');
}

$('export-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const error = $('export-error');
  const ssn = $('export-ssn').value.trim();
  if (!looksLikeSsn(ssn)) {
    showFormError(error, 'Enter your SSN as AAA-GG-SSSS or 9 digits.');
    return;
  }
  showFormError(error, '');
  const button = $('export-submit');
  button.disabled = true;
  try {
    const { response, data } = await api('/api/v1/account/export', { body: { ssn } });
    if (response.status === 401) {
      showSignedOut(messageFor(401, data));
      return;
    }
    if (!response.ok) {
      showFormError(error, messageFor(response.status, data));
      return;
    }
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'creditagent-data.json';
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    $('export-ssn').value = '';
    showNotice('Your data was downloaded as creditagent-data.json.');
  } catch {
    showFormError(error, 'Could not reach the server. Check your connection and try again.');
  } finally {
    button.disabled = false;
  }
});

$('delete-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const error = $('delete-error');
  const form = {
    password: $('delete-password').value,
    ssn: $('delete-ssn').value.trim(),
    confirmed: $('delete-confirm').checked,
  };
  const problem = checkDeletion(form);
  showFormError(error, problem || '');
  if (problem) return;
  const button = $('delete-submit');
  button.disabled = true;
  try {
    const { response, data } = await api('/api/v1/account/delete', { body: { password: form.password, ssn: form.ssn } });
    if (response.status === 204) {
      showSignedOut('Your account and all of its data have been deleted.');
      // The focused button is gone with its panel: move focus to the sign-in heading (WCAG 2.4.3).
      $('auth-title').focus();
      return;
    }
    if (response.status === 401) {
      showSignedOut(messageFor(401, data));
      return;
    }
    showFormError(error, messageFor(response.status, data));
  } catch {
    showFormError(error, 'Could not reach the server. Nothing was deleted; try again.');
  } finally {
    button.disabled = false;
  }
});

// ---------- start-up: resume a session from this tab if it is still valid ----------

loadRegistrationOffer();

(async () => {
  if (!session.token()) {
    showSignedOut();
    return;
  }
  try {
    const { response, data } = await api('/api/v1/auth/session', { method: 'GET' });
    if (response.ok) {
      showSignedIn(data.userId, data.ssnMasked);
    } else if (response.status === 401) {
      showSignedOut(messageFor(401, data));
    } else {
      // 429 or a server error says nothing about the token: keep it, and let the next request decide.
      showSignedIn(session.user());
      showNotice(messageFor(response.status, data));
    }
  } catch {
    showSignedIn(session.user());
    showNotice('Could not reach the server. Your session is kept; try again shortly.');
  }
})();
