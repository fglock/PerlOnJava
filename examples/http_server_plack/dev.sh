#!/usr/bin/env bash
set -euo pipefail

JPERL="../../jperl"

# Erstes Argument bestimmt das Script (Standard: test.pl)
case "${1:-}" in
  streaming|stream|s)
    SCRIPT="test_streaming.pl"
    shift
    ;;
  test|t|"")
    SCRIPT="test.pl"
    [[ -n "${1:-}" && "${1:-}" != --* ]] && shift
    ;;
  *)
    SCRIPT="test.pl"
    ;;
esac

if ! command -v watchexec &>/dev/null; then
  echo "Error: watchexec is not installed." >&2
  echo "  Install it from: https://github.com/watchexec/watchexec" >&2
  echo "  macOS:  brew install watchexec" >&2
  echo "  cargo:  cargo install watchexec-cli" >&2
  exit 1
fi

echo "Watching $SCRIPT (Ctrl+C to stop)..."

exec watchexec \
  --restart \
  --watch . \
  --exts pl \
  -- "$JPERL" "$SCRIPT" "$@"
