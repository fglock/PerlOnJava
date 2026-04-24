#!/bin/bash
# Fast DBIC indicator-set runner for perf/dbic-safe-port branch
#
# Runs the 8 user-flagged "most problematic" DBIC tests with timeouts,
# reports per-test pass/fail/TIMEOUT. Much faster than full ./jcpan -t.
#
# Usage: bash dev/scripts/dbic_fast_check.sh [TIMEOUT_SECS]
set -u
TIMEOUT=${1:-300}   # 300s per test default (Sub::Defer hangs → TIMEOUT)
DBIC=/Users/fglock/.perlonjava/cpan/build/DBIx-Class-0.082844-78
JPERL=/Users/fglock/projects/PerlOnJava4/jperl
TESTS=(
  t/96_is_deteministic_value.t
  t/resultset/as_subselect_rs.t
  t/search/select_chains.t
  t/storage/error.t
  t/storage/txn.t
  t/debug/pretty.t
  t/52leaks.t
  t/zzzzzzz_perl_perf_bug.t
)

cd "$DBIC" || exit 99
export PERL5LIB="$DBIC/blib/lib:$DBIC/blib/arch:${PERL5LIB-}"

pass=0; fail=0; timeout_count=0
for t in "${TESTS[@]}"; do
  start=$(date +%s)
  out=$(/usr/bin/env perl -e '
    use POSIX ":sys_wait_h";
    my ($cmd, $timeout) = (shift, shift);
    my $pid = fork;
    if ($pid == 0) { exec "/bin/sh", "-c", $cmd; exit 127 }
    my $deadline = time + $timeout;
    while (time < $deadline) {
      my $kid = waitpid($pid, WNOHANG);
      if ($kid > 0) { exit ($? >> 8) }
      sleep 1;
    }
    kill "KILL", $pid; waitpid($pid, 0);
    exit 124;
  ' "$JPERL $t 2>&1 | tail -3" "$TIMEOUT")
  rc=$?
  elapsed=$(( $(date +%s) - start ))
  if [ $rc -eq 0 ]; then
    printf "  PASS    %4ds  %s\n" "$elapsed" "$t"; pass=$((pass+1))
  elif [ $rc -eq 124 ]; then
    printf "  TIMEOUT %4ds  %s\n" "$elapsed" "$t"; timeout_count=$((timeout_count+1))
  else
    printf "  FAIL(%d) %4ds  %s\n" "$rc" "$elapsed" "$t"; fail=$((fail+1))
  fi
done
echo "----"
echo "pass=$pass  fail=$fail  timeout=$timeout_count  (total=${#TESTS[@]})"
exit $((fail + timeout_count))
