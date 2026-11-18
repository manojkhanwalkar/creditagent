// Structural checks on the served HTML: what the server and the regulations rely on being on the page.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const page = (name) => readFileSync(new URL(`../../main/resources/static/${name}`, import.meta.url), 'utf8');
const index = page('index.html');

test('registration asks for the FCRA authorisation, links the terms and privacy notice, and a mobile number', () => {
  assert.match(index, /<input id="reg-authorize" name="authorize" type="checkbox" required/);
  assert.match(index, /<label for="reg-authorize">[\s\S]*href="\/terms.html"[\s\S]*href="\/privacy.html"[\s\S]*credit reports/);
  // Required for sign-in codes, and stated as used for nothing else.
  assert.match(index, /<input id="reg-phone" name="mobilePhone" type="tel" autocomplete="tel" required/);
  assert.match(index, /Used only for sign-in codes/);
  assert.doesNotMatch(index, /name="phone"/);
});

test('the code step is a labelled group with an autofillable one-time-code field', () => {
  assert.match(index, /<form id="otp-form"[^>]*hidden/);
  assert.match(index, /<fieldset>\s*<legend>Send my sign-in code by<\/legend>/);
  assert.match(index, /<input id="otp-email" name="channel" type="radio" value="EMAIL"/);
  assert.match(index, /<input id="otp-sms" name="channel" type="radio" value="SMS"/);
  assert.match(index, /<input id="otp-code"[^>]*autocomplete="one-time-code"/);
  assert.match(index, /<p id="otp-status"[^>]*role="status"/);
  assert.match(index, /<p id="otp-error"[^>]*role="alert"/);
});

test('every form control on the main page has a label', () => {
  const ids = [...index.matchAll(/<(?:input|select|textarea)\b[^>]*\bid="([^"]+)"/g)].map((m) => m[1]);
  assert.ok(ids.length > 10);
  for (const id of ids) {
    assert.match(index, new RegExp(`<label for="${id}"`), `no label for #${id}`);
  }
});

test('the data rights, AI label, score note, idle warning and footer links are present', () => {
  for (const id of ['data-widget', 'export-form', 'delete-form', 'delete-confirm', 'chat-ai-label', 'idle-warning', 'idle-stay']) {
    assert.match(index, new RegExp(`id="${id}"`), id);
  }
  assert.match(index, /Automated assistant/);
  assert.match(index, /Lenders use their own\s+scores/);
  for (const href of ['/privacy.html', '/terms.html', '/accessibility.html']) {
    assert.match(index, new RegExp(`<footer[\\s\\S]*href="${href}"`), href);
  }
});

test('the legal and accessibility pages exist with a language, a title and one h1', () => {
  for (const name of ['privacy.html', 'terms.html', 'accessibility.html']) {
    const html = page(name);
    assert.match(html, /<html lang="en">/, name);
    assert.match(html, /<title>[^<]+<\/title>/, name);
    assert.equal(html.match(/<h1>/g).length, 1, name);
  }
  assert.match(page('terms.html'), /Fair\s+Credit\s+Reporting\s+Act/);
  assert.match(page('privacy.html'), /Gramm-Leach-Bliley/);
});

test('form fields have a 3:1 border and the grid reflows at 320 px', () => {
  const css = page('css/styles.css');
  assert.match(css, /--field-border: #767670;/);
  assert.match(css, /minmax\(min\(320px, 100%\), 1fr\)/);
  assert.doesNotMatch(css, /outline:\s*(none|0)/);
});
