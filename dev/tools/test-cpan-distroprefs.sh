#!/usr/bin/env bash
# Run jcpan -t for every distribution that has a bundled CPAN distropref under
# PerlOnJava/CpanDistroprefs/ (see dev/design/patch-and-cpan-prefs-layout.md).
#
# Usage (from repo root):
#   bash dev/tools/test-cpan-distroprefs.sh
# Environment:
#   JCPAN   — jcpan launcher (default: ./jcpan)
#   SKIP_*  — set SKIP_MOO=1 etc. to skip slow modules when iterating
#   INCLUDE_XML_LIBXML_IN_DISTROPREF_SMOKE=1 — run XML::LibXML (often fails: Java
#       backend vs full upstream t/; off by default)
#
# Each invocation is wrapped in timeout(1) per AGENTS.md.

set -uo pipefail
# Do not use `set -e`: we run every module and summarize failures at the end.

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1

JCPAN="${JCPAN:-./jcpan}"
if [[ ! -x "$JCPAN" && -f "./jcpan" ]]; then
  JCPAN="./jcpan"
fi

REPORT_DIR="${CPAN_DISTROPREFS_REPORT_DIR:-$ROOT/build/reports}"
mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/cpan-distroprefs-$(date +%Y%m%d-%H%M%S).log"
echo "Logging full output to: $REPORT"
exec > >(tee -a "$REPORT") 2>&1

echo "=============================================="
echo "PerlOnJava bundled distropref smoke (jcpan -t)"
echo "ROOT=$ROOT"
echo "JCPAN=$JCPAN"
echo "=============================================="

failures=()

run_one() {
  local var="$1"
  local module="$2"
  local seconds="$3"
  if [[ -n "${!var:-}" ]]; then
    echo "SKIP $module ($var is set)"
    return 0
  fi
  echo ""
  echo "---------- jcpan -t $module (timeout ${seconds}s) ----------"
  local start
  start=$(date +%s)
  timeout "$seconds" "$JCPAN" -t "$module"
  local ec=$?
  local elapsed=$(( $(date +%s) - start ))
  if [[ $ec -eq 0 ]]; then
    echo "OK $module (${elapsed}s)"
  elif [[ $ec -eq 124 ]]; then
    echo "FAIL $module — timeout after ${seconds}s"
    failures+=("$module:timeout")
  else
    echo "FAIL $module — exit $ec (${elapsed}s)"
    failures+=("$module:exit$ec")
  fi
}

# Default: offline OpenAI prefs (do not enable live API tests in this harness)
export PERLONJAVA_OPENAI_LIVE_TESTING="${PERLONJAVA_OPENAI_LIVE_TESTING:-}"

# Order: quick skips first, then smaller dists, then heavy suites.
run_one SKIP_DBI                DBI                      120
run_one SKIP_SQL_TRANSLATOR     SQL::Translator          120
run_one SKIP_CPAN_FINDDEPS      CPAN::FindDependencies   600
run_one SKIP_NET_SERVER         Net::Server              600
run_one SKIP_PARAMS_VALIDATE    Params::Validate         900
run_one SKIP_IO_ASYNC           IO::Async                1200
run_one SKIP_PERLIO_VIA_TIMEOUT PerlIO::via::Timeout    600
# OpenAI::API may recurse into IO::Async and other deps on a cold CPAN home; allow
# enough wall clock for first-time fetches + Build.PL chains.
run_one SKIP_OPENAI_API         OpenAI::API              3600
if [[ -n "${INCLUDE_XML_LIBXML_IN_DISTROPREF_SMOKE:-}" ]]; then
  run_one SKIP_XML_LIBXML       XML::LibXML              3600
else
  echo "SKIP XML::LibXML (set INCLUDE_XML_LIBXML_IN_DISTROPREF_SMOKE=1 to run; Java backend does not pass full upstream t/ yet)"
fi
run_one SKIP_MOO                Moo                      1200
run_one SKIP_MOOSE              Moose                    7200

echo ""
echo "=============================================="
if ((${#failures[@]})); then
  echo "SUMMARY: ${#failures[@]} failure(s):"
  for f in "${failures[@]}"; do echo "  - $f"; done
  exit 1
fi
echo "SUMMARY: all distropref jcpan -t runs passed."
exit 0
