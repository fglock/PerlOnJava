#!/bin/bash
# Compare eval STRING performance: Perl vs PerlOnJava
# Interleaves trials to account for CPU variability.
# Usage: ./dev/bench/compare_eval_string.sh [trials] [iters]

set -euo pipefail
cd "$(dirname "$0")/../.."

TRIALS=${1:-10}
N=${2:-200000}

cat > /tmp/bench_eval_trial.pl << 'PERL'
use strict; use warnings; use Time::HiRes qw(time);
my $n = $ARGV[0] || 200_000;
my $mode = $ARGV[1] || 'uncached';
my ($count, $k) = (0, 0);
my $start = time();
if ($mode eq 'cached') {
    my $str = 'my $x = 1';
    for (1 .. $n) { eval $str }
} else {
    for (1 .. $n) {
        $k++;
        my $str = 'while ($k < 40) { $k = $k + 1; $count = $count + 1; }' . "# $k";
        eval $str;
    }
}
printf "%.6f\n", time() - $start;
PERL

run_trial() {
    local engine=$1 mode=$2
    if [ "$engine" = "perl" ]; then
        perl /tmp/bench_eval_trial.pl "$N" "$mode"
    else
        timeout 300 ./jperl /tmp/bench_eval_trial.pl "$N" "$mode"
    fi
}

echo "=== eval STRING: Perl vs PerlOnJava ($TRIALS trials x $N iters) ==="
echo ""

for mode in uncached cached; do
    perl_times=()
    jperl_times=()

    # Warmup
    run_trial perl "$mode" > /dev/null
    run_trial jperl "$mode" > /dev/null

    # Interleaved trials
    for i in $(seq 1 "$TRIALS"); do
        p=$(run_trial perl "$mode")
        j=$(run_trial jperl "$mode")
        perl_times+=("$p")
        jperl_times+=("$j")
        # Compute per-trial ratio
        ratio=$(echo "$j / $p" | bc -l)
        printf "  trial %2d: perl=%.3fs  jperl=%.3fs  ratio=%.1fx\n" "$i" "$p" "$j" "$ratio"
    done

    # Compute stats with awk
    echo ""
    echo "${perl_times[@]}" | tr ' ' '\n' | awk -v n="$N" -v label="perl" '
    { a[NR]=$1; s+=$1 }
    END {
        m=s/NR; ss=0; for(i=1;i<=NR;i++) ss+=(a[i]-m)^2
        sd=sqrt(ss/(NR-1)); se=sd/sqrt(NR)
        us=m/n*1e6; us_se=se/n*1e6
        printf "  %-8s mean=%.3f +/- %.3f s  (%.2f +/- %.2f us/eval)\n", label, m, se, us, us_se
    }'
    echo "${jperl_times[@]}" | tr ' ' '\n' | awk -v n="$N" -v label="jperl" '
    { a[NR]=$1; s+=$1 }
    END {
        m=s/NR; ss=0; for(i=1;i<=NR;i++) ss+=(a[i]-m)^2
        sd=sqrt(ss/(NR-1)); se=sd/sqrt(NR)
        us=m/n*1e6; us_se=se/n*1e6
        printf "  %-8s mean=%.3f +/- %.3f s  (%.2f +/- %.2f us/eval)\n", label, m, se, us, us_se
    }'

    # Compute paired ratio with t-test
    paste <(printf '%s\n' "${perl_times[@]}") <(printf '%s\n' "${jperl_times[@]}") | awk -v mode="$mode" '
    { r[NR]=$2/$1; s+=r[NR] }
    END {
        m=s/NR; ss=0; for(i=1;i<=NR;i++) ss+=(r[i]-m)^2
        sd=sqrt(ss/(NR-1)); se=sd/sqrt(NR)
        t=(m-1)/se
        printf "\n  %s ratio: %.2fx +/- %.2f  (paired t vs 1.0: t=%.1f, df=%d)\n", mode, m, se, t, NR-1
    }'
    echo ""
done
