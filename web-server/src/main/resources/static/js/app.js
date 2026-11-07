import { bandFor, looksLikeSsn, ringDash } from './score-ring.js';

const RADIUS = 80;

const form = document.getElementById('score-form');
const input = document.getElementById('ssn');
const toggle = document.getElementById('toggle-ssn');
const submit = document.getElementById('submit');
const error = document.getElementById('form-error');
const result = document.getElementById('result');
const ringValue = document.getElementById('ring-value');
const ringScore = document.getElementById('ring-score');
const caption = document.getElementById('ring-caption');

toggle.addEventListener('click', () => {
  const show = input.type === 'password';
  input.type = show ? 'text' : 'password';
  toggle.textContent = show ? 'Hide' : 'Show';
  toggle.setAttribute('aria-pressed', String(show));
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  hideError();

  const ssn = input.value.trim();
  if (!looksLikeSsn(ssn)) {
    showError('Enter a 9-digit SSN, for example 123-45-6789.');
    return;
  }

  submit.disabled = true;
  submit.textContent = 'Checking…';
  try {
    const response = await fetch('/api/v1/credit-score', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ ssn }),
      credentials: 'same-origin',
      cache: 'no-store',
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      showError(messageFor(response.status, body));
      return;
    }
    render(body);
    // Do not leave the SSN sitting in the field.
    input.value = '';
  } catch {
    showError('Could not reach the server. Check your connection and try again.');
  } finally {
    submit.disabled = false;
    submit.textContent = 'Get score';
  }
});

function render({ score, ssnMasked }) {
  const band = bandFor(score);
  const { dash, gap } = ringDash(score, RADIUS);
  ringValue.style.strokeDasharray = `${dash} ${gap}`;
  ringValue.style.stroke = band.color;
  ringScore.textContent = String(score);
  ringScore.style.fill = band.color;
  caption.textContent = `${band.label} · ${ssnMasked}`;
  result.hidden = false;
}

function messageFor(status, body) {
  if (status === 429) return 'Too many requests. Please wait a minute and try again.';
  if (body && typeof body.detail === 'string') return body.detail;
  return 'Something went wrong. Please try again.';
}

function showError(message) {
  error.textContent = message;
  error.hidden = false;
  result.hidden = true;
}

function hideError() {
  error.textContent = '';
  error.hidden = true;
}
