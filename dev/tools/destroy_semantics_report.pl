#!/usr/bin/env perl
# dev/tools/destroy_semantics_report.pl
#
# Generate a baseline report for destroy/weaken/refcount-semantics tests.
# Runs all tests in dev/sandbox/destroy_weaken/ under both `perl` and
# `./jperl`, and prints a pass/fail table suitable for tracking progress
# of dev/design/refcount_alignment_plan.md.
#
# Usage:
#   dev/tools/destroy_semantics_report.pl [--write <path>]
#
# The report is also appended to dev/design/refcount_alignment_progress.md
# when --write is used, so each implementation phase can append its own
# row and diff against earlier baselines.

use strict;
use warnings;
use FindBin qw($Bin);
use File::Spec;
use Getopt::Long;

my $write_path;
GetOptions('write=s' => \$write_path) or die "Usage: $0 [--write <path>]\n";

my $root = File::Spec->rel2abs("$Bin/..");
my $sandbox_dir = "$Bin/../sandbox/destroy_weaken";
my $jperl = "$Bin/../../jperl";

opendir(my $dh, $sandbox_dir) or die "open $sandbox_dir: $!";
my @tests = sort grep { /\.t$/ } readdir($dh);
closedir $dh;

sub run_test {
    my ($runner, $test) = @_;
    my $cmd = "$runner $sandbox_dir/$test 2>&1";
    my $out = `$cmd`;
    my $ok = () = $out =~ /^ok \d+/mg;
    my $notok = () = $out =~ /^not ok \d+/mg;
    my ($plan) = $out =~ /^1\.\.(\d+)/m;
    return { ok => $ok, notok => $notok, plan => $plan // 0 };
}

my @rows;
my $total_perl_ok = 0;
my $total_perl_notok = 0;
my $total_jperl_ok = 0;
my $total_jperl_notok = 0;

printf("%-38s %10s %10s\n", "test", "perl", "jperl");
printf("%s\n", "-" x 62);

for my $t (@tests) {
    my $p = run_test('perl', $t);
    my $j = run_test($jperl, $t);
    $total_perl_ok  += $p->{ok};    $total_perl_notok  += $p->{notok};
    $total_jperl_ok += $j->{ok};    $total_jperl_notok += $j->{notok};
    my $p_str = sprintf("%d/%d", $p->{ok}, $p->{plan});
    my $j_str = sprintf("%d/%d", $j->{ok}, $j->{plan});
    printf("%-38s %10s %10s\n", $t, $p_str, $j_str);
    push @rows, [$t, $p_str, $j_str];
}

printf("%s\n", "-" x 62);
printf("%-38s %10s %10s\n",
    "TOTAL",
    "$total_perl_ok/" . ($total_perl_ok + $total_perl_notok),
    "$total_jperl_ok/" . ($total_jperl_ok + $total_jperl_notok),
);

if ($write_path) {
    my $ts = scalar localtime;
    open my $fh, '>>', $write_path or die "append $write_path: $!";
    print $fh "\n## Snapshot $ts\n\n";
    print $fh "| test | perl | jperl |\n|------|------|------|\n";
    for my $r (@rows) {
        print $fh "| $r->[0] | $r->[1] | $r->[2] |\n";
    }
    print $fh "| **TOTAL** | **$total_perl_ok/" . ($total_perl_ok + $total_perl_notok) .
              "** | **$total_jperl_ok/" . ($total_jperl_ok + $total_jperl_notok) . "** |\n";
    close $fh;
    print "\n(appended snapshot to $write_path)\n";
}
