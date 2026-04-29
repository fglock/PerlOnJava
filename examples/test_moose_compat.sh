#!/usr/bin/env bash
# Runs examples/moose.pl under both system Perl and PerlOnJava and asserts
# that the outputs are identical.
#
# Usage (from the repo root):
#   bash examples/test_moose_compat.sh

set -euo pipefail

SCRIPT="examples/moose.pl"
PERL_OUT=$(mktemp /tmp/moose_perl_XXXXXX.out)
JPERL_OUT=$(mktemp /tmp/moose_jperl_XXXXXX.out)

cleanup() { rm -f "$PERL_OUT" "$JPERL_OUT"; }
trap cleanup EXIT

echo "Running with system Perl..."
perl "$SCRIPT" > "$PERL_OUT" 2>&1
PERL_EXIT=$?

echo "Running with PerlOnJava..."
./jperl "$SCRIPT" > "$JPERL_OUT" 2>&1
JPERL_EXIT=$?

if [ "$PERL_EXIT" -ne 0 ]; then
    echo "FAIL: system Perl exited with $PERL_EXIT"
    cat "$PERL_OUT"
    exit 1
fi

if [ "$JPERL_EXIT" -ne 0 ]; then
    echo "FAIL: PerlOnJava exited with $JPERL_EXIT"
    cat "$JPERL_OUT"
    exit 1
fi

if diff -u "$PERL_OUT" "$JPERL_OUT" > /dev/null 2>&1; then
    echo "OK: outputs are identical"
    echo "--- output ---"
    cat "$PERL_OUT"
else
    echo "FAIL: outputs differ"
    diff -u "$PERL_OUT" "$JPERL_OUT"
    exit 1
fi
