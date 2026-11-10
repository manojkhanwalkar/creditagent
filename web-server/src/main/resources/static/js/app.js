import { bandFor, looksLikeSsn, ringDash } from './score-ring.js';
import { authHeaders, checkRegistration, createSession, lockView, registrationFailure, ssnHint } from './auth.js';
import { appendEntry, assistantEntry, checkChat, userEntry } from './chat.js';

const RADIUS = 80;
const session = createSession(window.sessionStorage);

const $ = (id) => document.getElementById(id);

const account = $('account');
const accountUser = $('account-user');
const notice = $('notice');
const authWidget = $('auth-widget');
const scoreWidget = $('score-widget');
const lockWidget = $('lock-widget');
const chatWidget = $('chat-widget');

// ---------- signed-in / signed-out view ----------

function showSignedIn(userId, ssnMasked) {
  accountUser.textContent = userId;
  $('ssn-hint').textContent = ssnHint(ssnMasked || session.ssnMasked());
  account.hidden = false;
  authWidget.hidden = true;
  scoreWidget.hidden = false;
  lockWidget.hidden = false;
  chatWidget.hidden = false;
  hideNotice();
  resetLockWidget();
  resetChat();
  loadReportStatus();
}

function showSignedOut(message) {
  session.clear();
  account.hidden = true;
  authWidget.hidden = false;
  scoreWidget.hidden = true;
  lockWidget.hidden = true;
  chatWidget.hidden = true;
  resetLockWidget();
  resetChat();
  $('result').hidden = true;
  $('ssn').value = '';
  if (message) showNotice(message);
}

function showNotice(message) {
  notice.textContent = message;
  notice.hidden = false;
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
  return { response, data };
}

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
  tabLogin.setAttribute('aria-selected', String(!register));
  tabRegister.setAttribute('aria-selected', String(register));
  loginForm.hidden = register;
  registerForm.hidden = !register;
  $('auth-title').textContent = register ? 'Create an account' : 'Sign in';
}

tabLogin.addEventListener('click', () => selectTab(false));
tabRegister.addEventListener('click', () => selectTab(true));

// ---------- login ----------

async function logIn(userId, password, errorElement) {
  const { response, data } = await api('/api/v1/auth/login', { body: { userId, password } });
  if (!response.ok) {
    showFormError(errorElement, messageFor(response.status, data));
    return false;
  }
  session.save(data.token, data.userId, data.ssnMasked);
  showSignedIn(data.userId, data.ssnMasked);
  return true;
}

loginForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const error = $('login-error');
  showFormError(error, '');
  const button = $('login-submit');
  button.disabled = true;
  try {
    if (await logIn($('login-user').value.trim(), $('login-password').value, error)) {
      loginForm.reset();
    }
  } catch {
    showFormError(error, 'Could not reach the server. Check your connection and try again.');
  } finally {
    button.disabled = false;
  }
});

// ---------- registration ----------

registerForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const error = $('register-error');
  const form = {
    userId: $('reg-user').value.trim(),
    password: $('reg-password').value,
    confirm: $('reg-confirm').value,
    email: $('reg-email').value.trim(),
    phone: $('reg-phone').value.trim(),
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
    body.append('phone', form.phone);
    body.append('ssnCard', form.ssnCard, form.ssnCard.name);
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
    if (await logIn(form.userId, form.password, error)) {
      registerForm.reset();
      selectTab(false);
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
  result.hidden = false;
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

function resetChat() {
  chatGeneration += 1;
  chatEntries = [];
  chatSsnValue = null;
  chatLog.replaceChildren();
  chatSsn.value = '';
  chatMessage.value = '';
  chatSsnRow.hidden = false;
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
  const problem = checkChat(message, ssn, needSsn);
  showFormError(chatError, problem || '');
  if (problem) return;

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
    chatSend.disabled = false;
    chatSend.textContent = 'Send';
  }
});

chatMessage.addEventListener('keydown', (event) => {
  // Enter sends; Shift+Enter makes a new line.
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    chatForm.requestSubmit();
  }
});

// ---------- start-up: resume a session from this tab if it is still valid ----------

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
