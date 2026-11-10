// Fills the OAuth consent page from its URL. Text only: values go into `value` and `textContent`,
// never into HTML, so nothing in the URL can add markup or script.
import { consentModel } from './oauth-consent-model.js';

const model = consentModel(window.location.search);
const form = document.getElementById('consent-form');

for (const [name, value] of model.hidden) {
  const input = document.createElement('input');
  input.type = 'hidden';
  input.name = name;
  input.value = value;
  form.prepend(input);
}

document.getElementById('client-name').textContent = model.clientName;
if (model.returnOrigin) {
  document.getElementById('return-origin').textContent = model.returnOrigin;
  document.getElementById('return-line').hidden = false;
}
if (model.signInFailed) {
  document.getElementById('sign-in-error').hidden = false;
}
