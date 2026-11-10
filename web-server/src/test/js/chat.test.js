import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MAX_ENTRIES, MAX_MESSAGE, appendEntry, assistantEntry, checkChat, userEntry } from '../../main/resources/static/js/chat.js';

test('a question needs text, a sane length and, the first time, an SSN', () => {
  assert.equal(checkChat('  ', '123-45-6789', true), 'Type a question first.');
  assert.match(checkChat('x'.repeat(MAX_MESSAGE + 1), '123-45-6789', true), /under 1000/);
  assert.match(checkChat('How is my score?', '12345', true), /9-digit SSN/);
  assert.equal(checkChat('How is my score?', '123-45-6789', true), null);
  assert.equal(checkChat('How is my score?', '', false), null);
  assert.equal(checkChat(undefined, '', false), 'Type a question first.');
});

test('entries carry text, the disclaimer and whether a guardrail refused', () => {
  assert.deepEqual(userEntry('  hi  '), { role: 'user', text: 'hi', disclaimer: null, refused: false });
  assert.deepEqual(assistantEntry({ reply: '<b>r</b>', disclaimer: 'd', refused: true }),
    { role: 'assistant', text: '<b>r</b>', disclaimer: 'd', refused: true });
  assert.deepEqual(assistantEntry({}), { role: 'assistant', text: 'No answer was returned.', disclaimer: null, refused: false });
  assert.equal(assistantEntry(null).text, 'No answer was returned.');
});

test('the transcript keeps only the latest entries', () => {
  let entries = [];
  for (let i = 0; i < MAX_ENTRIES + 5; i += 1) entries = appendEntry(entries, userEntry(`q${i}`));
  assert.equal(entries.length, MAX_ENTRIES);
  assert.equal(entries[0].text, 'q5');
  assert.equal(entries.at(-1).text, `q${MAX_ENTRIES + 4}`);
});
