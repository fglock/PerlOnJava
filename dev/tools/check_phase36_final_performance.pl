#!/usr/bin/env perl

use strict;
use warnings;

use File::Basename qw(dirname);
use File::Spec;
use FindBin qw($Bin);
use Getopt::Long qw(GetOptions);
use JSON::PP;

use lib File::Spec->catdir($Bin, 'lib');
use PerlOnJava::Phase36PerformanceEvidence qw(
    evaluate_performance load_json policy_sha256
);

my $requirements = File::Spec->catfile($Bin,
    'phase36_acceptance_requirements.json');
my ($evidence, $expected_candidate, $output, $java, $help);
my $mode = 'strict';
GetOptions(
    'requirements=s' => \$requirements,
    'evidence=s' => \$evidence,
    'expected-candidate=s' => \$expected_candidate,
    'mode=s' => \$mode,
    'output=s' => \$output,
    'java=s' => \$java,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV || !defined($evidence) || !defined($java)
    || $mode !~ /\A(?:report|strict)\z/;
die "--expected-candidate must be a full Git SHA\n"
    if defined($expected_candidate)
        && $expected_candidate !~ /\A[0-9a-f]{40}\z/;

my $rules = load_json($requirements, 'performance requirements', 4 * 1024 * 1024);
my $document = load_json($evidence, 'final performance evidence', 16 * 1024 * 1024);
my $root = dirname(File::Spec->rel2abs($evidence));
my $evaluation = evaluate_performance($document, $rules, $root, {
    java => $java,
    jfr_metrics_producer => File::Spec->catfile($Bin, 'Phase36JfrMetrics.java'),
});
my @envelope_issues;
push @envelope_issues, 'performance policy identity is wrong'
    if ($document->{policy_sha256} // '') ne policy_sha256($rules);
push @envelope_issues, 'candidate identity differs from --expected-candidate'
    if defined($expected_candidate)
        && (($document->{identity} // {})->{candidate_source_commit} // '')
            ne $expected_candidate;
push @envelope_issues, 'stored performance decision disagrees with raw evidence'
    if ($document->{decision} // '') ne $evaluation->{decision};
push @envelope_issues, 'stored performance verification flag is wrong'
    if (($document->{verified} ? 1 : 0) != ($evaluation->{verified} ? 1 : 0));
push @envelope_issues, 'stored performance evaluation is missing or stale'
    unless ref($document->{evaluation}) eq 'HASH'
        && JSON::PP->new->canonical->encode($document->{evaluation}) eq
            JSON::PP->new->canonical->encode($evaluation);

my $decision = @envelope_issues ? 'failed' : $evaluation->{decision};
my $report = {
    schema_version => 1,
    check_mode => $mode,
    decision => $decision,
    authoritative => ($mode eq 'strict' && $decision eq 'passed')
        ? JSON::PP::true : JSON::PP::false,
    envelope_issues => \@envelope_issues,
    evaluation => $evaluation,
};
my $rendered = JSON::PP->new->canonical->pretty->encode($report);
if (defined $output) {
    open my $fh, '>:raw', $output or die "Cannot write $output: $!\n";
    print {$fh} $rendered or die "Cannot write $output: $!\n";
    close $fh or die "Cannot close $output: $!\n";
} else {
    print $rendered;
}
exit 0 if $mode eq 'report';
exit($decision eq 'passed' ? 0 : $decision eq 'review-stop' ? 2 : 1);

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: check_phase36_final_performance.pl --evidence FINAL.json
       --java /exact/path/to/java
       [--expected-candidate SHA] [--mode strict|report] [--output REPORT.json]

Recompute Phase 36 performance status from every sealed raw artifact. Raw JFR
is streamed through the exact hash-bound source-mode helper and compared with
the bounded sealed metrics. A timing summary without exact ordinary,
psycho/speed, TAP, JFR, NMT, time, and identity evidence fails closed.
USAGE
    exit $status;
}
