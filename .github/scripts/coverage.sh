#!/usr/bin/env bash
# Totals JaCoCo line coverage across every module, writes a table to the GitHub job summary (or
# stdout), and fails if the project is below the target. Usage: coverage.sh [target-percent]
set -euo pipefail
target="${1:-95}"
out="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
shopt -s nullglob
reports=(*/target/site/jacoco/jacoco.csv)
if [ ${#reports[@]} -eq 0 ]; then
  echo "No JaCoCo reports found; run mvn verify first." >&2
  exit 1
fi
{
  echo "### Line coverage"
  echo
  echo "| Module | Covered | Lines | Coverage |"
  echo "|---|---:|---:|---:|"
  for report in "${reports[@]}"; do
    module="${report%%/*}"
    awk -F, -v m="$module" 'NR > 1 { missed += $8; covered += $9 }
      END { total = missed + covered; printf "| %s | %d | %d | %.1f%% |\n", m, covered, total, total ? 100 * covered / total : 100 }' "$report"
  done
} >> "$out"
total=$(awk -F, 'FNR > 1 { missed += $8; covered += $9 } END { printf "%.2f", 100 * covered / (missed + covered) }' "${reports[@]}")
{
  echo
  echo "**Project: ${total}%** (target ${target}%)"
} >> "$out"
if awk -v t="$total" -v min="$target" 'BEGIN { exit !(t < min) }'; then
  echo "Line coverage ${total}% is below the ${target}% target." >&2
  exit 1
fi
echo "Line coverage ${total}% meets the ${target}% target."
