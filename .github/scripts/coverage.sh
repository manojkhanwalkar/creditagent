#!/usr/bin/env bash
# Totals JaCoCo line coverage across every module, writes a table to the GitHub job summary (or
# stdout), and fails if the project is below the target. Usage: coverage.sh [target-percent]
# Reads <module>/target/site/jacoco/jacoco.csv (LINE_MISSED is column 8, LINE_COVERED column 9)
# from the repository root, wherever it is run from.
set -euo pipefail
target="${1:-95}"
if ! [[ "$target" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "Target must be a number of percent, such as 95; got '$target'." >&2
  exit 2
fi
out="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
shopt -s nullglob
reports=(*/target/site/jacoco/jacoco.csv)
if [ ${#reports[@]} -eq 0 ]; then
  echo "No JaCoCo reports found; run mvn verify first." >&2
  exit 1
fi
total=$(awk -F, 'FNR > 1 { missed += $8; covered += $9 }
  END { if (missed + covered == 0) exit 1; printf "%.2f", 100 * covered / (missed + covered) }' "${reports[@]}") || {
  echo "The JaCoCo reports contain no lines." >&2
  exit 1
}
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
  echo
  echo "**Project: ${total}%** (target ${target}%)"
} >> "$out"
if awk -v t="$total" -v min="$target" 'BEGIN { exit !(t + 0 < min + 0) }'; then
  echo "Line coverage ${total}% is below the ${target}% target." >&2
  exit 1
fi
echo "Line coverage ${total}% meets the ${target}% target."
