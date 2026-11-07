// Pure functions behind the credit score ring. No DOM access, so they run under `node --test`.

export const MAX_SCORE = 850;

export const BANDS = Object.freeze({
  GREEN: { name: 'GREEN', color: '#1f7a35', label: 'Good' },
  ORANGE: { name: 'ORANGE', color: '#a85300', label: 'Fair' },
  RED: { name: 'RED', color: '#c2272d', label: 'Poor' },
});

/** Above 700 green, 600–700 orange, below 600 red. Mirrors com.creditagent.common.ScoreBand. */
export function bandFor(score) {
  if (score > 700) return BANDS.GREEN;
  if (score >= 600) return BANDS.ORANGE;
  return BANDS.RED;
}

/**
 * Stroke-dasharray values for an SVG circle of radius `radius`: a full circle at MAX_SCORE and a
 * proportional arc below it. Out-of-range scores are clamped.
 */
export function ringDash(score, radius) {
  const circumference = 2 * Math.PI * radius;
  const clamped = Math.min(Math.max(Number(score) || 0, 0), MAX_SCORE);
  const dash = (clamped / MAX_SCORE) * circumference;
  return { circumference, dash, gap: circumference - dash };
}

/** Client-side pre-check only; the server is authoritative. */
export function looksLikeSsn(value) {
  return /^\d{3}-?\d{2}-?\d{4}$/.test(String(value).trim());
}
