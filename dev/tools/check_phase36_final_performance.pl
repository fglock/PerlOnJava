#!/usr/bin/env perl

use strict;
use warnings;

use Digest::SHA;
use File::Basename qw(dirname);
use File::Spec;
use File::Temp qw(tempfile);
use FindBin qw($Bin);
use Getopt::Long qw(GetOptions);
use JSON::PP;

use lib File::Spec->catdir($Bin, 'lib');
use PerlOnJava::Phase36PerformanceEvidence qw(
    evaluate_performance load_json policy_sha256
);

my $requirements = File::Spec->catfile($Bin,
    'phase36_acceptance_requirements.json');
my ($evidence, $expected_candidate, $output, $java, $perl, $authority_key,
    $baseline_source, $candidate_source, $perl5_source, $git, $ps, $uptime,
    $help);
my $mode = 'strict';
GetOptions(
    'requirements=s' => \$requirements,
    'evidence=s' => \$evidence,
    'expected-candidate=s' => \$expected_candidate,
    'mode=s' => \$mode,
    'output=s' => \$output,
    'java=s' => \$java,
    'perl=s' => \$perl,
    'git=s' => \$git,
    'ps=s' => \$ps,
    'uptime=s' => \$uptime,
    'authority-key=s' => \$authority_key,
    'baseline-source=s' => \$baseline_source,
    'candidate-source=s' => \$candidate_source,
    'perl5-source=s' => \$perl5_source,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
die "Phase 36 checker process-tree and authority-key contract is unsupported on Windows until A232 validates native process trees and private fixed-location ACLs\n"
    if $^O eq 'MSWin32';
reject_injection_environment();
my $missing_required = grep { !defined($_) }
    ($evidence, $java, $perl, $git, $ps, $uptime, $authority_key,
        $baseline_source, $candidate_source, $perl5_source);
usage(2) if @ARGV || $missing_required || $mode !~ /\A(?:report|strict)\z/;
die "--perl does not identify the interpreter executing the checker\n"
    unless -f $perl && -x $perl && -f $^X
        && file_sha256($perl) eq file_sha256($^X);
die "--expected-candidate must be a full Git SHA\n"
    if defined($expected_candidate)
        && $expected_candidate !~ /\A[0-9a-f]{40}\z/;

my $rules = load_json($requirements, 'performance requirements', 4 * 1024 * 1024);
my $document = load_json($evidence, 'final performance evidence', 16 * 1024 * 1024);
my $root = dirname(File::Spec->rel2abs($evidence));
my $evaluation = evaluate_performance($document, $rules, $root, {
    java => $java,
    perl => $perl,
    authority_key => $authority_key,
    baseline_source => $baseline_source,
    candidate_source => $candidate_source,
    perl5_source => $perl5_source,
    orchestrator => File::Spec->catfile($Bin,
        'run_phase36_final_performance.pl'),
    ordinary_performance_producer => File::Spec->catfile($Bin,
        'run_phase36_regex_performance.pl'),
    performance_evaluator => File::Spec->catfile($Bin, 'lib', 'PerlOnJava',
        'Phase36PerformanceEvidence.pm'),
    benchmark => File::Spec->catfile($Bin, 'phase36_regex_benchmark.pl'),
    requirements => $requirements,
    jfr_metrics_producer => File::Spec->catfile($Bin, 'Phase36JfrMetrics.java'),
    git => $git, ps => $ps, uptime => $uptime,
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
    write_atomic_exclusive($output, $rendered);
} else {
    print $rendered;
}

sub write_atomic_exclusive {
    my ($file, $contents) = @_;
    my $directory = dirname(File::Spec->rel2abs($file));
    my ($fh, $staging) = tempfile('.phase36-check-XXXXXX',
        DIR => $directory, UNLINK => 0);
    my $ok = eval {
        chmod 0600, $staging or die "Cannot protect $staging: $!\n";
        binmode $fh, ':raw';
        print {$fh} $contents or die "Cannot write $staging: $!\n";
        close $fh or die "Cannot close $staging: $!\n";
        undef $fh;
        link $staging, $file or die "Cannot exclusively publish $file: $!\n";
        unlink $staging or die "Cannot remove $staging: $!\n";
        1;
    };
    my $error = $@;
    close $fh if $fh;
    unlink $staging if -e $staging;
    die $error unless $ok;
}

sub file_sha256 {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $hash = Digest::SHA->new(256)->addfile($fh)->hexdigest;
    close $fh or die "Cannot close $path: $!\n";
    return $hash;
}

sub reject_injection_environment {
    my @bad = grep { /\A(?:(?:GIT|PERL|JAVA|JDK|CLASSPATH|JPERL|PHASE36)(?:_|\z)|LD_PRELOAD\z|DYLD_INSERT_LIBRARIES\z|BASH_ENV\z|ENV\z|CDPATH\z)/ }
        keys %ENV;
    die "ambient Git/JVM/Perl injection variables are forbidden: @bad\n" if @bad;
}
exit($decision eq 'passed' ? 0 : $decision eq 'review-stop' ? 2 : 1);

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: check_phase36_final_performance.pl --evidence FINAL.json
       --java /exact/path/to/java --perl /exact/path/to/perl
       --git /exact/path/to/git --ps /exact/path/to/ps
       --uptime /exact/path/to/uptime
       --authority-key PRIVATE_KEY --baseline-source DIR --candidate-source DIR
       --perl5-source DIR
       [--expected-candidate SHA] [--mode strict|report] [--output REPORT.json]

Recompute Phase 36 performance status from every sealed raw artifact. Raw JFR
is streamed through the exact hash-bound source-mode helper and compared with
the bounded sealed metrics. A timing summary without exact ordinary,
psycho/speed, TAP, JFR, NMT, time, and identity evidence fails closed.
USAGE
    exit $status;
}
