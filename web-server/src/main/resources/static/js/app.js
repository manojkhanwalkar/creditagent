import { bandFor, looksLikeSsn, ringDash } from './score-ring.js';
import { authHeaders, checkRegistration, createSession } from './auth.js';

const RADIUS = 80;
const session = createSession(window.sessionStorage);

const $ = (id) => document.getElementById(id);

const account = $('account');
const accountUser = $('account-user');
const notice = $('notice');
const authWidget = $('auth-widget');
const scoreWidget = $('score-widget');

// ---------- signed-in / signed-out view ----------

function showSignedIn(userId) {
  accountUser.textContent = userId;
  account.hidden = false;
  authWidget.hidden = true;
  scoreWidget.hidden = false;
  hideNotice();
}

function showSignedOut(message) {
  session.clear();
  account.hidden = true;
  authWidget.hidden = false;
  scoreWidget.hidden = true;
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
  session.save(data.token, data.userId);
  showSignedIn(data.userId);
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
  };
  const problem = checkRegistration(form);
  showFormError(error, problem || '');
  if (problem) return;

  const button = $('register-submit');
  button.disabled = true;
  try {
    const { response, data } = await api('/api/v1/auth/register', {
      body: { userId: form.userId, password: form.password, email: form.email, phone: form.phone },
    });
    if (!response.ok) {
      showFormError(error, messageFor(response.status, data));
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

// ---------- start-up: resume a session from this tab if it is still valid ----------

(async () => {
  if (!session.token()) {
    showSignedOut();
    return;
  }
  try {
    const { response, data } = await api('/api/v1/auth/session', { method: 'GET' });
    if (response.ok) {
      showSignedIn(data.userId);
    } else {
      showSignedOut(response.status === 401 ? messageFor(401, data) : undefined);
    }
  } catch {
    showSignedOut('Could not reach the server.');
  }
})();
